import Foundation
import RideLinkCore

/// The outcome of the most recent `STATE_REQUEST`/`STATE_SNAPSHOT` round trip, for FR-023.
///
/// `.snapshotPending` (independent review, Blocker 2E) is a genuinely different state from
/// `.requested`: `.requested` means "the wire round trip is still outstanding", while
/// `.snapshotPending` means "a valid `STATE_SNAPSHOT` for the live generation arrived — the wire
/// round trip is done — but the fresh clock was not yet trustworthy, so reconciliation itself is
/// still pending" (`SyncPlaybackCoordinator`'s own generation-owned deferral, not a reason to resend
/// `STATE_REQUEST`).
public enum ResyncOutcome: Sendable, Equatable {
    case none, requested, snapshotPending, reconciled, sendFailed
}

public struct ResyncDiagnostics: Sendable, Equatable {
    public var requestPending = false
    public var reconnectRequestCount = 0
    public var desyncRequestCount = 0
    public var roleViolationCount = 0
    public var lastOutcome: ResyncOutcome = .none
    public var lastSnapshotManifestRevision: Int64?
    public var lastSnapshotCommandSeq: Int64?

    public init() {}
}

/// The single owner of PROTOCOL §10's `STATE_REQUEST`/`STATE_SNAPSHOT` exchange (Phase 7,
/// ADR-028) — installed once per process, exactly as `SharedLibraryCoordinator` already is for
/// `MANIFEST_*`/`TRANSFER_*` (CLAUDE.md rule 8 extended to this plane; brief §25's "extend an
/// existing pure table rather than creating another owner" — the *decision* of when to ask is
/// `StateResyncGate`, the only new pure table this phase adds).
///
/// **A follower requests; only the leader answers** — the same asymmetry PROTOCOL §5/§9 already
/// enforce for every other authoritative frame (ADR-010), so a follower's stray `STATE_REQUEST`
/// (which should never occur, since leadership is stable across a reconnect — ARCHITECTURE §5) is
/// refused and counted rather than answered with unauthoritative state.
///
/// **What this type deliberately does not decide.** Whether an inbound `STATE_SNAPSHOT` may be
/// *applied* is entirely `SyncPlaybackCoordinator.onStateSnapshot`'s existing role/generation/hold-
/// gate machinery — this type only translates the wire message into the shapes that machinery
/// already reconciles, and only clears its own local "a request is outstanding" bookkeeping.
///
/// **Unlike Android's `ResyncCoordinator`, this type does not self-subscribe to session events.**
/// `ControlSessionManager.onEvent` is a single mutable callback slot on this platform (unlike
/// Android's multi-collector `SharedFlow`), and `SessionCoordinator` already owns it — so
/// `onConnected` is called explicitly from `SessionCoordinator.applySideEffects`, exactly as it
/// already forwards `.connected`/`.linkLost` to `SharedLibraryCoordinator` and
/// `SyncPlaybackCoordinator`. The desync trigger is likewise forwarded, through
/// `SyncPlaybackCoordinator.onDesynchronizedTrigger` — a dedicated slot separate from
/// `onDiagnosticsChanged`, which `SyncPlaybackPresenter` already owns for the UI.
///
/// Mirrors Android's `com.ridelink.app.resync.ResyncCoordinator` exactly wherever the platform
/// allows; the divergences above are structural, not behavioural.
@MainActor
public final class ResyncCoordinator {
    private let session: any ResyncSessionPort
    private let syncPlaybackCoordinator: SyncPlaybackCoordinator
    /// `SharedLibraryCoordinator.currentCatalogueRevision`, narrowed to the one value this type
    /// reads — mirroring `SharedLibraryCoordinator`'s own `activeCacheHash` closure parameter rather
    /// than depending on the whole coordinator, so a deterministic test needs no manifest generator,
    /// cache repository or bulk transport double to exercise this type.
    private let currentCatalogueRevision: @MainActor () -> Int64
    /// `SharedLibraryCoordinator.requestCatalogue`, narrowed the same way. Called only when a
    /// `STATE_SNAPSHOT`'s `manifest_revision` differs from the last one this device observed
    /// (§20/§21's "no unnecessary manifest retransmission") — never on the first snapshot of a
    /// session, which the existing unconditional `.connected -> requestCatalogue()` already covers.
    private let requestManifestRefresh: @MainActor () -> Void
    private let localPeerId: PeerId

