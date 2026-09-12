package com.ridelink.app.sync

import com.ridelink.core.playback.IngressAdmission
import com.ridelink.core.playback.Phase5FrameKind
import com.ridelink.core.playback.Phase5Ingress
import kotlinx.coroutines.channels.Channel

/**
 * The bounded, **lossless**, order-preserving handoff Phase 5 uses in **both** directions
 * (ADR-024 Amendment A1 Findings B and C). Mirrors `RideLinkPlatform.Phase5FrameQueue` decision for
 * decision.
 *
 * - **Inbound** it sits between the control read loop and the one Phase 5 consumer, with coalescing
 *   enabled for the latest-wins families.
 * - **Outbound** it is the one ordered path every Phase 5 frame this device sends leaves by, with
 *   coalescing disabled ([coalesceKeyOf] always null): a frame this device has already stamped may
 *   never be superseded by a later one, which is Finding B's whole invariant.
 *
 * **Why this type exists rather than a `Channel`.** Phase 5 shipped with
 * `Channel(capacity = 256, onBufferOverflow = DROP_OLDEST)`. That is a second, *lossy* queue sitting
 * immediately behind reliable ordered TLS-over-TCP: an authoritative `PLAY` could be evicted while
 * the `PAUSE` behind it survived, `CommandOrderGate` would legitimately accept the `PAUSE`, and the
 * follower would pause a track it never loaded. Worse, the eviction was **invisible** —
 * `trySend` on a `DROP_OLDEST` channel returns success, so the `droppedInboundCount` diagnostic the
 * phase shipped with could never increment for an eviction at all. Both halves of that are fixed
 * here: nothing is evicted, and a refusal is returned to the caller.
 *
 * **The three behaviours, decided by the pure [Phase5Ingress] table and not here:**
 *
 * - room ⇒ append, arrival order preserved;
 * - full, and the frame is one whose newest instance subsumes its older ones ([coalesceKeyOf]
 *   non-null — `POSITION_REPORT`, `PLAYBACK_STATE`, `QUEUE_SNAPSHOT`) with an older sibling queued
 *   ⇒ that sibling is replaced by this frame *at this frame's arrival position*. Nothing is lost:
 *   applying only the newest of such a run reaches the same state as applying all of them in order,
 *   which is PROTOCOL §5's "`PLAYBACK_STATE` … the reconciliation anchor, not an incremental
 *   update" and §9's "the snapshot always wins" taken literally. Coalescing is what keeps the
 *   overflow below from ever firing under a peer's ordinary 5 s report cadence;
 * - otherwise ⇒ [IngressAdmission.OVERFLOW], returned to the caller. The caller counts it and
 *   halts, which is the only honest answer when a queue full of unsupersedable commands meets
 *   another one.
 *
 * **Not blocking the read loop is a hard requirement.** [offer] never suspends and never waits on a
 * lock held across I/O, because it is called from the authenticated control read loop — the loop
 * that also carries `PING`/`PONG`, so blocking it would manufacture a false link loss. Genuine
 * TCP backpressure is therefore not available to us, and the explicit refusal is what replaces it.
 *
 * **Amendment A6: this pipe outlives sessions; the *identity* of what it lost does not.** The queue
 * object deliberately survives an authentication boundary (see [close]) — the boundary is expressed
 * by the generation each frame carries, not by tearing the pipe down. Its loss accounting used to
 * be two cumulative `Int`s the consumer diffed, which carried no such generation at all: a frame
 * refused under Session A, observed after Session B activated, told Session B it had lost a frame
 * and halted it. That is not a diagnostics defect — `playbackDesynchronized`/`queueDesynchronized`
 * decide whether incremental authoritative commands are applied at all. So every loss is now
 * recorded against **the refused frame's own generation** ([generationOf]) and handed to the
 * consumer as [IngressLoss] records, which it drains and attributes itself. Inferring the
 * generation from whatever is live at observation time is precisely the bug.
 *
 * @param capacity injectable so a deterministic test can force the edge at 1 or 2 rather than
 *   racing 256 frames against a sleep.
 * @param kindOf whether a frame is an authoritative command or a latest-wins frame.
 * @param coalesceKeyOf the latest-wins family a frame belongs to, or null for a command. Two frames
 *   coalesce only when their keys are equal, so a `POSITION_REPORT` never supersedes a
 *   `PLAYBACK_STATE`.
 * @param generationOf the authentication generation the frame was produced under — the generation
 *   that **owns** any loss this frame causes (Amendment A6).
 */
