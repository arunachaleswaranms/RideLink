import Foundation

/// ICE candidate types, by PROTOCOL §7.6. Only `.host` is expected: RideLink configures an **empty**
/// ICE server list, so a reflexive or relayed candidate cannot legitimately occur.
///
/// The others exist so their appearance can be *reported* rather than silently tolerated — an `srflx`
/// candidate would mean something contacted a STUN server, which is the accidental-egress path ADR-003
/// removed on purpose.
public enum IceCandidateType: String, Sendable, Equatable, CaseIterable {
    case host
    case srflx
    case prflx
    case relay
    case unknown

    /// True for anything that implies a server outside the local network was involved.
    public var impliesNonLocalDependency: Bool { self == .srflx || self == .prflx || self == .relay }

    /// Reads the `typ` token out of an ICE candidate line. The **type only** — the address and port are
    /// deliberately not extracted, because PROTOCOL §7.7 gives them no log path and a value that is
    /// never produced cannot be leaked.
    public static func fromCandidateLine(_ line: String) -> IceCandidateType {
        let tokens = line.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ")
        guard let typIndex = tokens.firstIndex(of: "typ"), typIndex + 1 < tokens.count else { return .unknown }
        return allCases.first { $0.rawValue == tokens[typIndex + 1].lowercased() } ?? .unknown
    }
}

/// Mirrors WebRTC's own peer-connection state names, so a diagnostic reads the same on both phones.
public enum MediaTransportState: String, Sendable, Equatable {
    case new
    case connecting
    case connected
    case disconnected
    case failed
    case closed
    case unknown
}

/// Mirrors WebRTC's ICE gathering state.
public enum IceGatheringState: String, Sendable, Equatable {
    case new
    case gathering
    case complete
    case unknown
}

/// Whether WebRTC's built-in audio processing is available and on. ADR-003 requires each stage to be
/// individually disableable so a suspect stage can be turned off and the result measured again
/// (FR-005) — this is the reporting half of that.
///
/// **These flags say what was requested and what the stack reported, not that echo is solved.** Whether
/// AEC copes with a helmet unit's acoustics at 100 km/h is a real-device measurement and nothing here
/// may be read as evidence about it.
public struct AudioProcessingStatus: Sendable, Equatable {
    public var echoCancellationEnabled: Bool?
    public var noiseSuppressionEnabled: Bool?
    public var autoGainControlEnabled: Bool?
    /// True when the platform reported hardware acceleration for the stage rather than software.
    public var hardwareEchoCancellation: Bool?

    public init(
        echoCancellationEnabled: Bool? = nil,
        noiseSuppressionEnabled: Bool? = nil,
        autoGainControlEnabled: Bool? = nil,
        hardwareEchoCancellation: Bool? = nil
    ) {
        self.echoCancellationEnabled = echoCancellationEnabled
        self.noiseSuppressionEnabled = noiseSuppressionEnabled
        self.autoGainControlEnabled = autoGainControlEnabled
        self.hardwareEchoCancellation = hardwareEchoCancellation
    }
}

/// ADR-003: each stage individually switchable, for exactly the reason FR-005 gives.
public struct AudioProcessingConfig: Sendable, Equatable {
    public var echoCancellation: Bool
    public var noiseSuppression: Bool
    public var autoGainControl: Bool

    public init(echoCancellation: Bool = true, noiseSuppression: Bool = true, autoGainControl: Bool = true) {
        self.echoCancellation = echoCancellation
        self.noiseSuppression = noiseSuppression
        self.autoGainControl = autoGainControl
    }
}

/// The FR-023 voice diagnostics surface, and the whole of what Phase 2a exposes about the media plane.
///
/// Everything here is safe to display and to log by PROTOCOL §7.7. What is **absent** is the point: no
/// SDP, no candidate string, no address or port, no DTLS or SRTP key material. `selectedLocalType` and
/// `selectedRemoteType` are candidate *types*, not candidates.
///
/// Optional fields mean "the platform has not told us", never zero. Inventing a value here would be
/// inventing a measurement, which is the one thing this file must not do.
public struct VoiceEngineDiagnostics: Sendable, Equatable {
    public var transportState: MediaTransportState = .new
    public var iceGatheringState: IceGatheringState = .new
    /// As reported by the media stack, e.g. `"connected"`. Nil until DTLS has a state to report.
    public var dtlsState: String?
    public var selectedLocalType: IceCandidateType?
    public var selectedRemoteType: IceCandidateType?
    /// Every candidate type this side gathered or received. §7.6 reports anything but `host`.
    public var observedCandidateTypes: Set<IceCandidateType> = []
    /// e.g. `"audio/opus"`. Nil until an answer has been applied.
    public var negotiatedCodec: String?
    public var negotiatedClockRateHz: Int?
    public var negotiatedChannels: Int?
    /// e.g. `"SRTP_AES128_CM_HMAC_SHA1_80"` — the cipher name, never the keys.
    public var srtpCipher: String?
    public var dtlsCipher: String?
    public var packetsSent: Int64?
    public var packetsReceived: Int64?
    public var packetsLost: Int64?
    public var jitterMs: Double?
    public var roundTripTimeMs: Double?
    public var localAudioTrackPresent = false
    public var remoteAudioTrackPresent = false
    /// Whether the built-in AEC/NS/AGC stages are available and enabled (ADR-003).
    public var audioProcessing = AudioProcessingStatus()

