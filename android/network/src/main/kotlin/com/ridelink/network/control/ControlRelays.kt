package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.AudioStateMessageTypes
import com.ridelink.core.protocol.ManifestMessageTypes
import com.ridelink.core.protocol.PlaybackMessageTypes
import com.ridelink.core.protocol.QueueMessageTypes
import com.ridelink.core.protocol.ResyncMessageTypes
import com.ridelink.core.protocol.TransferMessageTypes
import com.ridelink.core.protocol.VoiceMessageTypes
import com.ridelink.network.manifest.ManifestRelay
import com.ridelink.network.playback.PlaybackRelay
import com.ridelink.network.resync.ResyncRelay
import com.ridelink.network.transfer.TransferRelay
import com.ridelink.network.voice.AuthenticatedFrameWriter
import com.ridelink.network.voice.VoiceSignalRelay
import kotlinx.serialization.json.JsonObject

/**
 * Every message family that hangs off the authenticated control connection, in one place: `VOICE_*`
 * (PROTOCOL §7), `AUDIO_STATE` (§4.4), `MANIFEST_*` (§8.1), `TRANSFER_*` (§8.2) and Phase 5's
 * `PLAY`/`PAUSE`/`RESUME`/`SEEK`/`NEXT`/`PREVIOUS`/`POSITION_REPORT`/`PLAYBACK_STATE` plus
 * `QUEUE_*` (§5, §9).
 *
 * **Why this type exists.** `config/detekt/detekt.yml` records, in its own words, that the headroom
 * bought for `ControlSessionManager`'s `LargeClass` ceiling "is the last of it" — and Phase 5's
 * relay pushed it over. The discipline the file itself prescribes is to extract rather than raise
 * the number again, exactly as Phase 2a did when the voice wiring first tripped it.
 *
 * What moved here is the wiring only, and it was already five near-identical copies of the same
 * eleven lines: each relay was constructed with the same `localPeerId`/`monotonicNowUs`/`nextSeq`/
 * `activeSessionId` and its own copy of an `authenticatedWriter` supplier that returned non-null
 * only while the trust gate had passed. There is now one such supplier
 * ([authenticatedWriter]) and one place a new family gets attached. No behaviour changed: the
 * suppliers are the same closures over the same session state, still evaluated live on every send
 * rather than captured, which is what makes a send after a session boundary fail closed.
 *
 * **What did not move.** The pre-authentication *allowlist* is still `ControlSessionManager`'s
 * ([ControlSessionManager.PRE_AUTHENTICATION_FRAME_TYPES]) — this type is only ever asked which
 * relay owns a type, never whether a frame is allowed. [countPreAuthenticationDrop] and [deliver]
 * are two deliberately separate questions for that reason: the first is called from *inside* the
 * refusal path, the second only from the authenticated dispatch.
 */