internal class Phase5FrameQueue<T>(
    private val capacity: Int,
    private val kindOf: (T) -> Phase5FrameKind,
    private val coalesceKeyOf: (T) -> String?,
    private val generationOf: (T) -> Long,
) {
    private val lock = Any()
    private val items = ArrayDeque<T>()

    /**
     * A wake-up hint, never the data itself. `CONFLATED` is exactly right: [take] re-checks the
     * deque before ever awaiting, so a coalesced-away signal costs nothing, and a signal that
     * arrives between the check and the await is retained rather than lost.
     */
    private val signal = Channel<Unit>(capacity = Channel.CONFLATED)

    private var closed = false

    /**
     * Loss and coalescing events, in arrival order, each owned by the generation that caused it
     * (Amendment A6). Consecutive events from one generation share a bucket, so the list length is
     * the number of *generation changes* the consumer has not yet drained across, not the number of
     * events.
     */
    private val losses = ArrayDeque<MutableLoss>()

    private class MutableLoss(
        val generation: Long,
        var overflowCount: Int = 0,
        var coalescedCount: Int = 0,
    )

    val size: Int get() = synchronized(lock) { items.size }

    /**
     * True when nothing is buffered — the mirror of `RideLinkPlatform.Phase5FrameQueue`'s
     * `isConsumerWaiting`, expressed the way this implementation can: Android's consumer parks on a
     * `Channel` signal rather than on a stored continuation, so "empty" is the observable fact.
     */
    val isIdle: Boolean get() = synchronized(lock) { items.isEmpty() }

    /**
     * One generation's ingress losses, as the consumer must consider them: **whose** they are is
     * part of the fact, not something to be inferred later.
     */
    data class IngressLoss(
        val generation: Long,
        val overflowCount: Int,
        val coalescedCount: Int,
    )

    fun offer(item: T): IngressAdmission {
        val admission =
            synchronized(lock) {
                if (closed) {
                    recordLoss(generationOf(item), overflow = true)
                    return@synchronized IngressAdmission.OVERFLOW
                }
                val key = coalesceKeyOf(item)
                val hasSameKind = key != null && items.any { coalesceKeyOf(it) == key }
                val decision = Phase5Ingress.decide(kindOf(item), items.size, capacity, hasSameKind)
                when (decision) {
                    IngressAdmission.ADMIT -> items.addLast(item)
                    IngressAdmission.COALESCE -> {
                        // Remove the *oldest* sibling and append this one, so the newest frame keeps
                        // the newest arrival position relative to the commands around it.
                        val index = items.indexOfFirst { coalesceKeyOf(it) == key }
                        if (index >= 0) items.removeAt(index)
                        items.addLast(item)
                        // Attributed to the *incoming* frame's generation: it is the frame whose
                        // arrival caused the event, and the one whose session is being told about it.
                        recordLoss(generationOf(item), overflow = false)
                    }
                    IngressAdmission.OVERFLOW -> recordLoss(generationOf(item), overflow = true)
                }
                decision
            }
        if (admission != IngressAdmission.OVERFLOW) signal.trySend(Unit)
        return admission
    }

    /**
     * Takes every loss recorded so far, in arrival order, and clears them.
     *
     * Read by the **consumer** rather than reported to the producer. That direction is deliberate:
     * [offer] is called from the control read loop, which cannot suspend into a coordinator and must
     * not do work; and draining at the top of each iteration puts the observation exactly where it
     * has to be — *before* the next frame is dispatched, so a halt takes effect ahead of any command
     * that follows a refusal rather than one frame late.
     *
     * Draining rather than diffing a cumulative total is Amendment A6's other half. A baseline the
     * consumer re-samples at a session boundary cannot be correct: the read loop is still producing
     * under the *old* generation at that instant, so a loss recorded microseconds after the baseline
     * was taken would be read back as the new session's. Each record carries its own generation, so
     * there is nothing to infer and no window to lose.
     */
    fun drainLosses(): List<IngressLoss> =
        synchronized(lock) {
            if (losses.isEmpty()) return emptyList()
            val drained = losses.map { IngressLoss(it.generation, it.overflowCount, it.coalescedCount) }
            losses.clear()
            drained
        }

    /**
     * Appends to the newest bucket when it belongs to the same generation, and opens a new one
     * otherwise. Called only with [lock] held.
     *
     * The bucket count is hard-capped so a consumer that never drains cannot grow this without
     * bound. Eviction folds the oldest bucket into the next oldest rather than dropping it: with
     * more than one bucket present, generations being strictly increasing per authentication
     * (ADR-023 §3) makes both of the two oldest strictly older than the newest, hence both already
     * retired — so the total is preserved exactly and the only thing merged is *which* dead
     * generation two retired losses are attributed to. Nothing is silently discarded.
     */
    private fun recordLoss(
        generation: Long,
        overflow: Boolean,
    ) {
        val bucket =
            losses.lastOrNull()?.takeIf { it.generation == generation }
                ?: MutableLoss(generation).also { losses.addLast(it) }
        if (overflow) bucket.overflowCount += 1 else bucket.coalescedCount += 1
        while (losses.size > MAX_LOSS_GENERATIONS) {
            val evicted = losses.removeFirst()
            val next = losses.first()
            next.overflowCount += evicted.overflowCount
            next.coalescedCount += evicted.coalescedCount
        }
    }

    /** @return the next frame in arrival order, or null once [close] has been called and the queue is drained. */
    @Suppress("ReturnCount") // a frame, a closed-and-drained queue, or a closed signal — three distinct exits
    suspend fun take(): T? {
        while (true) {
            synchronized(lock) {
                items.removeFirstOrNull()
            }?.let { return it }
            if (synchronized(lock) { closed }) return null
            if (signal.receiveCatching().isFailure) return null
        }
    }

    /**
     * Ends the queue for process/coordinator teardown. **Not** a session boundary: a session
     * boundary is expressed by the generation each frame carries, so the pipe deliberately outlives
     * individual sessions (the same rule the iOS mirror's `shutdown` records).
     */
    fun close() {
        synchronized(lock) { closed = true }
        signal.close()
    }

    private companion object {
        /**
         * How many distinct generations' losses may be held undrained at once. The consumer drains
         * on every iteration, so reaching this at all means it has been parked across eight
         * authentications — far beyond anything a ride produces, and the bound is here so that
         * "far beyond" is a fact rather than an expectation.
         */
        const val MAX_LOSS_GENERATIONS = 8
    }
}