    public init() {}
}

/// How the media plane is set up for one negotiation.
///
/// There is no `iceServers` field. That is deliberate: PROTOCOL §7.6 configures an empty ICE server
/// list, and a config type with no way to express a STUN or TURN server cannot grow one by accident in
/// a later phase. If a future topology genuinely needs one, adding the field is a protocol and ADR
/// change, which is the point.
public struct VoiceEngineConfig: Sendable, Equatable {
    public let voiceSessionId: VoiceSessionId
    /// One audio track per peer (ADR-003). Used as the WebRTC track id.
    public let localTrackId: String
    public let audioProcessing: AudioProcessingConfig

    public init(
        voiceSessionId: VoiceSessionId,
        localTrackId: String,
        audioProcessing: AudioProcessingConfig = AudioProcessingConfig()
    ) {
        self.voiceSessionId = voiceSessionId
        self.localTrackId = localTrackId
        self.audioProcessing = audioProcessing
    }
}

/// Which kind of session description is being applied.
public enum SdpKind: Sendable, Equatable { case offer, answer }

/// What went wrong in the media stack. Coarse on purpose — the detail belongs in a local log line.
public enum VoiceEngineError: Error, Sendable, Equatable {
    case notStarted(String)
    case sdpFailed(String)
    case candidateRejected(IceCandidateType)
    case platformFailure(String)
}

/// What the media stack tells the controller. Every payload is a plain value, for two reasons that
/// happen to coincide:
///
/// 1. it keeps `RideLinkCore` free of platform types (CLAUDE.md rule 9 — the import allowlist here is
///    `Foundation` + `CryptoKit`), and
/// 2. the WebRTC ObjC types — `RTCSessionDescription`, `RTCIceCandidate`, `RTCStatisticsReport` — are
///    **not** `Sendable`, so under Swift 6 strict concurrency a value leaving a WebRTC callback has to
///    be reduced to primitives *inside* that callback anyway (ADR-020).
///
/// The `voiceSessionId` on every event is the generation guard applied to callbacks rather than to the
/// wire: a delegate call from a peer connection that has already been closed carries the old id and is
/// therefore inert (PROTOCOL §7.8).
public enum VoiceEngineEvent: Sendable, Equatable {
    case offerCreated(voiceSessionId: VoiceSessionId, sdp: String)
    case answerCreated(voiceSessionId: VoiceSessionId, sdp: String)
    case localCandidateGathered(
        voiceSessionId: VoiceSessionId,
        candidate: String,
        sdpMid: String?,
        sdpMlineIndex: Int
    )
    case transportStateChanged(voiceSessionId: VoiceSessionId, state: MediaTransportState)
    case remoteTrackChanged(voiceSessionId: VoiceSessionId, present: Bool)
    case failed(voiceSessionId: VoiceSessionId, error: VoiceEngineError)
}

/// The media plane, as the controller sees it: WebRTC behind a seam of plain values.
///
/// ADR-003 isolates WebRTC behind `RideLinkPlatform.Voice` so it is replaceable. This protocol is that
/// isolation made explicit and — because every parameter and every event payload is a value type — it
/// is also what makes `VoiceController` testable with no WebRTC, no microphone and no network at all.
///
/// **A fake implementation proves the controller, not the codec.** Nothing driven by a fake engine may
/// be reported as evidence that real voice works; that is what `VoiceEngineLoopbackTests` (real
/// WebRTC, real DTLS-SRTP, real Opus, on this machine) and the real-device gate are for.
public protocol VoiceEngine: Sendable {
    /// Creates the peer connection with an empty ICE server list and attaches the local audio track.
    /// Must be called before any other method. Does **not** open the capture device or configure the
    /// audio session — that is `VoiceAudioSession`'s job, and it is separate precisely so a control
    /// link blip can drop the media transport without moving the Bluetooth route (see `stop`).
    func start(config: VoiceEngineConfig) async -> Result<Void, VoiceEngineError>

    func createOffer() async -> Result<Void, VoiceEngineError>

    func createAnswer() async -> Result<Void, VoiceEngineError>

    func applyRemoteDescription(kind: SdpKind, sdp: String) async -> Result<Void, VoiceEngineError>

    func addRemoteCandidate(candidate: String, sdpMid: String?, sdpMlineIndex: Int) async
        -> Result<Void, VoiceEngineError>

    /// Gates *transmission* by disabling the sender's track. The capture device stays open.
    func setMicrophoneMuted(_ muted: Bool) async

