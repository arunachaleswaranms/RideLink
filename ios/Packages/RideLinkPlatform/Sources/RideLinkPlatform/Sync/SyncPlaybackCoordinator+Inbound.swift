import Foundation
import RideLinkCore

/// The receiving, applying and correcting half of `SyncPlaybackCoordinator`. Split by file rather
/// than by type on purpose: every function here reads *and writes* the same actor-isolated state as
/// the issuing half, so making it a second object would mean sharing mutable state across two
/// actors — the opposite of what the actor is for.
extension SyncPlaybackCoordinator {
    // MARK: - Queue mutation

    /// PROTOCOL §9: the leader applies and broadcasts the resulting snapshot; a follower sends the
    /// mutation as an intent and waits. **The snapshot is the only way the queue reaches a
    /// follower** (ADR-024 §5) — §9's own "the snapshot always wins, there is no merge algorithm to
    /// get subtly wrong", taken literally.
    func mutateQueue(_ mutation: SharedQueueMutation) async {
        guard let currentRole = role, !outboundAuthorityLost else { return }
        if currentRole == .follower {
            let generation = await session.currentAuthGeneration()
            guard await stillCurrent(generation) else { return }
            // Amendment A5: and its synchronous mirror, because the proof above is itself a
            // suspension. No `await` from here to the enqueue (Amendment A1 Finding B).
            guard stillCurrentNow(generation) else { return }
            let admitted = enqueueOutbound(
                Phase5Outbound(generation: generation, authority: .intent, frame: .queue(queueIntent(mutation)))
            )
            if !admitted { await onOutboundRefused(.intent, generation: generation) }
            return
        }
        await applyLeaderMutation(mutation)
    }

    private func queueIntent(_ mutation: SharedQueueMutation) -> QueueMessage {
        let header = QueueCommandHeader(commandSeq: PlaybackBounds.unassignedCommandSeq, queueRevision: queueState.revision)
        switch mutation {
        case .add(let items): return .add(header: header, items: items)
        case .remove(let ids): return .remove(header: header, queueItemIds: ids)
        case .move(let id, let toIndex): return .move(header: header, queueItemId: id, toIndex: toIndex)
        }
    }

    /// The leader's queue mutation: apply, bump the revision, and hand the snapshot to the ordered
    /// outbound queue — **with no `await` between them** (Amendment A1 Finding B). A playback command
    /// stamped for the new revision therefore cannot leave this device ahead of the snapshot that
    /// created it, and one stamped for the old revision cannot be overtaken by it.
    private func applyLeaderMutation(_ mutation: SharedQueueMutation) async {
        let generation = await session.currentAuthGeneration()
        guard await stillCurrent(generation), !outboundAuthorityLost else { return }
        // Amendment A5: `queueState` below is live state, and both reads above suspend.
        guard stillCurrentNow(generation) else { return }
        let outcome = SharedQueue.apply(state: queueState, mutation: mutation)
        guard outcome.rejection == nil, outcome.changed else { return }
        // Amendment A2 Finding A / §7: the candidate state is computed first and becomes
        // authoritative only once the snapshot that carries it has been admitted to the one ordered
        // outbound path. A refused snapshot leaves the revision exactly where it was, so the leader
        // can never sit on a revision the follower has no way to learn.
        let candidate = outcome.state
        let snapshot = QueueMessage.snapshot(
            queueRevision: candidate.revision, items: candidate.items, currentIndex: candidate.currentIndex
        )
        let admitted = enqueueOutbound(
            Phase5Outbound(generation: generation, authority: .authoritative, frame: .queue(snapshot)) {
                [weak self] result in
                await self?.onQueueOutcome(generation: generation, outcome: result)
            }
        )
        guard admitted else {
            await onOutboundRefused(.authoritative, generation: generation)
            return
        }
        queueState = candidate
        publishQueue()
        publishDiagnostics()
    }

    /// The leader's queue mutation, once the transport has answered (Amendment A2 Findings A and C).
    ///
    /// A1 Finding A's "the leader's own mutation is the authoritative queue state, so a Play waiting
    /// for exactly this revision may now be issued" is still true — but only once the peer has
    /// actually been told the revision. Issuing a `PLAY` stamped for a revision the follower never
    /// received is the same divergence one layer up, and the follower's own §5 rule 3 check would
    /// refuse it.
    func onQueueOutcome(generation: Int64, outcome: OutboundOutcome) async {
        switch OutboundCommitGate.decide(authority: .authoritative, outcome: outcome) {
        case .abortFailClosed:
            await failClosedOutbound(generation: generation)
        case .abortQuiet:
            break
        case .commit:
            // Launched rather than awaited: resolving a retained Play resolves content, which
            // suspends, and the one outbound consumer must keep draining while it does.
            Task { [weak self] in await self?.resolvePendingPlay() }
        }
    }

    func snapshotMessage() -> QueueMessage {
        .snapshot(queueRevision: queueState.revision, items: queueState.items, currentIndex: queueState.currentIndex)
    }

    /// The leader's answer to having lost an intent to its own bounded ingress: re-state authority.
    /// Both frames go through the one ordered outbound path, so the follower sees the queue snapshot
    /// and the playback state in the order the leader decided them.
    func rebroadcastAuthoritativeState() async {
        guard role == .leader, !outboundAuthorityLost else { return }
        let generation = await session.currentAuthGeneration()
        guard await stillCurrent(generation) else { return }
        // Amendment A5: an advisory frame enqueued into the session that replaced this one still
        // moves that session's outbound counters, and A3 §D settled that this is not an exemption.
        guard stillCurrentNow(generation) else { return }
        // ADVISORY (Amendment A2): it carries no new revision and no new `command_seq`. It re-states
        // authority the peer has already been told about, and PROTOCOL §9's "the snapshot always
        // wins" makes the next one subsume this one, so a failure here is a missed reconciliation
        // attempt rather than a divergence.
        enqueueOutbound(
            Phase5Outbound(generation: generation, authority: .advisory, frame: .queue(snapshotMessage()))
        )
        await emitCurrentPlaybackState()
    }

    // MARK: - Retained one-press Play (Amendment A1 Findings A and E)

    /// Re-evaluates the one retained Play against `PendingPlayGate`, and issues it the instant every
    /// precondition holds. Called on every event that can change one of them: the request itself, an
    /// authoritative queue snapshot, the leader's own accepted mutation, and Phase 4's
    /// verified-availability notification.
    ///
    /// Idempotent by construction — the request is cleared in the same actor-isolated step that
    /// decides to issue it, so two concurrent triggers cannot both fire it.
    func resolvePendingPlay() async {
        guard let pending = pendingPlay, playRequestFence.isCurrent(pending.token) else { return }
        // Both of these suspend, which is exactly why the gate re-proves the fence and the session
        // afterwards rather than trusting the check above.
        let local = await content.resolve(pending.contentHash)
        let peerHas = await content.peerHasContent(pending.contentHash)
        let sessionCurrent = await stillCurrent(pending.generation)
        // **Independent-review round 7.** This function is reached from the Phase 4 availability
        // callback — a transfer completing minutes later — and from the queue-snapshot path, so the
        // press it is resolving can be arbitrarily old. Its ride is therefore proved here, from the
        // provenance the press stored, rather than trusted from the press having once been valid.
        //
        // Deliberately *outside* `PendingPlayGate` rather than a sixth input to it: the gate is a
        // pure, mirrored, vector-pinned table (CLAUDE.md rule 18) and the ride lifetime is not a
        // distributed decision — the same reasoning ADR-024 Amendments A3/A5 gave for adding no
        // vector table. The effect is exactly the gate's own `.cancel`, so no vector moves.
        guard rideStillLive(pending.ride) else {
            clearPendingPlay(pending, cancelled: true)
            return
        }
        let decision = PendingPlayGate.decide(
            operationCurrent: playRequestFence.isCurrent(pending.token),
            sessionCurrent: sessionCurrent,
            syncEnabled: syncEnabled,
            queueSettled: queueState.items.contains { $0.queueItemId == pending.queueItemId },
            localContentReady: local != nil,
            // PROTOCOL §5 rule 4 makes requesting the transfer the *leader's* job, so a follower
            // that gated on the peer half would withhold the one message that unblocks it.
            peerContentRequired: role == .leader,
            peerHasContent: peerHas
        )
        switch decision {
        case .cancel:
            clearPendingPlay(pending, cancelled: true)
        case .waitForQueue:
            if !diagnostics.ingressDesynchronized { diagnostics.syncState = .waitingForQueue }
            publishDiagnostics()
        case .waitForContent:
            if !diagnostics.ingressDesynchronized { diagnostics.syncState = .waitingForContent }
            publishDiagnostics()
            // PROTOCOL §5 rule 4's transfer request, through the **existing** Phase 4 queue, which
            // already de-duplicates a hash it is holding or has (brief §20) — asked once per
            // retained request all the same, so the diagnostics say what actually happened.
            if local == nil, peerHas, transferRequestedForToken != pending.token {
                transferRequestedForToken = pending.token
                await content.requestTransfer(pending.contentHash)
            }
        case .issue:
            guard clearPendingPlay(pending, cancelled: false) else { return }
            let hash = pending.contentHash
            let itemId = pending.queueItemId
            let position = pending.positionMs
            // Round 7: the **press's** ride, not a fresh capture. This is the one authoritative
            // command whose authorising instant is genuinely older than the function issuing it.
            await issue(ride: pending.ride) { header in
                .play(header: header, trackHash: hash, positionMs: position, queueItemId: itemId)
            }
        }
    }

    /// - Returns: true if this call is the one that cleared `pending` — false if something else had.
    @discardableResult
    private func clearPendingPlay(_ pending: PendingPlay, cancelled: Bool) -> Bool {
        guard pendingPlay?.token == pending.token else { return false }
        pendingPlay = nil
        if cancelled {
            diagnostics.cancelledPendingPlayCount += 1
        } else {
            diagnostics.resumedPendingPlayCount += 1
        }
        publishDiagnostics()
        return true
    }

    // MARK: - Inbound dispatch

    /// PROTOCOL §5/§9's inbound path. `generation` was captured at *dispatch* time; every transition
    /// below re-proves it, because ADR-023 Amendment A3 found in Phase 4 that a check at handler
    /// entry says nothing about what suspends afterwards — and on an actor, re-entrancy makes that
    /// emphatically true.
    func onPlaybackMessage(_ message: PlaybackMessage, generation: Int64) async {
        guard await stillCurrent(generation) else { return }
        // Amendment A5: the proof above suspends, so this is what makes reaching the dispatch below
        // atomic with having taken it. Each handler proves for itself as well (A3 Finding B).
        guard stillCurrentNow(generation) else { return }
        switch message {
        case .positionReport(let trackHash, let positionMs, let atSessionUs, _, _):
            await onPeerPositionReport(
                trackHash: trackHash, positionMs: positionMs, atSessionUs: atSessionUs, generation: generation
            )
        case .playbackState(let commandSeq, let queueRevision, let trackHash, let queueItemId, let positionMs, let playing, let atSessionUs):
            // Round 7: captured here, atomically with the dispatch — the guard above is synchronous
            // and there is no `await` between it and this call.
            await onPeerPlaybackState(
                PlaybackStateSnapshotFields(
                    commandSeq: commandSeq, queueRevision: queueRevision, trackHash: trackHash,
                    queueItemId: queueItemId, positionMs: positionMs, playing: playing, atSessionUs: atSessionUs
                ),
                generation: generation,
                ride: admitRide()
            )
        default:
            await onInboundCommand(message, generation: generation)
        }
    }

    func onQueueMessage(_ message: QueueMessage, generation: Int64) async {
        guard await stillCurrent(generation) else { return }
        // Amendment A5, as in `onPlaybackMessage`.
        guard stillCurrentNow(generation) else { return }
        switch message {
        case .snapshot(let revision, let items, let currentIndex):
            await adoptSnapshot(revision: revision, items: items, currentIndex: currentIndex, generation: generation)
        case .add(let header, let items):
            await onQueueIntent(header, .add(items: items), generation: generation)
        case .remove(let header, let ids):
            await onQueueIntent(header, .remove(queueItemIds: ids), generation: generation)
        case .move(let header, let id, let toIndex):
            await onQueueIntent(header, .move(queueItemId: id, toIndex: toIndex), generation: generation)
        }
    }

    /// PROTOCOL §9: "The snapshot always wins — there is no merge algorithm to get subtly wrong."
    /// A follower adopts it wholesale, revision included; it never increments a revision itself.
    ///
    /// It is also the queue half of Amendment A1's reconciliation: adopting authoritative queue state
    /// is precisely what makes a desynchronised queue coherent again.
    @discardableResult
    private func adoptSnapshot(revision: Int64, items: [SharedQueueItem], currentIndex: Int?, generation: Int64) async -> StateSnapshotOutcome {
        // Independent review, Blocker 2E (and the same class as Blocker 2B/2C): proved for itself
        // rather than trusted from a caller. The ordinary wire path (`onQueueMessage`) already checks
        // this before dispatch, but `onStateSnapshot` (Phase 7, ADR-028) calls this function
        // directly, so the queue half needs the same self-contained proof the playback half
        // (`applyPeerPlaybackState`) already has — a snapshot authorised by a retired generation must
        // never touch a live one's queue.
        guard await stillCurrent(generation) else { return .rejectedStale }
        guard role == .follower else { return .rejectedRole }
        // Amendment A2 Finding D: a snapshot must not overtake a command already held for the clock.
        // Applying revision n+1 ahead of a held `NEXT` authored against revision n changes what that
        // `NEXT` means, and no later check can recover the intent it destroyed. Queue adoption itself
        // needs no clock (PROTOCOL §9 is unconditional) — the only reason to hold it is this
        // ordering rule, never clock readiness.
        if holdIfOvertaking(
            .queueSnapshot(revision: revision, items: items, currentIndex: currentIndex, generation: generation)
        ) { return .deferredClock }
        await applyQueueSnapshot(revision: revision, items: items, currentIndex: currentIndex)
        return .applied
    }

    /// `adoptSnapshot` with the hold gate already answered — the drain's entry point too.
    func applyQueueSnapshot(revision: Int64, items: [SharedQueueItem], currentIndex: Int?) async {
        queueState = SharedQueue.applySnapshot(revision: revision, items: items, currentIndex: currentIndex)
        queueDesynchronized = false
        publishDesynchronized()
        publishQueue()
        publishDiagnostics()
        // Finding A: the authoritative revision this Play was waiting for has arrived.
        await resolvePendingPlay()
    }

    // MARK: - Phase 7 resync (PROTOCOL §10, ADR-028)

