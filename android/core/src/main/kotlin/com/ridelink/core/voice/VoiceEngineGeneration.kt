package com.ridelink.core.voice

import com.ridelink.core.protocol.VoiceSessionId

/**
 * PROTOCOL §7.8's generation guard, applied to a media engine's own callbacks — extracted as a
 * pure function because neither real engine can be constructed in a host unit test (`WebRtcVoiceEngine`
 * needs an Android `Context`; its Apple mirror needs the Apple audio stack), so the rule itself has to
 * be testable somewhere both platforms can actually run it, the same reason [VoiceNegotiation] is a
 * separate pure table rather than logic inside `VoiceController`.
 *
 * The rule is strict on purpose: a torn-down engine's `active` generation is `null`, and **every**
 * callback from its now-closed peer connection must be inert then, not just the ones that happen to
 * name a still-remembered id. The bug this replaces was `active != null && active != expected` —
 * which reads as "reject a mismatch" but actually short-circuits to `false` (accept) the moment
 * `active` is `null`, letting a stale callback from a stopped engine straight through.
 */
object VoiceEngineGeneration {
    /** True only when [active] is the exact generation the caller is prepared to accept. */
    fun accepts(
        active: VoiceSessionId?,
        expected: VoiceSessionId,
    ): Boolean = active == expected
}