    /// Closes the peer connection, the remote track and the ICE state, and **keeps** the media factory
    /// and the local track alive.
    ///
    /// The split from `release` is not tidiness. Closing the audio unit is what makes a Bluetooth
    /// endpoint renegotiate between its media and duplex profiles — a 0.5–2 s audible route change
    /// (ARCHITECTURE §6.2) and the single worst thing this product can do to music (§6.3). A
    /// control-plane blip must therefore drop the *transport* without touching the capture path.
    /// Idempotent.
    func stop() async

    /// Disposes the media factory and the local track, releasing the capture device. Only a deliberate
    /// stop or `ENDING` may call this (ARCHITECTURE §3 rule 3), for the reason in `stop`. Idempotent,
    /// and implies `stop`.
    func release() async

    /// Refreshes `diagnostics` from the stack's own statistics. Cheap enough to poll while active.
    func refreshDiagnostics() async

    func diagnostics() async -> VoiceEngineDiagnostics

    /// Set by the controller before `start`. Every event carries its generation.
    func setEventSink(_ sink: @escaping @Sendable (VoiceEngineEvent) -> Void) async
}

/// Why `VoiceAudioSession.open` refused, by name.
///
/// A distinct type from `VoiceEngineError`: that one is about the *media stack*, this one about the
/// *platform audio session*, and the two fail for entirely different reasons with entirely different
/// things for the user to do about them. Collapsing both into one "connection failed" is exactly what
/// this phase's brief §41 forbids, and what the FR-023 diagnostics screen exists to distinguish.
public struct VoiceAudioSessionError: Error, Sendable, Equatable {
    public let failure: VoiceFailure

    public init(_ failure: VoiceFailure) {
        self.failure = failure
    }
}

/// The capture device and the platform audio session, kept deliberately separate from `VoiceEngine`.
///
/// The split is not tidiness. ARCHITECTURE §6.3/§6.4: the capture device is opened once while the app
/// is foreground-visible and stays open for the whole ride segment, because on Android there is no
/// second legal opportunity to open a microphone once the screen is locked. A link blip must therefore
/// tear down the *peer connection* without touching this. One protocol for both would make that
/// distinction impossible to express — and the two platforms share the negotiation table, so the
/// distinction has to exist on both.
public protocol VoiceAudioSession: Sendable {
    /// Configures the duplex audio session, selects the communication route and opens capture.
    ///
    /// Returns a failure — never traps, and never silently proceeds without a microphone — if the
    /// permission is absent or the platform refuses.
    func open() async -> Result<Void, VoiceAudioSessionError>

    /// Releases capture and restores the non-duplex configuration. Idempotent.
    func close() async

    func isOpen() async -> Bool

    /// The current route, in ADR-016's platform-neutral vocabulary.
    func route() async -> AudioRouteSnapshot

    func setRouteSink(_ sink: @escaping @Sendable (AudioRouteSnapshot) -> Void) async
}

/// How a voice signalling message leaves this device.
///
/// There is exactly one implementation in production and it writes to the **already authenticated**
/// TLS 1.3 control connection (PROTOCOL §7.1). There is no second signalling socket, no fallback
/// transport, and no way for this protocol to reach an unauthenticated peer.
public protocol VoiceSignalTransport: Sendable {
    /// - Returns: true if the signal was handed to a live authenticated control connection.
    ///
    /// False is a normal outcome, not an error: the link may have gone between the negotiation table
    /// deciding to send and the write happening. The controller records it and lets PROTOCOL §10's
    /// control ladder — the app's only reconnect loop — deal with the link.
    func send(_ signal: VoiceSignal) async -> Bool
}

/// Where a `VOICE_*` frame that has already passed the trust gate is delivered.
///
/// `submit` must be **non-blocking and never block the caller**: it is called from the control read
/// loop, so it cannot suspend that loop even under a flood of frames from an authenticated peer. It is
/// not, however, dropping in the sense that mattered before `VoiceInputMailbox` existed: an offer or
/// answer cannot silently vanish. Implementations enqueue into a bounded, classified mailbox drained by
/// exactly one consumer — critical inputs (an offer, an answer) held in a bounded FIFO that forces a
/// safe voice-only degrade if it fills, ICE candidates bounded exactly as `PendingCandidates` already
/// bounds them post-negotiation, and repeated state-style updates coalesced to their latest value —
/// rather than an unbounded queue an authenticated-but-compromised peer could grow without limit just
/// by sending frames faster than they are consumed.
///
/// Ordering is not a nicety here. `VOICE_OFFER` then `VOICE_ICE` must be handled in that order or the
/// candidate is queued when it did not need to be; `VOICE_STATE { closed }` arriving before a late
/// `VOICE_ICE` is what makes the generation guard work. This is the same lesson as the iOS
/// control-event ordering fix (STATUS §2h): a task per event preserves the order events were *created*
/// in, not the order they *run* in. The mailbox preserves FIFO order **within** each of its lanes; only
/// the coalesced lane deliberately collapses everything but the newest value.
public protocol VoiceSignalSink: Sendable {
    func submit(_ signal: VoiceSignal)
}