    /// `internal`, not `private`: `@testable import` visibility for `ReconnectResyncStressTests`'
    /// harness to prove `onConnected` has actually run before a test relies on it, since
    /// `.connected` being recorded on the manager and this coordinator's own forwarding `Task`
    /// having completed are two different, asynchronously-separated facts.
    var isLocalLeader: Bool?
    /// The `manifest_revision` this device last saw in a `STATE_SNAPSHOT`, or `nil` before the
    /// first one.
    private var lastKnownManifestRevision: Int64?
    /// `true` once this process has completed **any** prior authenticated session — see Android's
    /// identical field for the full reasoning.
    private var hasEverConnected = false
    /// `StateResyncGate`'s own state: the generation a `STATE_REQUEST` is outstanding for, if any.
    private var pendingRequestGeneration: Int64?

    public private(set) var diagnostics = ResyncDiagnostics()
    public var onDiagnosticsChanged: (@Sendable (ResyncDiagnostics) -> Void)?

    public init(
        session: any ResyncSessionPort,
        syncPlaybackCoordinator: SyncPlaybackCoordinator,
        currentCatalogueRevision: @escaping @MainActor () -> Int64,
        requestManifestRefresh: @escaping @MainActor () -> Void,
        localPeerId: PeerId
    ) {
        self.session = session
        self.syncPlaybackCoordinator = syncPlaybackCoordinator
        self.currentCatalogueRevision = currentCatalogueRevision
        self.requestManifestRefresh = requestManifestRefresh
        self.localPeerId = localPeerId
    }

    /// Installs this coordinator's sink and the desync trigger. Call once, after
    /// `syncPlaybackCoordinator` exists — mirrors Android's `init` block, split out because Swift
    /// has no equivalent of a constructor that can `await`.
    public func attach() async {
        await session.channel.setSink(ResyncSinkAdapter { [weak self] message, generation in
            guard let self else { return }
            Task { @MainActor in await self.handle(message, generation: generation) }
        })
        await syncPlaybackCoordinator.setDesynchronizedTrigger { [weak self] in
            guard let self else { return }
            Task { @MainActor in await self.onDesyncTrigger() }
        }
        // Independent review, Blocker 2E: a snapshot first deferred for the clock (`.snapshotPending`
        // below) only becomes genuinely reconciled later, from `drainDeferredEvents` — this is how
        // that later completion is reported back, mirroring `setDesynchronizedTrigger`'s wiring.
        await syncPlaybackCoordinator.setReconciliationAppliedTrigger { [weak self] generation in
            guard let self else { return }
            Task { @MainActor in self.onReconciliationApplied(generation: generation) }
        }
        // ADR-028 Amendment: outbound STATE_SNAPSHOT now travels through SyncPlaybackCoordinator's
        // own ordered outbound path — see `enqueueStateSnapshotReply` — so it can never be written
        // out of order relative to a QUEUE_SNAPSHOT/PLAYBACK_STATE decided around the same time.
        await syncPlaybackCoordinator.setResyncChannel(session.channel)
    }

    /// A previously-deferred reconciliation has now genuinely applied (Blocker 2E). Generation-keyed
    /// matching against `pendingRequestGeneration` — the same comparison `StateResyncGate` already
    /// does for the synchronous case — means a signal for an unrelated restoration (an ordinary wire
    /// `PLAYBACK_STATE`, or a retired generation's) is simply ignored rather than mismatched.
    private func onReconciliationApplied(generation: Int64) {
        guard generation == pendingRequestGeneration else { return }
        pendingRequestGeneration = nil
        diagnostics.requestPending = false
        diagnostics.lastOutcome = .reconciled
        publishDiagnostics()
    }

    /// Forwarded from `ControlEvent.connected` by `SessionCoordinator` — the counterpart of
    /// Android's `init`-time `SharedFlow` collector.
    public func onConnected(isLeader: Bool, generation: Int64) async {
        isLocalLeader = isLeader
        let isReconnectOrRestart = hasEverConnected
        hasEverConnected = true
        if isLeader || !isReconnectOrRestart { return }
        await triggerRequest(generation: generation, desync: false)
    }

    private func onDesyncTrigger() async {
        guard isLocalLeader == false else { return }
        let generation = await session.currentAuthGeneration()
        await triggerRequest(generation: generation, desync: true)
    }

    private func triggerRequest(generation: Int64, desync: Bool) async {
        switch StateResyncGate.onTrigger(pendingGeneration: pendingRequestGeneration, liveGeneration: generation) {
        case .alreadyPending: return
        case .sendRequest: break
        }
        pendingRequestGeneration = generation
        diagnostics.requestPending = true
        diagnostics.lastOutcome = .requested
        if desync {
            diagnostics.desyncRequestCount += 1
        } else {
            diagnostics.reconnectRequestCount += 1
        }
        publishDiagnostics()
        // Independent review, Blocker 1: the follower's own outbound STATE_REQUEST needs the same
        // generation-bound write STATE_SNAPSHOT now gets — `generation` is this function's own
        // parameter, captured at decision time, never re-read live at send time.
        let sent = await session.channel.send(.stateRequest, generation: generation)
        if !sent {
            pendingRequestGeneration = StateResyncGate.onSnapshotObserved(
                pendingGeneration: pendingRequestGeneration, snapshotGeneration: generation
            )
            diagnostics.requestPending = pendingRequestGeneration != nil
            diagnostics.lastOutcome = .sendFailed
            publishDiagnostics()
        }
    }

