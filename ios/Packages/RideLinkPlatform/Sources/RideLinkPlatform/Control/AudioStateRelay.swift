import Foundation
import RideLinkCore

/// Where an `AUDIO_STATE` frame that has passed the ADR-019 trust gate is delivered.
///
/// `submit` must be **non-blocking**: it is called from the control read loop. There is no queue behind
/// it because there is nothing to queue — `AudioStateInbox` keeps exactly one message, the newest by
/// revision, and PROTOCOL §4.4's revision rule is what makes that correct rather than lossy (this
/// phase's brief §38).
public protocol AudioStateSink: Sendable {
    func submit(_ message: AudioStateMessage)
}

/// The `AUDIO_STATE` half of the control plane (PROTOCOL §4.4): decode inbound frames, encode outbound
/// ones, and count what was refused.
///
/// **Why this is a separate type**, exactly as `VoiceSignalRelay` is: `ControlSessionManager` is the
/// largest type in the codebase and `docs/STATUS.md` §4 problem 18 says so. None of what is here touches
/// the session, the handshake, pairing, reconnect or the clock, so none of it belongs there — and the two
/// platforms are kept structurally the same on purpose.
///
/// **What it deliberately does not decide.** Whether a frame is *allowed* is `ControlSessionManager`'s
/// pre-authentication frame allowlist, and `AUDIO_STATE` is **absent** from it: PROTOCOL §4.1 admits only
/// `PING`, `PONG`, the three pairing frames, `BYE` and `ERROR` before the trust gate, and §4.1's
/// handshake diagram puts `AUDIO_STATE` on the trusted path. An unauthenticated peer's `AUDIO_STATE` is
/// dropped before the read loop's dispatch can reach `deliver`, and `countPreAuthenticationDrop` records
/// that it tried.
///
/// The one guard this type enforces is on the way out: `send` refuses unless the caller's supplier yields
/// an **authenticated** connection.
public actor AudioStateRelay {
    private let localPeerId: PeerId
    private let monotonicNowUs: @Sendable () -> Int64
    private let nextSeq: @Sendable () -> Int64
    private let activeSessionId: @Sendable () async -> SessionId
    private let authenticatedWriter: @Sendable () async -> AuthenticatedFrameWriter?

    private var sink: (any AudioStateSink)?
    private var rejections: [AudioStateRejection: Int] = [:]
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

    public func setSink(_ sink: (any AudioStateSink)?) {
        self.sink = sink
    }

    public func rejectionCounts() -> [AudioStateRejection: Int] { rejections }

    /// How many `AUDIO_STATE` frames were dropped **because the connection had not passed the trust
    /// gate**. Non-zero means a peer that had completed TLS but not RideLink authentication tried to tell
    /// this device what its audio was doing.
    public func droppedPreAuthentication() -> Int { preAuthenticationDrops }

    /// - Returns: true if the message was handed to a live authenticated control connection.
    public func send(_ message: AudioStateMessage) async -> Bool {
        guard let write = await authenticatedWriter() else { return false }
        let envelope = ControlMessages.audioState(
            localPeerId: localPeerId,
            sessionId: await activeSessionId(),
            seq: nextSeq(),
            sentAtMonoUs: monotonicNowUs(),
            message: message
        )
        return await write(envelope)
    }

    /// PROTOCOL §4.4: parse, bounds-check, hand over — and on any failure, **drop the frame and keep the
    /// connection**. The framing was intact; only this message's shape was wrong, exactly as for a
    /// malformed `PING` (§6) or `VOICE_*` (§7.4).
    ///
    /// Called only from the read loop's authenticated dispatch.
    public func deliver(payload: [String: JSONValue]) {
        switch AudioStateCodec.parse(payload) {
        case .parsed(let message):
            sink?.submit(message)
        case .rejected(let reason):
            rejections[reason, default: 0] += 1
        }
    }

    /// Counted rather than merely dropped: "it never happened" and "it happened and was refused" are
    /// different facts on a diagnostics screen, and only the second one tells you something tried.
    public func countPreAuthenticationDrop() {
        preAuthenticationDrops += 1
    }

    public func reset() {
        sink = nil
        rejections.removeAll()
        preAuthenticationDrops = 0
    }
}
