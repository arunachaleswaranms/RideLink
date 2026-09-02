package com.ridelink.network.voice

import android.content.Context
import com.ridelink.core.protocol.VoiceSessionId
import com.ridelink.core.voice.AudioProcessingConfig
import com.ridelink.core.voice.AudioProcessingStatus
import com.ridelink.core.voice.IceCandidateType
import com.ridelink.core.voice.IceGatheringState
import com.ridelink.core.voice.MediaTransportState
import com.ridelink.core.voice.SdpKind
import com.ridelink.core.voice.VoiceEngine
import com.ridelink.core.voice.VoiceEngineConfig
import com.ridelink.core.voice.VoiceEngineDiagnostics
import com.ridelink.core.voice.VoiceEngineError
import com.ridelink.core.voice.VoiceEngineEvent
import com.ridelink.core.voice.VoiceEngineGeneration
import com.ridelink.core.voice.VoiceStatsMapping
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The real WebRTC media plane on Android (ADR-003, ADR-020), behind `network/voice` so it stays
 * replaceable and so `org.webrtc` types appear in exactly one file.
 *
 * Two properties of this class are security-relevant rather than cosmetic:
 *
 * 1. **ICE is configured with an empty server list** and there is no code path here that can add
 *    one — [PeerConnection.RTCConfiguration.iceServers] is set from `emptyList()`, and
 *    [com.ridelink.core.voice.VoiceEngineConfig] deliberately has no field to carry one
 *    (PROTOCOL §7.6). No STUN, no TURN, no accidental egress.
 * 2. **Nothing here logs an SDP or a candidate string.** Candidates are reduced to their `typ`
 *    ([IceCandidateType]) before anything else looks at them, so the address and port are never
 *    even extracted (PROTOCOL §7.7).
 *
 * `RTCInitializeSSL`'s Android equivalent — [PeerConnectionFactory.initialize] — is process-global
 * and idempotent here via [initialized].
 *
 * **This file has never run on a phone.** `PeerConnectionFactory.initialize` requires an Android
 * `Context`, so it cannot be exercised by a JVM unit test, and no emulator or device is available
 * in this environment. `VoiceController` is fully tested against
 * [com.ridelink.core.voice.VoiceEngine] fakes; the real media path on Android is
 * **REAL-DEVICE AUDIO GATE PENDING** and must not be reported otherwise (docs/STATUS.md §7).
 */
