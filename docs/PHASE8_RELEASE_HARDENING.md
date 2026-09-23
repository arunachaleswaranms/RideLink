# Phase 8 — Release Hardening & Software Integration

Baseline: independently reviewed Phase 7 merge
`48b7a8e5d07fe52010d05c1893d3f914722d80f0`.
Branch: `phase8/release-hardening`. No merge is authorized.

This is the current audit record; STATUS's older implementation-pass narratives are historical.
Software closure requires independent review of the live PR and exact-head CI. Physical
validation remains **DEFERRED — HARDWARE NOT AVAILABLE**.

## Accepted clock-held commands — 23 September 2026

Review at `47dd2aca888c574304aeaa6f4afdabadda134128` found the delivered-authority model applied
too late on one follower path: a command accepted while the clock was untrusted advanced
`lastReceivedSeq` and was retained as ordinary ride-local work, so End Ride and the drain's ride
proofs discarded it after the leader had represented it.
[ADR-024 Amendment A13](DECISIONS/ADR-024-synchronized-playback-integration.md#amendment-a13--23-september-2026--an-accepted-clock-held-command-is-distributed-debt)
makes the retained form a structural `AcceptedCommand` whose ride is provenance only, has End Ride
retire only ride-scoped held anchors, and shares A12's successor test. The full trace, every
terminal exit and the regression names are in
[the delivered-authority audit](PHASE8_DELIVERED_AUTHORITY.md#amendment-a13--accepted-clock-held-commands).
No protocol, vector, dependency, security-workflow or cross-platform-gate change.

Every new regression failed against unmodified `47dd2ac` production for the stated reason first
(two-peer A/B: "End Ride must not erase an accepted distributed obligation" and "L applied C1, F
accepted C1, and F discarded C1"). Building them found three further defects, all fixed: iOS lost
the 100 ms drain cadence after a session's first hold; a represented snapshot at
`command_seq == lastReceivedSeq` left `lastAppliedSeq` behind on both platforms; and a held command
waiting for capacity was counted as a refused admission (Android also published the popped `seq`
as applied before the apply could refuse it).

| Gate | Evidence (AUTOMATED, local, final source) |
|---|---|
| Targeted repeats | iOS `SyncPlaybackAcceptedObligationTests` + `SyncPlaybackTwoPeerTests` + `RideSegmentLifecycleTests` 20× (50 tests each, 0 failing iterations); Android `SyncPlaybackAcceptedObligationTest` + `SyncPlaybackDeliveredAuthorityTest` + `ResyncRecoveryTest` 10× with `--rerun` (0 failing) |
| Android unit | 1,089 passed, 0 failed, counted from JUnit XML: core 459, app 282, network 284, audio 33, data 31 (`:core:test` and `test`, `--rerun-tasks`). The project defines no release unit-test task |
| Android static/build | ktlint, detekt, lint, assembleDebug and assembleRelease passed with JDK 21, `--rerun-tasks` |
| Android instrumentation | 50 passed on the API 36 emulator: app 5, audio 11, data 34 |
| iOS Core | 353 passed |
| iOS Platform | 661 executed, 0 failures, 1 skipped (the interop half that needs the orchestrator); includes all 16 real-TLS two-peer tests |
| iOS simulator builds | Unsigned Debug and Release: `** BUILD SUCCEEDED **` |
| Cross-platform | `tools/crossplatform/run.sh`: GATE PASSED 3 consecutive times, 17/17 comparisons each (pairing code, pins, leader, session_id, READY clocks, PLAY/QUEUE_SNAPSHOT/STATE_REQUEST/STATE_SNAPSHOT, silent reconnect 1 → 2, successor PLAYBACK_STATE) |
| Local security | Gitleaks over git history: 222 commits, no leaks. Local-only policy: 309 production files, 0 findings. (A `gitleaks dir` scan of the working tree reports 22 hits, all in the untracked SwiftPM checkout of GRDB's vendored SQLite sources under `.build/`, none in repository content) |
| GitHub CI/security | Exact pushed-head run IDs are supplied with the PR handoff |

Recorded rather than discarded: one full iOS run hung in
`TransferManagerTests.testABindThatCompletesAfterCloseNeverPublishesItsListener` (Phase 4 transfer
binding, untouched here) and was killed; it did not recur in 20 watchdog-guarded runs of that suite
or in two later full runs. The same interrupted run failed
`testDeliveredApplyParkedAcrossEndRideCompletesWithOriginalProvenance` once (0/30 in isolation):
its fixed yield budget let the parked apply resume between two separate actor reads under load; it
now waits on the outcome. SwiftLint/SwiftFormat are not installed on this machine and are not CI
gates.

Physical gates remain **DEFERRED — HARDWARE NOT AVAILABLE**. This blocker was software and is
closed in software.

## Delivered-authority follow-up — 23 September 2026

The remaining review blocker was independent of capacity: after successful delivery,
End Ride could retire the issuer's local apply while the peer remained authorised to
execute the command. [The full pipeline/design audit](PHASE8_DELIVERED_AUTHORITY.md)
evaluates both distributed debt and a distributed End Ride boundary, defines the chosen
control-generation-owned `DeliveredAuthority`, and names every new regression. Original
ride provenance and the exact pre-delivery reservation travel through both existing chains.
No protocol, dependency, security workflow or cross-platform gate was changed.

The two-peer **No Outcome D** regressions reproduce the defect against reviewed production
head `5b32de5`: the follower applies C1, but the issuer permanently refuses its local effect
after End Ride. Both platforms pass with the fix. Tests additionally cover nominal Ride 2
without authority, no subsequent Start, C2 admitted while C1 is parked, genuine C2 snapshot
restoration before C1 returns, and transport SENT returning after End Ride. iOS uses real
paired/authenticated TLS. The successor restoration tests let C2's actual player effect
complete before releasing C1 and assert that C1 dispatches no further player steps.

| Gate | Follow-up evidence (AUTOMATED) |
|---|---|
| Android unit tests | 1,081 unique tests passed: app 274, audio 33, core 459, data 31, network 284, counted from JUnit XML. Both Debug and Release unit tasks passed |
| Android static/build | Full ktlint, detekt, lint, assembleDebug and assembleRelease passed with JDK 21 |
| Android instrumentation | 50 passed on API 36: app 5, audio 11, data 34; no physical-device claim |
| iOS Core | 353 tests passed |
| iOS Platform | 652 executed, zero failures, one skipped (standalone cross-platform half requires the orchestrator); all 13 real-TLS two-peer tests also passed separately |
| iOS simulator builds | Unsigned Debug and Release passed |
| Cross-platform | `tools/crossplatform/run.sh` passed. Real TLS/SAS/pins/session/leader, READY clock estimates, all command/queue/state fields, and silent pinned reconnect passed. Measured READY RTT p95: iOS 7,518 µs, Android 9,568 µs. Both generations advanced 1 → 2. Existing transcript remains byte/field compatible; no codec/schema/vector changes |
| Local security | Gitleaks found no leaks; local-only policy checked 309 production files with zero findings |
| GitHub CI/security | Exact pushed-head CI and Security run IDs are supplied in the final handoff. The required jobs remain Android, iOS, Gitleaks, local-only policy, CodeQL Java/Kotlin, CodeQL Swift and Dependency Review |

Validation exposed fixture assumptions that equated acceptance/metadata with completed
playback. Those fixtures now wait for actual representation/completion; reconciliation and
completed-ride cleanup assertions remain. Kotlin reconciliation fixtures also align the
follower's independent fake clock to the leader deadline before asserting completed baseline
playback. One earlier full iOS run hit an unrelated real-TLS reconnect-stress `notReady`
timeout while builds ran concurrently; the complete suite subsequently passed without any
change to that stress test. No test was disabled or threshold weakened.

The older validation ledger below records previous review rounds; this section is the
current local software evidence. Independent review and exact-head checks remain required.

## Changes and defect evidence

| Defect | Reproduction and root cause | Fix | Regression |
|---|---|---|---|
| End Ride does nothing during recovery | Ride Mode remains visible in reconnect/disconnected, but the pure FSM rejects EndRide in both states | Reconnect toward CONNECTED after ending a ride; exhausted recovery enters existing ENDING teardown. iOS observes the full FSM, including returnTo | Two shared vectors failed before the fix; mirrored 1,000-session lifecycle tests cover duplicate refusal, recovery destination and terminal restart |
| Diagnostic retention grows indefinitely | Emit 100,000 events into the production sink used by both AppContainers; all remain retained | Latest 1,024 events only; chronological snapshots; synchronized reads/writes | Mirrored LogRetention tests; original implementation failed both count and retained-window assertions |
| Bounded wire queues feed unbounded task chains | Keep the injected deadline clock fixed and commit 300 PAUSE commands; iOS retains 300 live scheduled/apply tasks | **Revised after independent review — see the round-2 section below.** Capacity is now *reserved* before an authoritative command can be delivered (ADR-024 Amendment A11), not refused at node creation. The 256 bound is unchanged | Mirrored single-coordinator and two-peer regressions; see the round-2 table |
| Riding surface omits synchronization failure state | Ride Mode labels connection but provides no sync/content-wait/failure explanation | Derive a short label from existing sync diagnostics; connection loss takes precedence | Both presentation suites exhaust all sync states under disconnected/reconnecting |
| Reconnect stress harness can advance before event forwarding completes | Its readiness check reads a generation published inside handleConnected before resync.onConnected has completed; a subsequent cycle can overtake that task | Track the completed forwarding generation in the test rig; report the exact pending cycle and dump state on timeout | Isolated 100-cycle test and the complete platform suite; no production timeout increased |
| State request disappears during iOS connection reset | Park reset at the generation query after the pending slot is cleared; admit a request, then finish reset. Only the pre-reset candidate was flushed | Flush the newest original-generation candidate admitted before or during reset; existing generation proof still refuses retired requests | Three deterministic parked-reset tests: same-lifetime liveness, stale-only refusal followed by fresh liveness, and stale arrival cannot displace a live pre-reset request |

Fresh-fix checks: no external callback executes under the new log lock; snapshot readers retain
independent values. Overflow does not wait for the parked decoder, mint successor ownership, or
publish rejected work as applied. Chain-node ids remain monotonic, so old completion cannot erase
a new node. Existing ADR-024 Amendment A4 permits terminal absolute rate restoration to 1.0
as a cleanup effect; it does not authorize any post-suspension coordinator mutation. This phase
retains that baseline exception rather than claiming that cleanup makes no player calls. The parked-apply test awaits the actual predecessor task, not a fixed number of yields.
A successor's apply task is also awaited before clearing the test's effect recording.

## Independent review round 2 — the bounded-work decision, corrected

The review accepted everything above except decision 3 of ADR-029, and it was right. Full reasoning
is in [ADR-024 Amendment A11](DECISIONS/ADR-024-synchronized-playback-integration.md#amendment-a11--22-september-2026--local-work-capacity-is-reserved-before-delivery-never-refused-after-it)
and [ADR-029 Amendment A1](DECISIONS/ADR-029-release-hardening.md#amendment-a1--22-september-2026--independent-review-the-bounded-work-decision-and-the-software-gate).

**Root cause.** The chain-node limit asked the right question in the wrong place. The only thing that
creates a leader's apply node is `onCommandOutcome` — the outbound consumer's commit hook, which runs
*after* `send` returned true, so the follower already has the command. The overflow path then cleared
`role`, `lastAppliedSeq`, `lastReceivedSeq`, the timeline and the ride-scoped identity, cancelled both
chains and published `TRANSPORT_FAILED` for a transport that had just succeeded. Follower applies C;
leader silently does not; session still authenticated; nothing on the wire can say so.

**The fix.** `SessionWorkLedger` — pure, mirrored, in `core`/`RideLinkCore` — is the bound, still 256.
Capacity is *reserved* where responsibility is taken, always upstream of the point the peer can rely
on the command, and spent by the work that delivery obliges.

| Path | Reserved at | A refusal there |
|---|---|---|
| Leader's own command | `issue`, in the same critical section as the `command_seq` allocation and the enqueue | Never stamped, never enqueued, never written. `failClosedOutbound` with `SyncState.LOCAL_OVERLOAD` |
| Follower's inbound command | `admitAuthoritativeCommand`, before either sequence number moves | `latchDesynchronized()`; **no `command_seq` spent** |
| A replay from the held stream | before the pop | The command stays where it is, retried on the drain's cadence |
| A reconciliation restore | immediately before its one `applyPlay` | Retained with the same obligation id; `DEFERRED_CAPACITY` |

A `WorkReservation` is an immutable `(id, generation)` token from a never-reused counter, so a
release from a retired session names an id the ledger no longer holds and frees nothing. It is
refcounted because one command's obligation spans an apply node and the scheduled node it arms;
`enterPhase` runs synchronously inside the phase already held, so the count cannot reach zero in
between, and every reservation has exactly one release site in a `finally`/`defer`.
`retire(throughGeneration:)` is bounded by the generation that **ended**.

`LOCAL_OVERLOAD` is a new `SyncState` rather than a reuse of `TRANSPORT_FAILED`: the posture and
implementation are identical, but nothing was ever offered to the transport and a rider reading
"transport failed" would go looking at the Wi-Fi.

**Sequence semantics after the fix.** `nextSeq` is the next number this leader will stamp, and a
capacity refusal precedes the stamp, so it leaves no gap. `lastReceivedSeq` means work taken
responsibility for. `lastAppliedSeq` means work reflected in authoritative playback state. **Nothing
in this change rolls either back**; only a retired control lifetime clears them, in
`resetForNewSession`, as before.

### Regressions

| Property | Test |
|---|---|
| **Two peers, the boundary, no Outcome C** | `SyncPlaybackTwoPeerTest.local work capacity is refused before delivery and leaves both peers agreeing` (Android, two real coordinators on clocks 7.5 s apart) and `SyncPlaybackTwoPeerTests.testLocalWorkCapacityIsRefusedBeforeDeliveryAndLeavesBothPeersAgreeingOverRealTls` (iOS, two coordinators over a real authenticated TLS connection). Both assert the disjunction on the **follower**: the refused command reached it never, and every delivered command was honoured by both, with identical `lastAppliedCommandSeq` and identical player effects |
| Refused/failed sends release capacity | `a refused send releases the capacity it reserved` / `testARefusedSendReleasesTheCapacityItReserved` — 20 consecutive failed sends leave the ledger empty, then a fresh connection makes ordinary progress |
| Generation boundary with a send outstanding | `a generation boundary releases its own reservations and never a successor's` / `testAGenerationBoundaryReleasesItsOwnReservationsAndNeverASuccessors` — the send is parked strictly inside the write; the boundary releases exactly G1's; G1's late callback frees nothing of G2's and applies nothing |
| Ride boundary (corrected in follow-up) | `a ride boundary completes the delivered command and releases its capacity` / `testARideBoundaryCompletesTheDeliveredCommandAndReleasesItsCapacity` — a delivered command finishes with its original ride provenance and releases its reservation. The old cancellation assertion was the remaining divergence blocker; see [the two-peer follow-up](PHASE8_DELIVERED_AUTHORITY.md) |
| Boundedness | `a thousand commands against a frozen deadline retain a bounded amount of work` / `testAThousandCommandsAgainstAFrozenDeadlineRetainABoundedAmountOfWork` — with the bound injected at 8, **maximum observed retained production obligations: 8** (`peakRetainedWorkCount`), maximum live chain nodes ≤ 16, exactly 8 frames on the wire. Measured on `SessionWorkLedger`, not on a fixture's recording list |
| Same-lifetime liveness | `below capacity ordinary commands still deliver, commit and apply in order` / `testBelowCapacityOrdinaryCommandsStillDeliverCommitAndApplyInOrder` — `command_seq` 2, 3, 4 consecutive, applied in order, zero refusals |
| The ledger itself | `SessionWorkLedgerTest` / `SessionWorkLedgerTests`, 8 mirrored cases each: hard bound, monotonic ids and ABA, double release, phase lifetime, `enterPhase` after retirement, generation-scoped retirement, `clear`, and a 10 000-step alternating run that never exceeds the bound and ends empty |

**This pass's own fresh-fix audit found two defects in its own first draft**, both fixed before
anything was pushed and both recorded in ADR-024 Amendment A11: a double release across three drain
branches (worse than a leak — the armed effect still owns the obligation), and a drain storm where
`restoreFromPlaybackState` re-appended an anchor the drain had already popped. Also checked and clear:
reservation leak on every path, old-generation release touching a successor, callback under a lock
(`reserveWork`/`releaseWork` are non-suspending and take none), outbound/apply/scheduled deadlock
(the reserve is synchronous and adds no suspension), sequence gaps, request storms, and an unbounded
reservation map.

## Cross-platform software integration gate

`tools/crossplatform/run.sh` runs the Swift and Kotlin implementations as **two processes on one
machine joined by a real TCP socket carrying the real RideLink protocol**. Both halves
(`RideLinkPlatformTests.CrossPlatformInteropTests`,
`com.ridelink.network.interop.CrossPlatformInteropTest`) are inert unless the orchestrator supplies
the shared report directory, so neither affects ordinary CI. Nothing between them is faked: every
byte is produced and consumed by production code, and `tools/crossplatform/compare.py` makes the
assertions neither implementation can make alone.

Measured, three consecutive passes:

| Established | Observed |
|---|---|
| Real TLS 1.3, mutual authentication, ECDSA P-256 identities issued by Kotlin's and Swift's own `IdentityIssuer`, pinned by `identity_spki_sha256` | handshake completed; one pin persisted per side |
| **PROTOCOL §4.5's six digits, derived independently from each side's own TLS exporter** | identical every run (`114762 == 114762`). The assertion the protocol cannot make — §4.5 has two humans compare them — and the direct cross-platform statement of ADR-018 |
| ADR-010 leadership and session identity | exactly one leader; both agree on one `session_id` |
| ARCHITECTURE §7.1's real `PING`/`PONG` burst | both estimators ready; `rtt_p95` 1.9–2.9 ms over loopback |
| Playback/queue/resync codec compatibility | `PLAY`, `QUEUE_SNAPSHOT`, `STATE_REQUEST`, `STATE_SNAPSHOT`, `PLAYBACK_STATE` encoded by one platform and decoded field-for-field by the other |
| Reconnect and generation handling | silent re-authentication on the stored pin — **no second six-digit prompt on either side** — generation 1 → 2 on both, and a subsequent frame accepted under the successor generation |

**What it does not establish.** No UI is driven and no app is launched, so the interactive
emulator ↔ simulator journey is not claimed. Nothing here touches Bluetooth, audio, iPhone background
behaviour or a physical device. The Kotlin half runs on the JVM against Conscrypt rather than on a
device against Android's own TLS stack — the pre-existing limitation
`test-results/phase1b-security-spike-20260827.md` records, neither closed nor hidden by this gate.

## Lifecycle and cross-feature coverage map

These suites exercise different production boundaries. A fake port result is not a physical
transport/audio measurement, and a composition of suites is not a single live two-device journey.

| Flow / property | Production coverage |
|---|---|
| Discovery/connect/authenticate | PairingSessionIntegration, TLS channel/trust-gate suites, SessionLifecycleRestart; real TLS pairs in platform tests |
| Multiple rides in one control session | RideSegmentLifecycleTests; ResyncStress/Recovery; full FSM restart property tests |
| Reconnect, clock refresh, request/snapshot, reconciliation | ResyncCoordinator/ResyncRecovery; ReconnectResyncStress over persistent real-TLS pairs on macOS; Android deterministic two-peer coordinator harness |
| End Ride during deferred playback/reconciliation/content readiness | RideSegmentLifecycle, SyncPlaybackLifecycle/OperationLifetime audits, ResyncRecovery/Stress; shared recovery FSM vectors |
| Music + intercom/PTT/duck/pause | IntercomMusicCoexistence, VoiceControllerIntercom, SessionCoordinatorCoexistenceProvenance and CoexistenceCoordinator suites |
| Transfer completion and session changes | SharedLibraryCoordinatorCancellation, transfer operation/bulk-listener lifetime suites; late-content tests in ResyncRecovery and ReconnectResyncStress |
| Voice negotiation + reconnect | VoiceController lifetime/teardown/mailbox tests and shared VoiceNegotiation vectors; captured control generation and voice-session identity |
| Sequence truth / obligation identity | RideSegmentLifecycle parked admission/drain tests; ResyncCoordinator sequential obligations; SyncPlayback delivery/lifetime audits |
| Route transition + correction | Mirrored drift suites and 1,800-tick accelerated endurance tests |
| Terminal restart / cleanup | SessionLifecycleRestart, SessionTeardownOwner, VoiceController shutdown regressions; cancellation and actual completion are distinct |
| Android Activity recreation | ActivityOwnershipTest: 20 background/foreground + recreate cycles against the real Application container; same session/music/sync owners and released PTT |
| iOS scene lifecycle | Production MainScreen forwards active/inactive/background to the existing coordinator. Simulator interactive lifecycle remains a separate runtime gate, not established by package tests |

## Retained and asynchronous work audit

The source search covered Task, launch, async, continuation, callback, timer, observer, Flow,
AsyncStream, listeners, mDNS, reconnect, scheduled playback and content readiness. Major owners:

| Work | Authorizing lifetime / representation | Retirement and late-callback proof | Retention |
|---|---|---|---|
| Application coordinators/player | Android Application AppContainer; iOS App-held state | Activity/scene changes reuse references; terminal session cleanup does not create another player | One configured owner per process |
| Discovery/listener work | SessionRuntime Job / sessionWork registry and captured manager | Terminal owner cancels and joins before IDLE; discovery unregisters in its cleanup | One discovery/advertiser/listener per session |
| Control frame dispatch | Immutable ReadFrameBinding(connection, authentication generation) | Original binding is retained; relay compares with live authenticated generation | Frame-size and queue bounds; no re-pair bypass |
| Reconnect/clock work | ControlSessionManager connection/session jobs | Captured connection and generation; authenticated successor refreshes clock | One reconnect loop and clock estimator |
| Playback ingress/outbound | Original generation, ride admission and outbound authority | Refuse retired generation/ride at commit and effect boundary | 256 ingress / 256 outbound by default; generation-loss ledger capped at 8 |
| Deferred authoritative work | Generation + ride admission + synchronized-mode epoch; reconciliation obligation id | Each drain re-proves original admission; cancellation completes its own obligation | Existing deferred bound, not an append-only history |
| Apply/scheduled chains | Control generation + operation token + monotonic node id | Capacity retirement cancels chains and invalidates tokens before allowing fresh authority | New combined 256 live-node bound |
| Play waiting for content | Play-request token and original lifetime | Content callback retries only the retained request; End Ride/session loss retires it | One pending play |
| State recovery | Pending generation plus distinct obligation identity | S1 completion cannot complete S2; retained early reply carries original generation | One pending request/early reply; identity-scoped reconciliation |
| Bulk transfer/listener/token | Captured transfer id, operation gate and authenticated session binding | Cancellation retires gate before effects; late bind/accept closes its own result | One active bulk transfer; existing bounded framing/token state |
| Voice control/negotiation | Control generation, voice_session_id, mailbox lifetime | Named control retirement, stale floor, callback negotiation id; terminal shutdown joins owned consumers | Bounded priority mailbox and ICE state; conflated wakeup |
| Route/foreground/audio callbacks | Capture/audio-session generation and installed observer/owner | Retired route callbacks refused; observer cleanup belongs to installer | Existing single audio owner and route consumer |
| Coexistence effects | Coexistence lifetime + playback identity/operation proof | Re-proof after suspension; predecessor restoration cannot overwrite successor intent | One current temporary effect |
| Diagnostics | Process-owned log sink | Snapshot reads, no authoritative state in log history | Latest 1,024 events |

The work-node metric counts live authoritative nodes. It does not claim to measure all OS tasks,
RSS, or completion of a platform callback that ignores cancellation. Hardware resource/battery
measurements require the physical gates below.

## Stress evidence

- Each platform: 1,000 FSM sessions, three rides per session, alternating End Ride during
  recovery and successful ride recovery, then exhausted recovery/terminal teardown/restart.
- Each platform: 1,800 real coordinator correction/report ticks spanning exactly 9,000 seconds
  (2.5 hours) of virtual time. Alternating 50 ms/zero drift, route transition every 17th cycle.
  Assert one armed correction deadline, zero unintended hard seeks/deferred commands, and all
  outbound reports attempted. Fake recording lists are cleared, not counted as application state.
- Each platform: 100,000 log emissions; latest 1,024 retained in order; captured snapshot unchanged
  after another write.
- Each platform: 1,000 commands with frozen deadlines and 1,000 behind a parked load; bounded
  authority, explicit failure, new-generation liveness, and inert delayed predecessor completion.
- Existing deterministic ride/deferred/obligation/coexistence/mailbox tests remain part of CI.
  The real-TLS reconnect suite exercises 50- and 100-cycle persistent-pair runs; it is network
  integration evidence, separate from deterministic virtual-time endurance.

## Privacy, security and CI

No cloud, login, analytics or telemetry dependency was introduced. Production source search
found no HTTP endpoints/API clients. Android's declared runtime groups remain native AndroidX,
Kotlin and pinned WebRTC; Conscrypt is test-only. Swift external packages remain the exact
WebRTC and GRDB pins. Build/download URLs are not application runtime endpoints.
`tools/audit_local_only.py` (Python 3.11+) makes new source HTTP URLs or unreviewed catalog/package
groups fail CI. This is a source/manifest policy check, not a packet capture or an assertion
about every instruction in a binary WebRTC distribution.

Existing tests verify TLS 1.3, SPKI pin mismatch refusal, no silent re-pair, trust-gated voice,
host-only ICE without STUN/TURN, connection-bound bulk transfers, discovery TXT privacy and
identifier/path redaction. No authentication, permission or encryption rule was relaxed.
Gitleaks scanned 210 baseline commits with no findings; it is also committed as a CI job.

New security workflow: Gitleaks, local-only source policy, CodeQL manual builds for Kotlin/Java
and Swift, and PR Dependency Review failing on high/critical vulnerabilities. Dependabot groups
weekly action updates and monthly Gradle/Swift updates, with low open-PR limits. No automerge.
Actions are SHA-pinned. The repository is public; GitHub documents CodeQL and Dependency Review
availability for public repositories. A move to private would require re-checking plan eligibility.
Dependency Review covers dependency-graph data it receives; it is not a guarantee of full binary
SDK coverage. CodeQL uses built source, not no-build Java analysis that would omit Kotlin.

References: [CodeQL compiled languages](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/manage-your-configuration/codeql-for-compiled-languages),
[Dependency Review](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependency-review).

## Backlog and documentation audit

The production search for TODO/FIXME/HACK/TEMP/temporary/not implemented/future/placeholder
returned 56 lines. There were no outstanding TODO/FIXME/HACK/TEMP directives. Most hits describe
actual temporary files, temporary gain, library placeholders, or future-proofing invariants.
Those are implementation descriptions, not work to implement. PlayerModels' reference to a
“future session-time scheduler” is obsolete now that Phase 5 uses those fields and is corrected.

Intentional V1 limitations remain: no VOX level source, no resumable partial transfers, no
hardware-qualified Bluetooth/audio claims, and the dormant fatal-error FSM branch recorded in
STATUS problem 55. Implementing unrelated future features is outside Phase 8.

README and the current-phase entry points in STATUS/CLAUDE now acknowledge the accepted Phase 7
merge. ARCHITECTURE/TEST_PLAN and ADR-029 record Phase 8's recovery/retention changes. PROTOCOL's
session_id continuity claim is corrected against both existing handshakes (problem 51).
REQUIREMENTS remains read-only; no product scope or timing ladder changed.

## Validation ledger

Results are updated after the final source changes and exact-head CI. Do not infer a pass from
a test being listed above.

| Gate | Current evidence |
|---|---|
| Android unit/static/build | Round 2: 1,076 unit tests passed (app 269, audio 33, core 459, data 31, network 284) — counted from the JUnit XML reports, not estimated. Full ktlint, detekt, lint, assembleDebug and assembleRelease passed with JDK 21. The suite was additionally re-run four times, three with `--rerun-tasks`. One run taken immediately after the 2-minute emulator suite showed a transient double failure (`PairingSessionIntegrationTest`, `VoiceControllerIntercomTest` — both real-socket/real-timing suites); neither reproduced in five targeted re-runs of those two classes nor in the four subsequent full runs, and both are unrelated to this pass's changes. Recorded rather than discarded |
| Android emulator | 50 instrumentation tests passed on the API 36 emulator: app 5, audio 11, data 34. The app suite includes 20 activity recreation/foreground cycles |
| iOS Core | Round 2: 353 tests passed |
| iOS Platform | Round 2: 647 tests, 0 failures, 1 skipped — the skip is the interop gate's iOS half, which is inert without the orchestrator |
| iOS Simulator builds | Round 2: Debug and Release unsigned simulator builds passed |
| Cross-platform software integration | **PASS.** `tools/crossplatform/run.sh`: the Swift and Kotlin implementations as two processes joined by a real TCP socket carrying the real protocol. Three consecutive passes. See “Cross-platform software integration gate” below |
| Interactive emulator ↔ simulator UI journey | **ENVIRONMENT LIMITATION — not a product failure.** The UI-control tool cannot attach to Simulator, so no interactive lifecycle pass is claimed. It is not folded into the hardware-deferred list: it is a tooling gap on this machine |
| GitHub Actions / PR | Draft [PR #6](https://github.com/arunachaleswaranms/RideLink/pull/6). Round 1 history: at `1e6e889` security run 35684841003 passed all five jobs while CI run 35684840900 failed iOS at reconnect cycle 93; the reset fix was validated by CI run 35712994331 and Security run 35712994259 at `fcd5851`. Round 2's first push (`6463eff`) passed iOS and failed Android on ktlint in the new interop test — a local full-gate run had aborted at an earlier task and never reached ktlint, which is why CI caught it and this record says so. **Consult the PR checks for the exact-head result**; this document deliberately names no run id for its own head, because recording one would require a commit that changes it |

The cross-platform software gate is closed by a live two-implementation session; the interactive
UI journey is an environment limitation and is stated as one. Software closure still requires
independent review of the live PR and exact-head CI, which is what this record is submitted for.

## Physical gates

Every item below is **DEFERRED — HARDWARE NOT AVAILABLE**:

- Android ↔ physical iPhone; real cross-device mDNS and hotspot behavior.
- Real iPhone audio session and background/lock behavior.
- Helmet speaker/microphone, pillion TWS/headset, and Bluetooth route switching.
- Audible synchronization, duck/pause perception, and hardware latency.
- Battery, thermal behavior, and a real two-hour ride.

The available OnePlus Nord 5 cannot close any cross-device or iPhone gate. No physical result is
claimed by this phase's software evidence.