    /// PROTOCOL §10 (Phase 7, ADR-028 Amendment): the **leader**'s answer to a `STATE_REQUEST`,
    /// constructed and handed to the ordered outbound path in **one step** — the same "read current
    /// state, enqueue it with no `await` in between" discipline every other Phase 5 broadcast already
    /// follows (ADR-024 Amendment A1 Finding B), extended to this phase's new frame type.
    ///
    /// Before this, `ResyncCoordinator` read this same state and sent it over a **second**,
    /// independent channel — so a `QUEUE_SNAPSHOT`/`PLAYBACK_STATE` decided in between could reach the
    /// wire on either side of it, and `adoptSnapshot`'s "wholesale, no merge algorithm" rule meant a
    /// `STATE_SNAPSHOT` naming an **older** revision than one the follower had already adopted could
    /// silently regress it. Folding this into `Phase5Outbound.Frame` closes the gap: whichever was
    /// decided first is now guaranteed to be *written* first, on the one consumer that writes all
    /// three frame kinds.
    ///
    /// `outboundAuthorityLost` is deliberately not re-checked here — `outboundUsable` checks it on
    /// the one consumer immediately before the write, which is PROTOCOL §5 rule 9's "no further
    /// `PLAYBACK_STATE`" applied to this frame too, without a second copy of the same guard.
    func enqueueStateSnapshotReply(
        generation: Int64,
        leaderPeerId: PeerId,
        manifestRevision: Int64,
        transfersInFlight: [ResyncTransferInFlight]
    ) async {
        // **Independent-review round 8's CI investigation: `role == nil` is "not ready yet", not
        // "never".**
        //
        // `role` is cleared by `handleLinkLost`/`resetForNewSession` and set again by
        // `handleConnected` — which `SessionCoordinator` reaches through `launchInSession`, a
        // continuation. The peer's `STATE_REQUEST` travels a different path entirely: the read loop
        // on the freshly authenticated connection, through `ResyncRelay.deliver`'s own hop. Nothing
        // orders those two, so a follower's request can be dispatched here while this side's own
        // `.connected` is still queued — for the **same, live** generation.
        //
        // Returning silently lost the request outright: PROTOCOL §10 has no retry, `StateResyncGate`
        // deliberately sends exactly one request per generation (a storm is the failure mode it was
        // written to prevent), so the follower stayed `requestPending` — and desynchronised — until
        // the *next* reconnect. `ReconnectResyncStressTests`' reconnect loops hit it on a loaded
        // runner roughly once per 30-100 cycles; the drop is what those CI `notReady` timeouts were.
        //
        // The request is therefore **retained**, exactly as this phase retains every other piece of
        // authoritative work it cannot act on yet, and replayed by `handleConnected` once the
        // session it names is established. One slot: a newer generation's request supersedes an
        // older one (nothing can answer the older one any more), and `resetForNewSession` drops it,
        // so nothing accumulates and no request is ever answered twice.
        guard role != nil else {
            pendingStateSnapshotReply = PendingStateSnapshotReply(
                generation: generation, leaderPeerId: leaderPeerId,
                manifestRevision: manifestRevision, transfersInFlight: transfersInFlight
            )
            diagnostics.heldStateSnapshotReplyCount += 1
            publishDiagnostics()
            return
        }
        guard role == .leader else { return }
        let estimate = await estimate()
        let state = await player.playerState()
        guard await stillCurrent(generation) else { return }
        // No `await` between this proof and the enqueue below (Amendment A1/A5's pattern).
        guard stillCurrentNow(generation) else { return }
        // Independent review, Blocker 2B: track/queue-item identity comes from
        // `currentPlaybackIdentity`, not `timeline` — `timeline` is nil immediately after an ordinary
        // reconnect (it is control-lifetime scheduling apparatus and correctly retired with the
        // session that produced its anchor), so reading it here would report "nothing loaded" even
        // while the player is still audibly playing something. Position/playing still come from the
        // live player, which is unaffected by a control-lifetime boundary.
        let playback = estimate.map { est in
            ResyncPlaybackSnapshot(
                trackHash: currentPlaybackIdentity?.trackHash,
                queueItemId: currentPlaybackIdentity?.queueItemId,
                positionMs: max(state.positionMs, 0),
                playing: state.playing,
                atSessionUs: est.sessionUs(localMonoUs: monotonicNowUs())
            )
        }
        let message = ResyncMessage.stateSnapshot(
            leaderPeerId: leaderPeerId,
            commandSeq: lastAppliedSeq ?? 0,
            queueRevision: queueState.revision,
            playback: playback,
            queueItems: queueState.items,
            queueCurrentIndex: queueState.currentIndex,
            manifestRevision: manifestRevision,
            transfersInFlight: transfersInFlight
        )
        enqueueOutbound(Phase5Outbound(generation: generation, authority: .advisory, frame: .resync(message)))
    }

    /// PROTOCOL §10 (Phase 7, ADR-028): a **follower** reconciling against the leader's
    /// `STATE_SNAPSHOT`. Deliberately not a new reconciliation algorithm — it translates the
    /// snapshot's playback and queue portions into exactly the shapes `adoptSnapshot` and
    /// `onPeerPlaybackState` already know how to reconcile (PROTOCOL §5's own cross-reference: a
    /// `STATE_SNAPSHOT.playback` plus its envelope's `command_seq`/`queue_revision` **is** a
    /// `PLAYBACK_STATE`), so every provenance, ownership, hold-gate and desync-clearing rule those
    /// two functions already enforce applies here unchanged. This function invents no new authority
    /// check of its own — `role != .follower` and the generation proof both live inside the two
    /// calls below, exactly as they do for the wire messages this reuses. Mirrors Android's
    /// `SyncPlaybackCoordinator.onStateSnapshot` exactly.
    @discardableResult
    func onStateSnapshot(_ message: ResyncMessage, generation: Int64, reconciliation: Int64?) async -> StateSnapshotOutcome {
        guard case .stateSnapshot(_, let commandSeq, let queueRevision, let playback, let queueItems, let queueCurrentIndex, _, _) = message else {
            return .rejectedStale
        }
        // **Independent-review round 7: captured here, before `adoptSnapshot` suspends.** The playback
        // half is reached only after the queue half has awaited its own generation proof, so capturing
        // inside `onPeerPlaybackState` would label a reconciliation admitted under ride 1 with
        // whichever ride became current inside that await.
        let ride = admitRide()
        let queueOutcome = await adoptSnapshot(revision: queueRevision, items: queueItems, currentIndex: queueCurrentIndex, generation: generation)
        let playbackOutcome = await onPeerPlaybackState(
            PlaybackStateSnapshotFields(
                commandSeq: commandSeq,
                queueRevision: queueRevision,
                trackHash: playback?.trackHash,
                queueItemId: playback?.queueItemId,
                positionMs: playback?.positionMs ?? 0,
                playing: playback?.playing ?? false,
                atSessionUs: playback?.atSessionUs ?? 0
            ),
            generation: generation,
            reconciliation: reconciliation,
            ride: ride
        )
        // Both halves prove the same generation/role independently and will therefore always agree
        // on a rejection; the only way they can differ is the playback half needing to defer for the
        // clock, so a non-`.applied` queue outcome (a rejection) always wins, and otherwise the
        // playback half's own answer — including `.deferredClock` — is the honest overall one.
        // Mirrors Android's `SyncPlaybackCoordinator.onStateSnapshot` exactly.
        return queueOutcome != .applied ? queueOutcome : playbackOutcome
    }

    /// A follower's queue intent, arriving at the leader. Ordering and the stale-revision rule
    /// (PROTOCOL §5 rule 3) are applied here; the leader then serialises the mutation exactly as it
    /// would its own user's, which is what makes two simultaneous adds deterministic.
    private func onQueueIntent(_ header: QueueCommandHeader, _ mutation: SharedQueueMutation, generation: Int64) async {
        guard let currentRole = role, !outboundAuthorityLost else { return }
        switch CommandOrderGate.decide(role: currentRole, lastAppliedSeq: lastReceivedSeq, incomingSeq: header.commandSeq) {
        case .intent:
            break
        case .roleViolation:
            diagnostics.roleViolationCount += 1
            publishDiagnostics()
            return
        default:
            return
        }
        if header.queueRevision != queueState.revision {
            diagnostics.staleRevisionCount += 1
            publishDiagnostics()
            // PROTOCOL §5 rule 3's "the issuer refreshes": the leader re-broadcasts authoritative
            // state rather than waiting to be asked, which needs no message type §3 does not list.
            guard await stillCurrent(generation) else { return }
            guard stillCurrentNow(generation) else { return } // Amendment A5
            enqueueOutbound(
                Phase5Outbound(generation: generation, authority: .advisory, frame: .queue(snapshotMessage()))
            )
            return
        }
        await mutateQueue(mutation)
    }

    private func onInboundCommand(_ message: PlaybackMessage, generation: Int64) async {
        guard let header = Self.headerOf(message), let currentRole = role else { return }
        switch CommandOrderGate.decide(role: currentRole, lastAppliedSeq: lastReceivedSeq, incomingSeq: header.commandSeq) {
        case .duplicate:
            diagnostics.duplicateCommandCount += 1
            publishDiagnostics()
            return
        case .stale:
            diagnostics.staleCommandCount += 1
            publishDiagnostics()
            return
        case .roleViolation:
            diagnostics.roleViolationCount += 1
            publishDiagnostics()
            return
        case .intent:
            await servePlaybackIntent(message, header: header, generation: generation)
            return
        case .accept:
            break
        }
        // Amendment A1 Finding C: while incremental state is not trusted, an incremental command is
        // refused *without* spending its sequence number, so the authoritative snapshot that
        // reconciles us is what decides where ordering resumes from.
        if playbackDesynchronized || queueDesynchronized { return }
        // Amendment A2 Finding D: the revision rule is checked **against the state this command will
        // actually be applied to**. While an authoritative stream is held, the `QUEUE_SNAPSHOT` that
        // created this command's revision is itself held in front of it, so the revision applied
        // *now* is deliberately the older one and checking here would refuse a perfectly ordered
        // command for a revision it is about to be given. The check moves to the replay, in
        // `drainDeferredEvents`.
        if deferredEvents.isEmpty, header.queueRevision != queueState.revision {
            diagnostics.staleRevisionCount += 1
            publishDiagnostics()
            return
        }
        syncEnabled = true
        await admitAuthoritativeCommand(message, header: header, generation: generation)
    }

    /// Amendment A1 Finding D: what happens between "`CommandOrderGate` accepted it" and "it is
    /// scheduled".
    ///
    /// The old shape recorded the command as *applied* and then consulted the clock — so an
    /// estimator that was momentarily untrusted spent the sequence number and applied nothing, and
    /// the leader's replay of that same command was then correctly dropped as a duplicate. The
    /// command was lost permanently, on a clock condition that resolves itself in milliseconds.
    ///
    /// **ADR-024 Amendment A5 Finding A: `estimate()` suspends, and everything below it is live
    /// ordering state.** `sessionClockEstimate()` and `rttP95Us()` are both cross-actor reads, and
    /// on an actor every `await` is a re-entrancy point — so a boundary could land inside that read
    /// and the resumed continuation would then write Session A's `command_seq` into Session B's
    /// `lastReceivedSeq`, append Session A's command to Session B's held stream, or latch Session
    /// B's desynchronised flags. `applyAuthoritative`'s own proof is no defence: it runs *after*
    /// those writes, so it refused a command whose damage was already done, and Session B's own
    /// `command_seq` 1 was then correctly refused as stale by `CommandOrderGate` for the rest of the
    /// session. The proof is therefore taken before the *admission is acted on*, not before the
    /// apply, and there is no `await` between it and any of the three branches' writes.
    ///
    /// **Independent-review round 7: this is the command's real admission point, and it is where the
    /// ride provenance is captured.** `estimate()` below suspends, and both branches after it take
    /// responsibility for the command — `.defer_` writes it into the held stream, `.apply` hands it
    /// to `applyAuthoritative`. Capturing the ride *here*, before that suspension, is what makes both
    /// branches agree on which ride admitted the command; capturing it inside `applyAuthoritative`
    /// (round 6's position) was right for the apply branch and simply absent for the deferred one.
    private func admitAuthoritativeCommand(
        _ message: PlaybackMessage,
        header: PlaybackCommandHeader,
        generation: Int64
    ) async {
        let ride = admitRide()
        let estimate = await estimate()
        guard await stillCurrent(generation) else { return }
        // No `await` from here to any branch's writes below.
        guard stillCurrentNow(generation) else { return }
        // **Independent-review round 8, Blocker B: the ride is proved *here*, adjacent to the
        // bookkeeping, and not only downstream in `applyAuthoritative`.**
        //
        // Round 7 captured the admission before `estimate()` — correct, and necessary — and then
        // relied on the apply path to refuse a stale one. That is too late for the two writes this
        // function owns. `estimate()` suspends (`sessionClockEstimate()` and `rttP95Us()` are both
        // cross-actor reads, and on an actor every `await` is a re-entrancy point), and an accepted
        // End Ride plus an accepted Start Ride can both land inside it while ride 1's cleanup is
        // still parked in `launchInSession` — leaving the control generation unchanged, so both
        // proofs above still pass. `.apply` then wrote `lastAppliedSeq = header.commandSeq` and
        // published it, and only afterwards did `applyPlay`'s own `rideStillLive` refuse the frame
        // with `.rejectedRide`. The playback effect never happened and `lastAppliedSeq` said it had
        // — and that field is what `PLAYBACK_STATE.command_seq` and `STATE_SNAPSHOT.command_seq`
        // publish as "this command is reflected in my authoritative playback state".
        //
        // **Neither sequence number moves.** A ride boundary means this device did not take
        // responsibility for the command, so it does not spend its `command_seq` either — exactly
        // ADR-024 Amendment A1 Finding C's existing rule for an incremental command refused while
        // incremental state is untrusted, applied to the third lifetime. `CommandOrderGate` reads
        // `lastReceivedSeq` as its floor and treats a *gap* as `.accept`, so leaving the floor where
        // it was refuses nothing later; advancing it for a command that will never apply would, by
        // contrast, make the leader's own re-statement of that command a `.duplicate`.
        //
        // Synchronous, with no `await` between it and the branch writes below — the same shape
        // `stillCurrentNow` already has, for the same reason.
        guard rideStillLive(ride) else {
            diagnostics.retiredRideAdmissionCount += 1
            publishDiagnostics()
            return
        }
        let admission = PendingCommandGate.decide(
            clockReady: estimate?.ready == true,
            deferredCount: deferredEvents.count,
            capacity: deferredCommandCapacity
        )
        switch admission {
        case .overflow:
            onHoldOverflow()
        case .defer_:
            // ADR-024 Amendment A13: **this** is where a clock-held command becomes accepted
            // distributed debt — the `lastReceivedSeq` write below — so its retained form says so,
            // in the same synchronous step, with that exact `command_seq`.
            lastReceivedSeq = header.commandSeq
            deferredEvents.append(.acceptedCommand(AcceptedCommand(
                message: message, generation: generation, originalRide: ride, commandSeq: header.commandSeq
            )))
            diagnostics.lastReceivedCommandSeq = header.commandSeq
            diagnostics.deferredCommandCount = deferredEvents.count
            if !diagnostics.ingressDesynchronized { diagnostics.syncState = .clockUnready }
            diagnostics.clockReady = false
            publishDiagnostics()
            startDeferredDrain(generation: generation)
        case .apply:
            guard let estimate else { return }
            // **ADR-024 Amendment A11: a follower takes responsibility here, so capacity is taken
            // here.** Before either sequence number moves — a command whose local work cannot be
            // represented has not been accepted, and Amendment A1 Finding C's rule is that such a
            // command does not spend its `command_seq` either. The honest answer is the existing
            // halt-and-reconcile: the leader's next authoritative snapshot decides where ordering
            // resumes. A follower has delivered nothing, so nothing diverges.
            guard let reservation = reserveWork(generation: generation) else {
                onWorkCapacityExhausted()
                return
            }
            // Released **once**, on every path out of this case including a cancellation. Releasing
            // it twice would be worse than leaking it: the scheduled action this apply may arm joins
            // the same obligation, so a second release frees capacity a live armed effect still owns.
            defer { releaseWork(reservation) }
            lastReceivedSeq = header.commandSeq
            diagnostics.lastReceivedCommandSeq = header.commandSeq
            publishDiagnostics()
            await applyAuthoritative(
                message, generation: generation, ride: ride, estimate: estimate, reservation: reservation,
                delivered: DeliveredAuthority(ride: ride, commandSeq: header.commandSeq, reservation: reservation)
            )
        }
    }

