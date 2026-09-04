import Foundation

#if os(iOS)
import AVFoundation

/// The music-only half of ARCHITECTURE §6.2's two `AVAudioSession` configurations —
/// `.playback`/`.default`/no options, entirely separate from `IosVoiceAudioSession`'s
/// `.playAndRecord`/`.voiceChat` intercom configuration (this phase's brief §17's explicit ownership
/// boundary: no shared mutable session-config object between them). Mirrors the Android side's own
/// scope exactly — `com.ridelink.audio.player.ExoPlayerMusicPlayer` likewise does not itself request
/// `AudioManager` focus; a *second*, independent focus/session owner for music, distinct from the
/// intercom's, is this phase's honestly-scoped increment, not full duck/coexistence arbitration.
///
/// **What this deliberately does not do**: negotiate with `IosVoiceAudioSession` over which
/// configuration wins when both music and the intercom are active at once. `AVAudioSession` is a
/// single OS-level resource no matter how many Swift types touch it, so once real coexistence
/// behaviour (ducking, VOX/PTT-vs-music, the `profile_coupling: "input_forces_output"` risk
/// CLAUDE.md's `IntercomTransmission` KDoc describes) is built, *something* has to arbitrate between
/// the two configurations — that arbitration is Phase 6's job (CLAUDE.md: "Phase 6 intercom/music
/// coexistence... out of scope for V1's Phase 3"), not this type's. Calling `activate()` while the
/// intercom already owns `.playAndRecord` will simply switch the shared session's category — exactly
/// the coexistence risk this phase is required to expose a clean interface for, never to resolve.
public final class MusicAudioSession: Sendable {
    private let session: AVAudioSession

    public init(session: AVAudioSession = .sharedInstance()) {
        self.session = session
    }

    /// Activates the music-only configuration. Called once, before the first `Player.execute(.play)`
    /// of a ride segment — the same "configure before use" discipline `IosVoiceAudioSession` already
    /// follows for the intercom's own configuration.
    public func activate() throws {
        try session.setCategory(.playback, mode: .default, options: [])
        try session.setActive(true)
    }

    /// Deactivates the session once no music is playing and nothing else (the intercom) needs it —
    /// the composition root's job to call at the right moment, matching
    /// `RideForegroundService.stopMusic`'s "only actually stop if nothing else is active" shape on
    /// Android. `.notifyOthersOnDeactivation` lets another app resume its own audio, matching what a
    /// music-only app is expected to do when it steps back.
    public func deactivate() throws {
        try session.setActive(false, options: [.notifyOthersOnDeactivation])
    }
}
#endif
