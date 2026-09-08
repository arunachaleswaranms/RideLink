package com.ridelink.network.control

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SessionId
import com.ridelink.core.protocol.AudioStateMessageTypes
import com.ridelink.core.protocol.ManifestMessageTypes
import com.ridelink.core.protocol.PlaybackMessageTypes
import com.ridelink.core.protocol.QueueMessageTypes
import com.ridelink.core.protocol.TransferMessageTypes
import com.ridelink.core.protocol.VoiceMessageTypes
import com.ridelink.network.manifest.ManifestRelay
import com.ridelink.network.playback.PlaybackRelay
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
) {
    val voice: VoiceSignalRelay =
        VoiceSignalRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriter)

    val audioState: AudioStateRelay =
        AudioStateRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriter)

    val manifest: ManifestRelay =
        ManifestRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriter)

    val transfer: TransferRelay =
        TransferRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriter)

    val playback: PlaybackRelay =
        PlaybackRelay(localPeerId, monotonicNowUs, nextSeq, activeSessionId, authenticatedWriter)

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
            else -> return false
        }
        return true
    }

    /**
     * Hands an **authenticated** frame to whichever relay owns its type. Called only from the read
     * loop's post-trust-gate dispatch.
     *
     * @return false if no family owns [type], which PROTOCOL §2 rule 2 makes a non-fatal "ignore
     *   and log" rather than an error — that rule is what lets a newer peer introduce a message
     *   type against an older build.
     */
    fun deliver(
        type: String,
        payload: JsonObject,
    ): Boolean {
        when (type) {
            in VoiceMessageTypes.ALL -> voice.deliver(type, payload)
            AudioStateMessageTypes.AUDIO_STATE -> audioState.deliver(payload)
            in ManifestMessageTypes.ALL -> manifest.deliver(type, payload)
            in TransferMessageTypes.ALL -> transfer.deliver(type, payload)
            in PlaybackMessageTypes.ALL -> playback.deliverPlayback(type, payload)
            in QueueMessageTypes.ALL -> playback.deliverQueue(type, payload)
            else -> return false
        }
        return true
    }

    /**
     * A session boundary. Every sink attached by the previous session is detached, so a coordinator
     * still holding one cannot receive a frame belonging to the next — the hazard `docs/STATUS.md`
     * §2h fixed for control events, applied to every relay at once.
     */
    fun reset() {
        voice.reset()
        audioState.reset()
        manifest.reset()
        transfer.reset()
        playback.reset()
    }
}
