import Foundation
import RideLinkCore

/// Receives parsed, bounds-checked `STATE_REQUEST`/`STATE_SNAPSHOT` messages (PROTOCOL §10).
///
/// `submit` must be **non-blocking**: it is called from the control read loop, exactly as
/// `AudioStateSink`/`VoiceSignalSink`/`ManifestSink` are.
public protocol ResyncSink: Sendable {
    /// - Parameter generation: the authentication generation that owned **the connection this
    ///   message's frame was read from, at the moment of the read** (ADR-025 §1) — the same
    ///   contract every other relay's sink has.
    func submit(_ message: ResyncMessage, generation: Int64)
}

/// The PROTOCOL §10 half of the control plane (Phase 7, ADR-028): decode inbound
/// `STATE_REQUEST`/`STATE_SNAPSHOT` frames, encode outbound ones, and count what was refused.
///
/// Mirrors `ManifestRelay` exactly, for the same recorded reason (`docs/STATUS.md` §4 problem 18):
/// `ControlSessionManager` grows with every message family, and the fix is to extract, not to raise
/// a class-size threshold. Also mirrors Android's `com.ridelink.network.resync.ResyncRelay` exactly.
///
/// **What it deliberately does not decide.** Whether `STATE_REQUEST`/`STATE_SNAPSHOT` is allowed
/// before authentication is `ControlSessionManager`'s pre-authentication frame allowlist — both
/// types are **absent** from it, exactly as `VOICE_*` and `AUDIO_STATE` are, and that absence is
/// the whole of their access control. Whether a *follower* may apply an admitted `STATE_SNAPSHOT`
/// is the existing Phase 5 reconciliation path's role/generation check
/// (`SyncPlaybackCoordinator.onStateSnapshot`), reused rather than duplicated.
public actor ResyncRelay {
    private let localPeerId: PeerId
    private let monotonicNowUs: @Sendable () -> Int64
    private let nextSeq: @Sendable () -> Int64
    private let activeSessionId: @Sendable () async -> SessionId
    private let authenticatedWriter: @Sendable () async -> AuthenticatedFrameWriter?

    /// ADR-025's liveness half: the generation owning the connection that is an authenticated
    /// session **right now**, or nil when none is. A frame's own authorising generation is
    /// *compared* against it and never replaced by it.
    private let liveGeneration: @Sendable () -> Int64?

    private var sink: (any ResyncSink)?
    private var rejections: [ResyncMessageRejection: Int] = [:]
    private var preAuthenticationDrops = 0
    private var retiredGenerationDrops = 0

    public init(
        localPeerId: PeerId,
        monotonicNowUs: @escaping @Sendable () -> Int64,
        nextSeq: @escaping @Sendable () -> Int64,
        activeSessionId: @escaping @Sendable () async -> SessionId,
        authenticatedWriter: @escaping @Sendable () async -> AuthenticatedFrameWriter?,
        liveGeneration: @escaping @Sendable () -> Int64?
    ) {
        self.localPeerId = localPeerId
        self.monotonicNowUs = monotonicNowUs
        self.nextSeq = nextSeq
        self.activeSessionId = activeSessionId
        self.authenticatedWriter = authenticatedWriter
        self.liveGeneration = liveGeneration
    }

    var sinkForTest: (any ResyncSink)? { sink }

    public func setSink(_ sink: (any ResyncSink)?) {
        self.sink = sink
    }

    public func rejectionCounts() -> [ResyncMessageRejection: Int] { rejections }

    public func droppedPreAuthentication() -> Int { preAuthenticationDrops }

    public func droppedRetiredGeneration() -> Int { retiredGenerationDrops }

    /// - Returns: true if the message was handed to a live authenticated control connection.
    @discardableResult
    public func send(_ message: ResyncMessage) async -> Bool {
        guard let write = await authenticatedWriter() else { return false }
        let envelope = ControlMessages.raw(
            localPeerId: localPeerId,
            type: ResyncCodec.wireType(message),
            sessionId: await activeSessionId(),
            seq: nextSeq(),
            sentAtMonoUs: monotonicNowUs(),
            payload: ResyncCodec.encode(message)
        )
        return await write(envelope)
    }

    /// Called only from the read loop's authenticated dispatch. On any parse failure, the frame is
    /// dropped and the connection survives — the framing was intact, only this message's shape was
    /// wrong.
    public func deliver(type: String, payload: [String: JSONValue], generation: Int64) {
        guard generation == liveGeneration() else {
            retiredGenerationDrops += 1
            return
        }
        switch ResyncCodec.parse(type: type, payload: payload) {
        case .parsed(let message):
            sink?.submit(message, generation: generation)
        case .rejected(let reason):
            rejections[reason, default: 0] += 1
        }
    }

    /// A resync frame arrived on a connection that had not passed the trust gate. Counted, not just
    /// dropped.
    public func countPreAuthenticationDrop() {
        preAuthenticationDrops += 1
    }

    /// See `AudioStateRelay.resetCounters()`: the counters, never the sink.
    public func resetCounters() {
        rejections.removeAll()
        preAuthenticationDrops = 0
        retiredGenerationDrops = 0
    }
}
