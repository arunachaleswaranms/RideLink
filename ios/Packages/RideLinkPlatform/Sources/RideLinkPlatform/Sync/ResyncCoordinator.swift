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
/// `.cancelled` (independent-review round 4, Blocker 2) is the fourth terminal state a reconciliation
/// can reach, and it exists because the other three could not express it. A retained reconciliation
/// is **discarded** — not applied, not rejected, not still pending — whenever the ride segment or the
/// control lifetime that owns it ends: End Ride, "Play locally", a link loss, a fail-closed outbound
/// path. Reporting that as `.none` would be indistinguishable from "nothing has ever been asked", and
/// reporting it as `.reconciled` is the defect. Only `.reconciled` means authoritative state actually
/// converged; local state only, no wire change.
public enum ResyncOutcome: Sendable, Equatable {
    case none, requested, snapshotPending, reconciled, cancelled, sendFailed
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
    /// `StateResyncGate`'s own state: the generation a **wire** `STATE_REQUEST` is outstanding for,
    /// if any. Cleared the moment a valid snapshot for that generation arrives — there is nothing
    /// left to *ask* for — which is a genuinely different fact from reconciliation being complete.
    private var pendingRequestGeneration: Int64?

    /// Independent-review round 3, Blocker B: a snapshot that was **accepted** but whose
    /// reconciliation is still outstanding, and the lifetime that owns it.
    ///
    /// This is the fact `pendingRequestGeneration` above cannot carry. `handleStateSnapshot`
    /// correctly clears the wire request on `.deferredClock`/`.deferredContent` — no resend is
    /// needed, the snapshot arrived — and `onReconciliationApplied` then used to complete the
    /// reconciliation only if its generation `== pendingRequestGeneration`, which by then was
    /// **always `nil`**. `.snapshotPending` could therefore never become `.reconciled`, on either
    /// precondition, no matter how promptly it resolved. Two different obligations were being
    /// tracked in one field; they are now two, exactly as Android already models them.
    ///
    /// The generation is **immutable ownership**, recorded from the snapshot that created the
    /// obligation and compared — never reconstructed from whatever generation is live when the
    /// completion callback happens to run (CLAUDE.md rule 20's rule, applied to a local obligation
    /// rather than a frame). A successor generation's own `.connected` triggers a fresh request and
    /// overwrites this, so a predecessor's completion can only ever find a mismatch and be inert.
    ///
    /// The **message** is retained alongside the generation because the later completion is observed
    /// well after `handleStateSnapshot`'s stack frame is gone, and `.reconciled` must report the same
    /// `command_seq`/`manifest_revision` the immediate path reports.
    ///
    /// **Independent-review round 4, Blocker 2: the generation is not a unique owner, and `id` is.**
    /// End Ride deliberately does *not* move the authenticated control generation — the connection,
    /// the pairing and the session all stay alive on purpose — so two reconciliation obligations can
    /// exist one after another under a single generation: S1 accepted and deferred in ride 1,
    /// discarded when the user ends that ride, then S2 accepted in ride 2 under the very same
    /// generation. A generation-keyed completion could not tell them apart, so S2's success completed
    /// **S1**, publishing ride 1's `command_seq`/`manifest_revision` as a reconciliation that never
    /// happened. `id` is minted here, immutably, once per accepted snapshot; it travels into the
    /// retained anchor inside `SyncPlaybackCoordinator` and comes back out with the terminal result.
    /// Both halves are compared, and neither is ever re-derived from live state.
    private struct DeferredReconciliation {
        let id: Int64
        let generation: Int64
        let commandSeq: Int64
        let manifestRevision: Int64
    }

    private var deferredReconciliation: DeferredReconciliation?

    /// Mints `DeferredReconciliation.id`. Strictly increasing, process-local, never on the wire, and
    /// deliberately **not** derived from any live value — a live value is exactly what cannot
    /// identify an obligation whose lifetime has already ended. Starts at 1 so no valid id is the
    /// default-initialised zero of anything downstream.
    private var nextObligationId: Int64 = 1

