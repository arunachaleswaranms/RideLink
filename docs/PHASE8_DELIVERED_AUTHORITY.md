# Phase 8 — delivered authority across End Ride

23 September 2026. Narrow follow-up to PR #6 at reviewed head
`5b32de5e547b3bf15360663f1f5d5ec0aaa2568c`; base
`48b7a8e5d07fe52010d05c1893d3f914722d80f0`. No merge, new wire message, dependency,
or replacement of ADR-024 A11's pre-delivery reservation architecture.

## Decision made before implementation

**After successful delivery, the authenticated control generation owns the issuer's
obligation to mirror the command. The original RideAdmission remains provenance.**

A ride admission answers whether a local candidate may establish fresh authority. It
cannot revoke authority already distributed to the other phone. End Ride is still
`RIDE_ACTIVE -> CONNECTED`: pairing, connection and authenticated generation survive.

Two designs were evaluated against the existing pipeline:

* **Distributed obligation (chosen).** Retain the original ride, sequence and exact
  reservation in `DeliveredAuthority`. Finish the delivered stream within its control
  generation, while respecting newer established playback authority. Reuse both
  existing ordered chains and the existing capacity ledger.
* **Distributed End Ride boundary (rejected for V1).** Neither the local ride epoch nor
  synchronized-mode epoch is transmitted. `BYE` ends the session; `STATE_REQUEST` and
  snapshots reconcile state but cannot guarantee that a previously delivered PLAY
  will not execute before they arrive. None supplies an acknowledged cancellation
  boundary before C1's effect. Introducing that behavior would require a protocol and
  product change, rather than a fix to local ownership.

Standing rules now read together:

> If work can outlive the frame that admitted it, its original authority/provenance
> travels with it.
>
> Once authenticated authority has successfully crossed the wire, local lifetime
> retirement cannot silently erase the corresponding local obligation while the peer
> remains authorised to execute it.

## Pipeline and suspension audit

Both implementations have one ordered outbound consumer and one ordered playback
inbound consumer. Swift also crosses actor boundaries to query generation, clock,
content and player state. Kotlin's generation/clock/player-state reads are synchronous;
its mutex acquisition, channel write, content resolve and individual player effects
can suspend. The synchronous Swift generation mirror still accompanies async proofs.

| Phase | Leader | Follower | Owner at an End/Start boundary |
|---|---|---|---|
| Candidate | User action captures `RideAdmission` before the first read/await; generation is captured and compared | Authenticated read carries immutable connection/generation binding; role/order/revision gate; local ride context captured before admission's first await | Original ride can refuse candidates; a successor epoch is never recaptured |
| Reserved | `issue` proves generation/ride, reserves capacity, stamps `command_seq`, enqueues in the same critical section | Apply admission reserves before advancing `lastReceivedSeq`; clock-held replay reserves before its pop | Reservation belongs to its generation, never whichever ride is current when released |
| Queued/write | One consumer selects the generation-bound authenticated writer; queue admission advances `nextSeq`, not applied truth | Read loop/relay preserves authenticated generation through the ordered handoff | A write in flight may return SENT after End Ride; the original envelope still owns its reservation |
| Delivered authoritative | SENT advances `lastReceivedSeq` and creates immutable `DeliveredAuthority(ride, commandSeq, reservation)`; ordered `chainApply` owns the reservation | Accepted, reserved immediate/replayed command carries the equivalent delivered authority into apply | Control generation owns the distributed obligation; End Ride is not generation retirement |
| Representation | `applyAuthoritative` dispatches PLAY/transport/seek/step; content resolution may suspend; state writes require either live candidate admission or exact delivered obligation with no newer owner/sequence | Same apply functions after follower admission | Newer **established** ride authority and sequence floor protect successors. A nominal Start Ride alone establishes nothing |
| Pre-roll | PLAY establishes timeline, identity, original ride owner and playback token, then select/load/seek, with ownership re-proved between every effect | Same | End Ride preserves an outstanding delivered effect's token/state. A genuinely newer PLAY or full restoration supersedes that token; old continuation performs no further effects |
| Scheduled/completed | `scheduleAt` enters another phase of the **same** reservation before apply leaves; scheduled chain awaits predecessor/deadline, proves generation/token, dispatches single effects | Same | End Ride cannot locally revoke the delivered effect. A newer token or control-generation retirement can make it obsolete. Final phase releases exact ID |