    /// More authoritative work is outstanding than may be held. The same explicit halt-and-reconcile
    /// posture as an ingress overflow, and for the same reason: more authority is outstanding than we
    /// can honestly account for, and applying part of it in the wrong order is worse than admitting
    /// we lost track.
    func onHoldOverflow() {
        diagnostics.inboundOverflowCount += 1
        latchDesynchronized()
    }

    /// The one place `playbackDesynchronized`/`queueDesynchronized` are set on a follower
    /// (independent-review round 3, Blocker A). Mirrors Android's `latchDesynchronized` exactly.
    ///
    /// Latching the flags and refusing the held incremental stream are **one** decision, not two:
    /// ADR-024 Amendment A1 Finding C's rule is that while incremental state is untrusted an
    /// incremental command is refused without spending its sequence number, so the authoritative
    /// snapshot that reconciles us decides where ordering resumes. That rule was applied only to a
    /// command *arriving* (`onInboundCommand`); a command already *held* was left at the head of
    /// `deferredEvents`, where it blocked the repair snapshot queued behind it — and
    /// `drainDeferredEvents` could not step past it, because stepping past would reorder the
    /// authoritative stream (Amendment A1 Finding D). Refusing them here is the same rule applied to
    /// the stream, and it is what makes the drain's per-item desync rule **live** rather than a
    /// deadlock.
    ///
    /// Authoritative state frames already held — a `QUEUE_SNAPSHOT`, a reconciliation
    /// `PLAYBACK_STATE` — are deliberately kept: they are not incremental, they are precisely the
    /// repair, and each names its own instant (PROTOCOL §5 rule 2) rather than depending on the
    /// incremental history this latch has just declared untrustworthy.
    func latchDesynchronized() {
        playbackDesynchronized = true
        queueDesynchronized = true
        refuseHeldIncrementalCommands()
        publishDesynchronized()
        // Independent-review round 3: raised here rather than only from `onIngressOverflow`, so all
        // three latch sites are covered and both platforms are the same shape. One signal per latch
        // event — the edge, never the level (see Android's `onDesynchronizedTrigger` for the storm
        // that level-triggering caused there).
        onDesynchronizedTrigger?()
    }

    /// See `latchDesynchronized`. Counted, never silently dropped.
    private func refuseHeldIncrementalCommands() {
        let before = deferredEvents.count
        deferredEvents.removeAll { held in
            if case .acceptedCommand = held { return true }
            return false
        }
        let refused = before - deferredEvents.count
        guard refused > 0 else { return }
        diagnostics.deferredCommandCount = deferredEvents.count
        diagnostics.refusedHeldCommandCount += refused
    }

    /// A held command's `queue_revision` did not match the revision the replay had reached by the
    /// time it came round (Amendment A2 Finding D).
    ///
    /// In a stream the leader actually produced this cannot happen: replaying its frames in arrival
    /// order reproduces the revisions it stamped them against. Reaching here therefore means a frame
    /// between them is missing — an ingress overflow, or a leader that failed closed mid-sequence —
    /// so the honest answer is PROTOCOL §5 rule 3's refusal *plus* the same halt-and-reconcile
    /// posture as every other "we can no longer account for the authority we hold".
    func onHeldRevisionMismatch() {
        diagnostics.staleRevisionCount += 1
        latchDesynchronized()
    }

    /// Amendment A2 Finding D: whether an authoritative **state** frame may be applied now, or must
    /// join the held stream so it cannot change the meaning of a command already waiting.
    ///
    /// - Returns: true when the frame was held (and the caller must stop), false when it may proceed.
    func holdIfOvertaking(_ event: DeferredEvent) -> Bool {
        switch AuthoritativeHoldGate.decide(heldCount: deferredEvents.count, capacity: deferredCommandCapacity) {
        case .processNow:
            return false
        case .overflow:
            onHoldOverflow()
            return true
        case .hold:
            deferredEvents.append(event)
            diagnostics.deferredCommandCount = deferredEvents.count
            publishDiagnostics()
            startDeferredDrain(generation: event.generation)
            return true
        }
    }

    /// Re-checks the clock on a short cadence while commands wait, so a held `PLAY` becomes audible
    /// as soon as the estimator recovers rather than at the next 5 s position-report tick. One loop
    /// at a time, ended by the session boundary or by the buffer emptying.
    ///
    /// ADR-024 Amendment A13's fresh-fix audit: "running" is `deferredDrainRunning`, not "the task is
    /// not cancelled". A loop that ended because the stream emptied leaves a *finished*, uncancelled
    /// task behind, and treating that as live gave every later hold in the same session no retry
    /// cadence at all — it waited for the 5 s position-report tick. End Ride's retained debt relies
    /// on this drain, so it must start whenever none is running. Android's `isActive` is the mirror.
    func startDeferredDrain(generation: Int64) {
        if deferredDrainRunning, let existing = deferredDrainTask, !existing.isCancelled { return }
        deferredDrainRun &+= 1
        let run = deferredDrainRun
        deferredDrainRunning = true
        deferredDrainTask = Task { [weak self] in
            await self?.runDeferredDrain(generation: generation, run: run)
        }
    }

    /// Actor-isolated, so the `defer` clears the flag in the same actor step that decided to stop —
    /// an empty stream is observed synchronously by `hasDeferredWork` — rather than on a later hop a
    /// newly held command could land before. Only the run that is still current may clear it: a
    /// cancelled predecessor returning after a successor started must not clear the successor's.
    private func runDeferredDrain(generation: Int64, run: Int64) async {
        defer { if run == deferredDrainRun { deferredDrainRunning = false } }
        while await hasDeferredWork(generation: generation) {
            await sleeper.sleep(untilLocalMonoUs: monotonicNowUs() + Phase5GateBounds.deferredRetryIntervalUs)
            if Task.isCancelled { return }
            guard await stillCurrent(generation) else { return }
            await drainDeferredEvents()
        }
    }

    func hasDeferredWork(generation: Int64) async -> Bool {
        guard !deferredEvents.isEmpty else { return false }
        return await stillCurrent(generation)
    }

    /// Replays the held authoritative event stream **in original arrival order** (Amendment A1
    /// Finding D, widened by Amendment A2 Finding D).
    ///
    /// A1 drained commands, and only commands, so a `QUEUE_SNAPSHOT` that arrived while a `NEXT` was
    /// held had already been applied by the time the `NEXT` ran — and the `NEXT` then stepped a
    /// queue it was never authored against. The fix is *not* to re-check the revision here and drop
    /// the command, which would lose an authoritative operation all over again; it is that nothing
    /// overtook it in the first place, so replaying the stream reproduces exactly what the leader
    /// decided.
    ///
    /// A command needs a trustworthy clock and stops the drain until it has one. An authoritative
    /// state frame does not — it names its own instant and PROTOCOL §5 rule 2 applies it immediately
    /// — but it can only ever reach the head of this queue *after* every command in front of it has
    /// been applied, so its position in the stream is what preserves the semantics.
    ///
    /// `lastAppliedSeq` moves here, at the point a command actually takes effect, which is the whole
    /// of A1 Finding D's "received is not applied".
    func drainDeferredEvents() async {
        while !deferredEvents.isEmpty {
            let held = deferredEvents[0]
            // Independent-review round 3's own fresh-fix audit (§17): the proofs and reads below all
            // suspend, and the very next statement after them is an index-based `removeFirst()`. Two
            // things can legitimately shorten this stream inside those windows, because this function
            // is reached from the inbound consumer, the retry cadence **and** the content-availability
            // callback: `applyPeerPlaybackState`'s supersede rule (pre-existing), and — new in this
            // pass — `latchDesynchronized`'s refusal of held incremental commands. Removing by index
            // afterwards would take whatever had moved into position 0, a different authoritative
            // frame. `heldCount` is the cheapest honest witness that nothing moved; Swift enums have
            // no identity to compare, unlike Android's `===`.
            //
            // Ending the pass is safe rather than a wedge: `startDeferredDrain`'s loop and
            // `content.observeAvailability` both call back in, and the next pass re-reads the real
            // head and re-proves everything for it.
            let heldCount = deferredEvents.count
            // **Independent-review round 7: the retained work's own ride, proved before anything
            // else and re-derived nowhere.**
            //
            // Everything from `deferredEvents[0]` above to `removeFirst()` below is synchronous, so
            // nothing can have moved the stream underneath this decision — which is why the pop is
            // safe here without `heldCount`'s witness.
            //
            // This is the case an append-time proof alone cannot cover, and it is the reachable one:
            // `SessionCoordinator.endRide()` publishes its ride epoch synchronously and hands
            // `leaveSynchronizedMode` — the only thing that retires this stream's ride-scoped work,
            // and the only thing that moves `synchronizedModeEpoch` — to `launchInSession`. So
            // between an accepted End Ride and its cleanup actually running, a ride-1 event is still
            // sitting here, with its own `synchronizedModeEpoch` unchanged. Only the `rideEpoch` half
            // sees it. (Since ADR-024 Amendment A13 this applies to ride-scoped anchors only; an
            // accepted command has no `cancellingRide`.)
            //
            // The item is popped rather than left, and the loop continues: a later item may have been
            // admitted under a newer, still-live ride, and leaving a dead one at the head would wedge
            // the stream exactly as round 3's Blocker A did. Its reconciliation obligation gets its
            // terminal cancellation here — never `RECONCILED`, never silence.
            //
            // **Independent-review round 8: this proof is necessary and was not sufficient.** It is
            // taken before the clock read, the content resolve and the generation proofs below, all
            // of which suspend — so each branch re-proves the *same* retained admission immediately
            // before it pops and books the item. See `retireHeldRideEvent`.
            //
            // **ADR-024 Amendment A13: `cancellingRide`, not the event's provenance.** An accepted
            // command has none — its ride is provenance only — so this check can never retire
            // distributed debt; its successor test is `distributedObligationSuperseded`, below.
            if let ride = held.cancellingRide, !rideStillLive(ride) {
                retireHeldRideEvent(held)
                continue
            }
            // Independent-review round 3, Blocker A. This used to be a blanket
            // `if playbackDesynchronized || queueDesynchronized { return }`, which created a circular
            // recovery dependency: `playbackDesynchronized` clears only when the retained
            // authoritative reconciliation actually applies (`applyPeerPlaybackState`), and that
            // snapshot could only apply from this drain — which the very same flag stopped. A
            // follower whose ingress overflowed and whose `STATE_SNAPSHOT` then had to wait for a
            // fresh clock or a content transfer therefore stayed desynchronised **permanently**, no
            // matter how promptly the precondition resolved.
            //
            // The guard is now per-item, because the two kinds of held work answer the question
            // differently:
            //
            // - an **incremental command** must stay blocked — applying one against state we have
            //   declared untrustworthy is exactly what the latch exists to prevent (A1 Finding C).
            //   In practice none is ever here: `latchDesynchronized` refuses the held ones and
            //   `onInboundCommand` refuses arriving ones, so this branch is fail-closed defence for a
            //   shape that is unreachable by construction rather than by assumption.
            // - an **authoritative state frame** — a `QUEUE_SNAPSHOT`, or the reconciliation
            //   `PLAYBACK_STATE` a `STATE_SNAPSHOT` produced — *is* the repair. Blocking it on the
            //   condition it exists to clear is the deadlock. It still proves its own generation,
            //   role, clock readiness, content availability and ordering below and in the apply path;
            //   nothing is bypassed here except a flag that was never about authoritative state.
            if playbackDesynchronized || queueDesynchronized {
                if case .acceptedCommand = held { return }
            }
            guard await stillCurrent(held.generation) else {
                // Independent-review round 4, Blocker 2: a retained reconciliation thrown away here
                // owes its outer owner a terminal result, exactly as one thrown away by a boundary
                // does — "the buffer emptied" is not a result anybody may read.
                discardDeferredEvents()
                diagnostics.deferredCommandCount = 0
                publishDiagnostics()
                return
            }
            // Amendment A5: the proof above suspends. If a boundary landed in it, `resetForNewSession`
            // has already cleared this buffer, so there is nothing of the old session's left to drop —
            // and removing from it below would be indexing state the new session owns.
            guard stillCurrentNow(held.generation) else { return }
            guard deferredEvents.count == heldCount else { return }
            switch held {
            case .acceptedCommand(let accepted):
                let message = accepted.message, generation = accepted.generation
                guard let estimate = await estimate(), estimate.ready else { return }
                // Amendment A5 Finding A's shape one function along: `estimate()` suspends and the
                // very next statements index, remove from and write the held stream.
                guard await stillCurrent(generation) else { return }
                guard stillCurrentNow(generation), deferredEvents.count == heldCount else { return }
                // **Independent-review round 8, Blocker A.** The `estimate()` above and the
                // `stillCurrent` proof after it both suspend, and the top-of-loop ride proof is
                // therefore older than every write below. An accepted End Ride *and* an accepted
                // Start Ride can both land inside those suspensions while ride 1's cleanup is still
                // parked — the control generation does not move, and `deferredEvents` is not emptied
                // until that cleanup runs, so `stillCurrentNow` and the `heldCount` witness both
                // still pass. The old code then popped the item, set `lastAppliedSeq = seq`,
                // published `lastAppliedCommandSeq` and counted a recovery, and only afterwards did
                // `applyAuthoritative` refuse the frame as `.rejectedRide`. The caller owns its own
                // bookkeeping and must prove ownership before changing it: a downstream refusal
                // cannot un-publish a `command_seq` that `PLAYBACK_STATE`/`STATE_SNAPSHOT` have
                // already been told is reflected in this device's authoritative playback state.
                //
                // Retired rather than left: the stream is in arrival order and an item behind this
                // one may belong to a newer, still-live ride, so leaving a dead head would wedge it
                // exactly as independent-review round 3's Blocker A did. No `await` from here to the
                // writes below.
                //
                // **ADR-024 Amendment A13 replaced the proof, not the position.** This command
                // already advanced `lastReceivedSeq`; the leader has, or will, represent it. So its
                // original ride having ended is not a reason to drop it — that was the defect — and
                // the question adjacent to the pop is the one `mayRepresent` asks of delivered work:
                // has newer *established* authority already superseded it? The ordered stream makes
                // that unreachable here in practice (nothing overtakes held work), so this is
                // fail-closed defence; the reachable successor race is after the pop, where
                // `mayRepresent` refuses it. A superseded command is popped, counted, and the drain
                // continues — never left to wedge the stream.
                guard !distributedObligationSuperseded(
                    originalRide: accepted.originalRide, commandSeq: accepted.commandSeq
                ) else {
                    retireSupersededAcceptedCommand()
                    continue
                }
                let heldHeader = Self.headerOf(message)
                if let heldHeader, heldHeader.queueRevision != queueState.revision {
                    deferredEvents.removeFirst()
                    diagnostics.deferredCommandCount = deferredEvents.count
                    onHeldRevisionMismatch()
                    return
                }
                // **ADR-024 Amendment A11**: a replay takes responsibility exactly as a first arrival
                // does, so it takes capacity the same way — before the pop and before
                // `lastAppliedSeq` moves. With none, the command stays exactly where it is: still
                // head of the held stream, still owning its `command_seq`, re-attempted on the
                // drain's own cadence. Nothing is abandoned and nothing is claimed.
                //
                // ADR-024 Amendment A13: an accepted command waiting for capacity is **waiting**, not
                // refused — its `command_seq` is already spent — so the advisory pre-check keeps the
                // wait out of `workCapacityRefusedCount`, and the wait is counted as what it is: once
                // per drain pass, which the retry cadence paces, never a spin.
                guard hasWorkCapacity, let reservation = reserveWork(generation: generation) else {
                    diagnostics.heldCommandCapacityWaitCount += 1
                    publishDiagnostics()
                    return
                }
                // Exactly one release, on every path out of this case including a cancellation.
                defer { releaseWork(reservation) }
                deferredEvents.removeFirst()
                // Popping is not representation: `lastAppliedSeq` moves in `representDelivered`.
                diagnostics.deferredCommandCount = deferredEvents.count
                diagnostics.recoveredCommandCount += 1
                diagnostics.clockReady = true
                publishDiagnostics()
                // Round 7: the ride the command was **admitted** under, replayed unchanged. The
                // `estimate()` above suspends, so a fresh capture here would be precisely the defect.
                await applyAuthoritative(
                    message, generation: generation, ride: accepted.originalRide, estimate: estimate,
                    reservation: reservation,
                    delivered: DeliveredAuthority(
                        ride: accepted.originalRide, commandSeq: accepted.commandSeq, reservation: reservation
                    )
                )
            case .queueSnapshot(let revision, let items, let currentIndex, _):
                deferredEvents.removeFirst()
                diagnostics.deferredCommandCount = deferredEvents.count
                diagnostics.recoveredCommandCount += 1
                publishDiagnostics()
                await applyQueueSnapshot(revision: revision, items: items, currentIndex: currentIndex)
            case .playbackState(let fields, let generation, let reconciliation, let ride):
                // Amendment A11, and this pass's own fresh-fix audit: the same shape as the clock and
                // content pre-checks below. `restoreFromPlaybackState` re-appends this anchor when it
                // cannot reserve, so popping it first would pop-fail-re-append in a tight loop.
                guard hasWorkCapacity else { return }
                // Independent review, Blocker 2A/2D: mirrors the `.command` case immediately above.
                // A reconciliation that still needs full restoration must not be popped and applied
                // (or, without this check, popped, found not-ready again inside
                // `restoreFromPlaybackState`, re-appended, and retried in a tight synchronous loop
                // within this same call) while the clock remains untrustworthy — `applyPeerPlaybackState`
                // re-derives `needsFullRestore` itself, but checking it here first is what lets this
                // branch `return` (ending the drain, exactly like `.command` does) instead of looping.
                if playbackDesynchronized || timeline == nil {
                    guard let estimate = await estimate(), estimate.ready else { return }
                    // Independent review, Race 7: the identical guard, for content readiness — without
                    // it, this entry pops, finds content still missing inside `applyPeerPlaybackState`'s
                    // own pre-check, gets re-appended, and the `while` loop above retries it
                    // synchronously with nothing to wait on, exactly the tight loop the comment above
                    // already guards against for the clock.
                    let contentReady: Bool
                    if let trackHash = fields.trackHash {
                        contentReady = await content.resolve(trackHash) != nil
                    } else {
                        contentReady = true
                    }
                    guard contentReady else { return }
                    guard await stillCurrent(generation) else { return }
                    guard stillCurrentNow(generation) else { return }
                    guard deferredEvents.count == heldCount else { return }
                }
                // **Independent-review round 8, Blocker C.** Every path to this point has suspended
                // since the top-of-loop ride proof — the `await stillCurrent(held.generation)`
                // before the `switch` on the fast path, and `estimate()`/`content.resolve` as well
                // on the full-restore path. The same re-proof the `.command` branch needs, for the
                // same reason and in the same position: popped, counted and reported only once the
                // retained admission has been proved live with no `await` in between.
                //
                // Its obligation gets its terminal cancellation from `retireHeldRideEvent`, so a
                // `STATE_SNAPSHOT` retired here is `CANCELLED` and never `RECONCILED`, and never
                // left outstanding.
                guard rideStillLive(ride) else {
                    retireHeldRideEvent(held)
                    continue
                }
                deferredEvents.removeFirst()
                diagnostics.deferredCommandCount = deferredEvents.count
                publishDiagnostics()
                // Round 7: the retained admission, replayed unchanged — the clock and content reads
                // above both suspend.
                let outcome = await applyPeerPlaybackState(
                    fields, generation: generation, reconciliation: reconciliation, ride: ride
                )
                // **Independent-review round 8, Blocker C's second half.** `recoveredCommandCount`
                // is documented as "how many held events were **applied** once the clock became
                // trustworthy again", and this branch used to increment it before the outcome was
                // known — so a `.rejectedStale`, a `.rejectedRide`, or a re-deferral (which
                // `applyPeerPlaybackState` legitimately produces by re-appending this same anchor
                // for a transfer or a clock) all counted as a successful recovery. Only `.applied`
                // does now. The `.command` and `.queueSnapshot` branches keep theirs where it is:
                // both hand the event straight to an apply whose every precondition — clock,
                // ordering revision, control generation and ride lifetime — was proved
                // synchronously adjacent to the pop.
                if outcome == .applied {
                    diagnostics.recoveredCommandCount += 1
                    publishDiagnostics()
                }
                // Independent review, Blocker 2E: this is specifically the "was deferred, now
                // applied" transition `ResyncCoordinator` cannot otherwise observe — the synchronous
                // first attempt already answers its caller directly via `onStateSnapshot`'s return
                // value, so firing this callback only here (not on every restoration) keeps the
                // common, non-deferred reconnect path exactly as cheap as before this fix.
                //
                // Independent-review round 4, Blocker 2: **every** branch is terminal for the
                // obligation now. It has been popped, so if the apply refused it (its generation
                // ended inside the apply's own proofs) nothing will ever come back to it and its
                // owner must be told, not left waiting. A re-deferral is the one non-terminal
                // answer: `applyPeerPlaybackState` re-appended the anchor carrying this same id, so
                // the obligation is still live and still owned.
                //
                // A `nil` id is an ordinary wire `PLAYBACK_STATE` that was held for the clock: no
                // `STATE_SNAPSHOT` produced it, nobody outside is waiting on it, and firing a
                // terminal result for it would be a signal with no obligation behind it.
                if let reconciliation {
                    switch outcome {
                    case .applied: onReconciliationApplied?(reconciliation, generation)
                    case .rejectedStale, .rejectedRole, .rejectedRide: onReconciliationCancelled?(reconciliation, generation)
                    // Non-terminal: the anchor is retained, carrying this same obligation id, and
                    // the drain will report on it. Amendment A11 adds the third for the same reason.
                    case .deferredClock, .deferredContent, .deferredCapacity: break
                    }
                }
            }
        }
    }

