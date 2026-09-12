import Foundation
import RideLinkCore

/// Writes one already-built frame to the surviving **authenticated** control connection.
///
/// A named type rather than an inline closure so the async signature is unambiguous at both ends, and so
/// the thing being handed across the seam has a name that says what it is allowed to do.
public typealias AuthenticatedFrameWriter = @Sendable (Envelope) async -> Bool

/// The `VOICE_*` half of the control plane: decode inbound frames, encode outbound ones, and count what
/// was refused.
///
/// **Why this is a separate type.** `ControlSessionManager` is the largest type in the codebase and
/// `docs/STATUS.md` §4 problem 18 predicted it would get worse — Phase 2a is exactly the change that
/// would have made it worse. On the Android side detekt's `LargeClass` fired on the first attempt to add
/// the voice wiring inline; the answer was to extract rather than to raise the threshold, and the two
/// platforms are kept structurally the same on purpose. Everything here is genuinely separable: none of
/// it touches the session, the handshake, pairing, reconnect or the clock.
///
/// **What it deliberately does not decide.** Whether a frame is *allowed* is not this type's business
/// and cannot be: PROTOCOL §7.1's gate is `ControlSessionManager`'s pre-authentication frame allowlist,
/// which drops every `VOICE_*` type before the read loop's dispatch ever reaches `deliver`. What this
/// type adds is the *encoding*, the *bounds*, and the counters — so that a refused frame is a visible
/// fact rather than an absence.
///
/// The one guard it does enforce is on the way out: `send` refuses unless the caller's writer supplier
/// yields an **authenticated** connection, so a `VoiceController` wired up by mistake before the trust
/// gate still could not put an SDP on a socket.
public actor VoiceSignalRelay: VoiceSignalTransport {
    private let localPeerId: PeerId
    private let monotonicNowUs: @Sendable () -> Int64
    private let nextSeq: @Sendable () -> Int64
    private let activeSessionId: @Sendable () async -> SessionId
    /// Yields a writer for the surviving connection **only while it is authenticated**, and nil
    /// otherwise. A supplier rather than a connection because the link comes and goes and this type must
    /// never hold one across a teardown.
    private let authenticatedWriter: @Sendable () async -> AuthenticatedFrameWriter?

    /// ADR-025's liveness half: the generation owning the connection that is an authenticated
    /// session **right now**, or nil when none is. Synchronous and non-isolated on purpose — a
    /// frame's own authorising generation is *compared* against it and never replaced by it, and
    /// reading a live value to label a frame is ADR-024 Amendment A7's defect.
    private let liveGeneration: @Sendable () -> Int64?

    private var sink: (any VoiceSignalSink)?
    private var rejections: [VoiceSignalRejection: Int] = [:]
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

    public func setSink(_ sink: (any VoiceSignalSink)?) {
        self.sink = sink
    }

    public func rejectionCounts() -> [VoiceSignalRejection: Int] { rejections }

    /// How many `VOICE_*` frames were dropped **because the connection had not passed the trust gate**.
    /// Non-zero means a peer that had completed TLS but not RideLink authentication tried to start
    /// voice, which is exactly the condition PROTOCOL §7.1 exists to make inert.
    public func droppedPreAuthentication() -> Int { preAuthenticationDrops }

    /// See `retiredGenerationDrops`.
    public func droppedRetiredGeneration() -> Int { retiredGenerationDrops }

    public func send(_ signal: VoiceSignal) async -> Bool {
        guard let write = await authenticatedWriter() else { return false }
        let envelope = ControlMessages.voiceSignal(
            localPeerId: localPeerId,
            sessionId: await activeSessionId(),
            seq: nextSeq(),
            sentAtMonoUs: monotonicNowUs(),
            signal: signal
        )
        return await write(envelope)
    }

    /// PROTOCOL §7.4: parse, bounds-check, hand over — and on any failure, **drop the frame and keep the
    /// connection**. The framing was intact; only this message's shape was wrong. An attacker-supplied
    /// SDP must not be able to end a ride's control plane, and the bounds are checked before the string
    /// reaches the media stack, so it cannot make the reader allocate either.
    ///
    /// Called only from the read loop's authenticated dispatch.
    ///
    /// **ADR-025 §2.** `generation` is the frame's own authority — the generation that owned the
    /// connection it was read from, at the moment of the read. A frame whose session has since been
    /// replaced is refused here, before it can become a `VoiceInput`, because `VoiceController` is
    /// deliberately **retained across a control reconnect** (the capture device stays open for the
    /// ride segment, ARCHITECTURE §6.3/§6.4) and `VoiceNegotiation`'s own `voice_session_id` guards
    /// prove voice-session ownership, not control-session ownership. Concretely: a stale
    /// `VOICE_STATE { state: "closed" }` carrying no `voice_session_id` is not a generation mismatch
    /// to that table, so it would tear down the *successor* session's live media; and a stale
    /// `VOICE_OFFER` arriving after `.controlLinkLost` has reset the table to `.idle` would start a
    /// negotiation on the successor's connection.
    ///
    /// This is a refusal rather than a relabelling on purpose: there is no ledger here that a
    /// retired generation's frame has to reach, unlike Phase 5's (ADR-024 Amendment A6).
    public func deliver(type: String, payload: [String: JSONValue], generation: Int64) {
        guard generation == liveGeneration() else {
            retiredGenerationDrops += 1
            return
        }
        switch VoiceSignalCodec.parse(type: type, payload: payload) {
        case .parsed(let signal):
            sink?.submit(signal)
        case .rejected(let reason):
            rejections[reason, default: 0] += 1
        }
    }

    /// A `VOICE_*` frame arrived on a connection that had not passed the trust gate. Counted rather than
    /// merely dropped: PROTOCOL §7.1's whole point is that voice is inert before authentication, and "it
    /// never happened" and "it happened and was refused" are different facts on a diagnostics screen —
    /// and only the second one tells you something tried.
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
