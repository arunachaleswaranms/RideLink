# ADR-029 — Release hardening at recovery and retention boundaries

Date: 22 September 2026. Status: **accepted** — merged with Phase 8 (PR #6, `2aa728f`). Proposed
for independent review with Phase 8; the amendments below record that review.

## Context

Phase 7 is the accepted baseline. Phase 8 tests exposed four concrete defects:
End Ride was rejected while the riding screen remained visible during recovery; production
in-memory logs grew for the entire process lifetime; bounded wire queues fed unbounded
apply/scheduled task chains; an early state request arriving during iOS connection reset was
retained but never answered. A frozen-deadline test retained 300 live tasks after 300 pauses.

## Decision

1. End Ride during `RECONNECTING(returnTo=RIDE_ACTIVE)` changes only the recovery destination
   to `CONNECTED`, while the existing ride owner retires playback authority. End Ride from
   `DISCONNECTED` uses `ENDING` and its existing joined teardown. Duplicate End Ride during
   recovery to `CONNECTED` remains rejected. SwiftUI observes the entire FSM value, because
   the first transition changes `returnTo` without changing `status`.
2. The process log sink retains the latest 1,024 events in chronological order. Snapshot reads
   and writes are synchronized; no external callback occurs under the sink lock. Logs are
   diagnostics, never an authority ledger. No persistence or upload is added.
3. Apply and scheduled playback chains share a limit of 256 live nodes. Before adding a node,
   prove its original control generation. Overflow synchronously retires playback authority:
   clear role, supersede playback/request/mode tokens, cancel both chains and correction,
   discard deferred obligations and pending work, and clear authoritative identity/sequence
   claims. Report `TRANSPORT_FAILED` through the existing “Sync unavailable” presentation.
   The already-playing local audio continues at rate 1.0. A fresh authenticated connection is
   required to establish synchronization again. The refusal never waits for a parked decoder.
   A delayed predecessor retains its original tokens and cannot affect the successor.
4. Ride Mode displays existing sync state with ordinary language. Connection loss outranks
   any late synchronized-status publication. No second player or coordinator is introduced.
5. Correct PROTOCOL §2/§10's stale session-id continuity claim: both handshakes already mint
   a fresh id on reconnect. Authentication and recovery use SPKI trust, authentication
   generations and reconciliation identities, not an envelope id. STATUS problem 51 was a
   documentation mismatch; this decision changes no wire shape, codec, or handshake behavior.

6. iOS connection setup considers requests admitted both before and during its suspended reset.
   Select the newest original generation, clear the slot before replying, and prove that generation
   against the established connection. No request acquires authority from current state. Three
   gated regressions cover live admission and competing retired requests.

## Consequences and verification

Shared session-FSM vectors cover both new transitions. Mirrored lifecycle tests run 1,000
sessions with three rides and recovery variants; 100,000 log events prove bounded retention
and snapshot isolation. Coordinator tests force 1,000 commands against a frozen deadline,
then prove fresh-connection liveness. A parked non-cancellable load exercises overflow followed
by successor authority and delayed predecessor completion. Drift tests advance 2.5 virtual
hours through 1,800 real coordinator ticks, including route-transition/nudge interleavings.

The node bound is on live authoritative work, not a measurement of every runtime task or
resident memory. A non-cancellable platform callback may remain suspended after authority is
retired; correctness depends on its original lifetime proof when it returns. Tests must report
those two facts separately. No simulator result closes a physical-device gate.

---

## Amendment A1 — 22 September 2026 — independent review: the bounded-work decision, and the software gate

Status: accepted.

### Decision 3 is superseded by ADR-024 Amendment A11

Independent review confirmed that decision 3 above solved a real problem in a way that could
**abandon authority the peer had already been given**. The chain-node limit was checked where the
node is created, which on a leader is the outbound commit hook — after `send` returned true. Its
overflow path then cleared `role`, `lastAppliedSeq`, `lastReceivedSeq`, the timeline and the
ride-scoped identity for a command the follower was about to apply, and published
`TRANSPORT_FAILED` for a transport that had just succeeded.

The bound itself stays, and stays at 256. What moves is *where the question is asked*: capacity is
now **reserved before an authoritative command can be delivered** and spent by the work that
delivery obliges. [ADR-024 Amendment A11](ADR-024-synchronized-playback-integration.md#amendment-a11--22-september-2026--local-work-capacity-is-reserved-before-delivery-never-refused-after-it)
is the decision; it also introduces `SyncState.LOCAL_OVERLOAD`, because a refusal that never offered
anything to the transport must not be reported as a transport failure. Read decision 3 above as
history.

### The cross-platform software integration gate is closed

Phase 8's own evidence recorded the interactive emulator ↔ simulator journey as NOT VERIFIED and
left the software gate outstanding. Independent review was right that this is a *software* gate and
may not be moved into hardware debt.

`tools/crossplatform/run.sh` closes it by running the Swift and Kotlin implementations as **two
processes on one machine joined by a real TCP socket carrying the real RideLink protocol** —
`RideLinkPlatformTests.CrossPlatformInteropTests` and
`com.ridelink.network.interop.CrossPlatformInteropTest`, both inert unless the orchestrator supplies
the shared report directory. Neither is a vector comparison: every byte between them is produced and
consumed by production code.

It establishes, cross-language and cross-implementation:

- a real TLS 1.3 handshake with mutual authentication between an ECDSA P-256 identity issued by
  Kotlin's `IdentityIssuer` and one issued by Swift's, each pinned by `identity_spki_sha256`;
- **PROTOCOL §4.5's six digits, derived independently from each side's own TLS exporter, are
  identical** — the assertion the protocol structurally cannot make, because §4.5 has two humans
  compare them out loud, and the direct cross-platform statement of ADR-018;
- one agreed `session_id`, exactly one ADR-010 leader, and one pin persisted per side;
- ARCHITECTURE §7.1's real `PING`/`PONG` burst converging on both estimators over the real socket;
- `PLAY`, `QUEUE_SNAPSHOT`, `STATE_REQUEST`, `STATE_SNAPSHOT` and `PLAYBACK_STATE` encoded by one
  platform's production codec and decoded field-for-field by the other's;
- a link loss and reconnect that re-authenticates **silently** on the stored pin — no second
  six-digit prompt on either side — and mints a strictly greater authentication generation on both,
  with a subsequent frame accepted under the successor generation.

**What it deliberately does not establish**, and must not be read as: no UI is driven and no app is
launched, so the interactive emulator ↔ simulator journey remains an environment limitation rather
than a passing gate; and nothing here touches Bluetooth, audio, iPhone background behaviour or a
physical device. The Kotlin half also runs on the JVM against Conscrypt rather than on a device
against Android's own TLS stack — the pre-existing limitation
`docs/test-results/phase1b-security-spike-20260827.md` records, neither closed nor hidden by this
gate.

It is deliberately **not** in CI: it starts two toolchains and a real socket, and CI already runs
both suites separately. It is a re-runnable local gate, recorded with its measured result in
`docs/PHASE8_RELEASE_HARDENING.md`.

## Amendment A2 — 27 September 2026 — diagnostics export and sideload provenance

Status: **proposed** — Phase 9A prerequisites (STATUS §4 problems 107 and 109), pending independent
review.

### Context

REQUIREMENTS NFR-08 says local diagnostic logs "shall be exportable", and §13's Phase 8 row asks for
"diagnostics export … repeatable sideload builds". Neither was delivered (problem 107). Phase 9 is a
field exercise, and its primary evidence is ARCHITECTURE §3 rule 5's transition log. Decision 2
above bounds that log and says "no persistence or upload is added". This amendment adds exactly one
way out of the process, and it is initiated by the user.

### Decision

1. **Export is a user-initiated share of the existing redacted sink, and nothing else.** A pure,
   mirrored formatter (`core.logging.DiagnosticsExport` / `RideLinkCore.DiagnosticsExport`) renders a
   provenance header followed by one line per retained `LogEvent`. The header holds platform, app
   version, source revision and export time as a monotonic timestamp: no device name, no identity,
   no peer and no wall-clock read (the X.509 exception stays the only one). Newlines inside an event
   are escaped, so a message cannot forge a header or a second event. The export adds no data
   source, and therefore no log path. SAS codes, TLS secrets, exporter output, tokens and key
   material still have no API into the sink. No upload, background export, analytics or network
   path is added. Decision 2's "no upload" stands; its "no persistence" now has one narrow,
   user-initiated exception, stated in item 2.
2. **Platform hand-off.** Android writes each rendered snapshot to a unique
   `cacheDir/diagnostics/ridelink-diagnostics-<random UUID>.txt` file and shares its distinct URI
   through a **non-exported** `androidx.core` `FileProvider` restricted to that directory, with a
   read-only, non-prefix grant on the one `ACTION_SEND` intent. An old URI grant cannot access a
   later snapshot. At most four snapshots remain: before each write the oldest export files are
   deleted, and deleted names are never reused for new bytes. This bounds cache storage without
   claiming that active grants are revoked. A file is used rather than `EXTRA_TEXT` because a
   full log can exceed a Binder transaction. iOS uses `ShareLink` with a `Transferable` that renders
   lazily, in memory, when the target asks for the data. Neither adds a dependency: `FileProvider`
   ships in `androidx.core`, which is already approved and used.
3. **A redaction gap found while building it is fixed (problem 109).** On both platforms
   `SessionCoordinator` logged `AdvertiseState.Advertising` through the type's default description,
   which printed the **full 32-hex discovery handle** and the instance name (which carries 8 hex of
   the same handle), against ARCHITECTURE §11 item 3's 6-hex rule. The handle is ephemeral and already
   public in mDNS, so the harm was small, but an export would have carried it off the phone. The
   carrier now redacts itself, the way the identifier types do: `dh:` plus 6 hex, and no instance
   name. The fix is on the type, so every interpolation is covered, not only the one call site.
4. **Sideload provenance.** A qualification build must be traceable to one commit.
   `tools/sideload/android.sh` refuses a dirty tree, builds `assembleRelease` with
   `-Pridelink.sourceRevision=<HEAD>`, which the Gradle file validates as 40-hex before compiling it
   into `BuildConfig.SOURCE_REVISION`, then aligns, signs with a local key and verifies. After install
   it proves the phone's installed bytes equal the built APK. The signing key is a PKCS12 file outside
   the repository, and its random password is kept in the login Keychain and never printed or passed
   as an argument. Signing is deliberately **not** a Gradle `signingConfig`, so no key path or
   password property ever needs to exist in the build. iOS takes the same revision from a
   `RIDELINK_SOURCE_REVISION` build setting, which the command line sets and the project file does
   not, into the `RideLinkSourceRevision` Info.plist key. Any build that did not come from the
   procedure exports `source_revision: unrecorded`, and the formatter renders anything that is not
   exactly 40 lowercase hex the same way. The procedure is `docs/SIDELOAD.md`.

### Alternatives rejected

- **A runtime scrubber** that pattern-matches six-digit codes or hex runs out of the export. The
  timestamps are digits, and it would turn "has no log path" into "is probably filtered". The
  property stays structural, and the tests pin its premise.
- **A Gradle `signingConfig` reading a local `keystore.properties`.** It works, but it puts a
  password-bearing file on the build's configuration path. Post-build `apksigner` keeps the build
  itself key-free.
- **Exporting the diagnostics cards as well.** They are live UI state, not the NFR-08 log, and every
  added field would be a new thing to prove redacted. It can be added later, deliberately.

### Verification

`DiagnosticsExportTest` / `DiagnosticsExportTests` pin the same golden text (identical apart from
the platform line; no shared vector file, because the format is not a wire contract), provenance validation,
newline escaping, render-time reads and the retention bound. `SessionCoordinatorDiagnosticsExportTest`
drives every identifier-bearing Android `SessionCoordinator` log site through its real entry point
with fabricated full-length values, and proves the rendered export holds only 6-character prefixes.
Its source scan pins the premise: `SessionCoordinator` is the only production logger, and no log call
interpolates a SAS/prompt, token, secret, exporter or key value. The iOS app target has no test
bundle (problem 48), so iOS proves the redaction at the type (`DiscoveryPrivacyTests`). Its
coordinator's eleven log sites were enumerated by hand: they interpolate only the self-redacting
`PeerId`/`SpkiHash`, the now-redacting `AdvertiseState`, PROTOCOL §4.6 codes, FSM states and events,
an intercom refusal code and a boolean. Each redaction regression
fails with its fix neutralised. No wire format, vector, state machine or security rule changed.