    /// Discards the retained event at the **head** of the held stream because the ride lifetime that
    /// admitted it is no longer live, and gives its reconciliation obligation the terminal
    /// cancellation it is owed (independent-review rounds 7 and 8).
    ///
    /// Synchronous by construction, and every caller invokes it with no `await` between its own
    /// `rideStillLive` proof and this call — the pop, the counters and the cancellation are one
    /// actor-isolated step, exactly as the bookkeeping it replaces would have been.
    ///
    /// **It is deliberately not "recovered".** `recoveredCommandCount` means applied;
    /// `lastAppliedSeq` means applied; this event was applied to nothing. `retiredRideDeferredCount`
    /// is the one counter that grows, which is what makes the discard observable rather than silent.
    ///
    /// The caller **continues** the drain rather than returning: the stream is in arrival order and
    /// an item behind a dead one may have been admitted under a newer, still-live ride. Leaving a
    /// dead head in place would wedge everything behind it, which is independent-review round 3's
    /// Blocker A reintroduced by the fix written to prevent a different defect.
    private func retireHeldRideEvent(_ held: DeferredEvent) {
        assert(held.cancellingRide != nil, "an accepted command is never retired by its ride")
        deferredEvents.removeFirst()
        diagnostics.deferredCommandCount = deferredEvents.count
        diagnostics.retiredRideDeferredCount += 1
        publishDiagnostics()
        if let obligation = held.reconciliation {
            onReconciliationCancelled?(obligation, held.generation)
        }
    }

    /// Pops an accepted command at the head of the held stream because newer established authority
    /// already accounts for it (ADR-024 Amendment A13). Synchronous; the caller proved the head and
    /// the supersession with no `await` between. Counted as superseded — never "recovered" (it was
    /// applied to nothing) and never "retired by ride" (that is not its cancellation authority).
    private func retireSupersededAcceptedCommand() {
        deferredEvents.removeFirst()
        diagnostics.deferredCommandCount = deferredEvents.count
        diagnostics.supersededHeldCommandCount += 1
        publishDiagnostics()
    }

    /// A follower's playback intent, arriving at the leader (ADR-024 §3). The leader validates,
    /// stamps and broadcasts — one serialisation point, so two users pressing different buttons at
    /// the same instant resolve by the leader's arrival order rather than by comparing timestamps.
    private func servePlaybackIntent(_ message: PlaybackMessage, header: PlaybackCommandHeader, generation: Int64) async {
        // Round 7: the ride live at the instant the leader admitted the follower's intent, captured
        // before this function's first suspension and carried into the retained Play or the stamp.
        let ride = admitRide()
        if header.queueRevision != queueState.revision {
            diagnostics.staleRevisionCount += 1
            publishDiagnostics()
            guard await stillCurrent(generation) else { return }
            guard stillCurrentNow(generation) else { return } // Amendment A5
            enqueueOutbound(
                Phase5Outbound(generation: generation, authority: .advisory, frame: .queue(snapshotMessage()))
            )
            return
        }
        guard await stillCurrent(generation), !outboundAuthorityLost else { return }
        // Amendment A5: `playRequestFence.begin()` below **supersedes** whatever Play is current, so
        // a retired intent resuming here would cancel the live session's own retained Play.
        guard stillCurrentNow(generation) else { return }
        // Independent-review round 8's sweep, the same shape as `playSynchronized`: `stillCurrent`
        // above suspends, and `playRequestFence.begin()` below supersedes whatever retained Play is
        // current. `issue` proves the ride for itself, so this covers the retention branch alone.
        guard rideStillLive(ride) else { return }
        syncEnabled = true
        if case .play(_, let trackHash, _, let queueItemId) = message {
            // Amendment A1 Finding E, the other user's half: the leader retains the follower's Play
            // exactly as it retains its own user's, so a track neither phone can play yet becomes
            // one authoritative PLAY when the transfer verifies — the leader being the only side
            // with the authority to reschedule it (PROTOCOL §5 rule 4).
            var intentPositionMs: Int64 = 0
            if case .play(_, _, let positionMs, _) = message { intentPositionMs = positionMs }
            pendingPlay = PendingPlay(
                token: playRequestFence.begin(), generation: generation, ride: ride, contentHash: trackHash,
                queueItemId: queueItemId, positionMs: intentPositionMs
            )
            await resolvePendingPlay()
            return
        }
        await issue(ride: ride) { stamped in Self.restamp(message, with: stamped) }
    }

    /// The same message, carrying the leader's authoritative header instead of the intent's.
    static func restamp(_ message: PlaybackMessage, with header: PlaybackCommandHeader) -> PlaybackMessage {
        switch message {
        case .play(_, let trackHash, let positionMs, let queueItemId):
            return .play(header: header, trackHash: trackHash, positionMs: positionMs, queueItemId: queueItemId)
        case .pause(_, let positionMs): return .pause(header: header, positionMs: positionMs)
        case .resume(_, let positionMs): return .resume(header: header, positionMs: positionMs)
        case .seek(_, let target): return .seek(header: header, targetPositionMs: target)
        case .next: return .next(header: header)
        case .previous: return .previous(header: header)
        default: return message
        }
    }

    static func headerOf(_ message: PlaybackMessage) -> PlaybackCommandHeader? {
        switch message {
        case .play(let header, _, _, _), .pause(let header, _), .resume(let header, _),
             .seek(let header, _), .next(let header), .previous(let header):
            return header
        default:
            return nil
        }
    }

    /// Drains the inbound queue, one frame at a time, in arrival order. One consumer, so frame N+1
    /// is never handled before frame N — see `PlaybackForwarder`'s doc comment for why that is a
    /// correctness property and not a tidiness one.
    func drainInbound() async {
        while let item = await inbound.take() {
            observeIngressStats()
            switch item {
            case .playback(let message, let generation):
                await onPlaybackMessage(message, generation: generation)
            case .queue(let message, let generation):
                await onQueueMessage(message, generation: generation)
            }
            diagnostics.inboundProcessedCount += 1
            publishDiagnostics()
        }
    }

    /// Attributes the queue's ingress losses to the generations that caused them, and latches the
    /// halt if a frame **of the live session** was refused. Called once per drained frame,
    /// **before** that frame is dispatched, so a refusal can never be followed by an applied command.
    ///
    /// **Amendment A6.** This used to diff two cumulative counters and act on the difference with
    /// whatever role and session were live at that instant. The pipe deliberately outlives sessions,
    /// so that read "Session A lost a frame" as "*we* lost a frame" — and since a follower answers a
    /// loss by setting `playbackDesynchronized`/`queueDesynchronized`, a refusal in a dead session
    /// halted the live one. Each record now carries its own generation and is judged against that.
    ///
    /// Fully synchronous, deliberately: there is no `await` between reading the records, deciding
    /// whose they are and acting on them, so the decision cannot be overtaken by a boundary the way
    /// Amendment A5's three sites were. `stillCurrentNow` is therefore both necessary and
    /// sufficient — A5's `await stillCurrent` pairing exists for work that *has* suspended, and
    /// adding an `await` here would manufacture the very re-entrancy point it defends against.
    func observeIngressStats() {
        let losses = inbound.drainLosses()
        guard !losses.isEmpty else { return }
        var overflows = 0
        var coalesces = 0
        var retired = 0
        for loss in losses {
            if stillCurrentNow(loss.generation) {
                overflows += loss.overflowCount
                coalesces += loss.coalescedCount
            } else {
                // Never silently discarded: a loss belonging to a session that has ended is a real
                // event that simply has no live session to halt, and it is surfaced as exactly that.
                retired += loss.overflowCount + loss.coalescedCount
            }
        }
        diagnostics.inboundOverflowCount += overflows
        diagnostics.inboundCoalescedCount += coalesces
        diagnostics.inboundRetiredLossCount += retired
        if overflows > 0 { onIngressOverflow() }
        publishDiagnostics()
    }

    /// PROTOCOL §5/§9's frames arrived faster than they could be considered, and the queue held
    /// nothing that could be superseded. **The frame is refused, not evicted** — the difference
    /// matters, because it means the frames already queued still apply in order and the only thing
    /// lost is one we never claimed to have taken.
    ///
    /// On a **follower** that is a genuine loss of incremental authority, so incremental state stops
    /// being trusted until authoritative full state arrives or the session ends.
    ///
    /// On the **leader** it is not: the only incremental frames a leader accepts are *intents*, which
    /// it stamps rather than applies, so its own authoritative state cannot have become incoherent.
    /// What was lost is a button press, so the leader re-broadcasts its authoritative state and does
    /// not halt.
    func onIngressOverflow() {
        if role == .leader {
            Task { [weak self] in await self?.rebroadcastAuthoritativeState() }
            return
        }
        latchDesynchronized()
    }