class ControlRelays internal constructor(
    localPeerId: PeerId,
    monotonicNowUs: () -> Long,
    nextSeq: () -> Long,
    activeSessionId: () -> SessionId,
    /** Yields a writer only while the trust gate has passed; `null` at every other moment. */
    authenticatedWriter: () -> AuthenticatedFrameWriter?,
    /**
     * The same, **bound to one control lifetime**: a writer for the surviving connection only while
     * the generation asked for is the one that owns it (STATUS §4 problem 64, ADR-020 Amendment A9).
     *
     * [voice], [playback] and [resync] take it. A `VOICE_*` frame is one step of a negotiation
     * owned by a named control lifetime, and every step between that lifetime's authorisation and
     * the write suspends — so "the authenticated writer, now" is not the connection the frame was
     * authorised for. Phase 5's `PLAY`/`PAUSE`/… and Phase 7's `STATE_REQUEST`/`STATE_SNAPSHOT`
     * both travel through `Phase5FrameQueue`'s own single ordered consumer, the identical shape of
     * suspension (independent-review Blocker 1, mirroring ADR-024 Amendment A2's `PlaybackRelay`
     * exactly — Phase 7's own earlier reasoning that resync was "re-derived per session, not
     * carried on an outbound queue that could outlive one" stopped being true the moment
     * `STATE_SNAPSHOT` was folded into that same queue). `AUDIO_STATE`'s outbound work remains
     * re-derived per session (PROTOCOL §4.4 sends one on every `CONNECTED` regardless of change)
     * and Phase 4's transfers already carry their own generation to a check of their own, so
     * widening this to those two would duplicate a guard rather than add one.
     */
    authenticatedWriterFor: (Long) -> AuthenticatedFrameWriter?,
    /** ADR-023 §3's live authentication generation. Only [playback] needs it — see its doc. */
    currentAuthGeneration: () -> Long,
    /**
     * ADR-025's liveness half: the generation owning the connection that is an authenticated
     * session **right now**, or null when none is. Read at the moment of delivery and only ever
     * *compared* against a frame's own [ReadFrameBinding.generation] — never used to label one.
     */
    liveGeneration: () -> Long?,
) {
    val voice: VoiceSignalRelay =
        VoiceSignalRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriterFor, liveGeneration)

    val audioState: AudioStateRelay =
        AudioStateRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriter, liveGeneration)

    val manifest: ManifestRelay =
        ManifestRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriter, liveGeneration)

    val transfer: TransferRelay =
        TransferRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriter, liveGeneration)

    val playback: PlaybackRelay =
        PlaybackRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriter, currentAuthGeneration)

    /**
     * PROTOCOL §10 (Phase 7). Outbound `STATE_SNAPSHOT`/`STATE_REQUEST` now travel through
     * `Phase5FrameQueue`'s single ordered consumer exactly like [playback]'s own frames do, so
     * [resync] takes the same **generation-bound** writer supplier [voice] and [playback] do
     * (independent-review Blocker 1) rather than the plain [authenticatedWriter] a family whose
     * outbound work is re-derived per session would use. Inbound delivery is still gated on
     * liveness at [deliver], unchanged.
     */
    val resync: ResyncRelay =
        ResyncRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriterFor, liveGeneration)

    /**
     * Records that a frame of [type] was refused because the connection had not passed the trust
     * gate. Counted rather than merely dropped: "it never happened" and "it happened and was
     * refused" are different facts on a diagnostics screen, and only the second lets a test prove
     * the gate held rather than prove nothing was sent.
     *
     * @return true if [type] belongs to a family this object owns.
     */
    fun countPreAuthenticationDrop(type: String): Boolean {
        when (type) {
            in VoiceMessageTypes.ALL -> voice.countPreAuthenticationDrop()
            AudioStateMessageTypes.AUDIO_STATE -> audioState.countPreAuthenticationDrop()
            in ManifestMessageTypes.ALL -> manifest.countPreAuthenticationDrop()
            in TransferMessageTypes.ALL -> transfer.countPreAuthenticationDrop()
            in PlaybackMessageTypes.ALL, in QueueMessageTypes.ALL -> playback.countPreAuthenticationDrop()
            in ResyncMessageTypes.ALL -> resync.countPreAuthenticationDrop()
            else -> return false
        }
        return true
    }

    /**
     * Hands an **authenticated** frame to whichever relay owns its type. Called only from the read
     * loop's post-trust-gate dispatch.
     *
     * @param generation the authentication generation that owned **the connection this frame was
     *   read from, at the moment of the read** (ADR-024 Amendment A7's `ReadFrameBinding`). Every
     *   family below receives it rather than looking one up: a family that looks one up reads
     *   whatever is live when its own work happens to run, which is the whole of ADR-025.
     * @return false if no family owns [type], which PROTOCOL §2 rule 2 makes a non-fatal "ignore
     *   and log" rather than an error — that rule is what lets a newer peer introduce a message
     *   type against an older build.
     */
    fun deliver(
        type: String,
        payload: JsonObject,
        generation: Long,
    ): Boolean {
        when (type) {
            in VoiceMessageTypes.ALL -> voice.deliver(type, payload, generation)
            AudioStateMessageTypes.AUDIO_STATE -> audioState.deliver(payload, generation)
            in ManifestMessageTypes.ALL -> manifest.deliver(type, payload, generation)
            in TransferMessageTypes.ALL -> transfer.deliver(type, payload, generation)
            // Phase 5 is deliberately **not** gated on liveness here, unlike the four families
            // above (ADR-025 §3). A retired generation's playback/queue frame has to reach
            // `Phase5FrameQueue` so that ADR-024 Amendment A6's per-generation loss ledger can
            // attribute it to the session that caused it and surface it as
            // `inboundRetiredLossCount`; refusing it at this seam would silently delete exactly the
            // accounting A6 exists to produce. The generation it carries is what keeps it harmless.
            in PlaybackMessageTypes.ALL -> playback.deliverPlayback(type, payload, generation)
            in QueueMessageTypes.ALL -> playback.deliverQueue(type, payload, generation)
            in ResyncMessageTypes.ALL -> resync.deliver(type, payload, generation)
            else -> return false
        }
        return true
    }

    /**
     * A control-session boundary: the per-session diagnostics counters go back to zero. **No sink is
     * detached here, and that is the fix** (`docs/STATUS.md` §4 problem 54).
     *
     * This used to null all seven sinks, on the reasoning that "a sink attached by the previous
     * session must not survive into the next". That is true of exactly two of the five families and
     * false of the other three, and the difference is *who installed the sink*:
     *
     * - [voice] and [audioState] are installed per authenticated session by `SessionCoordinator`,
     *   which also detaches them — synchronously, at the instant the session is retired, before its
     *   teardown suspends for the first time. They are its sinks to remove, and it removes them.
     * - [manifest], [transfer] and [playback] are installed **once per process**, in the
     *   constructors of `SharedLibraryCoordinator` and `SyncPlaybackCoordinator`. Those coordinators
     *   deliberately outlive a control-session boundary — that is what ADR-023 §3's and ADR-025's
     *   per-frame generation is *for* — and nothing ever re-installs their sinks. Detaching them
     *   here therefore disabled Phase 4 and Phase 5 silently and permanently for the rest of the
     *   process, from the first Stop Discovery onward.
     *
     * So the rule this type now keeps is the narrow one that was always true: **a sink belongs to
     * whoever installed it, and only its installer may remove it.** Counters are this object's own
     * and are still cleared.
     */
    fun resetCounters() {
        voice.resetCounters()
        audioState.resetCounters()
        manifest.resetCounters()
        transfer.resetCounters()
        playback.resetCounters()
        resync.resetCounters()
    }
}
