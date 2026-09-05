import Foundation
import RideLinkCore

/// Where a `MANIFEST_*` frame that has passed the ADR-019 trust gate is delivered.
///
/// `submit` must be **non-blocking**: it is called from the control read loop, exactly as
/// `AudioStateSink`/`VoiceSignalSink` are.
public protocol ManifestSink: Sendable {
    func submit(_ message: ManifestMessage)
}

/// The `MANIFEST_*` half of the control plane (PROTOCOL §8.1): decode inbound frames, encode
/// outbound ones, and count what was refused.
///
/// **Why this is a separate type**, exactly as `VoiceSignalRelay`/`AudioStateRelay` are:
/// `ControlSessionManager` is the largest type in the codebase and `docs/STATUS.md` §4 problem 18
/// says so. None of what is here touches the session, the handshake, pairing, reconnect or the
/// clock, so none of it belongs there — and the two platforms are kept structurally the same on
/// purpose. Mirrors Android's `com.ridelink.network.manifest.ManifestRelay` exactly.
///
/// **What it deliberately does not decide.** Whether a `MANIFEST_*` frame is allowed before
/// authentication is `ControlSessionManager`'s pre-authentication frame allowlist, and
/// `MANIFEST_*` is **absent** from it (brief §22: unpaired peers never receive the catalogue). An
/// unauthenticated peer's `MANIFEST_*` is dropped before the read loop's dispatch can reach
/// `deliver`, and `countPreAuthenticationDrop` records that it tried.
///
/// The one guard this type enforces is on the way out: `send` refuses unless the caller's writer
/// supplier yields an **authenticated** connection.
public actor ManifestRelay {
    private let localPeerId: PeerId
    private let monotonicNowUs: @Sendable () -> Int64
    private let nextSeq: @Sendable () -> Int64
    private let activeSessionId: @Sendable () async -> SessionId
    /// Yields a writer for the surviving connection **only while it is authenticated**, and nil
    /// otherwise — see `VoiceSignalRelay`'s identical field for why this is a supplier rather than
    /// a held connection.
    private let authenticatedWriter: @Sendable () async -> AuthenticatedFrameWriter?

    private var sink: (any ManifestSink)?
    private var rejections: [ManifestMessageRejection: Int] = [:]
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

    public func setSink(_ sink: (any ManifestSink)?) {
        self.sink = sink
    }

    public func rejectionCounts() -> [ManifestMessageRejection: Int] { rejections }

    /// How many `MANIFEST_*` frames were dropped **because the connection had not passed the
    /// trust gate**. Non-zero means an unpaired peer tried to reach the shared-library catalogue.
    public func droppedPreAuthentication() -> Int { preAuthenticationDrops }

    /// - Returns: true if the message was handed to a live authenticated control connection.
    @discardableResult
    public func send(_ message: ManifestMessage) async -> Bool {
        guard let write = await authenticatedWriter() else { return false }
        let envelope = ControlMessages.raw(
            localPeerId: localPeerId,
            type: ManifestCodec.wireType(message),
            sessionId: await activeSessionId(),
            seq: nextSeq(),
            sentAtMonoUs: monotonicNowUs(),
            payload: ManifestCodec.encode(message)
        )
        return await write(envelope)
    }

    /// Called only from the read loop's authenticated dispatch. On any parse failure, the frame is
    /// dropped and the connection survives — the framing was intact, only this message's shape was
    /// wrong (the same rule PROTOCOL §7.4 applies to `VOICE_*`).
    public func deliver(type: String, payload: [String: JSONValue]) {
        switch ManifestCodec.parse(type: type, payload: payload) {
        case .parsed(let message):
            sink?.submit(message)
        case .rejected(let reason):
            rejections[reason, default: 0] += 1
        }
    }

    /// A `MANIFEST_*` frame arrived on a connection that had not passed the trust gate. Counted
    /// rather than merely dropped, so "it never happened" and "it happened and was refused" stay
    /// distinguishable facts on a diagnostics screen (brief §22).
    public func countPreAuthenticationDrop() {
        preAuthenticationDrops += 1
    }

    public func reset() {
        sink = nil
        rejections.removeAll()
        preAuthenticationDrops = 0
    }
}