    /// Test-only entry point for the exact effect a real ingress overflow already produces on a
    /// follower (Amendment A1 Finding C) — the mirror of Android's `forceDesynchronizedForTest`,
    /// added for independent-review round 3's Blocker A regressions so a test about Phase 7's
    /// *recovery* need not reconstruct Phase 5's overflow mechanics to reach the latch.
    func forceDesynchronizedForTest() {
        latchDesynchronized()
    }

    /// Publishes the latch. While it is set, `.desynchronized` is what the user sees; once it clears,
    /// the displayed state is left for the next real transition to set — the restore's `scheduleAt`
    /// (`.scheduled`), `markSynced` (`.synced`) or `readyEstimate` (`.clockUnready`) — rather than
    /// being guessed at here.
    func publishDesynchronized() {
        let desynchronized = playbackDesynchronized || queueDesynchronized
        diagnostics.ingressDesynchronized = desynchronized
        if desynchronized { diagnostics.syncState = .desynchronized }
        publishDiagnostics()
    }

    // MARK: - Applying

    /// Amendment A3 Finding B: **every** apply path proves its authorising generation before it
    /// mutates anything, and each of them does so for itself rather than trusting this entry point.
    /// The proof here is the cheap common case — an apply whose session died while it queued does no
    /// work at all — but `applyPlay`, `applyTransport`, `applySeek` and `applyStep` are each
    /// reachable from more than one caller and each suspends, so none of them may rely on it. On an
    /// actor that is not fastidiousness: every `await` below is a re-entrancy point.
    ///
    /// **Independent-review round 4, §17's audit: the ride lifetime is captured here, once, and
    /// threaded.** Round 3 closed this class in `applyPlay` alone, and by re-reading
    /// `synchronizedModeEpoch` at *that function's* own entry — which is right when `applyPlay` is the
    /// operation, and wrong when it is a later step of one. Two reachable holes remained, both of the
    /// shape the rule exists to stop and neither needing the control generation to move:
    ///
    /// - `applyStep` had no ride proof at all. `stillCurrent` suspends, so a `NEXT` that runs off the
    ///   end of the queue could take its `selected == nil` branch **after** an End Ride, call
    ///   `epoch.begin()` — minting a *fresh, live* playback epoch over the one `leaveSynchronizedMode`
    ///   had just superseded — and schedule `[.stop, .clearSelection]`, which reaches the player. End
    ///   Ride's whole contract is that local music keeps playing (FR-025); this stopped it.
    /// - `applyPlay` reached *through* `applyStep` or `restoreFromPlaybackState` captured the epoch at
    ///   its own entry, which by then was already the post-End-Ride value — so its guard compared the
    ///   new value with itself and passed, re-establishing a synchronised timeline and ride identity
    ///   for a ride that was over.
    ///
    /// The capture is the first statement here, before this function's own first `await`, and every
    /// step below compares it rather than re-reading. That is the same rule the *generation* already
    /// follows (CLAUDE.md rules 19/20) applied to the third lifetime: an operation's authorising ride
    /// travels with it, and a later stage never asks what the ride is *now*.
    ///
    /// **Independent-review round 6: `rideLifetime` alone is not enough, and the `rideEpoch` half is
    /// carried for the same reason.** `synchronizedModeEpoch` only moves when `leaveSynchronizedMode`
    /// actually *runs*, and End Ride's cleanup is asynchronous — so an operation parked here across an
    /// accepted End Ride *and* a further accepted Start Ride can resume with it unchanged even though
    /// `rideEpochs.current` has moved twice. See `RideAdmission` and `rideStillLive`.
    ///
    /// **Independent-review round 7: the admission is the caller's, and this function no longer
    /// captures one.** Round 6's capture-at-entry is correct for a command applied straight off the
    /// wire and *wrong* for every other route into here, because each of those routes has already
    /// suspended: a command replayed from `deferredEvents` was admitted before the clock failed, and a
    /// leader's own command was admitted before the transport answered. Making the parameter required
    /// is what stops a replay silently minting a replacement provenance — there is no default to fall
    /// back to, so every caller has to say which ride authorised the work.
    func applyAuthoritative(
        _ message: PlaybackMessage,
        generation: Int64,
        ride: RideAdmission,
        estimate: SessionClockEstimate,
        /// ADR-024 Amendment A11: the obligation the **caller** reserved when it took responsibility
        /// for this command, threaded rather than re-taken. A replay must not mint a replacement
        /// capacity any more than it may mint a replacement `RideAdmission`.
        reservation: WorkReservation,
        delivered: DeliveredAuthority? = nil
    ) async {
        guard await stillCurrent(generation) else { return }
        guard stillCurrentNow(generation) else { return } // Amendment A5
        switch message {
        case .play(let header, let trackHash, let positionMs, let queueItemId):
            await applyPlay(header, trackHash: trackHash, queueItemId: queueItemId, positionMs: positionMs,
                            generation: generation, estimate: estimate, ride: ride, reservation: reservation, delivered: delivered)
        case .pause(let header, let positionMs):
            await applyTransport(header, generation: generation, estimate: estimate, playing: false,
                                 positionMs: positionMs, ride: ride, reservation: reservation, delivered: delivered)
        case .resume(let header, let positionMs):
            await applyTransport(header, generation: generation, estimate: estimate, playing: true,
                                 positionMs: positionMs, ride: ride, reservation: reservation, delivered: delivered)
        case .seek(let header, let target):
            await applySeek(header, targetPositionMs: target, generation: generation, estimate: estimate,
                            ride: ride, reservation: reservation, delivered: delivered)
        case .next(let header):
            await applyStep(header, delta: 1, generation: generation, estimate: estimate,
                            ride: ride, reservation: reservation, delivered: delivered)
        case .previous(let header):
            await applyStep(header, delta: -1, generation: generation, estimate: estimate,
                            ride: ride, reservation: reservation, delivered: delivered)
        default:
            break
        }
    }

    /// ARCHITECTURE §7.2 steps 1-6: resolve, pre-roll the decoder while there is still time, then
    /// start at the deadline. The resolve and the pre-roll are both real suspension points, so the
    /// session generation and the epoch token are re-proved after each.
    ///
    /// - Returns: **why** authoritative state was or was not established here.
    ///
    ///   **Independent-review round 5, Blocker 2B: this was a `Bool`, and one bit could not carry
    ///   the distinction its only interested caller needs.** `restoreFromPlaybackState` mapped every
    ///   `false` to `.deferredContent` — including a `false` produced by the **ride** ending inside
    ///   `content.resolve`, and a `false` from a retired control generation. `.deferredContent`
    ///   promises the outer obligation that work is retained and will report later; on this path
    ///   nothing was retained, so `ResyncCoordinator` held an obligation with no route to either
    ///   `Applied` or `Cancelled` — outstanding for the rest of the session — while ride 1's
    ///   `manifest_revision` was published as accepted bookkeeping on the way past.
    ///
    ///   The other two callers (`applyAuthoritative`, `applyStep`) answer nobody and may discard it,
    ///   exactly as they already discarded this call's effect.
    @discardableResult
    private func applyPlay(
        _ header: PlaybackCommandHeader,
        trackHash: ContentHash,
        queueItemId: String,
        positionMs: Int64,
        generation: Int64,
        estimate: SessionClockEstimate,
        playing: Bool = true,
        /// The ride lifetime that authorised the **operation this is a step of**, captured by the
        /// coordinator at that operation's admission and never re-read here (independent-review
        /// rounds 4 §17, 6 and 7). See `RideAdmission`.
        ride: RideAdmission,
        /// ADR-024 Amendment A11: the caller's obligation. See `applyAuthoritative`.
        reservation: WorkReservation,
        delivered: DeliveredAuthority? = nil
    ) async -> StateSnapshotOutcome {
        // Amendment A3 Finding B: reached from `applyAuthoritative`, from `applyStep` and from
        // `restoreFromPlaybackState`, and `content.resolve` is real I/O on another actor. Proved on
        // entry so a Play that only *starts* after a boundary does no work, and again below because
        // the resolve suspends.
        guard await stillCurrent(generation) else { return .rejectedStale }
        guard stillCurrentNow(generation) else { return .rejectedStale } // Amendment A5
        let playable = await content.resolve(trackHash)
        guard await stillCurrent(generation) else { return .rejectedStale }
        // Amendment A5: `epoch.begin()` below retires whatever playback epoch is current, which is
        // A3 Finding C's catastrophe — so the proof adjacent to it has to be the synchronous one.
        guard stillCurrentNow(generation) else { return .rejectedStale }
        guard mayRepresent(ride, delivered: delivered) else { return .rejectedRide }
        guard let playable else {
            // PROTOCOL §5 rule 4: do not start, request the transfer, let the leader reschedule.
            diagnostics.syncState = .waitingForContent
            diagnostics.currentTrackHash = trackHash
            publishDiagnostics()
            await content.requestTransfer(trackHash)
            // Independent-review round 5, Blocker 2: reported, not swallowed. Nothing is retained
            // *here* — retention belongs to the caller that owns a reconciliation obligation, and
            // `restoreFromPlaybackState` does it, because "every `.deferred*` corresponds to actual
            // retained work carrying the same obligation id" is the promise this case makes.
            return .deferredContent
        }
        // Independent-review round 3, found by CI on this pass's own new ride regression. The proofs
        // above are all about the **control generation**, which End Ride deliberately does not move —
        // the session stays alive. `content.resolve` above suspends, so an apply authorised before
        // the ride ended can resume after `leaveSynchronizedMode` has retired every field written
        // below and put all of them back. Synchronous, adjacent to the writes, with no `await`
        // between: ADR-024 Amendment A5's `stillCurrentNow` pattern applied to the third lifetime.
        //
        // Independent-review round 6: the `synchronizedModeEpoch` half alone is not enough — it lags
        // an accepted End Ride until its asynchronous cleanup actually runs, so an apply parked here
        // across an accepted End Ride *and* a further accepted Start Ride could still pass it. The
        // `rideEpoch` half catches exactly that: any accepted Start or End Ride since admission moves
        // `rideEpochs.current` synchronously, with no window. See `rideStillLive`.
        guard mayRepresent(ride, delivered: delivered) else { return .rejectedRide }
        let token = epoch.begin()
        currentEpochToken = token
        driftState = DriftController.reset()
        queueState = SharedQueue.select(state: queueState, queueItemId: queueItemId)
        timeline = PlaybackTimeline(
            trackHash: trackHash, queueItemId: queueItemId, anchorPositionMs: positionMs,
            anchorSessionUs: header.effectiveAtSessionUs, playing: playing, generation: token
        )
        // Independent review, Blocker 2B: ride-segment identity, survives a control-lifetime
        // boundary unlike `timeline` itself.
        currentPlaybackIdentity = PlaybackIdentity(trackHash: trackHash, queueItemId: queueItemId)
        // Independent-review round 4, Blocker 1: ride-scoped authority has just been established, so
        // the ride that owns it is recorded adjacent to the write, with no `await` between. This is
        // what lets an End Ride boundary tell "ride 2 has taken over" from "ride 2 has not started
        // playing anything yet" — round 3 could not, and cleared nothing in the second case.
        //
        // Independent-review round 6: stamped with the **admitted** ride epoch, not a fresh
        // `rideEpochs.current` read — see `recordRideAuthority`.
        recordRideAuthority(admittedRideEpoch: ride.rideEpoch)
        representDelivered(delivered, token: token)
        diagnostics.currentTrackHash = trackHash
        diagnostics.hardSeekCount = 0
        diagnostics.lastCorrection = .none
        // A new epoch retires a previous sync failure outright: fresh timeline, fresh drift state,
        // fresh seek budget. Nothing from the failed epoch is still in force.
        if diagnostics.syncState == .syncFailed { diagnostics.syncState = .scheduled }
        publishQueue()
        publishDiagnostics()
        // ARCHITECTURE §7.2's pre-roll, as three **single-effect** steps rather than one opaque
        // `prepare` (Amendment A4 Findings A and B). The materialisation, the decoder load and the
        // seek each suspend, and each is now preceded by its own ownership proof — so a pre-roll
        // whose session ends inside the load can no longer seek the session that replaced it.
        guard await runOwnedSteps(
            [.select(playable), .load(playable), .seek(positionMs)], generation: generation, token: token
        ) else {
            // `runOwnedSteps` refuses for one of three reasons and only it knows which, so ask the
            // two lifetimes directly, synchronously, right here. A ride that ended inside the
            // pre-roll is `.rejectedRide` for the same reason it is above. The residue — both
            // lifetimes live, the *playback epoch* superseded by a newer authoritative PLAY — is
            // reported `.rejectedStale`: it must not become `.applied`, and it is conservative
            // rather than novel (a `.rejectedStale` leaves the wire request outstanding, which
            // `StateResyncGate` already dedups and a fresh `.connected` already re-arms).
            //
            // Independent-review round 6: the ride check here is the same pair used above, for the
            // same reason — `synchronizedModeEpoch` alone lags an accepted-but-uncleaned End Ride.
            return stillCurrentNow(generation) && !rideStillLive(ride) ? .rejectedRide : .rejectedStale
        }
        guard await owns(generation: generation, token: token),
              ownsNow(generation: generation, token: token) else { return .rejectedStale }
        // A snapshot-restored track that the authority says is paused is loaded and left alone:
        // there is no instant to schedule, because nothing is about to become audible.
        guard playing else {
            markSynced()
            publishDiagnostics()
            return .applied
        }
        scheduleAt(header.effectiveAtSessionUs, estimate: estimate, generation: generation, token: token,
                   steps: [.start], reservation: reservation)
        return .applied
    }

    /// Amendment A3 Finding B: the ownership proof is the **first** statement, before
    /// `currentEpochToken` is read and before `timeline` is re-anchored — which is why this is now
    /// `async` where it used to be synchronous.
    ///
    /// It had no proof at all. `PAUSE`/`RESUME` legitimately attach to whatever playback epoch is
    /// current — that is why the token is read live rather than carried — so a retired command
    /// reading it read the *new* session's epoch and re-anchored the *new* session's timeline to an
    /// instant its own dead leader had chosen. Nothing later could recover from that: the scheduled
    /// action was correctly refused, but every drift measurement afterwards was taken against a
    /// timeline no leader had authorised. There is no `await` between the proof and the writes, so
    /// the proof still holds when they happen — on an actor that is the property that matters.
    private func applyTransport(
        _ header: PlaybackCommandHeader,
        generation: Int64,
        estimate: SessionClockEstimate,
        playing: Bool,
        positionMs: Int64,
        ride: RideAdmission,
        /// ADR-024 Amendment A11: the caller's obligation. See `applyAuthoritative`.
        reservation: WorkReservation,
        delivered: DeliveredAuthority? = nil
    ) async {
        guard await stillCurrent(generation) else { return }
        guard stillCurrentNow(generation) else { return } // Amendment A5
        // Independent-review round 4, §17: the proof above suspends, and End Ride does not move the
        // control generation. Synchronous, adjacent, and before the first write.
        //
        // Independent-review round 6: the `rideEpoch` half closes the window `synchronizedModeEpoch`
        // alone leaves open while an accepted End Ride's cleanup is still parked — see `rideStillLive`.
        guard mayRepresent(ride, delivered: delivered) else { return }
        let token = currentEpochToken
        timeline = timeline?.reanchored(positionMs: positionMs, sessionUs: header.effectiveAtSessionUs, playing: playing)
        representDelivered(delivered, token: token)
        // Amendment A4 Finding A: these are two effects, and they used to sit inside one closure
        // behind one ownership proof. `player.pause()` suspends — the hop to `MusicCoordinator` and
        // on to the player is two actor boundaries — so a Session-A `PAUSE` firing as the boundary
        // landed paused nothing it owned and then seeked **Session B's** player to Session A's
        // position. As a step list, the fence is re-proved between them.
        scheduleAt(
            header.effectiveAtSessionUs, estimate: estimate, generation: generation, token: token,
            steps: playing ? [.seek(positionMs), .start] : [.pause, .seek(positionMs)],
            reservation: reservation
        )
    }