    /// `@testable`-only view of the obligation currently recorded, so a regression can name the exact
    /// id a late terminal signal must not be able to complete, rather than assuming what it is.
    /// Never read by production code.
    var pendingObligationIdForTest: Int64? { deferredReconciliation?.id }

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
        await syncPlaybackCoordinator.setReconciliationAppliedTrigger { [weak self] obligation, generation in
            guard let self else { return }
            Task { @MainActor in self.onReconciliationApplied(obligation: obligation, generation: generation) }
        }
        // Independent-review round 4, Blocker 2: the other terminal result. End Ride discards the
        // retained reconciliation inside `SyncPlaybackCoordinator` (`leaveSynchronizedMode` clears
        // `deferredEvents` outright, which is correct — the ride that asked for it is over), and the
        // obligation recorded *here* had no way to learn that. Since End Ride does not move the
        // control generation, the next genuine reconciliation under the same generation then
        // completed the discarded one and published ride 1's `command_seq`/`manifest_revision` as
        // `RECONCILED`.
        await syncPlaybackCoordinator.setReconciliationCancelledTrigger { [weak self] obligation, generation in
            guard let self else { return }
            Task { @MainActor in self.onReconciliationCancelled(obligation: obligation, generation: generation) }
        }
        // ADR-028 Amendment: outbound STATE_SNAPSHOT now travels through SyncPlaybackCoordinator's
        // own ordered outbound path — see `enqueueStateSnapshotReply` — so it can never be written
        // out of order relative to a QUEUE_SNAPSHOT/PLAYBACK_STATE decided around the same time.
        await syncPlaybackCoordinator.setResyncChannel(session.channel)
    }

    /// A previously-deferred reconciliation has now genuinely applied (Blocker 2E, repaired by
    /// independent-review round 3's Blocker B).
    ///
    /// Matched against `deferredReconciliation` — the **reconciliation** obligation — and never
    /// against `pendingRequestGeneration`, which is the *wire* obligation and is deliberately already
    /// `nil` by the time this can fire. A signal for an unrelated restoration (an ordinary wire
    /// `PLAYBACK_STATE` reconciling a plain reconnect, or a generation whose obligation has since
    /// been superseded) finds no matching owner and is inert, which is the point: ownership is
    /// compared, not reconstructed.
    ///
    /// Independent-review round 4, Blocker 2: matched on the obligation's **id and** generation.
    /// Matching on the generation alone is not ownership when End Ride leaves the generation
    /// untouched, which it deliberately does.
    private func onReconciliationApplied(obligation: Int64, generation: Int64) {
        guard let deferred = deferredReconciliation, deferred.id == obligation, deferred.generation == generation else { return }
        deferredReconciliation = nil
        completeReconciliation(commandSeq: deferred.commandSeq, manifestRevision: deferred.manifestRevision)
    }

    /// The retained reconciliation this obligation owned was **discarded** rather than applied
    /// (independent-review round 4, Blocker 2).
    ///
    /// Raised by `SyncPlaybackCoordinator` from the one place it throws the held authoritative stream
    /// away, which is reached by End Ride / "Play locally" (`leaveSynchronizedMode`), a control
    /// lifetime boundary (`resetForNewSession`, including a terminal teardown's link loss), a
    /// fail-closed outbound path, and a drain that finds its own generation retired.
    ///
    /// Matched exactly as the applied signal is, and it may **never** produce `.reconciled`: that is
    /// reserved for authoritative state genuinely converging. Nothing here is inferred from an
    /// absence — round 3 already had to remove one inference of that shape, and this is the rule it
    /// established, applied to the obligation rather than to the flag.
    private func onReconciliationCancelled(obligation: Int64, generation: Int64) {
        guard let deferred = deferredReconciliation, deferred.id == obligation, deferred.generation == generation else { return }
        deferredReconciliation = nil
        diagnostics.requestPending = pendingRequestGeneration != nil
        diagnostics.lastOutcome = .cancelled
        publishDiagnostics()
    }

    /// The completion bookkeeping shared by both routes to `.reconciled`: `handleStateSnapshot`'s own
    /// immediate `.applied` branch, and a deferred obligation's later success. Mirrors Android's
    /// `completeReconciliation` exactly.
    private func completeReconciliation(commandSeq: Int64, manifestRevision: Int64) {
        diagnostics.requestPending = pendingRequestGeneration != nil
        diagnostics.lastOutcome = .reconciled
        diagnostics.lastSnapshotManifestRevision = manifestRevision
        diagnostics.lastSnapshotCommandSeq = commandSeq
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
        // Independent-review round 4, Blocker 2. The obligation is minted **and recorded** before the
        // `await` below, and that ordering is load-bearing in both directions:
        //
        // - `onStateSnapshot` suspends. A cancellation raised inside that window — an End Ride, a
        //   link loss — must find something to cancel, or it would be raised against an obligation
        //   this function records a moment later and the discarded snapshot would stay alive.
        // - The cancellation callback hops to this actor, so it can equally arrive *after* this
        //   function resumes. Recording up front makes both orders converge on the same answer: the
        //   cancel clears the record whenever it lands, and every branch below acts only if its own
        //   id is still the recorded one.
        //
        // Recording early also replaces round 3's `supersededByNewer` generation comparison outright.
        // Ids are minted in arrival order on this actor, so a snapshot whose outcome comes back after
        // a newer one has been recorded simply fails its own identity check — which is the same
        // protection, by comparison of two recorded owners rather than of two generations, and it now
        // covers two obligations that share a generation as well.
        let obligation = nextObligationId
        nextObligationId += 1
        deferredReconciliation = DeferredReconciliation(
            id: obligation, generation: generation, commandSeq: commandSeq, manifestRevision: manifestRevision
        )
        let outcome = await syncPlaybackCoordinator.onStateSnapshot(message, generation: generation, reconciliation: obligation)
        switch outcome {
        // ADR-024 Amendment A11: `.deferredCapacity` is retained work carrying this same
        // obligation id and re-attempted by the drain — identical treatment for the same reason
        // `.deferredContent` gets it.
        case .applied, .deferredClock, .deferredContent, .deferredCapacity:
            // §21: the *wire* round trip is satisfied either way — a snapshot for the live generation
            // arrived, so there is nothing left to request — even though `.deferredClock`/
            // `.deferredContent` mean reconciliation itself is not yet complete (that is
            // `.snapshotPending`, and `deferredReconciliation` is what owns it).
            //
            // Independent-review round 3, Blocker B: **both** deferrals are retained obligations.
            // Since Blocker 2A/Race 7, `applyPeerPlaybackState` holds the snapshot in `deferredEvents`
            // for a missing transfer exactly as it does for an untrustworthy clock, and the drain
            // fires `onReconciliationApplied` for whichever precondition resolves — so no second
            // snapshot is needed for either.
            //
            // **Round 4's own fresh-fix defect, found by CI at the exact head.** This clear was
            // briefly placed *after* the obligation-identity guard below — which made the **wire**
            // obligation conditional on the **reconciliation** obligation surviving, and that is
            // precisely the conflation round 3's Blocker B existed to remove. A snapshot whose
            // reconciliation was cancelled inside the `await` above then left `requestPending` true
            // with nothing outstanding to clear it. The two obligations are separate, and a valid
            // snapshot for the live generation satisfies the wire one whatever happens to the other.
            pendingRequestGeneration = StateResyncGate.onSnapshotObserved(
                pendingGeneration: pendingRequestGeneration, snapshotGeneration: generation
            )
            // Whether *this* obligation is still the one being tracked. A cancellation (End Ride, a
            // lifetime boundary) or a newer snapshot during the `await` above means it is not, and
            // everything below belongs to a lifetime that has already been answered — so the wire
            // bookkeeping above stands, and nothing else here may run.
            guard deferredReconciliation?.id == obligation else {
                diagnostics.requestPending = pendingRequestGeneration != nil
                publishDiagnostics()
                return
            }
            // §20: manifest bookkeeping follows acceptance, not full playback application — the
            // queue/manifest portions of a snapshot have no clock dependency, so a `.deferredClock`
            // snapshot (playback alone waiting on the clock) still legitimately reports a real
            // manifest_revision worth acting on.
            let previousManifestRevision = lastKnownManifestRevision
            lastKnownManifestRevision = manifestRevision
            if let previousManifestRevision, previousManifestRevision != manifestRevision {
                requestManifestRefresh()
            }
            if outcome == .applied {
                // Reconciliation is complete, so nothing is left outstanding to watch for. The
                // obligation recorded up front was this snapshot's own, and it is discharged here.
                deferredReconciliation = nil
                completeReconciliation(commandSeq: commandSeq, manifestRevision: manifestRevision)
            } else {
                diagnostics.requestPending = pendingRequestGeneration != nil
                diagnostics.lastOutcome = .snapshotPending
                diagnostics.lastSnapshotManifestRevision = manifestRevision
                diagnostics.lastSnapshotCommandSeq = commandSeq
                publishDiagnostics()
            }
        case .rejectedRide:
            // Independent-review round 4, §17: the snapshot **did** arrive for the live generation —
            // it was refused because the ride segment that authorised its reconciliation ended. So
            // the wire round trip is satisfied exactly as it is above, and only the reconciliation is
            // cancelled. Conflating this with `.rejectedStale` (which never answered the outstanding
            // request at all) left `requestPending` true with nothing that could clear it.
            pendingRequestGeneration = StateResyncGate.onSnapshotObserved(
                pendingGeneration: pendingRequestGeneration, snapshotGeneration: generation
            )
            guard deferredReconciliation?.id == obligation else {
                diagnostics.requestPending = pendingRequestGeneration != nil
                publishDiagnostics()
                return
            }
            deferredReconciliation = nil
            diagnostics.requestPending = pendingRequestGeneration != nil
            diagnostics.lastOutcome = .cancelled
            publishDiagnostics()
        case .rejectedStale, .rejectedRole:
            // §21: a rejected snapshot must not falsely complete the request, and §20: must not
            // mutate manifest bookkeeping either. The obligation this call recorded up front is
            // released — nothing was retained for it, so nothing will ever report on it — but only
            // if it is still ours to release.
            if deferredReconciliation?.id == obligation { deferredReconciliation = nil }
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
