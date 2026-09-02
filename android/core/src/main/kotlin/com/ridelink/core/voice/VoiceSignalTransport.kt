package com.ridelink.core.voice

import com.ridelink.core.protocol.VoiceSignal

/**
 * How a voice signalling message leaves this device.
 *
 * There is exactly one implementation in production and it writes to the **already authenticated**
 * TLS 1.3 control connection (PROTOCOL §7.1). There is no second signalling socket, no fallback
 * transport, and no way for this interface to reach an unauthenticated peer: the only object that
 * implements it is the control session manager, which has no unauthenticated send path for a
 * `VOICE_*` frame at all.
 *
 * The interface is in `core` and takes a [VoiceSignal] — never an envelope, never a socket — so
 * `VoiceController` can be driven by a fake in a unit test without a network, and so the pure layer
 * stays free of platform types (CLAUDE.md rule 9).
 */
interface VoiceSignalTransport {
    /**
     * @return true if the signal was handed to a live authenticated control connection.
     *
     * False is a normal outcome, not an exception: the link may have gone between the negotiation
     * table deciding to send and the write happening. The controller records it and lets
     * PROTOCOL §10's control ladder — the app's only reconnect loop — deal with the link.
     */
    suspend fun send(signal: VoiceSignal): Boolean
}

/**
 * Where a `VOICE_*` frame that has already passed the trust gate is delivered.
 *
 * [submit] must be **non-suspending and never block the caller**: it is called from the control
 * read loop, so it cannot suspend that loop even under a flood of frames from an authenticated
 * peer. It is not, however, dropping in the sense that mattered before [VoiceInputMailbox] existed:
 * an offer or answer cannot silently vanish. Implementations enqueue into a bounded, classified
 * mailbox drained by exactly one consumer — critical inputs (an offer, an answer) held in a bounded
 * FIFO that forces a safe voice-only degrade if it fills, ICE candidates bounded exactly as
 * `PendingCandidates` already bounds them post-negotiation, and repeated state-style updates
 * coalesced to their latest value — rather than an unbounded queue an authenticated-but-compromised
 * peer could grow without limit just by sending frames faster than they are consumed.
 *
 * Ordering is not a nicety here. `VOICE_OFFER` then `VOICE_ICE` must be handled in that order or
 * the candidate is queued when it did not need to be; `VOICE_STATE { closed }` arriving before a
 * late `VOICE_ICE` is what makes the generation guard work. This is the same lesson as the iOS
 * control-event ordering fix (STATUS §2h): a task per event preserves the order events were
 * *created* in, not the order they *run* in. The mailbox preserves FIFO order **within** each of
 * its lanes; only the coalesced lane deliberately collapses everything but the newest value.
 */
interface VoiceSignalSink {
    fun submit(signal: VoiceSignal)
}