    /// `applyTransport`'s proof, for the same reason and in the same position.
    private func applySeek(
        _ header: PlaybackCommandHeader,
        targetPositionMs: Int64,
        generation: Int64,
        estimate: SessionClockEstimate,
        ride: RideAdmission,
        /// ADR-024 Amendment A11: the caller's obligation. See `applyAuthoritative`.
        reservation: WorkReservation,
        delivered: DeliveredAuthority? = nil
    ) async {
        guard await stillCurrent(generation) else { return }
        guard stillCurrentNow(generation) else { return } // Amendment A5
        // round 4, §17; round 6 adds the second half — see `applyTransport`.
        guard mayRepresent(ride, delivered: delivered) else { return }
        let token = currentEpochToken
        timeline = timeline?.reanchored(positionMs: targetPositionMs, sessionUs: header.effectiveAtSessionUs)
        representDelivered(delivered, token: token)
        scheduleAt(
            header.effectiveAtSessionUs, estimate: estimate, generation: generation, token: token,
            steps: [.seek(targetPositionMs)],
            reservation: reservation
        )
    }

    /// PROTOCOL §5's `NEXT`/`PREVIOUS`, resolved against the **shared** queue (brief §25). Both peers
    /// hold identical `SharedQueueState` at the revision the command names, so both resolve the same
    /// item without either consulting its own local queue.
    private func applyStep(
        _ header: PlaybackCommandHeader,
        delta: Int,
        generation: Int64,
        estimate: SessionClockEstimate,
        ride: RideAdmission,
        /// ADR-024 Amendment A11: the caller's obligation. See `applyAuthoritative`.
        reservation: WorkReservation,
        delivered: DeliveredAuthority? = nil
    ) async {
        // Amendment A3 Finding B: the highest-risk path in the phase, and it had no proof at all.
        // Everything below reads or writes *live* state — the shared queue, the selection, the
        // playback epoch, the timeline — so the proof has to come before the first read, not before
        // the first player call. A retired `NEXT` used to step the new session's queue; a retired
        // `NEXT` that ran off the end of it took the `step.selected == nil` branch and called
        // `epoch.begin()`, which **retired the live session's playback epoch** and silently stopped
        // its scheduled start from ever firing.
        guard await stillCurrent(generation) else { return }
        guard stillCurrentNow(generation) else { return } // Amendment A5
        // **Independent-review round 4, §17: this path had no ride proof at all, and it is the one
        // that could stop the music.** `stillCurrent` suspends; End Ride does not move the control
        // generation; and the `selected == nil` branch below calls `epoch.begin()` — minting a
        // *fresh, live* playback epoch over the one `leaveSynchronizedMode` had just superseded — and
        // then schedules `[.stop, .clearSelection]`, which the new token makes owned, so it reaches
        // the player. End Ride's whole contract is that local playback continues (FR-025).
        //
        // Independent-review round 6: the `rideEpoch` half too — see `applyTransport`.
        guard mayRepresent(ride, delivered: delivered) else { return }
        let step = SharedQueue.step(state: queueState, delta: delta)
        queueState = step.state
        publishQueue()
        publishDiagnostics()
        guard let selected = step.selected else {
            let token = epoch.begin()
            currentEpochToken = token
            timeline = nil
            // Independent review, Blocker 2B: authoritative "nothing loaded", not merely a
            // control-lifetime reset — the identity genuinely has nothing to report now.
            currentPlaybackIdentity = nil
            // Blocker 1: "the leader authoritatively has nothing loaded" is established authority
            // too, and it is this ride's. An older ride's End Ride boundary must not reach past it.
            // Round 6: stamped with the admitted ride epoch, not a fresh live read.
            recordRideAuthority(admittedRideEpoch: ride.rideEpoch)
            representDelivered(delivered, token: token)
            // Amendment A4 Finding C: `stop` used to mean "stop the player **and** clear the local
            // queue", composed inside `MusicCoordinator` across the player's own suspension — so a
            // Session-A stop returning after Session B had materialised a track cleared Session B's
            // local queue and its Now Playing metadata with it. Two steps, two proofs.
            scheduleAt(
                header.effectiveAtSessionUs, estimate: estimate, generation: generation, token: token,
                steps: [.stop, .clearSelection],
                reservation: reservation
            )
            return
        }
        guard step.moved else {
            representDelivered(delivered, token: currentEpochToken)
            return
        }
        await applyPlay(header, trackHash: selected.trackHash, queueItemId: selected.queueItemId,
                        positionMs: 0, generation: generation, estimate: estimate, ride: ride,
                        reservation: reservation, delivered: delivered)
    }

    // MARK: - Scheduling

    /// PROTOCOL §5 rule 2, exactly: a deadline still ahead is waited for on this device's own
    /// monotonic clock; a deadline already past is applied **immediately** and its lateness counted.
    /// Never skipped, never scheduled backwards.
    /// **Amendment A4 Finding A: the action is a list of single-effect steps, not a closure.** A
    /// closure could hold a second `await`, and its type said nothing about that — which is how
    /// `applyTransport` came to drive two player effects behind one ownership proof. Every step in
    /// the list is fenced independently by `runOwnedSteps`.
    private func scheduleAt(
        _ effectiveAtSessionUs: Int64,
        estimate: SessionClockEstimate,
        generation: Int64,
        token: Int64,
        steps: [PlayerStep],
        /// ADR-024 Amendment A11: the obligation this armed action belongs to, reserved at the
        /// command's own admission. `enterPhase` runs here, **synchronously inside** the apply phase
        /// that is still held, so the obligation can never be released between the apply deciding to
        /// arm and the armed node existing. A false answer means a lifetime boundary has already
        /// released it, so there is nothing left to arm.
        reservation: WorkReservation
    ) {
        guard stillCurrentNow(generation) else { return }
        guard workLedger.enterPhase(reservation) else { return }
        let decision = ScheduledCommand.decide(
            effectiveAtSessionUs: effectiveAtSessionUs,
            nowLocalMonoUs: monotonicNowUs(),
            offsetToLeaderUs: estimate.offsetToLeaderUs
        )
        var deadlineUs: Int64?
        switch decision {
        case .applyImmediately(let latenessUs):
            diagnostics.lateCommandCount += 1
            diagnostics.lastScheduleErrorUs = latenessUs
        case .schedule(let atLocalMonoUs):
            if !outboundAuthorityLost { diagnostics.syncState = .scheduled }
            deadlineUs = atLocalMonoUs
        }
        publishDiagnostics()
        // Finding G: joined to the previous armed action, so the authoritative order the leader chose
        // is the order the player is actually driven in. Amendment A3 Finding C: tracked in
        // `sessionChainNodes`, so a boundary retires it rather than leaving it armed.
        let previous = scheduledChain
        let id = claimChainNodeId()
        let node = Task { [weak self] in
            await previous?.value
            await self?.runScheduledNode(
                id: id, generation: generation, token: token, deadlineUs: deadlineUs, steps: steps,
                reservation: reservation
            )
        }
        scheduledChain = node
        trackChainNode(id, node)
    }

    /// One scheduled-action node's body, once the node ahead of it has finished.
    ///
    /// **Amendment A3 Finding C: the ownership proof comes before the measurement.** It used to come
    /// after. The player action itself was correctly refused, but a Session-A deadline arriving after
    /// Session B authenticated still overwrote Session B's `lastScheduleErrorUs` — the FR-023 figure
    /// a rider reads as "this is how well the last synchronised command landed" — and published it.
    /// "Diagnostics only" is not an exemption: a superseded action has *zero* effects (A1 Finding F).
    ///
    /// The proof is taken again after the sleep, because the sleep is a suspension and on an actor
    /// every suspension is a re-entrancy point.
    private func runScheduledNode(
        id: Int64,
        generation: Int64,
        token: Int64,
        deadlineUs: Int64?,
        steps: [PlayerStep],
        reservation: WorkReservation
    ) async {
        defer { releaseChainNode(id) }
        // Amendment A11: the scheduled phase is over, so the obligation is discharged.
        defer { releaseWork(reservation) }
        guard !Task.isCancelled, await owns(generation: generation, token: token) else { return }
        guard ownsNow(generation: generation, token: token) else { return } // Amendment A5
        if let deadlineUs {
            await sleeper.sleep(untilLocalMonoUs: deadlineUs)
            guard !Task.isCancelled, await owns(generation: generation, token: token) else { return }
            // Amendment A5: no `await` between this proof and A3 Finding C's measurement below.
            guard ownsNow(generation: generation, token: token) else { return }
            recordScheduleError(deadlineUs: deadlineUs)
        }
        if await runOwnedSteps(steps, generation: generation, token: token) {
            await markSyncedAndPublish(generation: generation, token: token)
        }
    }

    /// The software scheduling error, measured rather than assumed. It says nothing about audible
    /// alignment: the decoder, the mixer and two Bluetooth hops all sit between this instant and a
    /// listener's ear (brief §23/§66).
    private func recordScheduleError(deadlineUs: Int64) {
        diagnostics.lastScheduleErrorUs = monotonicNowUs() - deadlineUs
        publishDiagnostics()
    }

    /// A scheduled authoritative command took effect, so this device is tracking the timeline. The
    /// ownership proof is taken **again** here, because `action` suspended (Finding F).
    private func markSyncedAndPublish(generation: Int64, token: Int64) async {
        guard await owns(generation: generation, token: token) else { return }
        guard ownsNow(generation: generation, token: token) else { return } // Amendment A5
        markSynced()
        publishDiagnostics()
    }

    /// It will **not** overwrite `.syncFailed`: ARCHITECTURE §7.3's fourth tier means correction has
    /// given up, and a subsequent `PAUSE` landing on time does not make that untrue. Only a new
    /// playback epoch clears it, which `applyPlay` does explicitly.
    ///
    /// Nor will it overwrite `.desynchronized`, for the stronger version of the same reason: a
    /// command landing on time says nothing about the authority we know we are missing.
    func markSynced() {
        if diagnostics.syncState == .syncFailed { return }
        // Amendment A2: a command landing on time says nothing about the authority we know did not
        // reach the peer, and this state is latched for the generation.
        if diagnostics.outboundAuthorityLost { return }
        // Keyed on the *latch* rather than on the displayed state: once reconciliation has cleared
        // it, a command landing on time is genuinely news again. Guarding on the displayed value
        // instead would leave `.desynchronized` on screen forever, because nothing else would ever
        // be allowed to replace it.
        if diagnostics.ingressDesynchronized { return }
        diagnostics.syncState = .synced
    }

    // MARK: - Position reporting and drift

    func tickLoop(generation: Int64) async {
        while !Task.isCancelled {
            await sleeper.sleep(untilLocalMonoUs: monotonicNowUs() + Self.positionReportIntervalUs)
            if Task.isCancelled { return }
            guard await stillCurrent(generation) else { return }
            await tickOnce(generation: generation)
        }
    }

    /// One PROTOCOL §5 cadence tick: report our own position, then correct **our own** drift against
    /// the authoritative timeline.
    ///
    /// Brief §33: drift is `actual local position - expected position at the current session time`,
    /// never one phone's reported position minus the other's — those two numbers are sampled at
    /// different session instants and separated by a network delay, so their difference is not a
    /// drift. The peer's report produces the *observed peer drift* against the same timeline, which
    /// is FR-023 diagnostics, not a correction input.
    func tickOnce(generation: Int64) async {
        // A held command whose clock has recovered is applied before anything is measured against a
        // timeline it may be about to replace.
        await drainDeferredEvents()
        // Amendment A4 Finding E: that drain suspends — it resolves content and applies commands —
        // so everything below it reads *live* state. Without this proof a tick belonging to a
        // retired session read the new session's timeline and epoch and, though the frame it
        // enqueued was correctly refused at the wire, still moved the new session's outbound
        // counters. A3 §D already settled that "diagnostics only" is not an exemption.
        guard await stillCurrent(generation) else { return }
        // Amendment A5: the proof above suspends, and `timeline` and `currentEpochToken` below have
        // to be read as one — they are what every measurement in this tick is taken against.
        guard stillCurrentNow(generation) else { return }
        guard let active = timeline else { return }
        let token = currentEpochToken
        let estimate = await estimate()
        // Amendment A5 Finding B, first window: `estimate()` suspends and the "not ready" branch
        // below publishes. A retired tick announced `clockUnready` on the session that replaced it.
        guard await owns(generation: generation, token: token) else { return }
        guard ownsNow(generation: generation, token: token) else { return }
        guard let estimate, estimate.ready else {
            // brief §41: a dubious clock stops correction, and local playback simply continues.
            if !diagnostics.ingressDesynchronized { diagnostics.syncState = .clockUnready }
            diagnostics.clockReady = false
            publishDiagnostics()
            return
        }
        let nowSessionUs = estimate.sessionUs(localMonoUs: monotonicNowUs())
        let state = await player.playerState()
        // Amendment A5 Finding B, second window: the enqueue below used to happen *before* any
        // post-suspension proof — the `owns` check sat after it. The frame was correctly refused at
        // the wire, because it carries Session A's generation, but enqueueing and counting it moved
        // three of Session B's outbound counters, and A3 §D settled that "it never reached the peer"
        // is not an exemption. No `await` from here to the enqueue.
        guard await owns(generation: generation, token: token) else { return }
        guard ownsNow(generation: generation, token: token) else { return }
        let durationMs: Int64? = state.durationMs > 0 ? state.durationMs : nil
        // ADVISORY (Amendment A2): one diagnostics number on the peer's screen. A failed report is
        // one missing sample, superseded by the next tick 5 s later — never a divergence.
        enqueueOutbound(Phase5Outbound(
            generation: generation,
            authority: .advisory,
            frame: .playback(.positionReport(
                trackHash: active.trackHash,
                positionMs: max(state.positionMs, 0),
                atSessionUs: nowSessionUs,
                playing: state.playing,
                playbackRate: state.rate
            ))
        ))
        let transitioning = await routeState.isRouteTransitioning()
        // Amendment A5 Finding B, third window: `driftState` and six diagnostics fields follow, and
        // ADR-004's ladder is evaluated from values this tick sampled — so a retired tick resuming
        // here restated the live session's drift, clock offset, RTT and correction from a dead
        // session's measurements. No `await` from here to those writes.
        guard await owns(generation: generation, token: token) else { return }
        guard ownsNow(generation: generation, token: token) else { return }
        let drift = active.driftMs(actualPositionMs: state.positionMs, atSessionUs: nowSessionUs, durationMs: durationMs)
        let outcome = DriftController.evaluate(
            state: driftState,
            input: DriftInput(
                driftMs: drift,
                nowSessionUs: nowSessionUs,
                expectedPositionMs: active.expectedPositionMs(atSessionUs: nowSessionUs, durationMs: durationMs),
                playing: active.playing && state.playing,
                routeTransitioning: transitioning
            )
        )
        driftState = outcome.state
        diagnostics.clockReady = true
        diagnostics.clockOffsetUs = estimate.offsetToLeaderUs
        diagnostics.rttP95Us = estimate.rttP95Us
        diagnostics.leadUs = estimate.leadUs
        diagnostics.localDriftMs = drift
        diagnostics.routeTransitioning = transitioning
        publishDiagnostics()
        await applyCorrection(outcome.action, generation: generation, token: token)
        // Amendment A4 Finding F: `applyCorrection` suspends inside the player, so this counter had
        // the same shape as A3 Finding C's schedule-error write — a tick belonging to a retired
        // session incremented the *live* session's `correctionTickCount` when its parked rate call
        // finally returned. A superseded operation has zero effects, diagnostics included.
        guard await owns(generation: generation, token: token) else { return }
        guard ownsNow(generation: generation, token: token) else { return } // Amendment A5
        // Last, so the counter means "this tick finished" rather than "this tick began".
        diagnostics.correctionTickCount += 1
        publishDiagnostics()
    }