Clock-held candidates, pending user Play requests and snapshot reconciliation retain
all existing Phase 7 ride/provenance fences. No blanket `rideStillLive` deletion:
only explicit delivered command obligations take the alternative proof. Direct apply
and restoration callers default to the original ride proof. Existing deferred-stream
and reconciliation cancellation regressions remain required.

## End Ride semantics and successor protection

End Ride retires local candidate intent, cancels that ride's pending Play/reconciliation,
disables ongoing synchronization and restores normal playback rate as before. When an
already-delivered effect is still outstanding, it preserves that effect's playback
state/token instead of locally revoking it. If delivery returns later, the obligation
may establish its original ride's state, provided no newer authority has established
state in the meantime. **C1 can intentionally become audible after End Ride, including
when no subsequent Start Ride occurs**, because the peer already has that authority.
This is an intentional consequence of the selected V1 semantics; the software
race is resolved. End Ride does not wait for the decoder or join the apply chain.

No completion recaptures the new ride, reenables fresh candidate admission on its
behalf, or schedules deferred End Ride cleanup. `rideAuthorityEpoch` remains the origin
of the command that actually established state. When no delivered work is outstanding,
the existing completed-ride cleanup still clears identity and timeline.

A leader's C2 can be **delivered/accepted** while C1's local load is parked, but cannot
establish playback state ahead of C1 through the ordered apply chain. The scheduled
chain and playback-token supersession also remain intact: a newer PLAY may supersede
an obsolete scheduled PLAY; it cannot be overwritten by its old continuation.

The separate resync consumer **can** establish genuine C2 state while a follower's C1
player call is parked. The regression exercises a real leader's C2 and its real TLS
STATE_SNAPSHOT. The old C1 returns with its old playback token and cannot change C2's
identity, timeline, ride owner, pending Play, reconciliation state, synchronized-mode
epoch or track diagnostics. An indivisible player call already entered may finish;
no subsequent old player step or coordinator write is authorised by that fact.

## Sequence and capacity accounting

* `nextSeq`: next sequence the leader will stamp. Successful outbound admission
  consumes it; reservation/admission refusal does not.
* `lastReceivedSeq`: authority accepted/taken responsibility for. Leader advances on
  SENT, even if its original ride ended. Follower advances on admission.
* `lastAppliedSeq`: authority represented in playback state with effect ownership.
  A reserved apply-chain node alone is not representation. PLAY advances once its
  timeline/identity/playback token exist, before a future audible deadline; other
  commands advance when their state/effect ownership is represented. Both diagnostics
  mirrors follow these meanings. Emitted playback/state snapshots use applied truth,
  never `nextSeq - 1` as a fallback for unrepresented work.

`deliveredEffects` is bounded metadata, not an independent queue: at most one
`(DeliveredAuthority, playbackToken)` entry per live ledger ID. Apply/scheduled nodes
keep the existing ledger phases. The last phase removes that exact metadata entry;
control retirement filters it against that same ledger. Monotonic IDs, stale release
no-op, failed-send release, the capacity-before-sequence/send decision, LOCAL_OVERLOAD,
256 default bound, two-peer refusal tests and 1,000-command tests remain intact.
The 1,000-command tests also assert metadata count never exceeds live reservations.

## Regressions

AUTOMATED. iOS `SyncPlaybackTwoPeerTests`, using real paired/authenticated TLS:

