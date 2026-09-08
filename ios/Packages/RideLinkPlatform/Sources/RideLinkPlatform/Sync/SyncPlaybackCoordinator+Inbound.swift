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
        guard let currentRole = role else { return }
        if currentRole == .follower {
            await session.channel.send(queueIntent(mutation))
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

    private func applyLeaderMutation(_ mutation: SharedQueueMutation) async {
        let generation = await session.currentAuthGeneration()
        let outcome = SharedQueue.apply(state: queueState, mutation: mutation)
        guard outcome.rejection == nil, outcome.changed else { return }
        queueState = outcome.state
        publishQueue()
        publishDiagnostics()
        guard await stillCurrent(generation) else { return }
        await session.channel.send(snapshotMessage())
    }

    func snapshotMessage() -> QueueMessage {
        .snapshot(queueRevision: queueState.revision, items: queueState.items, currentIndex: queueState.currentIndex)
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
        case .playbackState(let commandSeq, _, let trackHash, _, let positionMs, let playing, let atSessionUs):
            onPeerPlaybackState(commandSeq: commandSeq, trackHash: trackHash, positionMs: positionMs, playing: playing, atSessionUs: atSessionUs)
        default:
            await onInboundCommand(message, generation: generation)
        }
    }

    func onQueueMessage(_ message: QueueMessage, generation: Int64) async {
        guard await stillCurrent(generation) else { return }
        switch message {
        case .snapshot(let revision, let items, let currentIndex):
            adoptSnapshot(revision: revision, items: items, currentIndex: currentIndex)
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
    private func adoptSnapshot(revision: Int64, items: [SharedQueueItem], currentIndex: Int?) {
        guard role == .follower else { return }
        queueState = SharedQueue.applySnapshot(revision: revision, items: items, currentIndex: currentIndex)
        publishQueue()
        publishDiagnostics()
    }

    /// A follower's queue intent, arriving at the leader. Ordering and the stale-revision rule
    /// (PROTOCOL §5 rule 3) are applied here; the leader then serialises the mutation exactly as it
    /// would its own user's, which is what makes two simultaneous adds deterministic.
    private func onQueueIntent(_ header: QueueCommandHeader, _ mutation: SharedQueueMutation, generation: Int64) async {
        guard let currentRole = role else { return }
        switch CommandOrderGate.decide(role: currentRole, lastAppliedSeq: lastAppliedSeq, incomingSeq: header.commandSeq) {
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
            if await stillCurrent(generation) { await session.channel.send(snapshotMessage()) }
            return
        }
        await mutateQueue(mutation)
    }

    private func onInboundCommand(_ message: PlaybackMessage, generation: Int64) async {
        guard let header = Self.headerOf(message), let currentRole = role else { return }
        switch CommandOrderGate.decide(role: currentRole, lastAppliedSeq: lastAppliedSeq, incomingSeq: header.commandSeq) {
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
        if header.queueRevision != queueState.revision {
            diagnostics.staleRevisionCount += 1
            publishDiagnostics()
            return
        }
        syncEnabled = true
        lastAppliedSeq = header.commandSeq
        diagnostics.lastAppliedCommandSeq = header.commandSeq
        publishDiagnostics()
        guard let estimate = await readyEstimate() else { return }
        guard await stillCurrent(generation) else { return }
        await applyAuthoritative(message, generation: generation, estimate: estimate)
    }

    /// A follower's playback intent, arriving at the leader (ADR-024 §3). The leader validates,
    /// stamps and broadcasts — one serialisation point, so two users pressing different buttons at
    /// the same instant resolve by the leader's arrival order rather than by comparing timestamps.
    private func servePlaybackIntent(_ message: PlaybackMessage, header: PlaybackCommandHeader, generation: Int64) async {
        if header.queueRevision != queueState.revision {
            diagnostics.staleRevisionCount += 1
            publishDiagnostics()
            if await stillCurrent(generation) { await session.channel.send(snapshotMessage()) }
            return
        }
        if case .play(_, let trackHash, _, _) = message, !(await gateContent(trackHash)) { return }
        guard await stillCurrent(generation) else { return }
        syncEnabled = true
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

    /// Drains the inbound channel, one frame at a time, in arrival order. One consumer, so frame
    /// N+1 is never handled before frame N — see `PlaybackForwarder`'s doc comment for why that is a
    /// correctness property and not a tidiness one.
    func drainInbound() async {
        for await item in inbound.stream {
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

    // MARK: - Applying

    func applyAuthoritative(_ message: PlaybackMessage, generation: Int64, estimate: SessionClockEstimate) async {
        switch message {
        case .play(let header, let trackHash, let positionMs, let queueItemId):
            await applyPlay(header, trackHash: trackHash, queueItemId: queueItemId, positionMs: positionMs,
                            generation: generation, estimate: estimate)
        case .pause(let header, let positionMs):
            applyTransport(header, generation: generation, estimate: estimate, playing: false, positionMs: positionMs)
        case .resume(let header, let positionMs):
            applyTransport(header, generation: generation, estimate: estimate, playing: true, positionMs: positionMs)
        case .seek(let header, let target):
            applySeek(header, targetPositionMs: target, generation: generation, estimate: estimate)
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
        estimate: SessionClockEstimate
    ) async {
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
            anchorSessionUs: header.effectiveAtSessionUs, playing: true, generation: token
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
        guard await stillCurrent(generation), epoch.isCurrent(token) else { return }
        scheduleAt(header.effectiveAtSessionUs, estimate: estimate, generation: generation, token: token) { [weak self] in
            await self?.player.start()
        }
    }

    private func applyTransport(
        _ header: PlaybackCommandHeader,
        generation: Int64,
        estimate: SessionClockEstimate,
        playing: Bool,
        positionMs: Int64
    ) {
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

    private func applySeek(_ header: PlaybackCommandHeader, targetPositionMs: Int64, generation: Int64, estimate: SessionClockEstimate) {
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
        switch decision {
        case .applyImmediately(let latenessUs):
            diagnostics.lateCommandCount += 1
            diagnostics.lastScheduleErrorUs = latenessUs
            publishDiagnostics()
            Task { [weak self] in await self?.runIfCurrent(generation: generation, token: token, action: action, markSynced: true) }
        case .schedule(let atLocalMonoUs):
            diagnostics.syncState = .scheduled
            publishDiagnostics()
            Task { [weak self] in
                guard let self else { return }
                await self.sleeper.sleep(untilLocalMonoUs: atLocalMonoUs)
                // The software scheduling error, measured rather than assumed. It says nothing about
                // audible alignment: the decoder, the mixer and two Bluetooth hops all sit between
                // this instant and a listener's ear (brief §23/§66).
                await self.recordScheduleError(deadlineUs: atLocalMonoUs)
                await self.runIfCurrent(generation: generation, token: token, action: action, markSynced: true)
            }
        }
    }

    private func recordScheduleError(deadlineUs: Int64) {
        diagnostics.lastScheduleErrorUs = monotonicNowUs() - deadlineUs
        publishDiagnostics()
    }

    /// Runs `action` only if both the session and the playback epoch that authorised it are still in
    /// force. `markSynced` is false for a correction: only a scheduled command means "we are now
    /// synchronised", and a correction must never overwrite a declared sync failure.
    func runIfCurrent(generation: Int64, token: Int64, action: @Sendable () async -> Void, markSynced: Bool) async {
        guard await stillCurrent(generation), epoch.isCurrent(token) else { return }
        await action()
        guard markSynced else { return }
        // ARCHITECTURE §7.3's fourth tier means correction has given up, and a subsequent PAUSE
        // landing on time does not make that untrue. Only a new playback epoch clears it.
        if diagnostics.syncState != .syncFailed {
            diagnostics.syncState = .synced
            publishDiagnostics()
        }
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
        guard let active = timeline else { return }
        let token = currentEpochToken
        guard let estimate = await estimate(), estimate.ready else {
            // brief §41: a dubious clock stops correction, and local playback simply continues.
            diagnostics.syncState = .clockUnready
            diagnostics.clockReady = false
            publishDiagnostics()
            return
        }
        let nowSessionUs = estimate.sessionUs(localMonoUs: monotonicNowUs())
        let state = await player.playerState()
        let durationMs: Int64? = state.durationMs > 0 ? state.durationMs : nil
        await session.channel.send(
            .positionReport(
                trackHash: active.trackHash,
                positionMs: max(state.positionMs, 0),
                atSessionUs: nowSessionUs,
                playing: state.playing,
                playbackRate: state.rate
            )
        )
        guard await stillCurrent(generation), epoch.isCurrent(token) else { return }
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

    private func applyCorrection(_ action: DriftAction, generation: Int64, token: Int64) async {
        switch action {
        case .none:
            return
        case .nudge(let rate):
            await runIfCurrent(generation: generation, token: token, action: { [weak self] in
                await self?.player.setRate(rate)
            }, markSynced: false)
            diagnostics.lastCorrection = .nudge
            diagnostics.playbackRate = rate
        case .restoreRate:
            await runIfCurrent(generation: generation, token: token, action: { [weak self] in
                await self?.player.setRate(DriftController.rateNormal)
            }, markSynced: false)
            diagnostics.lastCorrection = .restoreRate
            diagnostics.playbackRate = DriftController.rateNormal
        case .hardSeek(let positionMs):
            await runIfCurrent(generation: generation, token: token, action: { [weak self] in
                await self?.player.seek(positionMs: positionMs)
            }, markSynced: false)
            diagnostics.lastCorrection = .hardSeek
            diagnostics.hardSeekCount += 1
            await emitPlaybackState()
        case .declareSyncFailure:
            // ARCHITECTURE §7.3 tier four and FR-025: stop correcting, restore exactly 1.0, surface
            // it — and leave local music playing.
            await runIfCurrent(generation: generation, token: token, action: { [weak self] in
                await self?.player.setRate(DriftController.rateNormal)
            }, markSynced: false)
            diagnostics.playbackRate = DriftController.rateNormal
            diagnostics.lastCorrection = .syncFailed
            diagnostics.syncState = .syncFailed
            await emitPlaybackState()
        }
        publishDiagnostics()
    }

    /// PROTOCOL §5: the leader's authoritative snapshot after a correction. Never an incremental update.
    private func emitPlaybackState() async {
        guard role == .leader, let estimate = await estimate() else { return }
        let state = await player.playerState()
        await session.channel.send(
            .playbackState(
                commandSeq: lastAppliedSeq ?? max(nextSeq - 1, 0),
                queueRevision: queueState.revision,
                trackHash: timeline?.trackHash,
                queueItemId: timeline?.queueItemId,
                positionMs: max(state.positionMs, 0),
                playing: state.playing,
                atSessionUs: estimate.sessionUs(localMonoUs: monotonicNowUs())
            )
        )
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
    /// describes the track it is already playing. It deliberately does **not** start anything.
    private func onPeerPlaybackState(
        commandSeq: Int64,
        trackHash: ContentHash?,
        positionMs: Int64,
        playing: Bool,
        atSessionUs: Int64
    ) {
        guard role == .follower else { return }
        if lastAppliedSeq == nil || commandSeq > (lastAppliedSeq ?? 0) {
            lastAppliedSeq = commandSeq
            diagnostics.lastAppliedCommandSeq = commandSeq
            publishDiagnostics()
        }
        guard let active = timeline, trackHash == active.trackHash else { return }
        timeline = active.reanchored(positionMs: positionMs, sessionUs: atSessionUs, playing: playing)
        driftState = DriftController.reset()
    }

    static let positionReportIntervalUs: Int64 = PlaybackBounds.positionReportIntervalMs * 1_000
}

/// One inbound Phase 5 frame, already parsed, carrying the authentication generation that was live
/// when the read loop produced it.
enum Phase5Inbound: Sendable {
    case playback(PlaybackMessage, generation: Int64)
    case queue(QueueMessage, generation: Int64)
}

/// Forwards a `PLAY`/`PAUSE`/… frame into the coordinator's **ordered** inbound channel.
///
/// **Why a channel and not a `Task` per frame.** The previous shape — `Task { await
/// coordinator.onPlaybackMessage(…) }` — preserved only the order in which tasks were *created*;
/// Swift makes no guarantee that independently created tasks run in creation order, which
/// `OrderedEventChannel`'s own doc comment already says in as many words. A stress run of this
/// phase's suites caught it: two frames delivered back to back were processed out of order roughly
/// 8 % of the time. On the wire that is a real defect, not a test artefact — a follower processing
/// `PAUSE(seq 6)` before `PLAY(seq 5)` would drop the `PLAY` as stale (`CommandOrderGate` doing
/// exactly its job) and pause a track it never loaded. Same primitive, same reason as the Phase 1b
/// control-event ordering fix (`docs/STATUS.md` §2h).
///
/// `send` is synchronous, so the read loop is never blocked and the frame's arrival order is the
/// channel's order.
struct PlaybackForwarder: PlaybackSink {
    let inbound: OrderedEventChannel<Phase5Inbound>

    func submit(_ message: PlaybackMessage, generation: Int64) {
        inbound.send(.playback(message, generation: generation))
    }
}

struct QueueForwarder: QueueSink {
    let inbound: OrderedEventChannel<Phase5Inbound>

    func submit(_ message: QueueMessage, generation: Int64) {
        inbound.send(.queue(message, generation: generation))
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