class WebRtcVoiceEngine(
    private val context: Context,
) : VoiceEngine {
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var localSender: RtpSender? = null

    @Volatile
    private var generation: VoiceSessionId? = null

    @Volatile
    private var sink: ((VoiceEngineEvent) -> Unit)? = null

    @Volatile
    private var current = VoiceEngineDiagnostics()

    /** Whichever description this side authored last, so `setLocalDescription` has something to set. */
    @Volatile
    private var pendingLocal: SessionDescription? = null

    override val diagnostics: VoiceEngineDiagnostics get() = current

    override fun setEventSink(sink: (VoiceEngineEvent) -> Unit) {
        this.sink = sink
    }

    override suspend fun start(config: VoiceEngineConfig): Result<Unit> {
        stop()
        return runCatching {
            ensureFactoryInitialized()
            // Reused across rebuilds when one already exists: constructing a factory builds a new
            // audio device module, which reopens `AudioRecord` and moves the Bluetooth route. See
            // `VoiceEngine.stop`.
            val f =
                factory ?: run {
                    val adm = buildAudioDeviceModule(config.audioProcessing)
                    audioDeviceModule = adm
                    PeerConnectionFactory
                        .builder()
                        .setAudioDeviceModule(adm)
                        .createPeerConnectionFactory()
                        .also { factory = it }
                }

            val rtcConfig =
                PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>()).apply {
                    // PROTOCOL §7.6. Both peers are on one LAN or one phone's hotspot, so host
                    // candidates suffice and an empty list removes an accidental-egress path.
                    iceServers = emptyList()
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    // Gather continually so a Wi-Fi -> hotspot interface change on a moving
                    // motorcycle can surface a fresh host candidate without a full renegotiation.
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    // Belt and braces against a relayed path: with no TURN server configured there
                    // is nothing to relay through, and ALL still means "host and reflexive and
                    // relay if they existed", so the empty server list above is what does the work.
                    iceTransportsType = PeerConnection.IceTransportsType.ALL
                    // A single audio m-line, bundled and rtcp-muxed: one UDP flow, which is what a
                    // narrow duplex link wants.
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                }

            val pc =
                f.createPeerConnection(rtcConfig, Observer(config.voiceSessionId))
                    ?: error("createPeerConnection returned null")
            peerConnection = pc

            val source =
                audioSource ?: f.createAudioSource(audioConstraints(config.audioProcessing)).also { audioSource = it }
            val track = localTrack ?: f.createAudioTrack(config.localTrackId, source).also { localTrack = it }
            track.setEnabled(true)
            // One audio track per peer (ADR-003). Full duplex: this side always adds a sender, and
            // muting later disables the track rather than removing it (PROTOCOL §7.4).
            localSender = pc.addTrack(track, listOf(STREAM_ID))

            generation = config.voiceSessionId
            current =
                VoiceEngineDiagnostics(
                    transportState = MediaTransportState.NEW,
                    localAudioTrackPresent = true,
                    audioProcessing = requestedProcessingStatus(config.audioProcessing),
                )
        }.onFailure { failure ->
            // Not a media callback: this is start() reporting its own outcome, synchronously, and
            // must be delivered even though `generation` was never installed for this attempt —
            // the strict guard in `emit` exists for the *peer connection's* callbacks, which by
            // definition cannot exist yet if `start` itself failed. Reporting it through `emit`
            // would silently drop every start failure, which is the mistake §9 warns against.
            sink?.invoke(VoiceEngineEvent.Failed(config.voiceSessionId, platformFailure(failure)))
        }
    }

    override suspend fun createOffer(): Result<Unit> = createDescription(isOffer = true)

    override suspend fun createAnswer(): Result<Unit> = createDescription(isOffer = false)

    @Suppress("ReturnCount") // one early-out per failure the platform can report, in call order
    private suspend fun createDescription(isOffer: Boolean): Result<Unit> {
        val pc = peerConnection ?: return notStarted()
        val id = generation ?: return notStarted()
        val created =
            suspendCancellableCoroutine { cont ->
                val observer =
                    object : SingleShotSdpObserver() {
                        override fun onCreateSuccess(sdp: SessionDescription) = complete { cont.resume(Result.success(sdp)) }

                        override fun onCreateFailure(error: String?) = complete { cont.resume(Result.failure(SdpFailure(error.orEmpty()))) }
                    }
                val constraints = MediaConstraints()
                if (isOffer) pc.createOffer(observer, constraints) else pc.createAnswer(observer, constraints)
            }
        val sdp = created.getOrElse { return Result.failure(it) }
        pendingLocal = sdp
        setLocalDescription(pc, sdp).getOrElse { return Result.failure(it) }
        emit(
            id,
            if (isOffer) {
                VoiceEngineEvent.OfferCreated(id, sdp.description)
            } else {
                VoiceEngineEvent.AnswerCreated(id, sdp.description)
            },
        )
        return Result.success(Unit)
    }

    private suspend fun setLocalDescription(
        pc: PeerConnection,
        sdp: SessionDescription,
    ): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            pc.setLocalDescription(
                object : SingleShotSdpObserver() {
                    override fun onSetSuccess() = complete { cont.resume(Result.success(Unit)) }

                    override fun onSetFailure(error: String?) = complete { cont.resume(Result.failure(SdpFailure(error.orEmpty()))) }
                },
                sdp,
            )
        }

    override suspend fun applyRemoteDescription(
        kind: SdpKind,
        sdp: String,
    ): Result<Unit> {
        val pc = peerConnection ?: return notStarted()
        val type = if (kind == SdpKind.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        return suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(
                object : SingleShotSdpObserver() {
                    override fun onSetSuccess() = complete { cont.resume(Result.success(Unit)) }

                    override fun onSetFailure(error: String?) = complete { cont.resume(Result.failure(SdpFailure(error.orEmpty()))) }
                },
                SessionDescription(type, sdp),
            )
        }
    }

    /**
     * A candidate WebRTC itself refuses is **counted, not fatal** (PROTOCOL §7.4): a peer can send a
     * syntactically valid line the stack still dislikes, and one bad candidate must not fail a
     * negotiation whose other candidates are fine.
     */
    override suspend fun addRemoteCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMlineIndex: Int,
    ): Result<Unit> {
        val pc = peerConnection ?: return notStarted()
        val type = IceCandidateType.fromCandidateLine(candidate)
        current = current.copy(observedCandidateTypes = current.observedCandidateTypes + type)
        return runCatching {
            pc.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMlineIndex, candidate))
            Unit
        }.recoverCatching { throw VoiceEngineRejectedCandidate(type) }
    }

    /**
     * Gates *transmission*, not the hardware. ARCHITECTURE §6.3: the capture device stays open for a
     * whole ride segment and PTT/VOX/mute gate what leaves — thrashing a Bluetooth endpoint between
     * its media and duplex profiles per utterance is the worst thing this product can do to music.
     */
    override fun setMicrophoneMuted(muted: Boolean) {
        localTrack?.setEnabled(!muted)
    }

    override suspend fun stop() {
        // Order matters: drop the generation first, so any delegate call the platform makes during
        // teardown is already inert by the PROTOCOL §7.8 generation guard rather than racing it.
        generation = null
        pendingLocal = null
        localSender = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        // The factory, the audio device module and the local track deliberately survive — see the
        // interface doc. Disposing them here would close `AudioRecord`, and reopening it is an
        // audible Bluetooth profile renegotiation on every control-plane blip.
        current =
            current.copy(
                transportState = MediaTransportState.CLOSED,
                iceGatheringState = IceGatheringState.NEW,
                remoteAudioTrackPresent = false,
                selectedLocalType = null,
                selectedRemoteType = null,
                dtlsState = null,
            )
    }

    override suspend fun release() {
        stop()
        runCatching { localTrack?.dispose() }
        localTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null
        current = VoiceEngineDiagnostics(transportState = MediaTransportState.CLOSED)
    }

    override suspend fun refreshDiagnostics() {
        val pc = peerConnection ?: return
        // RTCStatsReport is flattened to primitives here, before anything else sees it, for the same
        // reason the iOS side must do it inside the callback: the values that leave are the ones
        // PROTOCOL §7.7 permits, and a report object carried around invites logging the rest of it.
        val flat: Map<String, Map<String, String>> =
            suspendCancellableCoroutine { cont ->
                pc.getStats(
                    RTCStatsCollectorCallback { report: RTCStatsReport ->
                        cont.resume(
                            report.statsMap.mapValues { (_, stat) ->
                                buildMap {
                                    put(VoiceStatsMapping.TYPE_KEY, stat.type)
                                    stat.members.forEach { (k, v) -> put(k, v.toString()) }
                                }
                            },
                        )
                    },
                )
            }
        current = VoiceStatsMapping.merge(current, flat)
    }

    // --- platform plumbing --------------------------------------------------------------------

    private fun ensureFactoryInitialized() {
        if (initialized.compareAndSet(false, true)) {
            // No field trials are configured, no native tracing is enabled, and WebRTC's `Metrics`
            // histograms — which are local-only and have no upload path in the library at all — are
            // left disabled. Nothing in this app collects or transmits them (ARCHITECTURE §11
            // rule 2). `setFieldTrials("")` would state the first of those explicitly but is
            // deprecated upstream, and an empty field-trial string is the default anyway, so a
            // comment is the honest way to say it rather than a deprecated call.
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
            )
        }
    }

    /**
     * ADR-003 requires AEC/NS/AGC to be individually disableable so a suspect stage can be turned
     * off and the result measured again (FR-005). Hardware AEC/NS is preferred when the device
     * reports it — whether it actually copes with a helmet unit at speed is a measurement nobody
     * has taken, and nothing here may be read as evidence about it.
     */
    private fun buildAudioDeviceModule(processing: AudioProcessingConfig): JavaAudioDeviceModule =
        JavaAudioDeviceModule
            .builder(context)
            .setUseHardwareAcousticEchoCanceler(processing.echoCancellation)
            .setUseHardwareNoiseSuppressor(processing.noiseSuppression)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .createAudioDeviceModule()

    /** Voice-oriented and mono. Not tuned further: ADR-003 says measure before tuning. */
    private fun audioConstraints(processing: AudioProcessingConfig): MediaConstraints =
        MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", processing.echoCancellation.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", processing.noiseSuppression.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", processing.autoGainControl.toString()))
        }

    private fun requestedProcessingStatus(processing: AudioProcessingConfig) =
        AudioProcessingStatus(
            echoCancellationEnabled = processing.echoCancellation,
            noiseSuppressionEnabled = processing.noiseSuppression,
            autoGainControlEnabled = processing.autoGainControl,
            hardwareEchoCancellation = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(),
        )

    private fun emit(
        expected: VoiceSessionId,
        event: VoiceEngineEvent,
    ) {
        // Every emission is generation-checked at the source as well as in the table, so a callback
        // from a torn-down connection cannot even reach the controller's queue. Strict on purpose
        // (com.ridelink.core.voice.VoiceEngineGeneration): after `stop()`, `generation` is `null`,
        // and a callback naming any generation — including a real one that used to be current — must
        // be inert then. This is for *peer-connection callbacks only*; `start()` reports its own
        // failure directly through `sink`, never through this method (see `start`'s `onFailure`).
        if (!VoiceEngineGeneration.accepts(generation, expected)) return
        sink?.invoke(event)
    }

    private fun <T> notStarted(): Result<T> = Result.failure(EngineNotStarted)

    private fun platformFailure(t: Throwable): VoiceEngineError =
        when (t) {
            is SdpFailure -> VoiceEngineError.SdpFailed(t.message.orEmpty())
            is VoiceEngineRejectedCandidate -> VoiceEngineError.CandidateRejected(t.type)
            else -> VoiceEngineError.PlatformFailure(t::class.java.simpleName)
        }

    /**
     * `org.webrtc`'s observers can, on some paths, report both success and failure. Resuming a
     * continuation twice crashes the process, so every observer here latches — the same
     * single-resume discipline the Phase 1a `SingleResumeContinuation` fix established on iOS
     * (STATUS §2e fix 2).
     */
    private abstract class SingleShotSdpObserver : SdpObserver {
        private val done = AtomicBoolean(false)

        protected fun complete(block: () -> Unit) {
            if (done.compareAndSet(false, true)) block()
        }

        override fun onCreateSuccess(sdp: SessionDescription) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String?) = Unit

        override fun onSetFailure(error: String?) = Unit
    }

    private inner class Observer(
        private val id: VoiceSessionId,
    ) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val type = IceCandidateType.fromCandidateLine(candidate.sdp)
            current = current.copy(observedCandidateTypes = current.observedCandidateTypes + type)
            emit(
                id,
                VoiceEngineEvent.LocalCandidateGathered(
                    voiceSessionId = id,
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid.takeIf { it.isNotEmpty() },
                    sdpMlineIndex = candidate.sdpMLineIndex,
                ),
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            val mapped = newState.toTransportState()
            current = current.copy(transportState = mapped)
            emit(id, VoiceEngineEvent.TransportStateChanged(id, mapped))
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            current = current.copy(iceGatheringState = newState.toGatheringState())
        }

        override fun onAddTrack(
            receiver: RtpReceiver,
            streams: Array<out MediaStream>,
        ) {
            val present = receiver.track()?.kind() == MediaStreamTrack.AUDIO_TRACK_KIND
            current = current.copy(remoteAudioTrackPresent = present)
            emit(id, VoiceEngineEvent.RemoteTrackChanged(id, present))
        }

        override fun onRemoveTrack(receiver: RtpReceiver) {
            current = current.copy(remoteAudioTrackPresent = false)
            emit(id, VoiceEngineEvent.RemoteTrackChanged(id, false))
        }

        // Not a failure: PROTOCOL §7.6 expects only host candidates, and a gathering error against
        // a server we never configured is noise. Counted by type, never by address.
        override fun onIceCandidateError(event: IceCandidateErrorEvent) = Unit

        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = Unit

        // ADR-003 / CLAUDE.md rule 3: control traffic never rides a WebRTC DataChannel, so an
        // inbound one is ignored rather than accepted.
        override fun onRenegotiationNeeded() = Unit

        override fun onTrack(transceiver: RtpTransceiver) = Unit
    }

    private companion object {
        const val STREAM_ID = "ridelink"
        val initialized = AtomicBoolean(false)
    }

    private object EngineNotStarted : Exception("voice engine not started")

    private class SdpFailure(
        message: String,
    ) : Exception(message)

    private class VoiceEngineRejectedCandidate(
        val type: IceCandidateType,
    ) : Exception("candidate rejected")
}

private fun PeerConnection.PeerConnectionState.toTransportState(): MediaTransportState =
    when (this) {
        PeerConnection.PeerConnectionState.NEW -> MediaTransportState.NEW
        PeerConnection.PeerConnectionState.CONNECTING -> MediaTransportState.CONNECTING
        PeerConnection.PeerConnectionState.CONNECTED -> MediaTransportState.CONNECTED
        PeerConnection.PeerConnectionState.DISCONNECTED -> MediaTransportState.DISCONNECTED
        PeerConnection.PeerConnectionState.FAILED -> MediaTransportState.FAILED
        PeerConnection.PeerConnectionState.CLOSED -> MediaTransportState.CLOSED
    }

private fun PeerConnection.IceGatheringState.toGatheringState(): IceGatheringState =
    when (this) {
        PeerConnection.IceGatheringState.NEW -> IceGatheringState.NEW
        PeerConnection.IceGatheringState.GATHERING -> IceGatheringState.GATHERING
        PeerConnection.IceGatheringState.COMPLETE -> IceGatheringState.COMPLETE
    }