* `testNoOutcomeDDeliveredRide1PlayCompletesOnBothPeersAfterEndAndStartOverRealTls`
* `testNoOutcomeDEndRideWithoutAnotherStartStillHonoursDeliveredPlayOverRealTls`
* `testRide2CommandAdmittedWhileC1IsParkedWinsOnBothPeersOverRealTls`
* `testRide2SnapshotEstablishesAuthorityBeforeC1ReturnsAndOldCompletionChangesNothingOverRealTls`
* `testTransportSentAfterEndRideKeepsOriginalObligationOverRealTls`

Android `SyncPlaybackDeliveredAuthorityTest`, using two real coordinators, in-process
ordered channels and independent fake monotonic clocks:

* `no Outcome D - delivered Ride 1 PLAY completes on both peers after End and Start`
* `no Outcome D - End Ride without Start still honours delivered PLAY`
* `Ride 2 command admitted while C1 is parked wins on both peers`
* `Ride 2 snapshot establishes authority before C1 returns and old completion changes nothing`
* `transport SENT after End Ride keeps original obligation on both peers`

The no-successor test explicitly checks that Ride 2's mere existence cannot cancel C1
or relabel its provenance. C2 tests inspect both received/applied floors, both track
identities and actual player-port effects; the ordinary ordered-stream case compares
queue state and timeline anchors. Transport-outcome gates park **after** authenticated
write success but before its callback; load gates park inside the player port. No sleeps
were added. State/condition gates determine progress, not elapsed delay.

Existing lifecycle fixtures that meant "completed playback" now wait for actual
completion instead of track metadata/held-stream pop. Their completed-ride cleanup and
reconciliation assertions remain. Older tests that explicitly required abandoning a
**delivered** command were corrected to assert its original-provenance completion and
exact capacity release; their old expectation was the independently reported blocker.

## Fresh-fix audit

* No arbitrary stale candidate reaches the alternative authority proof; only an explicit
  delivered value can use it. Generation remains attached and compared after suspension.
* No second send/replay was introduced. One outcome, one apply node, one reservation;
  existing inbound order gate still suppresses duplicate C2 after its newer snapshot.
* Applied sequence publication moved out of SENT/admission/pop, and emission fallback
  no longer reports allocated-but-unapplied authority.
* End Ride releases no reservation and launches no later authority cleanup. Exact-ID
  phase release cannot free a successor reservation. Metadata has no independent admission.
* C2 cannot overtake C1 in the issuer's apply chain; newer established authority on a
  reentrant restoration path supersedes the old playback token. The final pre-roll
  return also re-proves ownership before scheduling/diagnostics, including the last seek.
* No callback was introduced under a lock, no decoder wait was added to End Ride, and
  no additional work queue or generation source was introduced.
* Kotlin keeps synchronous generation/clock proofs; Swift keeps async plus synchronous
  mirror proofs. These implementation differences do not change the distributed result.

## Validation

Results are recorded in `PHASE8_RELEASE_HARDENING.md` after the final software gates.
Exact pushed-head GitHub CI/security run IDs are supplied with the PR handoff.
No protocol codec, field, frame, vector, version or handshake changes were made.
`tools/crossplatform/run.sh` remains unchanged and is rerun against this implementation.

Physical gates remain **DEFERRED — HARDWARE NOT AVAILABLE**: Android ↔ physical iPhone,
real iPhone/hotspot/cross-device mDNS, helmet Bluetooth, pillion TWS, route switching,
real background/lock behavior, audible synchronization, hardware latency, battery,
thermal and a two-hour physical ride. The authority race above is software-tested,
not hardware-deferred.

## Amendment A13 — accepted clock-held commands

