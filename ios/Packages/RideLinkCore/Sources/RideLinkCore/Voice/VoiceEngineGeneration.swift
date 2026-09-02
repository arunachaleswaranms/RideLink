import Foundation

/// PROTOCOL §7.8's generation guard, applied to a media engine's own callbacks — extracted as a pure
/// function because neither real engine can be constructed in a host unit test (`WebRtcVoiceEngine`
/// needs the Apple audio stack; its Android mirror needs an Android `Context`), so the rule itself has
/// to be testable somewhere both platforms can actually run it, the same reason `VoiceNegotiation` is a
/// separate pure table rather than logic inside `VoiceController`.
///
/// The rule is strict on purpose: a torn-down engine's `active` generation is `nil`, and **every**
/// callback from its now-closed peer connection must be inert then, not just the ones that happen to
/// name a still-remembered id. The bug this replaces was `if let generation, generation != expected` —
/// which reads as "reject a mismatch" but actually skips the check (accepts) the moment `generation` is
/// `nil`, letting a stale callback from a stopped engine straight through.
public enum VoiceEngineGeneration {
    /// True only when `active` is the exact generation the caller is prepared to accept.
    public static func accepts(active: VoiceSessionId?, expected: VoiceSessionId) -> Bool {
        active == expected
    }
}
