import MediaPlayer
import Observation
import RideLinkPlatform

/// Fills in ARCHITECTURE §6.2's "Background / lock screen" row — `MPNowPlayingInfoCenter` +
/// `MPRemoteCommandCenter` — which had been specified since the doc's first draft but had no
/// implementation anywhere until this pass (closure-audit Finding D). `UIBackgroundModes: audio`
/// (`Info.plist`) was already present; this is the other half.
///
/// **Thin routing only** (CLAUDE.md rule 8): every remote-command handler below calls straight into
/// the ONE `MusicCoordinator` instance the app composition root builds, and this type never
/// constructs a second player/queue. The actual `[String: Any]` shaping is
/// `NowPlayingInfoBuilder.build` (`RideLinkPlatform`) — kept separate so it is unit-testable off a
/// device; this class is the untestable half, the two `MediaPlayer` singleton calls themselves.
///
/// **Remote commands are left enabled even with an empty queue**, rather than disabled/re-enabled
/// as the queue empties and fills: every `MusicCoordinator` method this routes to
/// (`play`/`pause`/`seek`/`next`/`previous`) is already a safe no-op against an empty
/// `LocalQueueState` or an unloaded `Player` (`AVAudioEnginePlayer`'s own play/pause/seek commands
/// each guard on `audioFile != nil` and return early; `LocalQueue.reduce` returns the same state
/// with no effects for `.next`/`.previous` against an empty queue) — so there is no incorrect
/// behaviour to guard against, only a control the system could show the user when there is nothing
/// to control. That's judged not worth the extra enable/disable bookkeeping for a first
/// implementation of this integration.
@MainActor
final class NowPlayingController {
    private let musicCoordinator: MusicCoordinator
    private let commandCenter: MPRemoteCommandCenter
    private let infoCenter: MPNowPlayingInfoCenter
    /// Retained only so `deinit` can tear the same targets back down — `MPRemoteCommandCenter.shared()`
    /// is process-wide, and each command's own `removeTarget(_:)` needs the exact token its own
    /// `addTarget(handler:)` returned, paired here rather than pooled into one flat list so `deinit`
    /// never calls `removeTarget` against the wrong command.
    private var commandTokens: [(MPRemoteCommand, Any)] = []

    init(
        musicCoordinator: MusicCoordinator,
        commandCenter: MPRemoteCommandCenter = .shared(),
        infoCenter: MPNowPlayingInfoCenter = .default()
    ) {
        self.musicCoordinator = musicCoordinator
        self.commandCenter = commandCenter
        self.infoCenter = infoCenter
        registerCommandHandlers()
        observeCoordinator()
        updateNowPlayingInfo()
    }

    /// `isolated deinit` (not the default `nonisolated deinit` a class gets) — `MPRemoteCommand`
    /// is not `Sendable`, and `commandTokens` genuinely must only ever be touched on the main actor,
    /// matching every other access to it in this file.
    isolated deinit {
        for (command, token) in commandTokens {
            command.removeTarget(token)
        }
    }

    private func registerCommandHandlers() {
        addTarget(commandCenter.playCommand) { [weak self] _ in
            guard let self else { return .commandFailed }
            self.musicCoordinator.play()
            return .success
        }
        addTarget(commandCenter.pauseCommand) { [weak self] _ in
            guard let self else { return .commandFailed }
            self.musicCoordinator.pause()
            return .success
        }
        addTarget(commandCenter.nextTrackCommand) { [weak self] _ in
            guard let self else { return .commandFailed }
            self.musicCoordinator.next()
            return .success
        }
        addTarget(commandCenter.previousTrackCommand) { [weak self] _ in
            guard let self else { return .commandFailed }
            self.musicCoordinator.previous()
            return .success
        }
        addTarget(commandCenter.changePlaybackPositionCommand) { [weak self] event in
            guard let self, let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self.musicCoordinator.seek(positionMs: Int64((event.positionTime * Self.millisecondsPerSecond).rounded()))
            return .success
        }
    }

    private func addTarget(
        _ command: MPRemoteCommand,
        _ handler: @escaping (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus
    ) {
        commandTokens.append((command, command.addTarget(handler: handler)))
    }

    /// `@Observable`'s own tracking primitive, matching `MusicCoordinator`'s `@Observable` idiom
    /// (`import Observation`) rather than introducing Combine as a second reactive pattern nothing
    /// else in this app uses. `withObservationTracking`'s `onChange` fires at most once, so it
    /// re-arms itself here — the documented way to drive it continuously outside a SwiftUI view
    /// body, where a view's own re-render normally does the re-arming.
    private func observeCoordinator() {
        withObservationTracking {
            _ = musicCoordinator.playerState
            _ = musicCoordinator.queueState
            _ = musicCoordinator.currentEntry
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.updateNowPlayingInfo()
                self.observeCoordinator()
            }
        }
    }

    /// Clears rather than leaves stale metadata on the lock screen once there is no current queue
    /// item — matching `MusicSection`'s own `NowPlayingCard` treatment of the same
    /// `queueState.currentItem == nil` case as "nothing to show", not "show the last thing".
    private func updateNowPlayingInfo() {
        guard musicCoordinator.queueState.currentItem != nil else {
            infoCenter.nowPlayingInfo = nil
            return
        }
        let state = musicCoordinator.playerState
        let entry = musicCoordinator.currentEntry
        infoCenter.nowPlayingInfo = NowPlayingInfoBuilder.build(
            title: entry?.track.title,
            artist: entry?.track.artist,
            durationMs: state.durationMs,
            positionMs: state.positionMs,
            playing: state.playing
        )
    }

    private static let millisecondsPerSecond: Double = 1000
}