    /// ADR-004's ladder, applied to **this** device.
    ///
    /// Amendment A1 Finding F: every visible consequence of a correction — the player call, the
    /// diagnostics, the hard-seek budget, the sync-failed state and the outbound `PLAYBACK_STATE` —
    /// is now behind the *same* ownership proof, and the proof is taken again after the player's own
    /// suspension. A correction belonging to a superseded epoch or a dead session has **zero** side
    /// effects, which is a stronger statement than "it does not touch the player".
    private func applyCorrection(_ action: DriftAction, generation: Int64, token: Int64) async {
        switch action {
        case .none:
            return
        case .nudge(let rate):
            guard await runOwnedSteps([.setRate(rate)], generation: generation, token: token) else { return }
            guard await owns(generation: generation, token: token) else { return }
            guard ownsNow(generation: generation, token: token) else { return } // Amendment A5
            diagnostics.lastCorrection = .nudge
            diagnostics.playbackRate = rate
        case .restoreRate:
            guard await runOwnedSteps(
                [.setRate(DriftController.rateNormal)], generation: generation, token: token
            ) else { return }
            guard await owns(generation: generation, token: token) else { return }
            guard ownsNow(generation: generation, token: token) else { return } // Amendment A5
            diagnostics.lastCorrection = .restoreRate
            diagnostics.playbackRate = DriftController.rateNormal
        case .hardSeek(let positionMs):
            guard await runOwnedSteps([.seek(positionMs)], generation: generation, token: token) else { return }
            guard await owns(generation: generation, token: token) else { return }
            guard ownsNow(generation: generation, token: token) else { return } // Amendment A5
            diagnostics.lastCorrection = .hardSeek
            diagnostics.hardSeekCount += 1
            // Amendment A2 Finding E: the correction's *own* generation and epoch, carried to the
            // enqueue rather than replaced there by whatever is live by then.
            await emitPlaybackStateIfOwned(generation: generation, token: token)
        case .declareSyncFailure:
            // ARCHITECTURE §7.3 tier four and FR-025: stop correcting, restore exactly 1.0, surface
            // it — and leave local music playing.
            guard await runOwnedSteps(
                [.setRate(DriftController.rateNormal)], generation: generation, token: token
            ) else { return }
            guard await owns(generation: generation, token: token) else { return }
            guard ownsNow(generation: generation, token: token) else { return } // Amendment A5
            diagnostics.playbackRate = DriftController.rateNormal
            diagnostics.lastCorrection = .syncFailed
            diagnostics.syncState = .syncFailed
            await emitPlaybackStateIfOwned(generation: generation, token: token)
        }
        publishDiagnostics()
    }

    /// PROTOCOL §5's authoritative snapshot, emitted **because the leader chose to re-state its
    /// current state now** — the reconciliation re-broadcast, and nothing else.
    ///
    /// Amendment A2 Finding E made this a separate function from `emitPlaybackStateIfOwned`. Reading
    /// the live generation is correct *here*, because "now" is what this call means; it is exactly
    /// wrong for a snapshot that exists as a consequence of some earlier operation, and one function
    /// cannot honestly serve both.
    func emitCurrentPlaybackState() async {
        guard role == .leader else { return }
        let generation = await session.currentAuthGeneration()
        await emitPlaybackStateFrame(generation: generation, token: nil)
    }

    /// PROTOCOL §5's authoritative snapshot, emitted **as a consequence of a correction**, and
    /// therefore carrying that correction's own authorisation all the way to the enqueue
    /// (Amendment A2 Finding E).
    ///
    /// The old shape took no arguments and read `session.currentAuthGeneration()` *inside itself*. A
    /// correction that had legitimately proved `owns(generation: A, token: A)` before calling it
    /// could therefore have that proof replaced, one `await` later, by whatever generation happened
    /// to be live — so a snapshot caused by a correction in Session A could be enqueued into
    /// Session B. On an actor that window is not theoretical: every `await` in the chain is a
    /// re-entrancy point. It is the same "authorising generation versus live generation" distinction
    /// ADR-023 Amendments A3/A5 drew in Phase 4, and A1 Finding F's "a superseded correction has zero
    /// effects" was one read of the live generation short of being true.
    ///
    /// The proof is taken again *immediately before* the enqueue, with no `await` between, so none
    /// of the reads above it is a hole in it.
    func emitPlaybackStateIfOwned(generation: Int64, token: Int64) async {
        guard role == .leader else { return }
        await emitPlaybackStateFrame(generation: generation, token: token)
    }

    /// The two emits' shared body.
    ///
    /// **ADR-024 Amendment A5** replaced the `stillOwned` closure this took with `token`, for a
    /// reason the closure could not express: the proof it held was `async`, so taking it was itself a
    /// re-entrancy point and "no `await` from here to the enqueue" was true of the statements and
    /// false of the guard. A token — present for a correction's snapshot, absent for the leader's
    /// "state as of now" re-broadcast — lets both halves of the proof be taken here, adjacently.
    private func emitPlaybackStateFrame(generation: Int64, token: Int64?) async {
        guard !outboundAuthorityLost, let estimate = await estimate() else { return }
        let state = await player.playerState()
        if let token {
            guard await owns(generation: generation, token: token) else { return }
            guard ownsNow(generation: generation, token: token) else { return }
        } else {
            guard await stillCurrent(generation) else { return }
            guard stillCurrentNow(generation) else { return }
        }
        // No `await` from here to the enqueue: the actor makes the proof and the hand-off atomic.
        // ADVISORY (Amendment A2): PROTOCOL §5 calls this "the full authoritative snapshot … the
        // reconciliation anchor, not an incremental update", so the next one subsumes it and a
        // failed send costs nothing that cannot be re-stated. It carries no new `command_seq`.
        enqueueOutbound(Phase5Outbound(
            generation: generation,
            authority: .advisory,
            frame: .playback(.playbackState(
                commandSeq: lastAppliedSeq ?? 0,
                queueRevision: queueState.revision,
                trackHash: timeline?.trackHash,
                queueItemId: timeline?.queueItemId,
                positionMs: max(state.positionMs, 0),
                playing: state.playing,
                atSessionUs: estimate.sessionUs(localMonoUs: monotonicNowUs())
            ))
        ))
    }

    /// The peer's `POSITION_REPORT`. Bound to the current playback epoch by **both** its `track_hash`
    /// and its `at_session_us`: a report from a previous play of the *same* track carries a session
    /// instant before this epoch's anchor, which is what makes `content_hash` alone insufficient
    /// (brief §32) without adding a generation field to the wire.
    ///
    /// It is never a command and can never outrank one — the only thing it produces is a number on
    /// the diagnostics screen. **ADR-024 Amendment A5 Finding C: that is not the same as producing
    /// nothing.** This carried no `generation` at all, so after `player.playerState()` suspended
    /// there was nothing it *could* prove — and a report admitted under Session A wrote FR-023's
    /// observed-peer-drift figure, computed against Session A's anchor for a track Session B is not
    /// playing, onto Session B's diagnostics screen.
    ///
    /// The epoch the report was admitted against is **retained**, not re-read. `active` and
    /// `currentEpochToken` are captured together before the suspension, because the report's meaning
    /// is "the peer's position minus what *this* timeline expects at that instant" — the timeline its
    /// `track_hash` and `at_session_us` were just matched against. Re-reading would silently answer a
    /// different question about a different track; proving the captured epoch is still current, and
    /// otherwise producing nothing, is the honest answer.
    private func onPeerPositionReport(
        trackHash: ContentHash,
        positionMs: Int64,
        atSessionUs: Int64,
        generation: Int64
    ) async {
        guard let active = timeline, trackHash == active.trackHash, atSessionUs >= active.anchorSessionUs else { return }
        let token = currentEpochToken
        let state = await player.playerState()
        guard await owns(generation: generation, token: token) else { return }
        // No `await` from here to the write.
        guard ownsNow(generation: generation, token: token) else { return }
        let durationMs: Int64? = state.durationMs > 0 ? state.durationMs : nil
        let expected = active.expectedPositionMs(atSessionUs: atSessionUs, durationMs: durationMs)
        diagnostics.peerDriftMs = positionMs - expected
        publishDiagnostics()
    }

    /// PROTOCOL §5's reconciliation anchor. A follower adopts the leader's `command_seq` so ordering
    /// continues from the authoritative value, and re-anchors its timeline when the snapshot
    /// describes the track it is already playing.
    ///
    /// **In normal operation it deliberately starts nothing**, exactly as PROTOCOL §5 says: a change
    /// of track is a `PLAY`, which the leader sends separately.
    ///
    /// **While this device is desynchronised it does** (ADR-024 Amendment A1 Finding C). That is the
    /// one narrow behavioural addition the amendment makes to an existing message: after an ingress
    /// overflow, "re-anchor only" would leave the follower coherent about ordering and wrong about
    /// what is playing, so the snapshot — which PROTOCOL §5 already calls "the full authoritative
    /// snapshot … the reconciliation anchor" — is treated as one. No wire field changed; the
    /// snapshot already carries every value needed, and the deadline it names is in the past, so §5
    /// rule 2's "apply immediately and count the lateness" is what happens rather than any reuse of
    /// an expired instant as if it were still ahead.
    @discardableResult
    private func onPeerPlaybackState(
        _ fields: PlaybackStateSnapshotFields,
        generation: Int64,
        reconciliation: Int64? = nil,
        /// Independent-review round 7: the ride that admitted this reconciliation, captured by the
        /// caller before **its** first suspension. `onStateSnapshot` awaits the queue half before
        /// reaching here, so capturing inside this function would be a re-derivation.
        ride: RideAdmission
    ) async -> StateSnapshotOutcome {
        guard role == .follower else { return .rejectedRole }
        // Round 7: proved before the hold gate, so a snapshot whose ride ended inside the queue half's
        // own suspension gets its terminal answer immediately rather than being retained, drained and
        // refused a cadence interval later. The drain's identical check is what covers a ride that
        // ends *after* this point.
        guard rideStillLive(ride) else { return .rejectedRide }
        // Amendment A2 Finding D: the reconciliation anchor is authoritative state, so it waits its
        // turn behind held commands exactly as a queue snapshot does. Its supersede rule below then
        // runs against whatever is *still* held at that point, which is the honest reading of "the
        // authoritative state is strictly newer than the command that produced it".
        if holdIfOvertaking(
            .playbackState(fields, generation: generation, reconciliation: reconciliation, ride: ride)
        ) { return .deferredClock }
        return await applyPeerPlaybackState(
            fields, generation: generation, reconciliation: reconciliation, ride: ride
        )
    }