    private func handle(_ message: ResyncMessage, generation: Int64) async {
        switch message {
        case .stateRequest: await handleStateRequest(generation: generation)
        case .stateSnapshot: await handleStateSnapshot(message, generation: generation)
        }
    }

    /// Only the leader answers (ADR-010). Construction and enqueue now happen **inside**
    /// `SyncPlaybackCoordinator.enqueueStateSnapshotReply` in one step — see its doc comment — so
    /// the provenance re-proof this used to do here (immediately before an independent `send`) is
    /// now the same `stillCurrent`/`stillCurrentNow` pair every other Phase 5 outbound frame gets,
    /// on the one consumer that writes them all in decision order.
    private func handleStateRequest(generation: Int64) async {
        guard isLocalLeader == true else {
            diagnostics.roleViolationCount += 1
            publishDiagnostics()
            return
        }
        await syncPlaybackCoordinator.enqueueStateSnapshotReply(
            generation: generation,
            leaderPeerId: localPeerId,
            manifestRevision: currentCatalogueRevision(),
            // V1 never resumes a transfer (PROTOCOL §10 rule 4), and the receiver already
            // discards/re-requests on every session boundary regardless of this field's contents —
            // so it is left empty rather than threading a transfer id up through this coordinator
            // for a field with no functional consumer (ADR-028, disclosed limitation, mirrors
            // Android exactly).
            transfersInFlight: []
        )
    }

    /// A follower's reconciliation (independent review, Blocker 2E/§20/§21). Unlike before, this no
    /// longer assumes a snapshot *received* is a snapshot *applied* — `onStateSnapshot`'s own return
    /// value says which, and only a genuine acceptance may clear the outstanding-request bookkeeping
    /// or touch manifest bookkeeping.
    private func handleStateSnapshot(_ message: ResyncMessage, generation: Int64) async {
        guard case .stateSnapshot(_, let commandSeq, _, _, _, _, let manifestRevision, _) = message else { return }
        let outcome = await syncPlaybackCoordinator.onStateSnapshot(message, generation: generation)
        switch outcome {
        case .applied, .deferredClock, .deferredContent:
            // §21: the *wire* round trip is satisfied either way — a snapshot for the live generation
            // arrived, so there is nothing left to request — even though `.deferredClock`/
            // `.deferredContent` mean reconciliation itself is not yet complete (that is
            // `.snapshotPending` below). `.deferredContent` resolves through the leader's next
            // authoritative `PLAY` once both sides verify the transferred content (§22), not through
            // `onReconciliationApplied` — there is nothing enqueued for that callback to fire for.
            pendingRequestGeneration = StateResyncGate.onSnapshotObserved(
                pendingGeneration: pendingRequestGeneration, snapshotGeneration: generation
            )
            // §20: manifest bookkeeping follows acceptance, not full playback application — the
            // queue/manifest portions of a snapshot have no clock dependency, so a `.deferredClock`
            // snapshot (playback alone waiting on the clock) still legitimately reports a real
            // manifest_revision worth acting on.
            let previousManifestRevision = lastKnownManifestRevision
            lastKnownManifestRevision = manifestRevision
            if let previousManifestRevision, previousManifestRevision != manifestRevision {
                requestManifestRefresh()
            }
            diagnostics.requestPending = pendingRequestGeneration != nil
            diagnostics.lastOutcome = outcome == .applied ? .reconciled : .snapshotPending
            diagnostics.lastSnapshotManifestRevision = manifestRevision
            diagnostics.lastSnapshotCommandSeq = commandSeq
            publishDiagnostics()
        case .rejectedStale, .rejectedRole:
            // §21: a rejected snapshot must not falsely complete the request, and §20: must not
            // mutate manifest bookkeeping either. Nothing here to update.
            break
        }
    }

    private func publishDiagnostics() {
        onDiagnosticsChanged?(diagnostics)
    }
}

/// Adapts a closure to `ResyncSink`. `submit` is called from the actor-isolated relay and must be
/// non-blocking; it hops to `@MainActor` via `Task` exactly as `ResyncCoordinator.attach` does for
/// the desync trigger.
private struct ResyncSinkAdapter: ResyncSink {
    let onSubmit: @Sendable (ResyncMessage, Int64) -> Void

    func submit(_ message: ResyncMessage, generation: Int64) {
        onSubmit(message, generation)
    }
}
