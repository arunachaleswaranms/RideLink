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

    /// Deactivates the session. **Currently unused — no composition root calls this**
    /// (this phase's closure-audit hardening pass, Finding F). That is a deliberate deferral, not an
    /// oversight this doc used to describe as already wired: safely deciding *when* to deactivate
    /// requires knowing whether the intercom also currently needs `AVAudioSession` (a shared,
    /// single OS-level resource — see the class doc above), which is exactly the arbitration Phase 6
    /// owns. Calling this unconditionally whenever music-only playback stops would be safe in
    /// isolation but is not yet wired to avoid the false impression that doing so is a complete
    /// answer once the intercom can also be active; today the session is deliberately left active
    /// rather than risk tearing down a session the intercom might depend on. Revisit when Phase 6
    /// arbitration exists — `RideForegroundService.stopMusic`'s "only actually stop if nothing else
    /// is active" shape on Android is the pattern to match once there is something here to check
    /// against. `.notifyOthersOnDeactivation` lets another app resume its own audio, matching what a
    /// music-only app is expected to do when it steps back, whenever this method is actually wired.
    public func deactivate() throws {
        try session.setActive(false, options: [.notifyOthersOnDeactivation])
    }
}
#endif