    /// `onPeerPlaybackState` with the hold gate already answered — the drain's entry point too.
    ///
    /// **Independent-review round 7: `ride` is required and is never captured here.** This function is
    /// entered both fresh from the wire and from `drainDeferredEvents`, and round 6's capture-at-entry
    /// was therefore a *replacement* provenance for every replayed snapshot: S1, admitted under ride 1
    /// and held for the clock or for a transfer, was re-admitted under whichever ride was current when
    /// its precondition finally resolved.
    @discardableResult
    func applyPeerPlaybackState(
        _ fields: PlaybackStateSnapshotFields,
        generation: Int64,
        reconciliation: Int64? = nil,
        ride: RideAdmission
    ) async -> StateSnapshotOutcome {
        // Amendment A3 Finding B: everything below writes live state — the received/applied sequence
        // numbers, the held stream, the timeline — and this is reached through the hold gate or the
        // deferred drain, both of which suspend before getting here.
        guard await stillCurrent(generation) else { return .rejectedStale }
        // Amendment A5: the two sequence numbers, the held stream and the timeline all follow.
        guard stillCurrentNow(generation) else { return .rejectedStale }
        // Independent-review round 4, §17: and the ride that authorised this reconciliation, which
        // End Ride ends without moving the control generation. Stated **once**, here, and reported as
        // its own outcome — the snapshot did arrive for the live generation, so this is not
        // `.rejectedStale`, and the outer owner needs to tell the two apart.
        //
        // Round 6: the second half of the pair — see `rideStillLive`.
        guard rideStillLive(ride) else { return .rejectedRide }
        let commandSeq = fields.commandSeq
        if lastReceivedSeq == nil || commandSeq > (lastReceivedSeq ?? 0) {
            lastReceivedSeq = commandSeq
            lastAppliedSeq = commandSeq
        }
        // Anything held for the clock that the snapshot already accounts for is superseded by it —
        // the authoritative state is strictly newer than the command that produced it.
        //
        // ADR-024 Amendment A13: this is the authoritative-state route by which an **accepted**
        // command may end without being applied — decided by `command_seq`, never by a ride — and it
        // is counted, not silent.
        let heldBefore = deferredEvents.count
        deferredEvents.removeAll { held in
            guard let seq = held.acceptedCommandSeq else { return false }
            return seq <= commandSeq
        }
        diagnostics.supersededHeldCommandCount += heldBefore - deferredEvents.count
        diagnostics.lastAppliedCommandSeq = lastAppliedSeq
        diagnostics.lastReceivedCommandSeq = lastReceivedSeq
        diagnostics.deferredCommandCount = deferredEvents.count
        publishDiagnostics()
        // Independent review, Blocker 2C: restore whenever there is no live anchor to incrementally
        // update, not only when the ingress-overflow-specific `playbackDesynchronized` flag happens
        // to be set. `resetForNewSession` clears `timeline` on every ordinary reconnect too, so
        // routing on `wasDesynchronized` alone silently skipped restoration for that case — the
        // follower updated its sequence bookkeeping above and then fell into the "already synced,
        // just re-anchor" branch below, which requires a `timeline` that does not exist yet.
        let needsFullRestore = playbackDesynchronized || timeline == nil
        if needsFullRestore {
            // Independent review, Race 7 (mirroring an Android/iOS parity gap found while building
            // it): when the snapshot names a track, both preconditions a full restore needs — clock
            // readiness *and* content availability — are checked **before** ever reaching `applyPlay`,
            // exactly as the clock-only check already was. Before this, a still-missing transfer fell
            // through to `applyPlay`'s own unheld check, which requests the transfer and returns
            // `false` with **nothing retained** — so content arriving later had no snapshot left to
            // apply it to, and the follower stayed on `.waitingForContent` until the leader's next
            // *unrelated* authoritative `PLAY` happened to arrive. Holding it here, through the same
            // `deferredEvents`/drain machinery the clock case already uses, means
            // `content.observeAvailability`'s prompt trigger (below, in `start()`) — not just the
            // periodic retry cadence — can complete this restoration the moment the transfer verifies.
            //
            // A `nil` `trackHash` ("nothing loaded") needs neither: there is nothing to schedule and
            // nothing to resolve, so gating it on clock readiness would defer a restoration that has
            // no dependency on the clock at all — `restoreFromPlaybackState`'s own nil-track branch
            // already applies it unconditionally, and this pre-check must not disturb that.
            if let trackHash = fields.trackHash {
                let clockEstimate = await estimate()
                let clockReady = clockEstimate?.ready == true
                let contentReady = await content.resolve(trackHash) != nil
                // Amendment A3/A5: both reads above suspend, and the append below mutates live state.
                guard await stillCurrent(generation) else { return .rejectedStale }
                guard stillCurrentNow(generation) else { return .rejectedStale }
                if !clockReady || !contentReady {
                    // **Independent-review round 7, Bug C — the append-time race.** `estimate()` and
                    // `content.resolve` above both suspend, and until round 7 only the *control
                    // generation* was re-proved here. A ride boundary accepted inside those
                    // suspensions therefore led straight to a retention carrying no ride provenance at
                    // all, which the drain later handed whatever ride was current by then.
                    //
                    // Stated **before** the transfer request as well as before the append: a ride that
                    // is over asks Phase 4 for nothing and retains nothing.
                    guard rideStillLive(ride) else { return .rejectedRide }
                    if !contentReady {
                        await content.requestTransfer(trackHash)
                        // `requestTransfer` suspends too, and the append below is a live-state
                        // mutation — so both lifetimes are re-proved with no `await` before it.
                        guard await stillCurrent(generation) else { return .rejectedStale }
                        guard stillCurrentNow(generation) else { return .rejectedStale }
                        guard rideStillLive(ride) else { return .rejectedRide }
                    }
                    deferredEvents.append(
                        .playbackState(fields, generation: generation, reconciliation: reconciliation, ride: ride)
                    )
                    diagnostics.deferredCommandCount = deferredEvents.count
                    publishDiagnostics()
                    startDeferredDrain(generation: generation)
                    return clockReady ? .deferredContent : .deferredClock
                }
            }
            // Blocker 2D: the obligation is cleared only once restoration genuinely completes, not
            // merely attempted — `restoreFromPlaybackState` itself decides `.applied` vs
            // `.deferredClock`/`.deferredContent`, and only `.applied` may clear it.
            let outcome = await restoreFromPlaybackState(
                fields, generation: generation, reconciliation: reconciliation, ride: ride
            )
            if outcome == .applied {
                playbackDesynchronized = false
                publishDesynchronized()
                representAuthoritativeSequence(commandSeq)
            }
            return outcome
        }
        guard let active = timeline, fields.trackHash == active.trackHash else { return .applied }
        timeline = active.reanchored(positionMs: fields.positionMs, sessionUs: fields.atSessionUs, playing: fields.playing)
        driftState = DriftController.reset()
        representAuthoritativeSequence(commandSeq)
        return .applied
    }

    /// The playback half of Amendment A1's reconciliation. See `onPeerPlaybackState`.
    private func restoreFromPlaybackState(
        _ fields: PlaybackStateSnapshotFields,
        generation: Int64,
        reconciliation: Int64? = nil,
        ride: RideAdmission
    ) async -> StateSnapshotOutcome {
        // Amendment A3 Finding B: the nil-track branch below supersedes the playback epoch and clears
        // the timeline, so it needs the same pre-mutation proof `applyStep` needs.
        guard await stillCurrent(generation) else { return .rejectedStale }
        // Amendment A5: `epoch.supersede()` in the branch below retires the live playback epoch.
        guard stillCurrentNow(generation) else { return .rejectedStale }
        // Independent-review round 4, §17: and the ride, for the same reason `applyPeerPlaybackState`
        // states it — this is reached from the drain as well, whose own entry proof is older.
        //
        // Round 6: the second half — see `rideStillLive`.
        guard rideStillLive(ride) else { return .rejectedRide }
        guard let trackHash = fields.trackHash, let queueItemId = fields.queueItemId else {
            // "Nothing is loaded" is a representable authoritative state (ADR-024 §4). Every
            // scheduled effect from the epoch we lost track of is superseded, and nothing replaces it.
            epoch.supersede()
            timeline = nil
            // Independent review, Blocker 2B/2D: the *leader's own* authoritative say-so that
            // nothing is loaded — genuinely different from this device merely having lost track of
            // its own identity across a reconnect (which is what the caller's routing fix now keeps
            // separate; see `applyPeerPlaybackState`).
            currentPlaybackIdentity = nil
            // Blocker 1, as in `applyStep`'s equivalent branch: adopting the leader's "nothing
            // loaded" is this ride exercising authority, not an absence of it. Round 6: stamped with
            // the admitted ride epoch, not a fresh live read.
            recordRideAuthority(admittedRideEpoch: ride.rideEpoch)
            diagnostics.currentTrackHash = nil
            markSynced()
            publishDiagnostics()
            return .applied
        }
        guard let estimate = await readyEstimate(generation: generation) else {
            // Independent review, Blocker 2A/2D: retained rather than silently dropped.
            // `readyEstimate` already re-proved `stillCurrent`/`stillCurrentNow` before returning nil
            // and already published `.clockUnready`/`clockReady = false`, so this generation is still
            // the live one at this exact point — safe to enqueue under it. `drainDeferredEvents`'
            // periodic retry (`startDeferredDrain`) picks this back up once the estimator recovers;
            // `resetForNewSession` (a boundary landing before then) clears `deferredEvents` outright,
            // so a retired generation's retained snapshot can never apply to a successor.
            //
            // **Round 7: `readyEstimate` proves the generation and not the ride**, and it suspends —
            // so the ride is proved here, adjacent to the append, and stored with the retained work.
            guard rideStillLive(ride) else { return .rejectedRide }
            deferredEvents.append(
                .playbackState(fields, generation: generation, reconciliation: reconciliation, ride: ride)
            )
            diagnostics.deferredCommandCount = deferredEvents.count
            publishDiagnostics()
            startDeferredDrain(generation: generation)
            return .deferredClock
        }
        // **ADR-024 Amendment A11.** This restore's `applyPlay` can arm a scheduled start, which is
        // retained local work, so it needs an obligation of its own — the command paths' obligations
        // belong to their own commands. Taken here, immediately before the only call that can create
        // that work and after both lifetimes have been proved, with no `await` between. When there
        // is no capacity the snapshot is **retained**, not dropped: the drain re-attempts it and its
        // reconciliation obligation stays live and owned.
        guard let reservation = reserveWork(generation: generation) else {
            guard stillCurrentNow(generation) else { return .rejectedStale }
            guard rideStillLive(ride) else { return .rejectedRide }
            deferredEvents.append(
                .playbackState(fields, generation: generation, reconciliation: reconciliation, ride: ride)
            )
            diagnostics.deferredCommandCount = deferredEvents.count
            publishDiagnostics()
            startDeferredDrain(generation: generation)
            return .deferredCapacity
        }
        // The apply phase's obligation, released exactly once on every path out of this function —
        // including a cancellation, which is what stops a boundary-cancelled drain leaking capacity
        // past its own `retire`. A scheduled start `applyPlay` arms joins this same obligation
        // synchronously inside that call, so it keeps a phase of its own after this one leaves.
        defer { releaseWork(reservation) }
        let header = PlaybackCommandHeader(
            commandSeq: fields.commandSeq, effectiveAtSessionUs: fields.atSessionUs, issuedBy: localPeerId,
            queueRevision: fields.queueRevision
        )
        // Independent-review round 5, Blocker 2B. This used to be `started ? .applied :
        // .deferredContent` — one bit, three meanings. `applyPlay` returns `false` when content is
        // unavailable (genuinely deferrable), when the control generation has retired, when the
        // playback epoch has been superseded, and — the reachable case this closes — when the
        // **ride** that authorised the reconciliation ended inside `content.resolve`. Reporting the
        // last three as `.deferredContent` told `ResyncCoordinator` that retained work existed and
        // would report later, when nothing was retained at all: the obligation stayed outstanding
        // for the rest of the session, and ride 1's `manifest_revision`/`command_seq` were published
        // as accepted bookkeeping on the way past. `applyPlay` now says which, and this forwards it.
        let outcome = await applyPlay(header, trackHash: trackHash, queueItemId: queueItemId, positionMs: fields.positionMs,
                                      generation: generation, estimate: estimate, playing: fields.playing, ride: ride,
                                      reservation: reservation)
        guard outcome == .deferredContent else { return outcome }
        // A `.deferred*` result promises the obligation is **retained** and carries the same
        // reconciliation id, so retain it here rather than letting `applyPlay`'s own PROTOCOL §5
        // rule 4 transfer request stand alone. In practice this is defence in depth —
        // `applyPeerPlaybackState` already proved content available before reaching here and holds
        // it there when it is not — so this covers only content disappearing inside the narrow
        // window between those two resolves.
        //
        // Both lifetimes are re-proved before the append, in ADR-024 Amendment A5's exact pattern —
        // `await stillCurrent` (which asks the session actor), then the synchronous `stillCurrentNow`
        // mirror, then the ride, then the mutation with no `await` anywhere between the last proof
        // and the write. `applyPlay` returns from `.deferredContent` immediately after
        // `content.requestTransfer`, which suspends and carries no proof of its own, so this is the
        // first proof on that path and it may not be the weaker half of the pair.
        guard await stillCurrent(generation) else { return .rejectedStale }
        guard stillCurrentNow(generation) else { return .rejectedStale }
        guard rideStillLive(ride) else { return .rejectedRide }
        deferredEvents.append(
            .playbackState(fields, generation: generation, reconciliation: reconciliation, ride: ride)
        )
        diagnostics.deferredCommandCount = deferredEvents.count
        publishDiagnostics()
        startDeferredDrain(generation: generation)
        return .deferredContent
    }

    /// Authoritative state at `commandSeq` is now **represented** here, so applied truth is at least
    /// that (ADR-024 Amendment A13, found by its Regression F).
    ///
    /// Adoption above advances both floors only when the snapshot is newer than `lastReceivedSeq`.
    /// When this device had already *accepted* the command the snapshot accounts for — a held C1 the
    /// supersession rule has just removed, or one the desynchronisation latch refused — the snapshot's
    /// `command_seq` equals `lastReceivedSeq`, adoption moved nothing, and once the restoration or
    /// re-anchor represented that state `lastAppliedSeq` still said the command had never applied.
    /// Called only where the state is actually represented, and monotone, so it can neither publish
    /// unrepresented work nor roll applied truth back.
    func representAuthoritativeSequence(_ commandSeq: Int64) {
        guard lastAppliedSeq.map({ commandSeq > $0 }) ?? true else { return }
        lastAppliedSeq = commandSeq
        diagnostics.lastAppliedCommandSeq = commandSeq
        publishDiagnostics()
    }

    static let positionReportIntervalUs: Int64 = PlaybackBounds.positionReportIntervalMs * 1_000
}

/// One inbound Phase 5 frame, already parsed, carrying the authentication generation that was live
/// when the read loop produced it.
enum Phase5Inbound: Sendable {
    case playback(PlaybackMessage, generation: Int64)
    case queue(QueueMessage, generation: Int64)

    /// The authentication generation live when the read loop produced this frame — and therefore
    /// the generation that owns any ingress loss this frame causes (Amendment A6).
    var generation: Int64 {
        switch self {
        case .playback(_, let generation), .queue(_, let generation): return generation
        }
    }

    /// Whether a strictly newer frame of the same kind can replace this one without losing anything
    /// (ADR-024 Amendment A1 Finding C). A command never can.
    var kind: Phase5FrameKind {
        switch self {
        case .playback(let message, _):
            switch message {
            case .positionReport, .playbackState: return .latestWins
            default: return .command
            }
        case .queue(let message, _):
            if case .snapshot = message { return .latestWins }
            return .command
        }
    }

    /// The latest-wins family this frame belongs to, or nil when it is an authoritative command. Two
    /// frames coalesce only when their keys match, so a `POSITION_REPORT` never supersedes a
    /// `PLAYBACK_STATE`.
    var coalesceKey: String? {
        switch self {
        case .playback(let message, _):
            switch message {
            case .positionReport: return "POSITION_REPORT"
            case .playbackState: return "PLAYBACK_STATE"
            default: return nil
            }
        case .queue(let message, _):
            if case .snapshot = message { return "QUEUE_SNAPSHOT" }
            return nil
        }
    }
}

/// Forwards a `PLAY`/`PAUSE`/… frame into the coordinator's **ordered, lossless** inbound queue.
///
/// **Why a queue and not a `Task` per frame.** The previous shape — `Task { await
/// coordinator.onPlaybackMessage(…) }` — preserved only the order in which tasks were *created*;
/// Swift makes no guarantee that independently created tasks run in creation order. A stress run of
/// this phase's suites caught it: two frames delivered back to back were processed out of order
/// roughly 8 % of the time. On the wire that is a real defect, not a test artefact — a follower
/// processing `PAUSE(seq 6)` before `PLAY(seq 5)` would drop the `PLAY` as stale
/// (`CommandOrderGate` doing exactly its job) and pause a track it never loaded.
///
/// **Why `Phase5FrameQueue` and not `OrderedEventChannel(bufferingNewest:)`.** ADR-024 Amendment A1
/// Finding C: that stream evicted the *oldest* element when full, which is a lossy queue sitting
/// immediately behind reliable ordered TCP — and this forwarder discarded the `.dropped` result that
/// would at least have reported it. Nothing is evicted now.
///
/// `offer` is synchronous, so the read loop is never blocked and the frame's arrival order is the
/// queue's order.
struct PlaybackForwarder: PlaybackSink {
    let inbound: Phase5FrameQueue<Phase5Inbound>

    func submit(_ message: PlaybackMessage, generation: Int64) {
        inbound.offer(.playback(message, generation: generation))
    }
}

struct QueueForwarder: QueueSink {
    let inbound: Phase5FrameQueue<Phase5Inbound>

    func submit(_ message: QueueMessage, generation: Int64) {
        inbound.offer(.queue(message, generation: generation))
    }
}

extension PlaybackTimeline {
    /// A new anchor for the same track — what a `PAUSE`, `RESUME`, `SEEK` or a leader's
    /// `PLAYBACK_STATE` produces. The epoch (`generation`) deliberately does not move: the same
    /// track is still playing, so a scheduled `PAUSE` must not supersede the `PLAY` that armed it.
    func reanchored(positionMs: Int64, sessionUs: Int64, playing: Bool? = nil) -> PlaybackTimeline {
        PlaybackTimeline(
            trackHash: trackHash,
            queueItemId: queueItemId,
            anchorPositionMs: positionMs,
            anchorSessionUs: sessionUs,
            playing: playing ?? self.playing,
            generation: generation
        )
    }
}
