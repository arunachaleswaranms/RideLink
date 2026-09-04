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

    /// Releases the underlying decoder/engine resources. Unlike `VoiceEngine`'s `stop`/`release`
    /// split, there is no hardware reason to keep two lifecycles here — a local player has no
    /// Bluetooth profile to avoid disturbing — so one method covers what a control-link blip and a
    /// deliberate app teardown both need. Idempotent.
    func release() async
}
