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
            // No `await` from here to the enqueue (Amendment A1 Finding B).
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
            await issue { header in .play(header: header, trackHash: hash, positionMs: position, queueItemId: itemId) }
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
        switch message {
        case .positionReport(let trackHash, let positionMs, let atSessionUs, _, _):
            await onPeerPositionReport(trackHash: trackHash, positionMs: positionMs, atSessionUs: atSessionUs)
        case .playbackState(let commandSeq, let queueRevision, let trackHash, let queueItemId, let positionMs, let playing, let atSessionUs):
            await onPeerPlaybackState(
                PlaybackStateSnapshotFields(
                    commandSeq: commandSeq, queueRevision: queueRevision, trackHash: trackHash,
                    queueItemId: queueItemId, positionMs: positionMs, playing: playing, atSessionUs: atSessionUs
                ),
                generation: generation
            )
        default:
            await onInboundCommand(message, generation: generation)
        }
    }

    func onQueueMessage(_ message: QueueMessage, generation: Int64) async {
        guard await stillCurrent(generation) else { return }
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
    private func adoptSnapshot(revision: Int64, items: [SharedQueueItem], currentIndex: Int?, generation: Int64) async {
        guard role == .follower else { return }
        // Amendment A2 Finding D: a snapshot must not overtake a command already held for the clock.
        // Applying revision n+1 ahead of a held `NEXT` authored against revision n changes what that
        // `NEXT` means, and no later check can recover the intent it destroyed.
        if holdIfOvertaking(
            .queueSnapshot(revision: revision, items: items, currentIndex: currentIndex, generation: generation)
        ) { return }
        await applyQueueSnapshot(revision: revision, items: items, currentIndex: currentIndex)
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
    private func admitAuthoritativeCommand(
        _ message: PlaybackMessage,
        header: PlaybackCommandHeader,
        generation: Int64
    ) async {
        let estimate = await estimate()
        let admission = PendingCommandGate.decide(
            clockReady: estimate?.ready == true,
            deferredCount: deferredEvents.count,
            capacity: deferredCommandCapacity
        )
        switch admission {
        case .overflow:
            onHoldOverflow()
        case .defer_:
            lastReceivedSeq = header.commandSeq
            deferredEvents.append(.command(message, generation: generation))
            diagnostics.lastReceivedCommandSeq = header.commandSeq
            diagnostics.deferredCommandCount = deferredEvents.count
            if !diagnostics.ingressDesynchronized { diagnostics.syncState = .clockUnready }
            diagnostics.clockReady = false
            publishDiagnostics()
            startDeferredDrain(generation: generation)
        case .apply:
            guard let estimate else { return }
            lastReceivedSeq = header.commandSeq
            lastAppliedSeq = header.commandSeq
            diagnostics.lastAppliedCommandSeq = header.commandSeq
            diagnostics.lastReceivedCommandSeq = header.commandSeq
            publishDiagnostics()
            await applyAuthoritative(message, generation: generation, estimate: estimate)
        }
    }

    /// More authoritative work is outstanding than may be held. The same explicit halt-and-reconcile
    /// posture as an ingress overflow, and for the same reason: more authority is outstanding than we
    /// can honestly account for, and applying part of it in the wrong order is worse than admitting
    /// we lost track.
    func onHoldOverflow() {
        diagnostics.inboundOverflowCount += 1
        playbackDesynchronized = true
        queueDesynchronized = true
        publishDesynchronized()
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
        playbackDesynchronized = true
        queueDesynchronized = true
        publishDesynchronized()
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
    func startDeferredDrain(generation: Int64) {
        if let existing = deferredDrainTask, !existing.isCancelled { return }
        deferredDrainTask = Task { [weak self] in
            guard let self else { return }
            while await self.hasDeferredWork(generation: generation) {
                await self.sleeper.sleep(untilLocalMonoUs: self.monotonicNowUs() + Phase5GateBounds.deferredRetryIntervalUs)
                if Task.isCancelled { return }
                guard await self.stillCurrent(generation) else { return }
                await self.drainDeferredEvents()
            }
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
            if playbackDesynchronized || queueDesynchronized { return }
            let held = deferredEvents[0]
            guard await stillCurrent(held.generation) else {
                deferredEvents.removeAll()
                diagnostics.deferredCommandCount = 0
                publishDiagnostics()
                return
            }
            switch held {
            case .command(let message, let generation):
                guard let estimate = await estimate(), estimate.ready else { return }
                let heldHeader = Self.headerOf(message)
                if let heldHeader, heldHeader.queueRevision != queueState.revision {
                    deferredEvents.removeFirst()
                    diagnostics.deferredCommandCount = deferredEvents.count
                    onHeldRevisionMismatch()
                    return
                }
                deferredEvents.removeFirst()
                let seq = heldHeader?.commandSeq
                if let seq { lastAppliedSeq = seq }
                diagnostics.lastAppliedCommandSeq = lastAppliedSeq
                diagnostics.deferredCommandCount = deferredEvents.count
                diagnostics.recoveredCommandCount += 1
                diagnostics.clockReady = true
                publishDiagnostics()
                await applyAuthoritative(message, generation: generation, estimate: estimate)
            case .queueSnapshot(let revision, let items, let currentIndex, _):
                deferredEvents.removeFirst()
                diagnostics.deferredCommandCount = deferredEvents.count
                diagnostics.recoveredCommandCount += 1
                publishDiagnostics()
                await applyQueueSnapshot(revision: revision, items: items, currentIndex: currentIndex)
            case .playbackState(let fields, let generation):
                deferredEvents.removeFirst()
                diagnostics.deferredCommandCount = deferredEvents.count
                diagnostics.recoveredCommandCount += 1
                publishDiagnostics()
                await applyPeerPlaybackState(fields, generation: generation)
            }
        }
    }

    /// A follower's playback intent, arriving at the leader (ADR-024 §3). The leader validates,
    /// stamps and broadcasts — one serialisation point, so two users pressing different buttons at
    /// the same instant resolve by the leader's arrival order rather than by comparing timestamps.
    private func servePlaybackIntent(_ message: PlaybackMessage, header: PlaybackCommandHeader, generation: Int64) async {
        if header.queueRevision != queueState.revision {
            diagnostics.staleRevisionCount += 1
            publishDiagnostics()
            guard await stillCurrent(generation) else { return }
            enqueueOutbound(
                Phase5Outbound(generation: generation, authority: .advisory, frame: .queue(snapshotMessage()))
            )
            return
        }
        guard await stillCurrent(generation), !outboundAuthorityLost else { return }
        syncEnabled = true
        if case .play(_, let trackHash, _, let queueItemId) = message {
            // Amendment A1 Finding E, the other user's half: the leader retains the follower's Play
            // exactly as it retains its own user's, so a track neither phone can play yet becomes
            // one authoritative PLAY when the transfer verifies — the leader being the only side
            // with the authority to reschedule it (PROTOCOL §5 rule 4).
            var intentPositionMs: Int64 = 0
            if case .play(_, _, let positionMs, _) = message { intentPositionMs = positionMs }
            pendingPlay = PendingPlay(
                token: playRequestFence.begin(), generation: generation, contentHash: trackHash,
                queueItemId: queueItemId, positionMs: intentPositionMs
            )
            await resolvePendingPlay()
            return
        }
        await issue { stamped in Self.restamp(message, with: stamped) }
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

    /// Reconciles the queue's admission statistics into diagnostics, and latches the halt if a frame
    /// was refused. Called once per drained frame, **before** that frame is dispatched, so a refusal
    /// can never be followed by an applied command.
    func observeIngressStats() {
        let stats = inbound.stats
        let newOverflows = stats.overflowCount - reportedInboundOverflows
        let newCoalesces = stats.coalescedCount - reportedInboundCoalesces
        guard newOverflows != 0 || newCoalesces != 0 else { return }
        reportedInboundOverflows = stats.overflowCount
        reportedInboundCoalesces = stats.coalescedCount
        diagnostics.inboundOverflowCount += newOverflows
        diagnostics.inboundCoalescedCount += newCoalesces
        if newOverflows > 0 { onIngressOverflow() }
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
        playbackDesynchronized = true
        queueDesynchronized = true
        publishDesynchronized()
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
    func applyAuthoritative(_ message: PlaybackMessage, generation: Int64, estimate: SessionClockEstimate) async {
        guard await stillCurrent(generation) else { return }
        switch message {
        case .play(let header, let trackHash, let positionMs, let queueItemId):
            await applyPlay(header, trackHash: trackHash, queueItemId: queueItemId, positionMs: positionMs,
                            generation: generation, estimate: estimate)
        case .pause(let header, let positionMs):
            await applyTransport(header, generation: generation, estimate: estimate, playing: false, positionMs: positionMs)
        case .resume(let header, let positionMs):
            await applyTransport(header, generation: generation, estimate: estimate, playing: true, positionMs: positionMs)
        case .seek(let header, let target):
            await applySeek(header, targetPositionMs: target, generation: generation, estimate: estimate)
        case .next(let header):
            await applyStep(header, delta: 1, generation: generation, estimate: estimate)
        case .previous(let header):
            await applyStep(header, delta: -1, generation: generation, estimate: estimate)
        default:
            break
        }
    }

    /// ARCHITECTURE §7.2 steps 1-6: resolve, pre-roll the decoder while there is still time, then
    /// start at the deadline. The resolve and the pre-roll are both real suspension points, so the
    /// session generation and the epoch token are re-proved after each.
    private func applyPlay(
        _ header: PlaybackCommandHeader,
        trackHash: ContentHash,
        queueItemId: String,
        positionMs: Int64,
        generation: Int64,
        estimate: SessionClockEstimate,
        playing: Bool = true
    ) async {
        // Amendment A3 Finding B: reached from `applyAuthoritative`, from `applyStep` and from
        // `restoreFromPlaybackState`, and `content.resolve` is real I/O on another actor. Proved on
        // entry so a Play that only *starts* after a boundary does no work, and again below because
        // the resolve suspends.
        guard await stillCurrent(generation) else { return }
        let playable = await content.resolve(trackHash)
        guard await stillCurrent(generation) else { return }
        guard let playable else {
            // PROTOCOL §5 rule 4: do not start, request the transfer, let the leader reschedule.
            diagnostics.syncState = .waitingForContent
            diagnostics.currentTrackHash = trackHash
            publishDiagnostics()
            await content.requestTransfer(trackHash)
            return
        }
        let token = epoch.begin()
        currentEpochToken = token
        driftState = DriftController.reset()
        queueState = SharedQueue.select(state: queueState, queueItemId: queueItemId)
        timeline = PlaybackTimeline(
            trackHash: trackHash, queueItemId: queueItemId, anchorPositionMs: positionMs,
            anchorSessionUs: header.effectiveAtSessionUs, playing: playing, generation: token
        )
        diagnostics.currentTrackHash = trackHash
        diagnostics.hardSeekCount = 0
        diagnostics.lastCorrection = .none
        // A new epoch retires a previous sync failure outright: fresh timeline, fresh drift state,
        // fresh seek budget. Nothing from the failed epoch is still in force.
        if diagnostics.syncState == .syncFailed { diagnostics.syncState = .scheduled }
        publishQueue()
        publishDiagnostics()
        await player.prepare(content: playable, positionMs: positionMs)
        guard await owns(generation: generation, token: token) else { return }
        // A snapshot-restored track that the authority says is paused is loaded and left alone:
        // there is no instant to schedule, because nothing is about to become audible.
        guard playing else {
            markSynced()
            publishDiagnostics()
            return
        }
        scheduleAt(header.effectiveAtSessionUs, estimate: estimate, generation: generation, token: token) { [weak self] in
            await self?.player.start()
        }
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
        positionMs: Int64
    ) async {
        guard await stillCurrent(generation) else { return }
        let token = currentEpochToken
        timeline = timeline?.reanchored(positionMs: positionMs, sessionUs: header.effectiveAtSessionUs, playing: playing)
        scheduleAt(header.effectiveAtSessionUs, estimate: estimate, generation: generation, token: token) { [weak self] in
            guard let self else { return }
            if playing {
                await self.player.seek(positionMs: positionMs)
                await self.player.start()
            } else {
                await self.player.pause()
                await self.player.seek(positionMs: positionMs)
            }
        }
    }

    /// `applyTransport`'s proof, for the same reason and in the same position.
    private func applySeek(
        _ header: PlaybackCommandHeader,
        targetPositionMs: Int64,
        generation: Int64,
        estimate: SessionClockEstimate
    ) async {
        guard await stillCurrent(generation) else { return }
        let token = currentEpochToken
        timeline = timeline?.reanchored(positionMs: targetPositionMs, sessionUs: header.effectiveAtSessionUs)
        scheduleAt(header.effectiveAtSessionUs, estimate: estimate, generation: generation, token: token) { [weak self] in
            await self?.player.seek(positionMs: targetPositionMs)
        }
    }

    /// PROTOCOL §5's `NEXT`/`PREVIOUS`, resolved against the **shared** queue (brief §25). Both peers
    /// hold identical `SharedQueueState` at the revision the command names, so both resolve the same
    /// item without either consulting its own local queue.
    private func applyStep(_ header: PlaybackCommandHeader, delta: Int, generation: Int64, estimate: SessionClockEstimate) async {
        // Amendment A3 Finding B: the highest-risk path in the phase, and it had no proof at all.
        // Everything below reads or writes *live* state — the shared queue, the selection, the
        // playback epoch, the timeline — so the proof has to come before the first read, not before
        // the first player call. A retired `NEXT` used to step the new session's queue; a retired
        // `NEXT` that ran off the end of it took the `step.selected == nil` branch and called
        // `epoch.begin()`, which **retired the live session's playback epoch** and silently stopped
        // its scheduled start from ever firing.
        guard await stillCurrent(generation) else { return }
        let step = SharedQueue.step(state: queueState, delta: delta)
        queueState = step.state
        publishQueue()
        publishDiagnostics()
        guard let selected = step.selected else {
            let token = epoch.begin()
            currentEpochToken = token
            timeline = nil
            scheduleAt(header.effectiveAtSessionUs, estimate: estimate, generation: generation, token: token) { [weak self] in
                await self?.player.stop()
            }
            return
        }
        guard step.moved else { return }
        await applyPlay(header, trackHash: selected.trackHash, queueItemId: selected.queueItemId,
                        positionMs: 0, generation: generation, estimate: estimate)
    }

    // MARK: - Scheduling

    /// PROTOCOL §5 rule 2, exactly: a deadline still ahead is waited for on this device's own
    /// monotonic clock; a deadline already past is applied **immediately** and its lateness counted.
    /// Never skipped, never scheduled backwards.
    private func scheduleAt(
        _ effectiveAtSessionUs: Int64,
        estimate: SessionClockEstimate,
        generation: Int64,
        token: Int64,
        action: @escaping @Sendable () async -> Void
    ) {
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
                id: id, generation: generation, token: token, deadlineUs: deadlineUs, action: action
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
        action: @Sendable () async -> Void
    ) async {
        defer { releaseChainNode(id) }
        guard !Task.isCancelled, await owns(generation: generation, token: token) else { return }
        if let deadlineUs {
            await sleeper.sleep(untilLocalMonoUs: deadlineUs)
            guard !Task.isCancelled, await owns(generation: generation, token: token) else { return }
            recordScheduleError(deadlineUs: deadlineUs)
        }
        if await runIfCurrent(generation: generation, token: token, action: action) {
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

    /// Runs `action` only if both the session and the playback epoch that authorised it are still in
    /// force. Deliberately writes **no** state of its own: a correction and a scheduled command both
    /// need this guard, and only one of them means "we are now synchronised".
    ///
    /// Amendment A1 Finding F: it **returns whether it ran**, and every caller branches on the
    /// answer. This function used to return `Void` and take a `markSynced` flag, and the correction
    /// call sites then mutated diagnostics, incremented the hard-seek count, set `syncFailed` and
    /// emitted a `PLAYBACK_STATE` *unconditionally* — so a correction the guard had just refused
    /// still had four visible side effects, one of them on the wire.
    @discardableResult
    func runIfCurrent(generation: Int64, token: Int64, action: @Sendable () async -> Void) async -> Bool {
        guard await owns(generation: generation, token: token) else { return false }
        await action()
        return true
    }

    /// A scheduled authoritative command took effect, so this device is tracking the timeline. The
    /// ownership proof is taken **again** here, because `action` suspended (Finding F).
    private func markSyncedAndPublish(generation: Int64, token: Int64) async {
        guard await owns(generation: generation, token: token) else { return }
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
        guard let active = timeline else { return }
        let token = currentEpochToken
        guard let estimate = await estimate(), estimate.ready else {
            // brief §41: a dubious clock stops correction, and local playback simply continues.
            if !diagnostics.ingressDesynchronized { diagnostics.syncState = .clockUnready }
            diagnostics.clockReady = false
            publishDiagnostics()
            return
        }
        let nowSessionUs = estimate.sessionUs(localMonoUs: monotonicNowUs())
        let state = await player.playerState()
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
        guard await owns(generation: generation, token: token) else { return }
        let transitioning = await routeState.isRouteTransitioning()
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
            guard await runIfCurrent(generation: generation, token: token, action: { [weak self] in
                await self?.player.setRate(rate)
            }) else { return }
            guard await owns(generation: generation, token: token) else { return }
            diagnostics.lastCorrection = .nudge
            diagnostics.playbackRate = rate
        case .restoreRate:
            guard await runIfCurrent(generation: generation, token: token, action: { [weak self] in
                await self?.player.setRate(DriftController.rateNormal)
            }) else { return }
            guard await owns(generation: generation, token: token) else { return }
            diagnostics.lastCorrection = .restoreRate
            diagnostics.playbackRate = DriftController.rateNormal
        case .hardSeek(let positionMs):
            guard await runIfCurrent(generation: generation, token: token, action: { [weak self] in
                await self?.player.seek(positionMs: positionMs)
            }) else { return }
            guard await owns(generation: generation, token: token) else { return }
            diagnostics.lastCorrection = .hardSeek
            diagnostics.hardSeekCount += 1
            // Amendment A2 Finding E: the correction's *own* generation and epoch, carried to the
            // enqueue rather than replaced there by whatever is live by then.
            await emitPlaybackStateIfOwned(generation: generation, token: token)
        case .declareSyncFailure:
            // ARCHITECTURE §7.3 tier four and FR-025: stop correcting, restore exactly 1.0, surface
            // it — and leave local music playing.
            guard await runIfCurrent(generation: generation, token: token, action: { [weak self] in
                await self?.player.setRate(DriftController.rateNormal)
            }) else { return }
            guard await owns(generation: generation, token: token) else { return }
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
        await emitPlaybackStateFrame(generation: generation) { [weak self] in
            await self?.stillCurrent(generation) ?? false
        }
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
        await emitPlaybackStateFrame(generation: generation) { [weak self] in
            await self?.owns(generation: generation, token: token) ?? false
        }
    }

    private func emitPlaybackStateFrame(generation: Int64, stillOwned: () async -> Bool) async {
        guard !outboundAuthorityLost, let estimate = await estimate() else { return }
        let state = await player.playerState()
        guard await stillOwned() else { return }
        // No `await` from here to the enqueue: the actor makes the proof and the hand-off atomic.
        // ADVISORY (Amendment A2): PROTOCOL §5 calls this "the full authoritative snapshot … the
        // reconciliation anchor, not an incremental update", so the next one subsumes it and a
        // failed send costs nothing that cannot be re-stated. It carries no new `command_seq`.
        enqueueOutbound(Phase5Outbound(
            generation: generation,
            authority: .advisory,
            frame: .playback(.playbackState(
                commandSeq: lastAppliedSeq ?? max(nextSeq - 1, 0),
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
    /// the diagnostics screen.
    private func onPeerPositionReport(trackHash: ContentHash, positionMs: Int64, atSessionUs: Int64) async {
        guard let active = timeline, trackHash == active.trackHash, atSessionUs >= active.anchorSessionUs else { return }
        let state = await player.playerState()
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
    private func onPeerPlaybackState(_ fields: PlaybackStateSnapshotFields, generation: Int64) async {
        guard role == .follower else { return }
        // Amendment A2 Finding D: the reconciliation anchor is authoritative state, so it waits its
        // turn behind held commands exactly as a queue snapshot does. Its supersede rule below then
        // runs against whatever is *still* held at that point, which is the honest reading of "the
        // authoritative state is strictly newer than the command that produced it".
        if holdIfOvertaking(.playbackState(fields, generation: generation)) { return }
        await applyPeerPlaybackState(fields, generation: generation)
    }

    /// `onPeerPlaybackState` with the hold gate already answered — the drain's entry point too.
    func applyPeerPlaybackState(_ fields: PlaybackStateSnapshotFields, generation: Int64) async {
        // Amendment A3 Finding B: everything below writes live state — the received/applied sequence
        // numbers, the held stream, the timeline — and this is reached through the hold gate or the
        // deferred drain, both of which suspend before getting here.
        guard await stillCurrent(generation) else { return }
        let commandSeq = fields.commandSeq
        if lastReceivedSeq == nil || commandSeq > (lastReceivedSeq ?? 0) {
            lastReceivedSeq = commandSeq
            lastAppliedSeq = commandSeq
        }
        // Anything held for the clock that the snapshot already accounts for is superseded by it —
        // the authoritative state is strictly newer than the command that produced it.
        deferredEvents.removeAll { held in
            guard case .command(let message, _) = held else { return false }
            return (Self.headerOf(message)?.commandSeq ?? 0) <= commandSeq
        }
        diagnostics.lastAppliedCommandSeq = lastAppliedSeq
        diagnostics.lastReceivedCommandSeq = lastReceivedSeq
        diagnostics.deferredCommandCount = deferredEvents.count
        publishDiagnostics()
        let wasDesynchronized = playbackDesynchronized
        playbackDesynchronized = false
        publishDesynchronized()
        if wasDesynchronized {
            await restoreFromPlaybackState(fields, generation: generation)
            return
        }
        guard let active = timeline, fields.trackHash == active.trackHash else { return }
        timeline = active.reanchored(positionMs: fields.positionMs, sessionUs: fields.atSessionUs, playing: fields.playing)
        driftState = DriftController.reset()
    }

    /// The playback half of Amendment A1's reconciliation. See `onPeerPlaybackState`.
    private func restoreFromPlaybackState(_ fields: PlaybackStateSnapshotFields, generation: Int64) async {
        // Amendment A3 Finding B: the nil-track branch below supersedes the playback epoch and clears
        // the timeline, so it needs the same pre-mutation proof `applyStep` needs.
        guard await stillCurrent(generation) else { return }
        guard let trackHash = fields.trackHash, let queueItemId = fields.queueItemId else {
            // "Nothing is loaded" is a representable authoritative state (ADR-024 §4). Every
            // scheduled effect from the epoch we lost track of is superseded, and nothing replaces it.
            epoch.supersede()
            timeline = nil
            diagnostics.currentTrackHash = nil
            markSynced()
            publishDiagnostics()
            return
        }
        guard let estimate = await readyEstimate() else { return }
        let header = PlaybackCommandHeader(
            commandSeq: fields.commandSeq, effectiveAtSessionUs: fields.atSessionUs, issuedBy: localPeerId,
            queueRevision: fields.queueRevision
        )
        await applyPlay(header, trackHash: trackHash, queueItemId: queueItemId, positionMs: fields.positionMs,
                        generation: generation, estimate: estimate, playing: fields.playing)
    }

    static let positionReportIntervalUs: Int64 = PlaybackBounds.positionReportIntervalMs * 1_000
}

/// One inbound Phase 5 frame, already parsed, carrying the authentication generation that was live
/// when the read loop produced it.
enum Phase5Inbound: Sendable {
    case playback(PlaybackMessage, generation: Int64)
    case queue(QueueMessage, generation: Int64)

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
