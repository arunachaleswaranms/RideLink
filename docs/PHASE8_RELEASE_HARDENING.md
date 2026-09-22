# Phase 8 — Release Hardening & Software Integration

Baseline: independently reviewed Phase 7 merge
`48b7a8e5d07fe52010d05c1893d3f914722d80f0`.
Branch: `phase8/release-hardening`. No merge is authorized.

This is the current audit record; STATUS's older implementation-pass narratives are historical.
Software closure requires independent review of the live PR and exact-head CI. Physical
validation remains **DEFERRED — HARDWARE NOT AVAILABLE**.

## Changes and defect evidence

| Defect | Reproduction and root cause | Fix | Regression |
|---|---|---|---|
| End Ride does nothing during recovery | Ride Mode remains visible in reconnect/disconnected, but the pure FSM rejects EndRide in both states | Reconnect toward CONNECTED after ending a ride; exhausted recovery enters existing ENDING teardown. iOS observes the full FSM, including returnTo | Two shared vectors failed before the fix; mirrored 1,000-session lifecycle tests cover duplicate refusal, recovery destination and terminal restart |
| Diagnostic retention grows indefinitely | Emit 100,000 events into the production sink used by both AppContainers; all remain retained | Latest 1,024 events only; chronological snapshots; synchronized reads/writes | Mirrored LogRetention tests; original implementation failed both count and retained-window assertions |
| Bounded wire queues feed unbounded task chains | Keep the injected deadline clock fixed and commit 300 PAUSE commands; iOS retains 300 live scheduled/apply tasks | Combined 256-node limit; overflow retires authority synchronously, invalidates original tokens and reports Sync unavailable. Local audio continues; new authenticated connection restores eligibility | Mirrored 1,000-command tests; parked non-cancellable load, fresh connection, successor track, then predecessor release. Original iOS test observed 300 > 256 |
| Riding surface omits synchronization failure state | Ride Mode labels connection but provides no sync/content-wait/failure explanation | Derive a short label from existing sync diagnostics; connection loss takes precedence | Both presentation suites exhaust all sync states under disconnected/reconnecting |
| Reconnect stress harness can advance before event forwarding completes | Its readiness check reads a generation published inside handleConnected before resync.onConnected has completed; a subsequent cycle can overtake that task | Track the completed forwarding generation in the test rig; report the exact pending cycle and dump state on timeout | Isolated 100-cycle test and the complete platform suite; no production timeout increased |

Fresh-fix checks: no external callback executes under the new log lock; snapshot readers retain
independent values. Overflow does not wait for the parked decoder, mint successor ownership, or
publish rejected work as applied. Chain-node ids remain monotonic, so old completion cannot erase
a new node. The parked-apply test awaits the actual predecessor task, not a fixed number of yields.
A successor's apply task is also awaited before clearing the test's effect recording.

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
| Android unit/static/build | Full unit run passed; ktlint passed. Follow-up detekt/lint/Debug/Release build passed after an explicit ReturnCount suppression for the three-outcome admission guard |
| Android emulator | All 5 app instrumentation tests passed, including 20 activity recreation/foreground cycles; other module instrumented tests are included in the Gradle run |
| iOS Core | 345 tests passed before the final shared-capacity constant addition; final run pending |
| iOS Platform | Full run: 637 tests, zero failures after the forwarding completion latch; additional parked-apply fresh-fix test pending final verification |
| iOS Simulator builds | Debug built and installed; launch returned a process id. Release/final Debug rebuild pending |
| Interactive emulator ↔ simulator journey | Not yet verified. The UI-control tool could not attach to Simulator; launch/build success is not an interactive lifecycle pass |
| Exact-head GitHub Actions / PR | Pending push and PR creation |

## Physical gates

Every item below is **DEFERRED — HARDWARE NOT AVAILABLE**:

- Android ↔ physical iPhone; real cross-device mDNS and hotspot behavior.
- Real iPhone audio session and background/lock behavior.
- Helmet speaker/microphone, pillion TWS/headset, and Bluetooth route switching.
- Audible synchronization, duck/pause perception, and hardware latency.
- Battery, thermal behavior, and a real two-hour ride.

The available OnePlus Nord 5 cannot close any cross-device or iPhone gate. No physical result is
claimed by this phase's software evidence.
