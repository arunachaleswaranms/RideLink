package com.ridelink.network.voice

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.VoiceSignal
import com.ridelink.core.protocol.VoiceSignalCodec
import com.ridelink.core.protocol.VoiceSignalRejection
import com.ridelink.core.voice.VoiceSignalSink
import com.ridelink.core.voice.VoiceSignalTransport
import com.ridelink.network.control.ControlMessages
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes one already-built frame to the surviving **authenticated** control connection.
 *
 * A named type rather than an inline lambda so the suspend signature is unambiguous at both ends, and
 * so the thing being handed across the seam has a name that says what it is allowed to do.
 */
internal fun interface AuthenticatedFrameWriter {
    suspend fun write(envelope: com.ridelink.core.protocol.Envelope)
}

/**
 * The `VOICE_*` half of the control plane: decode inbound frames, encode outbound ones, and count
 * what was refused.
 *
 * **Why this is a separate type.** `ControlSessionManager` was already the largest class in the
 * codebase and `docs/STATUS.md` §4 problem 18 predicted it would get worse — Phase 2a is exactly the
 * change that would have made it worse. detekt's `LargeClass` fired on the first attempt to add the
 * voice wiring inline, and the answer was to extract rather than to raise the threshold. Everything
 * here is genuinely separable: none of it touches the session, the handshake, pairing, reconnect or
 * the clock.
 *
 * **What it deliberately does not decide.** Whether a frame is *allowed* is not this type's business
 * and cannot be: PROTOCOL §7.1's gate is `ControlSessionManager`'s pre-authentication frame
 * allowlist, which drops every `VOICE_*` type before the read loop's dispatch ever reaches
 * [deliver]. What this type adds is the *encoding*, the *bounds*, and the counters — so that a
 * refused frame is a visible fact rather than an absence.
 *
 * The one guard it does enforce is on the way out: [send] refuses unless the caller's socket supplier
 * yields an **authenticated** connection, so a `VoiceController` wired up by mistake before the trust
 * gate still could not put an SDP on a socket.
 */
