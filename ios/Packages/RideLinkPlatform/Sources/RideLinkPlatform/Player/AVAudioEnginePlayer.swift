import AVFoundation
import Foundation
import RideLinkCore

/// The real local-playback binding: `AVAudioEngine` + `AVAudioPlayerNode` behind
/// `RideLinkCore.Player`'s platform-free seam — the same isolation `RideLinkPlatform.Voice` gives
/// WebRTC (ADR-003), applied to the music plane. Mirrors `com.ridelink.audio.player.ExoPlayerMusicPlayer`.
///
/// `AVAudioEngine`/`AVAudioPlayerNode` over the simpler `AVAudioPlayer`, per ARCHITECTURE §7.2 —
/// chosen for Phase 5's later sample-accurate `scheduleSegment(at: AVAudioTime(hostTime:))`, even
/// though this phase uses only the load/play/pause/seek/stop subset with no scheduling tricks yet.
///
/// **No `#if os(iOS)` gate** — unlike `IosVoiceAudioSession` (whose `AVAudioSession` truly is
/// iOS-only), `AVAudioEngine` and `AVAudioPlayerNode` are available on macOS too, so this gets real
/// `swift test` coverage against the actual `test-media/synthetic/` fixtures rather than only a
/// device/simulator-only claim — the same reason `RideLinkPlatform.Voice`'s real WebRTC engines are
/// provable under `swift test` (the Apple WebRTC XCFramework carries a macOS slice).
///
/// Position tracking works by converting `AVAudioPlayerNode`'s node time to a *player* time via
/// `playerTime(forNodeTime:)`, which measures elapsed frames **since the current scheduled segment
/// started**, not the file's absolute position after a seek — `seekOffsetFrames` is added back in to
/// get the absolute position, and is itself updated any time the node is paused, stopped, or asked
/// to seek, from whatever the absolute position was at that moment.
public actor AVAudioEnginePlayer: Player {
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()

    private var audioFile: AVAudioFile?
    private var totalFrames: AVAudioFramePosition = 0
    private var sampleRate: Double = 1
    /// The absolute frame position the *next* scheduled segment should start from — updated on
    /// load, seek, pause and stop; read (never written) while playing, since while playing the
    /// absolute position is `seekOffsetFrames + playerTime(forNodeTime:).sampleTime` instead.
    private var seekOffsetFrames: AVAudioFramePosition = 0
    /// The generation a scheduled segment's completion handler was scheduled under — incremented on
    /// every load/seek/stop so a completion callback from a superseded segment (the exact ExoPlayer-
    /// shaped double-emission `TrackEndEdge` exists for) is inert rather than reported as "ended"
    /// twice or for the wrong track. Matches `RideLinkPlatform.Voice`'s existing generation-guard
    /// convention (ADR-019's direct lesson, applied here).
    private var generation = 0

    private var cachedState = PlayerState()
    private var stateSink: (@Sendable (PlayerState) -> Void)?
    private var positionTickTask: Task<Void, Never>?

    public init() {
        engine.attach(playerNode)
        engine.connect(playerNode, to: engine.mainMixerNode, format: nil)
    }

    public func execute(_ command: PlaybackCommand) async {
        switch command {
        case let .load(quickId, location):
            await load(quickId: quickId, location: location)
        case .play:
            playCommand()
        case .pause:
            pauseCommand()
        case let .seek(positionMs):
            seekCommand(positionMs: positionMs)
        case .stop:
            stopCommand()
        }
    }

    public var state: PlayerState {
        get async { cachedState }
    }

    public func setStateSink(_ sink: @escaping @Sendable (PlayerState) -> Void) async {
        stateSink = sink
    }

    public func release() async {
        stopPositionTicking()
        playerNode.stop()
        engine.stop()
    }

    // MARK: - Commands

    /// `location.uri` must already be a directly-openable reference — an absolute `file://` URL
    /// string, or a plain absolute path — **not** the relative-to-`musicDirectory` filename
    /// `LibraryIndexer` stores in `Track.location` (ADR-014's module boundary: this module must not
    /// depend on `RideLinkPlatform.Library` to know what the music directory even is). The app-layer
    /// coordinator that owns both a `Player` and a `LibraryRepository` is the one place both facts
    /// meet, and is responsible for calling `LibraryIndexer.resolvedUrl(for:)` and rebuilding a
    /// `LocalTrackLocation` with that absolute URL's string before constructing a
    /// `PlaybackCommand.load` — exactly mirroring how `com.ridelink.audio.player.ExoPlayerMusicPlayer`
    /// expects an already-openable `content://` URI, never a bare relative reference.
    private func load(quickId: QuickId, location: LocalTrackLocation) async {
        stopPositionTicking()
        playerNode.stop()
        generation += 1
        seekOffsetFrames = 0
        audioFile = nil
        totalFrames = 0
        sampleRate = 1
        cachedState = PlayerState(quickId: quickId)
        emit(cachedState)

        guard let url = URL(string: location.uri) ?? URL(fileURLWithPath: location.uri) as URL? else {
            updateState { _ in PlayerState(quickId: quickId, error: .fileMissing) }
            return
        }
        // Checked explicitly, before ever calling AVAudioFile: a real bug found by actually running
        // this — `AVAudioFile(forReading:)` reports a missing file as
        // `com.apple.coreaudio.avfaudio` error `2003334207` (`kAudioFileUnspecifiedError`, `'wht?'`
        // as a four-char code), the same generic error a genuinely corrupt file can also produce.
        // There is no domain/code in that error CoreAudio documents as distinguishing "does not
        // exist" from "exists but unparseable" the way `PlaybackException.errorCode` does on
        // Android, so file-missing is answered by a direct existence check instead of by
        // interpreting an opaque OSStatus.
        guard FileManager.default.fileExists(atPath: url.path) else {
            updateState { _ in PlayerState(quickId: quickId, error: .fileMissing) }
            return
        }
        do {
            let file = try AVAudioFile(forReading: url)
            audioFile = file
            totalFrames = file.length
            sampleRate = file.processingFormat.sampleRate
            updateState { _ in PlayerState(quickId: quickId, durationMs: durationMs(forFrames: totalFrames)) }
        } catch {
            updateState { _ in PlayerState(quickId: quickId, error: classify(error)) }
        }
    }

    private func playCommand() {
        guard let file = audioFile else { return }
        do {
            if !engine.isRunning { try engine.start() }
        } catch {
            updateState { $0.copy(error: .storageIo) }
            return
        }
        scheduleFromCurrentOffset(file: file, generationAtSchedule: generation)
        playerNode.play()
        updateState { $0.copy(playing: true, error: nil) }
        startPositionTicking()
    }

    private func pauseCommand() {
        guard audioFile != nil else { return }
        seekOffsetFrames = currentAbsoluteFrame()
        playerNode.pause()
        stopPositionTicking()
        updateState { $0.copy(positionMs: durationMs(forFrames: seekOffsetFrames), playing: false) }
    }

    private func seekCommand(positionMs: Int64) {
        guard audioFile != nil else { return }
        let wasPlaying = playerNode.isPlaying
        playerNode.stop()
        generation += 1
        seekOffsetFrames = frames(forMs: positionMs)
        // A seek while paused has no position-tick loop running to observe it, matching the real
        // bug found in ExoPlayerMusicPlayer on Android — updated immediately rather than left stale
        // until the next Play.
        updateState { $0.copy(positionMs: durationMs(forFrames: seekOffsetFrames)) }
        if wasPlaying, let file = audioFile {
            scheduleFromCurrentOffset(file: file, generationAtSchedule: generation)
            playerNode.play()
        }
    }

    private func stopCommand() {
        stopPositionTicking()
        playerNode.stop()
        generation += 1
        seekOffsetFrames = 0
        updateState { $0.copy(positionMs: 0, playing: false) }
    }

    // MARK: - Scheduling and position

    private func scheduleFromCurrentOffset(file: AVAudioFile, generationAtSchedule: Int) {
        let remaining = AVAudioFrameCount(max(0, totalFrames - seekOffsetFrames))
        guard remaining > 0 else { return }
        playerNode.scheduleSegment(
            file, startingFrame: seekOffsetFrames, frameCount: remaining, at: nil, completionCallbackType: .dataPlayedBack
        ) { [weak self] _ in
            guard let self else { return }
            Task { await self.handleSegmentFinished(generationAtSchedule: generationAtSchedule) }
        }
    }

    private func handleSegmentFinished(generationAtSchedule: Int) {
        // The generation guard: a segment scheduled before a since-superseding load/seek/stop must
        // not report "ended" for a track the player has already moved on from.
        guard generationAtSchedule == generation else { return }
        stopPositionTicking()
        updateState { $0.copy(positionMs: $0.durationMs, playing: false) }
    }

    private func startPositionTicking() {
        guard positionTickTask == nil else { return }
        positionTickTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                await self.tickPosition()
                try? await Task.sleep(nanoseconds: Self.positionTickNanoseconds)
            }
        }
    }

    private func stopPositionTicking() {
        positionTickTask?.cancel()
        positionTickTask = nil
    }

    private func tickPosition() {
        updateState { $0.copy(positionMs: durationMs(forFrames: currentAbsoluteFrame())) }
    }

    private func currentAbsoluteFrame() -> AVAudioFramePosition {
        guard let nodeTime = playerNode.lastRenderTime, let playerTime = playerNode.playerTime(forNodeTime: nodeTime) else {
            return seekOffsetFrames
        }
        return min(seekOffsetFrames + playerTime.sampleTime, totalFrames)
    }

    private func durationMs(forFrames frames: AVAudioFramePosition) -> Int64 {
        guard sampleRate > 0 else { return 0 }
        return Int64((Double(frames) / sampleRate * Self.millisecondsPerSecond).rounded())
    }

    private func frames(forMs ms: Int64) -> AVAudioFramePosition {
        AVAudioFramePosition((Double(ms) / Self.millisecondsPerSecond * sampleRate).rounded())
    }

    /// Only ever called once `load`'s own `FileManager.fileExists` check has already ruled out a
    /// missing file — CoreAudio's own error domains do not reliably distinguish "unparseable
    /// container" from "unsupported codec" the way `PlaybackException.errorCode` does on Android, so
    /// this is a best-effort split rather than an exhaustive one: `NSOSStatusErrorDomain` (a real
    /// CoreAudio format-negotiation failure, confirmed by running genuinely non-media bytes through
    /// this) maps to `.unsupportedFormat`; anything else falls to the generic `.decodeFailed` bucket.
    private func classify(_ error: Error) -> MusicFailure {
        let nsError = error as NSError
        return nsError.domain == NSOSStatusErrorDomain ? .unsupportedFormat : .decodeFailed
    }

    private func updateState(_ transform: (PlayerState) -> PlayerState) {
        cachedState = transform(cachedState)
        emit(cachedState)
    }

    private func emit(_ state: PlayerState) {
        stateSink?(state)
    }

    private static let positionTickNanoseconds: UInt64 = 250_000_000
    private static let millisecondsPerSecond: Double = 1000
}

private extension PlayerState {
    /// A small, local `copy`-style helper — `PlayerState` has no memberwise `with`/`copy` of its own
    /// (Swift has no Kotlin-style data-class `copy`), and repeating the full 6-argument initializer
    /// at every call site above would bury each actual change in five unrelated field repeats.
    func copy(
        quickId: QuickId? = nil,
        positionMs: Int64? = nil,
        durationMs: Int64? = nil,
        playing: Bool? = nil,
        rate: Double? = nil,
        error: MusicFailure?? = nil
    ) -> PlayerState {
        PlayerState(
            quickId: quickId ?? self.quickId,
            positionMs: positionMs ?? self.positionMs,
            durationMs: durationMs ?? self.durationMs,
            playing: playing ?? self.playing,
            rate: rate ?? self.rate,
            error: error ?? self.error
        )
    }
}
