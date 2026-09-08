import Foundation
import RideLinkCore

/// Where a PROTOCOL §5 playback frame that has passed the ADR-019 trust gate is delivered.
///
/// `generation` is the authentication generation that was live **when the frame was read off the
/// wire** (ADR-023 §3). It is a parameter rather than something the receiver looks up, because a
/// receiver that looks it up reads whatever is live when its own work happens to run — which is the
/// exact shape of bug ADR-023 Amendment A3 found in Phase 4.
///
/// `submit` must be non-suspending and must not block: it is called from the control read loop.
public protocol PlaybackSink: Sendable {
    func submit(_ message: PlaybackMessage, generation: Int64)
}

/// Where a PROTOCOL §9 queue frame that has passed the ADR-019 trust gate is delivered.
public protocol QueueSink: Sendable {
    func submit(_ message: QueueMessage, generation: Int64)
}

/// The Phase 5 half of the control plane (PROTOCOL §5 and §9): decode inbound frames, encode
/// outbound ones, and count what was refused. Mirrors `VoiceSignalRelay`/`AudioStateRelay`/
/// `ManifestRelay`/`TransferRelay` exactly, and Android's
/// `com.ridelink.network.playback.PlaybackRelay`.
///
/// **One relay for two message families, deliberately.** Playback commands and queue mutations share
/// one serialisation point (the ADR-010 leader), one ordering authority (`command_seq`) and one
/// owner, so splitting them would put two halves of one subsystem on two objects. The two sinks stay
/// separate because the two codecs produce different types.
///
/// **What it deliberately does not decide.** Whether a frame is *allowed* is
/// `ControlSessionManager`'s pre-authentication allowlist, and every `PLAY`/`PAUSE`/`RESUME`/`SEEK`/
/// `NEXT`/`PREVIOUS`/`POSITION_REPORT`/`PLAYBACK_STATE`/`QUEUE_*` type is **absent** from it — that
/// absence *is* their access control, exactly as it is for `VOICE_*` (PROTOCOL §7.1) and
/// `AUDIO_STATE`. Whether an allowed frame may be *applied* is `CommandOrderGate`'s and the
/// session-generation guard's.
public actor PlaybackRelay {
    private let localPeerId: PeerId
    private let monotonicNowUs: @Sendable () -> Int64
    private let nextSeq: @Sendable () -> Int64
    private let activeSessionId: @Sendable () async -> SessionId
    private let authenticatedWriter: @Sendable () async -> AuthenticatedFrameWriter?

    private var playbackSink: (any PlaybackSink)?
    private var queueSink: (any QueueSink)?
    private var playbackRejections: [PlaybackMessageRejection: Int] = [:]
    private var queueRejections: [QueueMessageRejection: Int] = [:]
    private var preAuthenticationDrops = 0

    public init(
        localPeerId: PeerId,
        monotonicNowUs: @escaping @Sendable () -> Int64,
        nextSeq: @escaping @Sendable () -> Int64,
        activeSessionId: @escaping @Sendable () async -> SessionId,
        authenticatedWriter: @escaping @Sendable () async -> AuthenticatedFrameWriter?
    ) {
        self.localPeerId = localPeerId
        self.monotonicNowUs = monotonicNowUs
        self.nextSeq = nextSeq
        self.activeSessionId = activeSessionId
        self.authenticatedWriter = authenticatedWriter
    }

    public func setPlaybackSink(_ sink: (any PlaybackSink)?) { playbackSink = sink }

    public func setQueueSink(_ sink: (any QueueSink)?) { queueSink = sink }

    public func playbackRejectionCounts() -> [PlaybackMessageRejection: Int] { playbackRejections }

    public func queueRejectionCounts() -> [QueueMessageRejection: Int] { queueRejections }

    /// How many Phase 5 frames were dropped **because the connection had not passed the trust gate**.
    public func droppedPreAuthentication() -> Int { preAuthenticationDrops }

    /// - Returns: true if the message was handed to a live authenticated control connection.
    @discardableResult
    public func send(_ message: PlaybackMessage) async -> Bool {
        await write(type: PlaybackCodec.wireType(message), payload: PlaybackCodec.encode(message))
    }

    @discardableResult
    public func send(_ message: QueueMessage) async -> Bool {
        await write(type: QueueCodec.wireType(message), payload: QueueCodec.encode(message))
    }

    private func write(type: String, payload: [String: JSONValue]) async -> Bool {
        guard let writeFrame = await authenticatedWriter() else { return false }
        let envelope = ControlMessages.raw(
            localPeerId: localPeerId,
            type: type,
            sessionId: await activeSessionId(),
            seq: nextSeq(),
            sentAtMonoUs: monotonicNowUs(),
            payload: payload
        )
        return await writeFrame(envelope)
    }

    /// Called only from the read loop's authenticated dispatch. A malformed frame is dropped and the
    /// connection survives — the framing was intact, only this message's shape was wrong.
    public func deliverPlayback(type: String, payload: [String: JSONValue], generation: Int64) {
        switch PlaybackCodec.parse(type: type, payload: payload) {
        case .parsed(let message):
            playbackSink?.submit(message, generation: generation)
        case .rejected(let reason):
            playbackRejections[reason, default: 0] += 1
        }
    }

    public func deliverQueue(type: String, payload: [String: JSONValue], generation: Int64) {
        switch QueueCodec.parse(type: type, payload: payload) {
        case .parsed(let message):
            queueSink?.submit(message, generation: generation)
        case .rejected(let reason):
            queueRejections[reason, default: 0] += 1
        }
    }

    /// A Phase 5 frame arrived before the trust gate passed. Counted rather than merely dropped, for
    /// the same reason every other relay counts it: "it never happened" and "it happened and was
    /// refused" are different facts on a diagnostics screen, and only the second one lets a test
    /// prove the gate held rather than prove nothing was sent.
    public func countPreAuthenticationDrop() {
        preAuthenticationDrops += 1
    }

    public func reset() {
        playbackSink = nil
        queueSink = nil
        playbackRejections.removeAll()
        queueRejections.removeAll()
        preAuthenticationDrops = 0
    }
}
