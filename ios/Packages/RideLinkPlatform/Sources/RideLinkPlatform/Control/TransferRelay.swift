import Foundation
import RideLinkCore

/// Where a `TRANSFER_*` frame that has passed the ADR-019 trust gate is delivered.
public protocol TransferSink: Sendable {
    /// - Parameter generation: the authentication generation that owned **the connection this
    ///   message's frame was read from, at the moment of the read** (ADR-025 §1). See
    ///   `ManifestSink.submit` for why it is a parameter rather than something the receiver looks
    ///   up.
    func submit(_ message: TransferMessage, generation: Int64)
}

/// The `TRANSFER_*` half of the control plane (PROTOCOL §8.2): decode inbound frames, encode
/// outbound ones, and count what was refused. Mirrors `VoiceSignalRelay`/`ManifestRelay` exactly,
/// and Android's `com.ridelink.network.transfer.TransferRelay`.
///
/// Carries only the small `TRANSFER_*` **control** messages (request/offer/progress/result/cancel)
/// — the bulk byte stream itself is `TransferManager`'s job, over a separate TLS connection
/// (ADR-023).
public actor TransferRelay {
    private let localPeerId: PeerId
    private let monotonicNowUs: @Sendable () -> Int64
    private let nextSeq: @Sendable () -> Int64
    private let activeSessionId: @Sendable () async -> SessionId
    private let authenticatedWriter: @Sendable () async -> AuthenticatedFrameWriter?

    /// ADR-025's liveness half: the generation owning the connection that is an authenticated
    /// session **right now**, or nil when none is. Synchronous and non-isolated on purpose — a
    /// frame's own authorising generation is *compared* against it and never replaced by it, and
    /// reading a live value to label a frame is ADR-024 Amendment A7's defect.
    private let liveGeneration: @Sendable () -> Int64?

    private var sink: (any TransferSink)?
    private var rejections: [TransferMessageRejection: Int] = [:]
    private var preAuthenticationDrops = 0

    /// How many frames were dropped because the control session that authorised their read had
    /// already been replaced (ADR-025). Distinct from `droppedPreAuthentication`: that one counts a
    /// peer that was never authenticated, this one counts a peer that *was*, on a connection that
    /// is gone.
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

    public func setSink(_ sink: (any TransferSink)?) {
        self.sink = sink
    }

    public func rejectionCounts() -> [TransferMessageRejection: Int] { rejections }

    /// How many `TRANSFER_*` frames were dropped **because the connection had not passed the
    /// trust gate**.
    public func droppedPreAuthentication() -> Int { preAuthenticationDrops }

    /// See `retiredGenerationDrops`.
    public func droppedRetiredGeneration() -> Int { retiredGenerationDrops }

    /// - Returns: true if the message was handed to a live authenticated control connection.
    @discardableResult
    public func send(_ message: TransferMessage) async -> Bool {
        guard let write = await authenticatedWriter() else { return false }
        let envelope = ControlMessages.raw(
            localPeerId: localPeerId,
            type: TransferCodec.wireType(message),
            sessionId: await activeSessionId(),
            seq: nextSeq(),
            sentAtMonoUs: monotonicNowUs(),
            payload: TransferCodec.encode(message)
        )
        return await write(envelope)
    }

    /// Called only from the read loop's authenticated dispatch. A malformed frame is dropped,
    /// never fatal.
    ///
    /// **ADR-025 §1.** `generation` is the frame's own authority and is handed on to the sink rather
    /// than looked up there. A frame whose session has since been replaced is refused before it is
    /// even parsed.
    public func deliver(type: String, payload: [String: JSONValue], generation: Int64) {
        guard generation == liveGeneration() else {
            retiredGenerationDrops += 1
            return
        }
        switch TransferCodec.parse(type: type, payload: payload) {
        case .parsed(let message):
            sink?.submit(message, generation: generation)
        case .rejected(let reason):
            rejections[reason, default: 0] += 1
        }
    }

    /// A `TRANSFER_*` frame arrived before the trust gate passed. Counted, per the same reasoning
    /// as `ManifestRelay`.
    public func countPreAuthenticationDrop() {
        preAuthenticationDrops += 1
    }

    public func reset() {
        sink = nil
        rejections.removeAll()
        preAuthenticationDrops = 0
        retiredGenerationDrops = 0
    }
}