23 September 2026. Narrow follow-up at reviewed head `47dd2aca888c574304aeaa6f4afdabadda134128`
([ADR-024 Amendment A13](DECISIONS/ADR-024-synchronized-playback-integration.md#amendment-a13--23-september-2026--an-accepted-clock-held-command-is-distributed-debt)).
No wire, protocol, vector, dependency or security-workflow change.

### Root cause

The immediate paths above create `DeliveredAuthority` at the moment of responsibility. The
follower's clock-held path did not: `admitAuthoritativeCommand` wrote `lastReceivedSeq = C1` and
appended `DeferredEvent.command(C1, generation, ride)` — the same shape a still-refusable candidate
had — and `DeliveredAuthority` was minted only when `drainDeferredEvents` later reached
`applyAuthoritative`. Until then three places treated the ride as cancellation authority:
`leaveSynchronizedMode(preserveDistributed: true)` → `discardDeferredEvents()`; the drain's
top-of-loop `held.ride`/`rideStillLive` retirement; and its adjacent-to-pop re-proof (iOS `guard
rideStillLive(ride)`, Android `!rideStillLive(held.ride) -> RETIRED`). Terminal state: L applied C1,
F accepted C1, F discarded C1.

### Pipeline trace (follower)

| Step | Suspends? | Who may end the command here |
|---|---|---|
| authenticated frame → `onInboundCommand` → `CommandOrderGate` | iOS actor hops; Android no | order gate (duplicate/stale/role) — **candidate**, `command_seq` not spent |
| desync latch / revision check at arrival | no | refused without spending `command_seq` (A1 Finding C) — **candidate** |
| `admitRide()` captured, then `estimate()` | iOS yes; Android no | — |
| generation + `rideStillLive(ride)` proof, adjacent to the write | no | retired-ride admission — **candidate**, `command_seq` not spent |
| DEFER: `lastReceivedSeq = C1`; append `AcceptedCommand(C1, G, ride, 1)` | no (Android: inside `commandMutex`, append follows with no suspension) | **accepted distributed obligation from here** |
| retained in `deferredEvents` (bounded by `deferredCommandCapacity`, no ledger reservation) | — | see exits |
| drain pass: cancelling-ride check → per-item desync → generation → `estimate()` | yes | control generation; desync latch |
| stream witness → queue revision → capacity pre-check + `reserveWork` | no | revision mismatch → latch + `STATE_REQUEST`; no capacity → **wait** (stays head) |
| supersession check → pop | no (Android: in `commandMutex`) | superseded by established authority (counted) |
| `applyAuthoritative(…, DeliveredAuthority(originalRide, 1, reservation))` | yes | `mayRepresent`: generation, exact live reservation, `distributedObligationSuperseded` |
| `representDelivered` → `lastAppliedSeq = C1` | no | **represented** |

### Every exit, answered

| Exit | Distributed authority yet? | Behaviour now |
|---|---|---|
| clock becomes ready | yes | drained and represented as `DeliveredAuthority(originalRide, …)` |
| End Ride | yes | **kept**; End Ride retires only `cancellingRide` events |
| Start Ride (nominal) | yes | nothing: `rideEpochs` moves, `rideAuthorityEpoch` does not, so nothing is superseded |
| generation retirement (`resetForNewSession`) | yes, and its owner ended | discarded with the ledger's generation-bounded `retire`; `lastReceivedSeq`/`lastAppliedSeq` cleared; Phase 7 resync converges |
| full session reset / shutdown | yes, owner ended | as above; drain task cancelled |
| desynchronisation latch (overflow, ingress loss) | yes | refused explicitly (`refusedHeldCommandCount`), `STATE_REQUEST` raised — authoritative reconciliation |
| queue revision mismatch at drain | yes | popped, `staleRevisionCount`, latch → same reconciliation. In a stream the leader produced this implies a missing frame; intentional and unchanged |
| capacity unavailable | yes | stays head of stream; `heldCommandCapacityWaitCount` once per cadence pass; not popped, not refused |
| `STATE_SNAPSHOT`/`PLAYBACK_STATE` covering its `command_seq` | yes | removed by PROTOCOL §5's supersession rule, counted `supersededHeldCommandCount`; applied truth follows the represented snapshot |
| successor authority | yes | pre-pop `distributedObligationSuperseded` (unreachable in an ordered stream; fail-closed) or post-pop `mayRepresent` refusal — no write, no player step |
| `failClosedOutbound` | n/a | leader-only (authoritative outbound frames); a leader never holds accepted commands |
| "Play locally" | yes | unchanged whole-stream discard: the user leaves synchronised playback on this device, not a ride boundary (same choice A12 made for immediate delivery) |

### End Ride and the held stream

| Held event | End Ride | Why |
|---|---|---|
| `AcceptedCommand` | kept | accepted debt; ride is provenance |
| `QueueSnapshot` | kept, in order | control-generation queue authority (A8); dropping it would change a kept command's meaning |
| `PlaybackState` / `STATE_SNAPSHOT` anchor | retired, reconciliation `CANCELLED` | ride-scoped anchor (ADR-028 A3–A7), unchanged |
| pending user Play | cancelled (unchanged) | local candidate |

The drain owning the kept events keeps running (or is started). `deferredCommandCount` publishes what
remains. No reservation is released and no cleanup is deferred by End Ride.

### Sequence and capacity

* `nextSeq` — leader only: the next `command_seq` to stamp; consumed only by successful outbound
  admission after capacity is reserved.
* `lastReceivedSeq` — the highest authoritative command this device has accepted responsibility for:
  leader on SENT; follower on immediate admission **or deferral into an `AcceptedCommand`**; or
  adopted from a newer snapshot. End Ride never rolls it back; only generation retirement clears it.
* `lastAppliedSeq` — the highest authoritative command represented in playback state with effect
  ownership: `representDelivered`, snapshot adoption newer than `lastReceivedSeq`, and — new —
  `representAuthoritativeSequence` when a snapshot's state is actually represented. Never at SENT,
  admission, deferral or pop. `lastReceivedSeq = C1, lastAppliedSeq < C1` is a valid held state.
* Bounds: retained debt ≤ `deferredCommandCapacity` (16 default); local work ≤ `SessionWorkLedger`
  (256 default). Maximum observed in the regressions: 4 accepted commands retained (the injected
  bound in the boundedness test; overflow reconciled, never grew), otherwise 1; local reservations
  never above the injected capacity of 1 in the capacity-wait tests, and 1 for a parked C1.

### Regressions

AUTOMATED, all reproduced against unmodified `47dd2ac` production first.

| | iOS | Android (`SyncPlaybackAcceptedObligationTest`) |
|---|---|---|
| A — clock-held C1 + End Ride | `SyncPlaybackTwoPeerTests.testClockHeldAcceptedC1SurvivesFollowerEndRideAndCompletesOnBothPeersOverRealTls` (real TLS) | `clock-held accepted C1 survives follower End Ride and completes on both peers` |
| B — End + nominal Start | `…testClockHeldAcceptedC1SurvivesEndAndNominalStartWithOriginalProvenanceOverRealTls` | `clock-held accepted C1 survives End and nominal Start with original provenance` |
| C — genuine Ride-2 authority wins | `…testGenuineRide2AuthorityEstablishedBeforeHeldC1RepresentsWinsOnBothPeersOverRealTls` | `genuine Ride 2 authority established before held C1 represents wins on both peers` |
| D — generation retirement | `SyncPlaybackAcceptedObligationTests.testAHeldG1AcceptedCommandDiesWithG1AndNeverAppliesUnderG2` | `a held G1 accepted command dies with G1 and never applies under G2` |
| E — capacity unavailable | `…testAClockReadyAcceptedCommandWaitsForCapacityWithoutLossOrSpinAndReleasesOnce` | `a clock-ready accepted command waits for capacity without loss or spin and releases once` |
| F — snapshot supersedes | `…testAnAuthoritativeSnapshotCoveringAHeldAcceptedCommandSupersedesItBySequence` | `an authoritative snapshot covering a held accepted command supersedes it by sequence` |
| End Ride partition | `…testEndRideKeepsAcceptedDebtAndQueueStateButCancelsTheHeldReconciliation` | `End Ride keeps accepted debt and queue state but cancels the held reconciliation` |
| drain cadence | `…testASecondClockHoldInOneSessionRecoversOnTheRetryCadence` | `a second clock hold in one session recovers on the retry cadence` (parity pin) |
| boundedness | `…testAcceptedDebtKeptAcrossEndRideStaysWithinTheHeldStreamsOwnBound` | — (same pure `PendingCommandGate`, vector-pinned) |

A, B and C assert by name the forbidden terminal state (`assertNoLeaderAppliedFollowerAcceptedFollowerDiscarded`).
C's reachable form is C1's first suspension after its pop: nothing authoritative can overtake a
*retained* C1 (A2 Finding D), so C2 or a snapshot arriving while C1 is held is held behind it.

Corrected, because they asserted the blocker: iOS `RideSegmentLifecycleTests` —
`testADeferredRideOneCommandCompletesAsRideOneWorkAndNeverBecomesRideTwosAuthority`,
`testAnAcceptedRideOneCommandAtTheHeadDrainsInOrderAndDoesNotBlockLiveWorkQueuedBehindIt`,
`testARideBoundaryInsideTheDrainsClockReadNeitherDiscardsNorRelabelsTheAcceptedCommand`, and both
fifty-cycle tests; Android `ResyncRecoveryTest.an end ride retires ride-scoped held work in the same
step that moves the ride epoch and keeps accepted debt`. Each still asserts that the command is never
relabelled with a successor ride and that applied truth moves only with representation.
`testDeliveredApplyParkedAcrossEndRideCompletesWithOriginalProvenance` now waits on its outcome
instead of a yield budget (it failed once under full-suite load, reading between two actor hops).

### Fresh-fix audit

| Risk | Result |
|---|---|
| End Ride preserving all deferred events | no — partition test: reconciliation retired and cancelled |
| old reconciliation surviving into Ride 2 | no — same test; it never reports applied |
| accepted command retained forever | **found on iOS** (drain cadence lost after the first hold in a session); fixed, regression added |
| drain tight loop when capacity unavailable | no — one wait per cadence pass, none without time |
| duplicate apply after snapshot supersession | no — F: one `select` |
| `lastReceivedSeq` rolled back | no — A/B/C |
| `lastAppliedSeq` advanced before representation | **found on Android** (drain published popped `seq`); fixed. Opposite gap **found on both** (represented snapshot at `lastReceivedSeq` left `lastAppliedSeq` behind); fixed |
| C1 relabelled with current ride | no — B and corrected round-7 tests |
| G1 command applying under G2 | no — D |
| old held command blocking a newer repair | no — latch refuses held debt; per-item desync rule unchanged |
| queue revision repair impossible | no — kept queue snapshots make it more available |
| unbounded retained metadata | no — boundedness test |
| duplicate/double/leaked reservation | no — one reserve per pop, one `defer`/`finally` release; E returns to 0 |
| callback under `commandMutex` / new lock ordering | no — reconciliation callbacks fire from End Ride exactly where `discardDeferredEvents` did; no new lock |
| capacity wait misreported as refusal | **found on both**; fixed with the pre-check and `heldCommandCapacityWaitCount` |

### Parity

Android and iOS agree on every outcome above. Mechanics differ where the platforms do: Android's
`estimate()` is synchronous and its drain decisions run inside `commandMutex`; Swift's actor hops
suspend, so its proofs pair async and synchronous checks. Android `applyPlay` still stamps identity
before its content resolve (pre-existing, documented), so C is guarded there by the post-resolve
`mayRepresent`. No async guard was added for symmetry.
