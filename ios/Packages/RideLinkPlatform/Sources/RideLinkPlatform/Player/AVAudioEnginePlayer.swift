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
    /// Phase 5's rate-nudge tier (ARCHITECTURE §7.3 names this exact unit). Sits between the player
    /// node and the main mixer so `PlaybackCommand.setRate` has somewhere to land; at the +/-0.2 %
    /// the drift ladder asks for, resampling shifts pitch by ~3.5 cents, which is inaudible.
    ///
    /// It is in the graph unconditionally rather than attached on demand: rewiring a running
    /// `AVAudioEngine` mid-playback would interrupt rendering, which is the opposite of what a drift
    /// correction is for. At `rate == 1.0` it is a pass-through.
    private let varispeed = AVAudioUnitVarispeed()

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
    private var coexistenceGeneration: Int64 = 0

    private var cachedState = PlayerState()
    private var stateSink: (@Sendable (PlayerState) -> Void)?
    private var positionTickTask: Task<Void, Never>?

    public init() {
        engine.attach(playerNode)
        engine.attach(varispeed)
        engine.connect(playerNode, to: varispeed, format: nil)
        engine.connect(varispeed, to: engine.mainMixerNode, format: nil)
    }

    public func execute(_ command: PlaybackCommand) async {
        switch command {
        case let .load(localEntryId, location):
            await load(localEntryId: localEntryId, location: location)
        case .play:
            playCommand()
        case .pause:
            pauseCommand()
        case let .seek(positionMs):
            seekCommand(positionMs: positionMs)
        case .stop:
            stopCommand()
        case .setRate(let rate):
            setRateCommand(rate)
        }
    }

    public var state: PlayerState {
        get async { cachedState }
    }

    public func setStateSink(_ sink: @escaping @Sendable (PlayerState) -> Void) async {
        stateSink = sink
    }

    public func beginCoexistenceLifetime(_ generation: Int64) async {
        if generation > coexistenceGeneration { coexistenceGeneration = generation }
    }

    public func setCoexistenceGain(_ gain: Double, generation: Int64) async -> Bool {
        guard generation == coexistenceGeneration else { return false }
        engine.mainMixerNode.outputVolume = Float(min(max(gain, 0), 1))
        return true
    }

    public func pauseForVoice(generation: Int64, trackToken: String) async -> Bool {
        guard generation == coexistenceGeneration,
              cachedState.localEntryId?.value == trackToken,
              cachedState.playing else { return false }
        pauseCommand()
        return true
    }

    public func resumeAfterVoice(generation: Int64, trackToken: String) async -> Bool {
        guard generation == coexistenceGeneration,
              cachedState.localEntryId?.value == trackToken,
              !cachedState.ended,
              !cachedState.playing else { return false }
        playCommand()
        return true
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
    private func load(localEntryId: LocalEntryId, location: LocalTrackLocation) async {
        stopPositionTicking()
        playerNode.stop()
        generation += 1
        seekOffsetFrames = 0
        audioFile = nil
        totalFrames = 0
        sampleRate = 1
        cachedState = PlayerState(localEntryId: localEntryId)
        emit(cachedState)

        guard let url = URL(string: location.uri) ?? URL(fileURLWithPath: location.uri) as URL? else {
            updateState { _ in PlayerState(localEntryId: localEntryId, error: .fileMissing) }
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
            updateState { _ in PlayerState(localEntryId: localEntryId, error: .fileMissing) }
            return
        }
        do {
            let file = try AVAudioFile(forReading: url)
            audioFile = file
            totalFrames = file.length
            sampleRate = file.processingFormat.sampleRate
            updateState { _ in PlayerState(localEntryId: localEntryId, durationMs: durationMs(forFrames: totalFrames)) }
        } catch {
            updateState { _ in PlayerState(localEntryId: localEntryId, error: classify(error)) }
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
        // STATUS §4 problem 58. `playing: true` used to be published unconditionally, including when
        // `scheduleFromCurrentOffset` had scheduled **nothing** because the offset was already at or
        // past the end of the file. The node then "played" silence forever: no segment, so no
        // completion callback, so `PlayerState.ended` (which requires `!playing`) could never become
        // true and a queue owner could never advance. Android reaches `STATE_ENDED` here and reports
        // `playing = false, positionMs = durationMs`; this is the same observable outcome, and it is
        // exactly the state `handleSegmentFinished` publishes when a segment does play out.
        guard scheduleFromCurrentOffset(file: file, generationAtSchedule: generation) else {
            reportEndOfMedia()
            return
        }
        playerNode.play()
        updateState { $0.copy(playing: true, error: nil) }
        startPositionTicking()
    }

    private func pauseCommand() {
        guard audioFile != nil else { return }
        seekOffsetFrames = currentAbsoluteFrame()
        // `stop()`, never `pause()`: `playCommand` resumes by scheduling a fresh segment from
        // `seekOffsetFrames`, which is only correct on an empty node whose sample clock restarts at
        // zero. `pause()` keeps both the queued remainder and the clock, so a resume played the rest
        // of the track twice, published `ended` while the second copy was still audible, and reported
        // `seekOffsetFrames + sampleTime` — the pre-pause time counted twice. Measured, not inferred:
        // `AVAudioEnginePlayerTests.testPauseThenResumeContinuesFromThePausePointAndEndsExactlyOnce`.
        // The generation bump makes the stopped segment's completion inert, exactly as in `seekCommand`.
        playerNode.stop()
        generation += 1
        stopPositionTicking()
        updateState { $0.copy(positionMs: durationMs(forFrames: seekOffsetFrames), playing: false) }
    }

    private func seekCommand(positionMs: Int64) {
        guard audioFile != nil else { return }
        let wasPlaying = playerNode.isPlaying
        playerNode.stop()
        generation += 1
        // STATUS §4 problem 58: clamped into the loaded file, because `scheduleSegment` is given
        // `seekOffsetFrames` directly and `durationMs(forFrames:)` reports it back to the app.
        // Unclamped, a target past the end reported a `positionMs` **larger than `durationMs`** for a
        // track that had not moved at all, and a negative one would hand `scheduleSegment` a negative
        // `startingFrame`. `ExoPlayer.seekTo` clamps to the period on Android and this side reports
        // whatever it clamped to, so clamping here is what makes the two platforms answer the same
        // question the same way. PROTOCOL §5's `target_position_ms` is already rejected below zero
        // (`PlaybackCodec.isValidPosition`), so the lower bound is defence in depth; the upper bound
        // is not — a peer's `SEEK` names a position in *its* copy and nothing guarantees this side's
        // decoded length is identical.
        seekOffsetFrames = clampedToFile(frames(forMs: positionMs))
        // A seek while paused has no position-tick loop running to observe it, matching the real
        // bug found in ExoPlayerMusicPlayer on Android — updated immediately rather than left stale
        // until the next Play.
        updateState { $0.copy(positionMs: durationMs(forFrames: seekOffsetFrames)) }
        if wasPlaying, let file = audioFile {
            guard scheduleFromCurrentOffset(file: file, generationAtSchedule: generation) else {
                // Seeked to the end while playing: the same end-of-media state a played-out segment
                // reaches, rather than a node left "playing" with nothing scheduled (problem 58).
                reportEndOfMedia()
                return
            }
            playerNode.play()
        }
    }

    private func stopCommand() {
        stopPositionTicking()
        playerNode.stop()
        generation += 1
        seekOffsetFrames = 0
        // Phase 5 brief §38: a stop must never leave a drift nudge in force on the player the next
        // track would inherit. Reset here, not only by the sync coordinator, so the invariant holds
        // even for a purely local Stop.
        varispeed.rate = Float(Self.normalRate)
        updateState { $0.copy(positionMs: 0, playing: false, rate: Self.normalRate) }
    }

    private func setRateCommand(_ rate: Double) {
        varispeed.rate = Float(rate)
        updateState { $0.copy(rate: rate) }
    }

    // MARK: - Scheduling and position

    /// @return false when there is nothing left to schedule — the offset is at the end of the file.
    /// Callers must treat that as end-of-media rather than starting the node anyway (problem 58).
    @discardableResult
    private func scheduleFromCurrentOffset(file: AVAudioFile, generationAtSchedule: Int) -> Bool {
        let remaining = AVAudioFrameCount(max(0, totalFrames - seekOffsetFrames))
        guard remaining > 0 else { return false }
        playerNode.scheduleSegment(
            file, startingFrame: seekOffsetFrames, frameCount: remaining, at: nil, completionCallbackType: .dataPlayedBack
        ) { [weak self] _ in
            guard let self else { return }
            Task { await self.handleSegmentFinished(generationAtSchedule: generationAtSchedule) }
        }
        return true
    }

    /// The one end-of-media state, published from the two places that can reach it without a segment
    /// ever playing out: a `play` or a `seek`-while-playing whose offset is already at the end. It is
    /// deliberately identical to what `handleSegmentFinished` publishes, so `PlayerState.ended` means
    /// one thing however the end was reached.
    private func reportEndOfMedia() {
        stopPositionTicking()
        updateState { $0.copy(positionMs: $0.durationMs, playing: false, error: nil) }
    }

    /// Clamps an absolute frame position into the loaded file. See `seekCommand`.
    private func clampedToFile(_ frame: AVAudioFramePosition) -> AVAudioFramePosition {
        min(max(0, frame), totalFrames)
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
    private static let normalRate: Double = 1.0
}

private extension PlayerState {
    /// A small, local `copy`-style helper — `PlayerState` has no memberwise `with`/`copy` of its own
    /// (Swift has no Kotlin-style data-class `copy`), and repeating the full 6-argument initializer
    /// at every call site above would bury each actual change in five unrelated field repeats.
    func copy(
        localEntryId: LocalEntryId? = nil,
        positionMs: Int64? = nil,
        durationMs: Int64? = nil,
        playing: Bool? = nil,
        rate: Double? = nil,
        error: MusicFailure?? = nil
    ) -> PlayerState {
        PlayerState(
            localEntryId: localEntryId ?? self.localEntryId,
            positionMs: positionMs ?? self.positionMs,
            durationMs: durationMs ?? self.durationMs,
            playing: playing ?? self.playing,
            rate: rate ?? self.rate,
            error: error ?? self.error
        )
    }
}
