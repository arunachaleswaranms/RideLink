import Foundation

/// The music-facing adapter for ARCHITECTURE §6.2's process-global audio resource. It never writes
/// `AVAudioSession` itself: both music and voice report their needs to the one
/// `IosAudioSessionCoordinator`, where voice-active configuration wins and music-only configuration
/// is restored after voice closes. This keeps category/mode/active writes under one owner.
public final class MusicAudioSession: Sendable {
    private let coordinator: IosAudioSessionCoordinator

    public init(coordinator: IosAudioSessionCoordinator) {
        self.coordinator = coordinator
    }

    /// Activates the music-only configuration. Called once, before the first `Player.execute(.play)`
    /// of a ride segment — the same "configure before use" discipline `IosVoiceAudioSession` already
    /// follows for the intercom's own configuration.
    public func activate() async throws {
        try await coordinator.activateMusic()
    }

    /// Reports that music no longer needs the shared resource. The central coordinator keeps the
    /// session active if voice still owns it.
    public func deactivate() async throws {
        try await coordinator.deactivateMusic()
    }
}
