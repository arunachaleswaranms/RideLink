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
