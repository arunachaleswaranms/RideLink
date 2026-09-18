import Foundation

/// The local playback engine, as the app sees it — same seam pattern as `RideLinkCore.Voice`'s
/// `VoiceEngine`: every parameter and event payload is a plain value, so `RideLinkCore` stays free
/// of platform types (CLAUDE.md rule 9) and a fake implementation can drive queue/coordinator logic
/// with no `AVAudioEngine`, no file I/O and no decoder at all.
///
/// **A fake implementation proves the coordinator, not the codec** — the same caveat `VoiceEngine`
/// carries. Real decode/output behaviour is only ever proven by the real binding
/// (`RideLinkPlatform.Player`) and, beyond that, by the real-device gate.
///
/// `execute` never throws and reports no synchronous result: a command against a missing or corrupt
/// file resolves to `PlayerState.error` on the next emitted state, exactly the way `.load` loading a
/// deleted file must surface `.fileMissing` rather than crash the caller or return a value the
/// caller would have to check separately from `state`.
public protocol Player: Sendable {
    func execute(_ command: PlaybackCommand) async

    var state: PlayerState { get async }

    /// Pushed on every state change, including position ticks while playing.
    func setStateSink(_ sink: @escaping @Sendable (PlayerState) -> Void) async

    /// Installs the Phase 6 coexistence lifetime. Delayed predecessor effects become inert.
    func beginCoexistenceLifetime(_ generation: Int64) async

    /// Applies one already-interpolated temporary gain step only for the owning lifetime.
    func setCoexistenceGain(_ gain: Double, generation: Int64) async -> Bool

    /// Temporarily pauses only if the exact expected track is still loaded.
    func pauseForVoice(generation: Int64, trackToken: String) async -> Bool

    /// Resumes only the exact track that coexistence previously suppressed.
    func resumeAfterVoice(generation: Int64, trackToken: String) async -> Bool

    /// Releases the underlying decoder/engine resources. Unlike `VoiceEngine`'s `stop`/`release`
    /// split, there is no hardware reason to keep two lifecycles here — a local player has no
    /// Bluetooth profile to avoid disturbing — so one method covers what a control-link blip and a
    /// deliberate app teardown both need. Idempotent.
    func release() async
}

public extension Player {
    func beginCoexistenceLifetime(_: Int64) async {}
    func setCoexistenceGain(_: Double, generation _: Int64) async -> Bool { false }
    func pauseForVoice(generation _: Int64, trackToken _: String) async -> Bool { false }
    func resumeAfterVoice(generation _: Int64, trackToken _: String) async -> Bool { false }
}