class VoiceSignalRelay internal constructor(
    private val localPeerId: PeerId,
    private val monotonicNowUs: () -> Long,
    private val nextSeq: () -> Long,
    private val activeSessionId: () -> SessionId,
    /**
     * Yields a writer for the surviving connection **only while it is authenticated and owned by the
     * generation asked for**, and null otherwise (STATUS §4 problem 64, ADR-020 Amendment A9).
     *
     * A supplier rather than a socket because the connection comes and goes and this type must never
     * hold one across a teardown — and a *generation-bound* supplier because "is a session live" and
     * "is **this** frame's session live" are different questions. It resolves the writer and the
     * generation from the one immutable `AuthenticatedConnection` record, so there is no ordering in
     * which a socket can be handed out under a generation that is not the one its own activation
     * assigned.
     */
    private val authenticatedWriterFor: (Long) -> AuthenticatedFrameWriter?,
    /**
     * ADR-025's liveness half: the generation owning the connection that is an authenticated session
     * **right now**, or null when none is. A frame's own authorising generation is *compared*
     * against it and never replaced by it — reading a live value to label a frame is ADR-024
     * Amendment A7's defect.
     */
    private val liveGeneration: () -> Long?,
) : VoiceSignalTransport {
    @Volatile
    var sink: VoiceSignalSink? = null

    private val rejections = ConcurrentHashMap<VoiceSignalRejection, Int>()

    @Volatile
    var droppedPreAuthentication: Int = 0
        private set

    /**
     * How many `VOICE_*` frames were dropped because the control session that authorised their read
     * had already been replaced (ADR-025 §2). Distinct from [droppedPreAuthentication]: that one
     * counts a peer that was never authenticated, this one counts a peer that *was*, on a connection
     * that is gone.
     */
    @Volatile
    var droppedRetiredGeneration: Int = 0
        private set

    /**
     * How many **outbound** `VOICE_*` frames were refused because the control lifetime that
     * authorised them no longer owns the surviving connection (STATUS §4 problem 64, ADR-020
     * Amendment A9).
     *
     * The mirror of [droppedRetiredGeneration], and counted for the same reason: a negotiation that
     * degrades because its offer could not be placed is a fact about the ride, and "the link was
     * gone" and "the link was *replaced*" are different facts — only the second one says a successor
     * exists to rebuild under.
     */
    @Volatile
    var droppedRetiredGenerationOutbound: Int = 0
        private set

    val rejectionCounts: Map<VoiceSignalRejection, Int> get() = rejections.toMap()

    /**
     * **A frame authorised by one control lifetime may be written only to that lifetime's
     * connection** (STATUS §4 problem 64, ADR-020 Amendment A9).
     *
     * Before this, `send` asked for "the authenticated writer" at the moment the write happened —
     * which is not the moment the frame was authorised, because everything between the two suspends:
     * the mailbox's single consumer, the engine's offer/answer callbacks, `withContext(ioDispatcher)`,
     * a write lock, a flush. So a `VOICE_OFFER` authorised by a lifetime that had since ended was
     * written to its **successor's** socket, where the peer accepted it as current — and the
     * predecessor's own boundary, arriving afterwards, then tore this side's media down while the
     * peer was still negotiating. That is ADR-024 Amendment A7's rule in the outbound direction: a
     * live value may be *compared* against an authorisation, never substituted for one.
     *
     * A refusal is a plain `false`, which is the outcome [VoiceSignalTransport] already defines and
     * `VoiceController.degradeIfUnsent` already answers with `NegotiationSendFailed` — never with
     * `ControlLinkLost`, for the reason ADR-020 Amendment A6 gives. It is counted rather than silent.
     */
    override suspend fun send(
        signal: VoiceSignal,
        controlGeneration: Long?,
    ): Boolean {
        // One lookup, one refusal: a null authorisation and a generation that no longer owns the
        // surviving connection are the same fact -- there is no connection this frame may go on.
        val write = controlGeneration?.let(authenticatedWriterFor)
        if (write == null) {
            droppedRetiredGenerationOutbound += 1
            return false
        }
        return runCatching {
            write.write(
                ControlMessages.voiceSignal(
                    localPeerId = localPeerId,
                    sessionId = activeSessionId(),
                    seq = nextSeq(),
                    sentAtMonoUs = monotonicNowUs(),
                    signal = signal,
                ),
            )
        }.isSuccess
    }

    /**
     * PROTOCOL §7.4: parse, bounds-check, hand over — and on any failure, **drop the frame and keep
     * the connection**. The framing was intact; only this message's shape was wrong. An
     * attacker-supplied SDP must not be able to end a ride's control plane, and the bounds are
     * checked before the string reaches the media stack, so it cannot make the reader allocate
     * either.
     *
     * Called only from the read loop's authenticated dispatch.
     *
     * **ADR-025 §2.** [generation] is the frame's own authority — the generation that owned the
     * connection it was read from, at the moment of the read. A frame whose session has since been
     * replaced is refused here, before it can become a `VoiceInput`, because `VoiceController` is
     * deliberately **retained across a control reconnect** (the capture device stays open for the
     * ride segment, ARCHITECTURE §6.3/§6.4) and `VoiceNegotiation`'s own `voice_session_id` guards
     * prove voice-session ownership, not control-session ownership. Concretely: a stale
     * `VOICE_STATE { state: "closed" }` carrying no `voice_session_id` is not a generation mismatch
     * to that table, so it would tear down the *successor* session's live media; and a stale
     * `VOICE_OFFER` arriving after `ControlLinkLost` has reset the table to `IDLE` would start a
     * negotiation on the successor's connection.
     *
     * This is a refusal rather than a relabelling on purpose: there is no ledger here that a retired
     * generation's frame has to reach, unlike Phase 5's (ADR-024 Amendment A6).
     *
     * **This check is liveness, not provenance, and it is not on its own sufficient** (STATUS §4
     * problem 60). Nothing spans the read of [liveGeneration] and the [VoiceSignalSink.submit]
     * below — `endConnection` clears the authenticated record from another coroutine — so a frame
     * can pass here and be overtaken by the entire teardown before it is queued. What makes that
     * harmless is that [generation] travels with it: `VoiceInputMailbox` refuses a signal whose
     * admitting generation has been retired, whenever it arrives.
     */
    fun deliver(
        type: String,
        payload: JsonObject,
        generation: Long,
    ) {
        if (generation != liveGeneration()) {
            droppedRetiredGeneration += 1
            return
        }
        when (val result = VoiceSignalCodec.parse(type, payload)) {
            // [generation] — this frame's own, from its `ReadFrameBinding` — and never
            // `liveGeneration()`. They were just proved equal, so substituting the live read would
            // be indistinguishable *here* and wrong everywhere downstream: the sink's consumer
            // decides against the generation it is given long after this returns, and a value read
            // from live state is exactly ADR-024 Amendment A7's defect (STATUS §4 problem 60).
            is VoiceSignalCodec.Result.Parsed -> sink?.submit(result.signal, generation)
            is VoiceSignalCodec.Result.Rejected -> rejections.merge(result.reason, 1) { a, b -> a + b }
        }
    }

    /**
     * A `VOICE_*` frame arrived on a connection that had not passed the trust gate. Counted rather
     * than merely dropped: PROTOCOL §7.1's whole point is that voice is inert before authentication,
     * and "it never happened" and "it happened and was refused" are different facts on a diagnostics
     * screen — and only the second one tells you something tried.
     */
    fun countPreAuthenticationDrop() {
        droppedPreAuthentication += 1
    }

    /** See [ControlRelays.resetCounters]: the diagnostics counters, never [sink]. */
    fun resetCounters() {
        rejections.clear()
        droppedPreAuthentication = 0
        droppedRetiredGeneration = 0
        droppedRetiredGenerationOutbound = 0
    }
}
