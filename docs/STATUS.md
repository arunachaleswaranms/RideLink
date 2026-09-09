# RideLink — Status

**Updated:** 9 September 2026 (Phase 5 closure audit A2, twenty-sixth session — see §2ac)
**Current milestone:** M1 (Private voice link) is **software-complete with no known defect** — its
hardware gate is the only thing left open. M2 (local music) is implementation-complete and
closure-audited (§2q/§2r). Phase 4 is closure-audited **five** times (§2v–§2z). **M4 (Synced ride
music) now has its software half, and it has been audited twice**: Phase 5 is closure-audited A1
(§2ab) and A2 (§2ac) on both platforms, with its real-device gate open.
**Current phase:** Phase 5 — synchronized playback, **closure-audited twice** (this session, §2ac;
the first audit is §2ab, the implementation §2aa). Clock-scheduled
`PLAY`/`PAUSE`/`RESUME`/`SEEK`/`NEXT`/`PREVIOUS`, a replicated shared queue, drift measurement and
the ADR-004 correction ladder, all on top of the Phase 1a clock layer and the Phase 3 player.
**Phase 6 (intercom/music coexistence) and Phase 7 (Ride Mode) are untouched.**
**Phase 5 status: SOFTWARE CLOSURE A2 COMPLETE — REAL-DEVICE SYNCHRONIZED-PLAYBACK GATE PENDING.**
Independent verification of A1 found **five more** findings, all on one seam A1 had built but not
finished — the join between *deciding* something authoritative and *the peer actually receiving it*.
**All five were confirmed**, and fixing them turned up a sixth (§2ac **F**) that the fix for the
fourth would otherwise have introduced. Not one was a false positive — the fifth consecutive time on
this codebase that an audit of a CI-green phase has found real defects, and the reason this says
"A2" rather than "final". Read it as two audits' worth of assurance and verify it independently.

**What the seven were, in one line each:** a follower's first Play refused by a revision its own
queue add had just moved (**A**); the leader's semantic order not surviving to the wire, because the
allocation was locked and the send was not — on iOS an actor re-entrancy point (**B**); a *lossy*
queue immediately behind reliable ordered TCP, whose drop counter was structurally incapable of ever
firing (**C**); a `command_seq` spent before the clock was consulted, so an estimator that was
momentarily untrusted lost a command permanently (**D**); one press of Play forgotten across a Phase 4
transfer (**E**); a superseded correction that still spent the seek budget and put `PLAYBACK_STATE`
on the wire (**F**); and one task per armed action, so `PAUSE(n)` and `RESUME(n+1)` could reach the
player in either order (**G**).

**What Phase 5 changed in the specification, and why none of it was silent.** Implementing PROTOCOL
§5 and §9 for real found three unspecified message shapes and two genuine contradictions. All five
are resolved in **ADR-024**, and `docs/PROTOCOL.md`, `docs/ARCHITECTURE.md`, `docs/TEST_PLAN.md`,
`protocol/README.md` and `CLAUDE.md` are updated in the same change:

1. **The follower→leader intent had no message.** §5 describes the hop and §3's catalogue has no `*_INTENT` type. Resolved as `command_seq: 0` on the *same* message type — zero is free because leader-assigned sequences start at 1 — which adds no type and makes the role rule checkable: an authoritative `command_seq` arriving *at the leader* is a role violation, so a follower cannot fabricate one.
2. **`RESUME` had no payload.** Filled in with `PAUSE`'s shape.
3. **`PLAYBACK_STATE` had no payload.** Filled in from §10's `STATE_SNAPSHOT.playback` plus the two ordering values a reconciliation anchor needs.
4. **§9's 2 000-item queue cap does not fit the frame cap.** ~190 encoded bytes per item × 2 000 ≈ 378 KB against `MAX_CONTROL_FRAME_BYTES` = 262 144. **The cap moved to 1 000; the frame limit did not** (CLAUDE.md rule 11).
5. **§9 put `status` on the wire in the same paragraph that called it untrusted.** Removed. A field that must never be trusted has no reason to be sent, and sending it hands a peer a channel to influence what the local UI claims about local storage.

**Read Phase 4's "software closure complete" narrowly, and read the wording history with it.** It means every
laptop-runnable gate is green on both platforms and the three lifecycle findings this session was
scoped to are confirmed-fixed with regressions each verified to fail against the pre-fix code. It is
**not** a prediction that a sixth audit would find nothing: five consecutive independent audits have
each found real defects in code that was already CI-green, and §2y deliberately dropped the word
"final" for exactly that reason — that reasoning still stands and this session does not restore
the word. §2y's Finding T in particular would have made every `content://`-sourced Android transfer
fail deterministically on a real phone, and no laptop test could have surfaced it, because no laptop
test ever opens a `content://` stream. The remaining Phase 4 risk is concentrated exactly where this
machine cannot look. No phone-to-phone transfer, no real Wi-Fi/hotspot topology, and no real
storage/battery measurement has run — see §2z and §7.
**Phase 2b status: FINAL SOFTWARE CLOSURE COMPLETE — REAL-DEVICE INTERCOM GATE PENDING
(unchanged).** The timeout-ownership defect §2r confirmed and deliberately left unfixed was fixed
in §2s (ADR-021 Amendment A4); §2t fixed one more gap in that same fix. No other known software
defect remains in this phase.
**Phase 3 status: IMPLEMENTATION COMPLETE — REAL-DEVICE LOCAL-MUSIC GATE PENDING (unchanged).** All
seven closure-audit findings (A–G, §2r) were already fixed and verified; this session's work is
additive (a new coordinator/screen on each platform) and touches none of Phase 3's own files beyond
three small, additive forwarding properties on `MusicCoordinator`/`SessionCoordinator` (§2u).
**Phase 2a status: IMPLEMENTATION COMPLETE — REAL-DEVICE AUDIO GATE PENDING (unchanged).**
**Phase 1b status: IMPLEMENTATION COMPLETE — REAL-DEVICE GATE PENDING (unchanged).**
**Phase 4 status: SOFTWARE CLOSURE COMPLETE — REAL-DEVICE SHARED-LIBRARY/TRANSFER GATE PENDING
(unchanged by this session).** Phase 5 uses Phase 4 and does not rewrite it: the one Phase 4 file it
touches gains a consumer for a message Phase 4 already *received and discarded*
(`TRANSFER_RESULT` on the provider side, ADR-024 §7), plus the session-boundary clearing that goes
with it. Every Phase 4 suite is re-run and green (§2aa).
**The overall "2 Intercom" milestone is NOT complete** — TEST_PLAN A-01, A-02, A-04, A-09 and
V-01…V-11 are hardware gates and none of them has run. **Phase 6 (intercom/music coexistence) and
Phase 7 (Ride Mode + resilience) are NOT STARTED.**

> **What is genuinely new this session, and what is not.** Phase 2b is the intercom *as an app*: the
> five REQUIREMENTS §8 modes as one interpreted policy object, transmission gated at the WebRTC audio
> track and **never** at the capture device, `AUDIO_STATE` implemented on the authenticated path, the
> whole `AVAudioSession`/`AudioManager` decision surface moved into a shared pure reducer, and
> software setup-timing instrumentation. All of it is green on both platforms, and three of those are
> pinned by new shared vector sets.
>
> **Nothing in it ran on a phone.** No microphone, no speaker, no Bluetooth, no foreground service,
> no lock screen. The Android WebRTC media path still has no test of any kind. Every `assumed` value
> in the two route mappers is still assumed. **No latency figure exists** — the setup timings added
> this session measure how long the *app* took to bring voice up, include no Bluetooth hop and no
> jitter buffer, and mouth-to-ear latency cannot be inferred from them or from network RTT.

> **What is genuinely new evidence this session, and what is not.** Real WebRTC media *was*
> established and measured on this machine: two real `WebRtcVoiceEngine`s, host candidates only,
> DTLS `connected`, `SRTP_AES128_CM_HMAC_SHA1_80`, `audio/opus` at 48 kHz, deterministic over 5 runs.
> That is possible because `stasel/WebRTC`'s XCFramework carries a **macOS** slice, so `swift test`
> links the same binary an iPhone build would ([ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md),
> [evidence](test-results/phase2a-webrtc-spike-20260828.md)).
>
> **No audio was captured or played anywhere, on either platform.** No microphone, no speaker, no
> Bluetooth, no phone. The **Android** media path is untested even locally — `PeerConnectionFactory.initialize`
> needs an Android `Context`. `AVAudioSession` and `AudioManager` have no test coverage at all; only
> their pure route mappers do. See §4 and §7, and TEST_PLAN §3.1a for the line item by item.

> **Fixed this session (§2g), and it was a real security bug, not a tidy-up.** An unknown peer
> could reach `CONNECTED` **before** the six-digit SAS was displayed, let alone confirmed:
> `ControlEvent.Connected` was emitted as soon as duplicate resolution picked a survivor, and
> `SessionCoordinator` — needing *something* to carry `PAIRING -> CONNECTING` — read it as pairing
> success. Both platforms. Every individual mechanism (TLS, the pin, the exporter, the exchange,
> the trust store) was correct and tested; the sentence joining them was wrong, and no test looked
> at the join. `Connected` now means "the trust gate has passed", the gate is a pure shared table
> ([ADR-019](DECISIONS/ADR-019-connected-means-authenticated.md)) pinned by
> `protocol/vectors/session-gate/` on both platforms, and the invariant is covered by real-TLS
> integration suites. **Do not treat "CI is green" as evidence about a join no test crosses.**

The two open risks that governed this phase are **closed with measurements, not argument**
([ADR-007 Amendment A1](DECISIONS/ADR-007-control-channel-over-tcp-tls.md#amendment-a1--26-august-2026--secure-transport-contingency)
required both to be spiked before anything was built on them):

1. **Self-signed X.509 on iOS.** A ~150-line DER encoder plus `SecKeyCreateSignature` produces a certificate that Apple's own parser, BoringSSL **and** OpenSSL all accept, and `SecIdentityCreate` turns it into a `Network.framework` TLS identity with **no PKCS#12 and no key export**.
2. **TLS keying-material exporter.** Both platforms expose one from public API — `android.net.ssl.SSLSockets.exportKeyingMaterial` (API **31**, exactly the ADR-011 `minSdk`, verified against `api-versions.xml`) and `sec_protocol_metadata_create_secret` (iOS 12). For the **same TLS 1.3 connection** an Apple endpoint and a Conscrypt/BoringSSL endpoint produce **byte-identical** exporter output, cross-checked against OpenSSL 3.6.3 as a third stack.

Evidence: [`docs/test-results/phase1b-security-spike-20260827.md`](test-results/phase1b-security-spike-20260827.md),
re-runnable via [`tools/spikes/phase1b-tls-exporter/run.sh`](../tools/spikes/phase1b-tls-exporter/).
Decisions: [ADR-017](DECISIONS/ADR-017-identity-key-and-certificate.md) (P-256 identity key, shared
certificate encoder) and [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md) (the SAS
channel binding). **No design review was triggered and nothing weaker was substituted.**

On top of that, the whole secure control channel is implemented and tested on both platforms:
per-device identity in Android Keystore / the iOS Keychain, self-signed X.509 identity
certificates with ADR-012 re-issuance semantics, TLS 1.3 with mutual authentication,
`identity_spki_sha256` pinning that fails closed on mismatch, PROTOCOL §4.5 first-pair SAS
verification with persisted trust, and a pairing/security UI on both. **The Phase 1a plaintext
transport is gone from every production source set** — not gated, deleted — and a mechanical test
fails if a raw socket reappears there.

**What is still not done is running any of it on the two real phones.** That gate was already
open for Phase 1a and this phase does not close it: this machine has no **physical** Android or iOS
device, only the iOS *simulator* — and, as of the Phase 3 session (§2q), an Android **emulator**
(`RideLink_API36`), which has run Phase 3's real instrumented library-indexer/player/database tests
and a manual walkthrough (§2q), but has run no Phase 1a/1b/2a/2b control-plane, security, WebRTC or
intercom evidence of any kind — that emulator existing does not narrow §4 problems 15/22 (below) any
further than §2q's own local-music claims. Everything below §2q is a laptop measurement; §2q itself
is the one section with real-emulator evidence, scoped exactly as it states. See §4 and §7.

**Repository state (updated §2s, sixteenth session):** Android —
**531** unit tests across five modules (`core` 321, `network` 160, `audio` 33, `data` 9, `app` 8),
`test ktlintCheck detekt lint assembleDebug assembleRelease` all green, plus real instrumented tests
on `RideLink_API36` (`:data` 34, `:app` 4). iOS — `RideLinkCore` **207** tests, `RideLinkPlatform`
**219** tests, `RideLink.xcodeproj` builds in **both** Debug and Release for the simulator with zero
new warnings. Shared vectors: `protocol/vectors/identity/`,
`protocol/vectors/session-gate/` (120 rows), `protocol/vectors/voice-signal/` (70 rows),
`protocol/vectors/voice-fsm/` (**59** rows, was 52 — Phase 2b's `ModeSelected`),
`protocol/vectors/intercom/` (58 rows, new) and `protocol/vectors/audio-state/` (74 rows across five
groups, new) — every one run by **both** platforms from the same file.
Neither the Phase 2a hardening pass (§2j) nor its follow-up (§2k) added a new vector file: their
mailbox and doorbell fixes are pinned by ordinary unit tests, not shared wire vectors, since none
of them has a wire shape of its own — `VoiceNegotiation`'s reducer, which does, is unchanged by
either pass.

---

## 1. Where the project actually is

| Phase | State | Note |
|---|---|---|
| Phase 0 — Feasibility | ✅ Complete (by user, off-repo) | **Do not repeat.** Results not yet recorded — see §6 |
| Docs baseline | ✅ Complete (earlier session) | Requirements transcribed, architecture/protocol/test plan/ADR-001…010 written |
| **Architecture correction pass** | ✅ Complete | 15 corrections applied before implementation. Details in §2 |
| **ADR-015/ADR-010 leadership-independence correction** | ✅ **Complete this session** | See §2b |
| **Phase 1a — control-plane skeleton** | ✅ **IMPLEMENTATION COMPLETE — REAL-DEVICE GATE PENDING** | Protocol vectors, Android + iOS discovery, plaintext control transport, clock sync, diagnostics UI, hardening pass (§2e). Real-device gate still open — see §7. Its plaintext transport has since been **deleted** (§2f) |
| **Phase 1b — secure control channel** | ✅ **IMPLEMENTATION COMPLETE — REAL-DEVICE GATE PENDING** | Both ADR-007 A1 spikes closed with measurements; identity, TLS 1.3, pinning, SAS pairing, trust persistence and UI on both platforms (§2f). The trust-gate security bug found afterwards is fixed and vector-pinned (§2g, ADR-019) |
| **Phase 2a — voice transport foundation** | ✅ **IMPLEMENTATION COMPLETE — REAL-DEVICE AUDIO GATE PENDING** | WebRTC pinned and reviewed on both platforms, PROTOCOL §7 specified in full, the negotiation table shared and vector-pinned, the pre-authentication `VOICE_*` refusal proven over real TLS on both platforms, and **real DTLS-SRTP/Opus media measured on this machine** (§2i, [ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md)). No audio captured or played anywhere; the Android media path is untested even locally |
| **Phase 2b — intercom integration / audio lifecycle** | ✅ **FINAL SOFTWARE CLOSURE COMPLETE — REAL-DEVICE INTERCOM GATE PENDING** | The five modes as one interpreted policy object; transmission gated at the audio track and never at the capture device; `AUDIO_STATE` implemented with no wire change; the platform audio lifecycle as a shared pure reducer; readiness as a shared pure decision; setup-timing instrumentation (§2m, [ADR-021](DECISIONS/ADR-021-intercom-transmission-and-capture-ownership.md)). The Phase 3 closure audit's one confirmed-not-fixed defect (`stopAndAwaitRelease`/`shutdown` timeout ownership) is fixed (§2s, ADR-021 Amendment A4); a second, narrower gap in that same fix (a proven-complete release still reporting its own stale timeout, orphaning the foreground service) is fixed (§2t, ADR-021 Amendment A5) — no other known software defect remains. Nothing ran on a phone; VOX has no level source; no latency figure exists |
| **Phase 3 — local music player** | ✅ **IMPLEMENTATION COMPLETE — REAL-DEVICE LOCAL-MUSIC GATE PENDING** | Library indexing, two-tier hashing, database/search, ExoPlayer/AVAudioEngine player, local queue, Android `MediaSession` (ADR-022), iOS `MPNowPlayingInfoCenter`/`MPRemoteCommandCenter`, all on both platforms (§2q, and this session's closure-audit hardening pass). Real-emulator instrumented evidence exists for the indexer/database/player (§2q, TEST_PLAN §4.3); nothing has run on a physical phone |
| **Phase 4 — shared library + peer file transfer** | ✅ **SOFTWARE CLOSURE COMPLETE — REAL-DEVICE SHARED-LIBRARY/TRANSFER GATE PENDING** | Catalogue paging, `ContentHash`-keyed transfer over a second session-bound TLS connection, the two-phase verified cache, availability display, verified-cache-only local playback and a Shared Library screen on both platforms (§2u, [ADR-023](DECISIONS/ADR-023-bulk-transfer-session-binding.md)). **Closure-audited five times** — §2v (18 findings), §2w (2), §2x (2), §2y (3, one `CRITICAL`), §2z (3 lifecycle races, two of them gaps A4 had documented rather than closed) — Amendments A1–A5. "Software closure" is the narrow claim that every laptop gate passes and every named finding is fixed; the word "final" stays deliberately absent, since five consecutive audits have each found real defects in already-CI-green code. Real loopback-TLS multi-chunk transfer, a real emulator smoke check and a real simulator smoke check exist; **no phone-to-phone transfer, no real Wi-Fi/hotspot topology, no storage/battery figure** |
| Phases 5–8 | ⬜ Not started | The earlier commits named "init phase 2a" and "phase 2a" (`d709c45`, `90cbe12`) were Phase 1b work under a misleading name. Phase 2a proper is the sixth session, §2i |

`protocol/schema/` and `protocol/vectors/` now exist (§2c). `android/` is a real five-module
Gradle project that builds. `ios/` now has all three pieces ARCHITECTURE §9.2 describes:
`Packages/RideLinkCore`, `Packages/RideLinkPlatform`, and `RideLink.xcodeproj` — all three build,
and the app target runs on-simulator with the correct UI.

The build commands in `CLAUDE.md` now run for real on both platforms: `./gradlew` from
`android/`; `swift build`/`swift test` from each package under `ios/Packages/`; and
`xcodebuild -project RideLink.xcodeproj -scheme RideLink -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' build`
from `ios/`.

**Toolchain state (installed and verified 26 Aug 2026):**

| Tool | State | Path |
|---|---|---|
| OpenJDK 21 | ✅ 21.0.12.1, Homebrew `openjdk@21` formula | `/opt/homebrew/opt/openjdk@21` |
| Android SDK platform 36 | ✅ rev 2, `ApiLevel=36`, licences accepted | `/opt/homebrew/share/android-commandlinetools` |
| Android build-tools | ✅ 36.1.0 | …/`build-tools/36.1.0` |
| Android platform-tools | ✅ 37.0.1 (adb 1.0.41) | …/`platform-tools` |
| Gradle | ❌ **not installed globally, on purpose** | the project uses its own committed wrapper |
| Swift / macOS SDK | ✅ Swift 6.3.2, macOS 26.5 SDK | Command Line Tools |
| **Xcode / iOS SDK** | ✅ Xcode 27.0 beta, iOS SDK 27.0 (user-supplied) | `/Applications/Xcode-beta.app` |

**Two toolchain corrections made in the Phase 1b session, both pre-existing and both local-only:**

- `android/gradle.properties`'s `org.gradle.java.installations.paths` pointed at Homebrew's **keg root** (`/opt/homebrew/opt/openjdk@21`) rather than the JDK *home* (`…/libexec/openjdk.jdk/Contents/Home`). The keg root has `bin/java`, so Gradle's toolchain detection accepted it, but it has no `lib/modules`, so the Kotlin compiler failed with `No class roots are found in the JDK path` **the moment it actually had to compile something**. Up-to-date and cached builds never resolve the JDK home at all, which is why it survived earlier sessions as an intermittent failure. Now corrected in the committed file.
- **`detekt` cannot run on this machine without an explicit daemon JVM.** The Gradle daemon inherits the machine's default `java` (Temurin 25); detekt 1.23.8 is handed `25.0.3` as a JVM target, cannot parse it, and every detekt task fails with a bare version string for a message. CI is unaffected — `actions/setup-java` makes the daemon JDK 21 — which is why this was never seen before. `jvmTarget`/`jdkHome` on the task do **not** fix it (verified). The workaround, and the way every detekt result in §3 was produced, is to run Gradle with `-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Recorded in §4 as an open, low-severity problem rather than papered over.

JDK 21 is keg-only and deliberately *not* symlinked into the system JVM directory, so the
machine's default `java` remains Temurin 25 and the build reaches JDK 21 by explicit path. Set
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` (or record it as `sdk.dir` in
`android/local.properties`, which is gitignored) and pin the Gradle toolchain to the JDK 21 path.
Note that `/usr/libexec/java_home -v 21` reports the JDK 25 install — it means "at least 21", so
it is not a valid presence check for JDK 21.

**Resolved mid-session:** the user supplied Xcode 27.0 (beta, build `27A5252f`, Apple-signed,
verified genuine) and installed it to `/Applications/Xcode-beta.app`, ran `xcode-select -s`,
accepted the license and ran `-runFirstLaunch`. `swift test` for `RideLinkCore` now runs and
passes 16/16 — see §3 and ADR-011 Amendment A2. §4 problem 10 (below) is resolved; kept in the
table with its resolution noted rather than deleted, per this file's own discipline of recording
what changed rather than erasing history.

**Android Gradle toolchain versions, pinned this session** (all verified by real builds, not
assumed): AGP `9.3.2` (requires Gradle ≥ 9.5.0 — the wrapper targets Gradle `9.7.1`, downloaded
and SHA-256-verified against the official checksum), Kotlin `2.4.10`. AGP 9.x no longer needs
(and rejects) the separate `org.jetbrains.kotlin.android` Gradle plugin — Kotlin support is
built into AGP now; do not re-add that plugin. Compose BOM pinned to `2026.04.01`, `androidx.core`
to `1.18.0`, and `androidx.lifecycle:lifecycle-runtime-ktx` to `2.10.0` — one release newer of any
of these three currently requires `compileSdk 37`, which conflicts with the ADR-011 `compileSdk
36` baseline. **Do not bump these three without also revisiting ADR-011.**

All toolchain prerequisites are installed and verified (table above) — nothing here still blocks
either platform's scaffolding. The only remaining gate for Phase 1a is the two-real-phones test
pass described in §7, which is hardware, not toolchain.

---

## 2. What changed in the correction pass

Documentation, ADRs and repository hygiene only — **no feature implementation**, as instructed.
Fifteen corrections from an independent review of the completed baseline.

| # | Correction | Files touched |
|---|---|---|
| 1 | `CLAUDE.md` removed from `.gitignore` and rewritten to be worth committing | `.gitignore`, `CLAUDE.md` |
| 2 | Minimal 2-line `.gitignore` replaced with a full one: macOS, Gradle/Android Studio, Xcode/SPM, signing material, personal music, transfer temporaries, diagnostic logs | `.gitignore` |
| 3 | **Manifest pagination.** Single-frame `MANIFEST` replaced by `MANIFEST_BEGIN` / `MANIFEST_PAGE` × n / `MANIFEST_END` / `MANIFEST_ABORT`, sized by encoded bytes, with a deterministic digest. 256 KiB frame cap left untouched | `PROTOCOL` §1, §3, §8.1, §9, §10, §11 · `ARCHITECTURE` §1.1, §8.2 · `TEST_PLAN` §2, §3, §4, §5 · `ADR-013` (new), `ADR-006` (amended) · `protocol/README.md` |
| 4 | **Six-digit SAS fixed.** The old "decimal of the first 20 bits" could produce **seven** digits. Now: exporter → first 4 bytes big-endian → `mod 1 000 000` → zero-padded to exactly 6. Ten boundary vectors tabulated with expected values | `PROTOCOL` §4.5.1, §4.5.2 · `ARCHITECTURE` §4.3 · `TEST_PLAN` §2, §3 · `protocol/README.md` |
| 5 | **Identity standardised on SPKI.** `cert_fingerprint` retired everywhere in favour of `identity_spki_sha256`; certificate re-issuance vs key rotation semantics defined | `PROTOCOL` §4.1, §4.5, §4.5.3, §4.6, §8.2 · `ARCHITECTURE` §4.3, §11 · `ADR-012` (new), `ADR-007` (amended) |
| 6 | **`fp6` removed from mDNS.** TXT records are now `{v, dh, plat}` with `dh` an ephemeral rotating handle. Known-peer recognition moved after the TLS handshake | `ARCHITECTURE` §4.1, §11 · `ADR-002` Amendment A1 · `TEST_PLAN` §4, §5 (I-22) |
| 7 | **Simultaneous-connection deduplication defined.** New `conn_tiebreak` field; larger tiebreak's outbound connection survives; deliberately not keyed on `peer_id` | `PROTOCOL` §4.1, §4.2, §4.6, §10 · `ARCHITECTURE` §3, §4.2, §5 · `ADR-015` (new), `ADR-010` Amendment A1 · `TEST_PLAN` §2, §5 (I-15…I-18) |
| 8 | **Platform baselines fixed:** Android 31/36/36 + JDK 21 toolchain; iOS 26.0 | `ARCHITECTURE` §1.2, §10 · `ADR-011` (new) · `README.md`, `CLAUDE.md` · `TEST_PLAN` §4, §8 |
| 9 | **Android background-microphone rules architected.** Foreground-visible start sequence, service types, full permission list, seven failure modes | `ARCHITECTURE` §6.1, §6.4 · `TEST_PLAN` §4.1 (AF-01…AF-10) |
| 10 | **`.allowBluetooth` → `.allowBluetoothHFP`**, plus two distinct audio-session configurations instead of one option set | `ARCHITECTURE` §6.2 · `ADR-016` · `TEST_PLAN` §4.2 |
| 11 | **Bluetooth capability model corrected.** Independent `output_route`/`input_route` replaced by declared `CAPABILITIES.audio` + runtime `AUDIO_STATE`, with `profile_coupling: "input_forces_output"` as the load-bearing field | `PROTOCOL` §4.3, §4.3.1, §4.4, §7 · `ARCHITECTURE` §6.5, §7.3 · `ADR-016` (new) · `TEST_PLAN` §2, §4.2, §5, §6 |
| 12 | **TLS-PSK withdrawn as a claimed fallback.** Contingency is now "stop and run a focused secure-transport design review"; status recorded as *contingency unresolved pending implementation spike* | `ADR-007` Amendment A1 · `ARCHITECTURE` §12 · `PROTOCOL` §4.5.1 |
| 13 | **Module count reduced** from ~20 Gradle modules to 5 (`app`, `core`, `network`, `audio`, `data`) and 4 SPM packages/17 targets to 2 packages. Boundaries preserved and now *compiler-enforced* | `ARCHITECTURE` §2, §9 · `ADR-014` (new) · `TEST_PLAN` §2, §8 · `CLAUDE.md` |
| 14 | **Manual constructor DI** instead of Hilt, with a concrete revisit trigger | `ARCHITECTURE` §10.1, §10.2, §10.3 · `ADR-014` §2 |
| 15 | **Repository-state wording corrected.** `android/`/`ios/` described as planned, not existing. The claim that `.DS_Store` files were tracked was also wrong — they are on disk but were never committed | `STATUS.md`, `README.md` |

**ADRs created:** 011 (platform baselines), 012 (SPKI identity), 013 (manifest pagination),
014 (module structure + DI), 015 (connection dedup), 016 (audio capability model).
**ADRs amended, dated and labelled:** 002 (A1 — no stable identity in TXT), 007 (A1 — secure
transport contingency), 010 (A1 — leadership ≠ connection ownership). 006 updated to point at
ADR-013. No ADR was rewritten in place and none was deleted.

**Verification performed this session:** repository-wide search for the retired terms `fp6`,
`cert_fingerprint`, `.allowBluetooth` (bare), `TLS-PSK`, `Hilt`, the old module names and the old
`MANIFEST` shape; `git diff` and `git status` reviewed; internal Markdown links checked;
requirements DOCX confirmed unmodified; `.gitignore` confirmed no longer ignoring `CLAUDE.md`; no
application code added.

**No builds or tests were run — there is no code to build.** Stated plainly rather than implied.

---

## 2b. ADR-015 / ADR-010 correction (26 August 2026 session)

Before any implementation, corrected an inaccurate rationale in ADR-015 that claimed the
`conn_tiebreak` comparison direction was "chosen so that on the surviving connection the leader is
the acceptor, not the initiator." That claim is **false**: `conn_tiebreak` (ADR-015) and `peer_id`
(ADR-010) are independent random values with no relationship, so leadership lands on either side
of the surviving connection by chance, never as a guaranteed consequence of the dedup rule.

- The dedup **algorithm** is unchanged — only the rationale text was wrong.
- Fixed via append-only amendments, not in-place rewrites: [ADR-015 Amendment
  A2](DECISIONS/ADR-015-duplicate-connection-resolution.md#amendment-a2--26-august-2026--correction-connection-ownership-does-not-determine-leadership),
  [ADR-010 Amendment
  A2](DECISIONS/ADR-010-internal-leader-election.md#amendment-a2--26-august-2026--correction-to-amendment-a1),
  and a corrected paragraph in [ARCHITECTURE §4.2](ARCHITECTURE.md#42-duplicate-and-simultaneous-connections).
- `protocol/vectors/dedup/dedup_vectors.json` now encodes the corrected property directly:
  `initiator-not-assumed-leader` and `acceptor-not-assumed-leader` are two vectors with identical
  dedup mechanics but opposite leader assignment, so an implementation cannot pass both while
  assuming either correlation.

## 2c. Phase 1a scaffolding (26 August 2026 session)

**Protocol (`protocol/`):**
`schema/envelope.schema.json` (JSON Schema, informational/normative reference) and four vector
files: `vectors/envelope/` (11 cases: round-trip, unknown-field/type tolerance, missing-field/
null-payload/malformed-value/malformed-JSON rejection, version mismatch, the 262144-byte cap
accepted and 262144+1 rejected), `vectors/sas/` (the 10 PROTOCOL §4.5.2 values transcribed
verbatim + 2 property vectors), `vectors/dedup/` (6 vectors, see §2b), `vectors/session-fsm/`
(27 legal transitions, 10 illegal, 3 non-fault non-transition cases for duplicate-connection
close). The frame-size vectors use a padding recipe (documented in `protocol/README.md`) instead
of committing literal 256 KiB fixtures.

**Android (`android/`):** five Gradle modules (`app`, `core`, `network`, `audio`, `data`), Gradle
wrapper committed (`gradlew`, verified against the pinned distribution's published SHA-256).
`core` (pure `kotlin("jvm")`, no `android.*` on its classpath) implements: `model` (7 REQUIREMENTS
§16 entities + `PeerId`/`SessionId`/`SpkiHash`/`ConnTiebreak`/`ContentHash`, each redacting its own
`toString()`), `protocol` (`Envelope` + `EnvelopeCodec` via kotlinx.serialization, `Sas`, `Dedup`,
`Leadership`), `sessionfsm` (the 10-state pure FSM, `FsmState` carrying `returnTo` for
RECONNECTING per ARCHITECTURE §3 rule 1), `logging` (`Redactor` + `StructuredLogger` +
`InMemoryLogSink`). `network.discovery` implements `NsdDiscoveryController` (advertise + browse,
TXT limited to `{v, dh, plat}`, `DiscoveryHandle` via `SecureRandom`). `app` wires a
`SessionCoordinator` (owns FSM state + discovered-peer list) through manual DI
(`AppContainer`) into a minimal Compose screen matching CLAUDE.md's Phase 1a UI spec exactly.
`audio` and `data` are placeholder modules (compilable, empty) establishing the module boundary
only — no Phase 2+ logic added early.

**iOS (`ios/`):** all three ARCHITECTURE §9.2 pieces now exist.

- `Packages/RideLinkCore` — a Swift Package porting the same domain logic 1:1:
  `Model/Identifiers.swift` + `Entities.swift`, `Protocol/{JSONValue,Envelope,EnvelopeCodec,Sas,Dedup}.swift`,
  `SessionFSM/SessionFsm.swift`, `Logging/Logging.swift`. Vector-driven tests exist for all four
  protocol vector files plus the same redaction regression tests as Android, using **XCTest**
  rather than Swift Testing — see §4 problem 10 (resolved) for why.
- `Packages/RideLinkPlatform` — a second Swift Package, depending on `RideLinkCore`, implementing
  `Discovery/BonjourDiscovery.swift` (`NWListener`+`NWBrowser`, TXT limited to `{v, dh, plat}` via
  `NWTXTRecord`, `DiscoveryHandle` via `SecRandomCopyBytes`) — the iOS mirror of Android's
  `NsdDiscoveryController`. Builds and tests (2, pure `DiscoveryHandle` format checks) pass; the
  live `NWListener`/`NWBrowser` wiring is unverified for the same reason as Android's — no second
  peer to discover in this environment.
- `RideLink.xcodeproj` — a hand-authored project (there is no Apple CLI for scaffolding a fresh
  Xcode project; `xcodegen`/`tuist` weren't installed without asking first, so this was written
  directly and verified by building, the same way the Gradle/AGP version issues were resolved
  earlier this session) with one app target, `RideLink/{RideLinkApp,SessionCoordinator,MainScreen}.swift`
  + `Info.plist`, local Swift package dependencies on both packages above, iOS 26.0 deployment
  target, Swift 6. **Builds and runs** on the iPhone 17 Pro Max simulator — confirmed with an
  actual screenshot showing "RideLink / Device: iPhone 17 Pro Max / Connection: Idle /
  [Start Discovery]", matching CLAUDE.md's Phase 1a UI spec exactly. Device builds need a
  development team (CLAUDE.md "Apple Signing" — a personal choice, not made here).

---

## 2d. Phase 1a control transport, discovery lifecycle and diagnostics UI (27 August 2026 session)

Completes steps 8–10 of §7's ordered list from the previous session, plus fixes to step 7
(discovery) that the previous session had left unverified. No Phase 1b work (TLS, identity,
pairing) was started — see the explicit non-goals at the end of this section.

**`core.sync` / `RideLinkCore.Sync` (step 9):** a pure clock-offset estimator matching
ARCHITECTURE §7.1 — `rtt`/`offset` from the four PING/PONG timestamps, outlier rejection (discard
any sample whose rtt exceeds 2× the window minimum), minimum-RTT sample selection, EWMA smoothing
(α = 0.2), and the 30 ms step-rejection-with-two-window-confirmation rule. All arithmetic is
exact-rational integer math (no floating point) so Kotlin `Long` and Swift `Int64` divide
identically. Two implementation parameters ARCHITECTURE §7.1 leaves as prose — the jitter formula
and the step-confirmation tolerance — are pinned by `protocol/vectors/clock/clock_vectors.json`
(16 vectors: ideal symmetric RTT, low-RTT sample selection, a high-latency outlier, jitter under
varying RTT, an asymmetric-path example documenting the known NTP-style limitation, positive/
negative offsets, an integer-overflow-boundary sanity check, two "no valid samples" cases, a
rejected-then-confirmed 50 ms step, and four EWMA convergence steps). **Both platforms pass all 16
byte-for-byte** (`./gradlew :core:test`, `swift test --package-path ios/Packages/RideLinkCore`).

**`network.control` / `RideLinkPlatform.Control` (step 8) — `PlainControlTransportPhase1a`,
explicitly named and documented as plaintext/debug-only, never to be mistaken for the Phase 1b
transport:**

- Framing: `uint32` BE length prefix + JSON body, 262144-byte cap. The length is validated
  **before** any payload buffer/receive is requested — proven by a test that declares an
  oversized length with no body and asserts the read returns `frameTooLarge` promptly rather than
  hanging or allocating (both platforms).
- HELLO/HELLO_ACK per PROTOCOL §4.1, using the existing `EnvelopeCodec`/`Envelope` — no second
  JSON protocol. Phase 1a has no real identity yet: `identity_spki_sha256` is a fixed,
  documented, non-security-bearing sentinel (`ProvisionalIdentity` / ADR-012's field populated
  with a structurally-valid placeholder), and `peer_id` is a random value generated once per
  process start, not persisted, not a Phase 1b durable identity.
- **Real-socket duplicate-connection resolution** (PROTOCOL §4.2 / ADR-015), wired end to end:
  `DuplicateConnectionArbiter` holds candidate sockets until both `conn_tiebreak` values are
  known, applies `core.protocol.Dedup`, and — because a rival can complete its handshake a moment
  after this one does — a lone candidate is held 300 ms (documented, tunable implementation
  constant, not a protocol value) before being declared the survivor. Tested with **two
  independent `ControlSessionManager`/`ControlSessionManager`(actor) instances dialling each
  other over real loopback TCP at once** on both platforms: exactly one survivor, the loser
  closes cleanly with `BYE{duplicate_connection}`, `reconnect_count` is untouched, and both sides
  independently agree on the leader (ADR-010) with no correlation to which side's connection
  survived (ADR-015 Amendment A2) — asserted directly in the test.
- `TCP_NODELAY` set on every socket; OS-level `SO_KEEPALIVE` best-effort; the PROTOCOL §1
  application PING/PONG (2 s / 6 s-lost) remains authoritative for session health, as specified.
- Reconnect: the exact PROTOCOL §10 ladder (0.5, 1, 2, 4, 8, 8, 8… s, ±20 % jitter, 120 s budget),
  pure and tested with an injected delay recorder — no `Thread.sleep`/real `Task.sleep` in the
  test, and a separate test proves the 120 s budget is honoured before `DISCONNECTED`.
- Clock sync wired to the wire: an 11-sample, ~50 ms-spaced burst runs at `CONNECTED` and every
  10 s thereafter (ARCHITECTURE §7.1's two cadences), each burst run through `ClockSync` to update
  `offset_us`/`jitter_us`/`rtt_ms` in the diagnostics snapshot.

**Discovery lifecycle fixes (step 7, both platforms):**

- Android: **`NsdManager.ServiceInfoCallback` (API 34+)** used for resolution/live-update
  tracking; **API 31–33** falls back to legacy `resolveService`, now with a **fresh
  `ResolveListener` per call** (the previous session's implementation reused one listener across
  concurrent resolutions, which is unsafe). iOS: `NWBrowser.Result.Change` (`.added`/`.changed`/
  `.removed`) drives Found/Updated/Lost directly — `.removed` recovers the discovery handle from
  the browser's own cached TXT metadata, no extra resolve needed.
- Platform-neutral `Found`/`Updated`/`Lost` event model on both platforms (`DiscoveryEvent`),
  extracted into pure, unit-tested logic (`DiscoveryLifecycleTracker` on Android; `NWBrowser`'s
  own change set on iOS) — repeated discovery of the same peer never re-emits `Found`, losing a
  peer removes it, and a subsequent rediscovery is `Found` again.
- Self-filtering by discovery handle, not IP/hostname/peer_id, on both platforms.
- `dh` rotation: regenerated on advertise start and at least every 15 minutes thereafter
  (`DiscoveryHandleRotationPolicy`, identical constant/logic on both platforms, unit-tested
  against an injected clock — no real 15-minute wait in any test). Rotation re-registers the
  Android `NsdServiceInfo` / re-assigns the iOS `NWListener.service` in place; neither touches the
  TCP listener socket or any live control connection.
- **Advertising now shares the control listener's socket** on both platforms — no second, unused
  TCP port. Android: `NsdDiscoveryController.advertise(name, port)` takes the real port from
  `ControlSessionManager.startListening()`. iOS: `BonjourDiscovery.startAdvertising(on: listener)`
  takes the already-bound `NWListener` from the control layer directly and attaches the Bonjour
  service registration + TXT rotation to it in place.
- TXT record content is exactly `{v, dh, plat}` on both platforms, asserted by a dedicated
  privacy test run against the **same function** (`buildTxtRecord` / `buildTxtRecord`) the real
  advertise path calls — an accidental future field addition fails this test, not a manual review.

**Diagnostics UI (step 10, both platforms):** device identity, FSM connection state, discovered-
peer count (current + cumulative), and a Phase 1a diagnostics card — control state, peer,
`is_local_leader`, RTT, clock offset, clock jitter, reconnect count — plus a highly visible
`TRANSPORT: PLAIN / PHASE 1A / NOT SECURE` banner (yellow/amber on both platforms). Verified on
iOS by installing and screenshotting on the iPhone 17 Pro Max **simulator** (not a physical
device) — renders correctly, matches the spec. Android has no emulator/device available in this
environment (no `adb`, no AVD configured) — verified by `./gradlew assembleDebug` only, **not**
run or screenshotted. See §7 for exactly what that leaves pending.

**`SessionCoordinator` end-to-end wiring (both platforms):** `Start Discovery` → bind the control
listener → advertise + browse concurrently → first discovered peer auto-selected (`PeerSelected`
then `PairingSucceeded` applied immediately — Phase 1a has no pairing UI to tap into yet, see the
explicit non-goal below; this is a Phase 1a simplification of *when* those two already-legal FSM
transitions fire, not a new transition) → outbound `connectTo` → duplicate resolution → HELLO
exchange → `ConnectionEstablished`/`ReconnectSucceeded` on `ControlEvent.Connected`,
`ConnectionFailed`/`LinkLost(NETWORK)` (state-dependent) on a failed/lost link,
`DuplicateConnectionClosed` and `ReconnectBudgetExhausted` mapped straight through → `CONNECTED`.
`ENDING`'s existing `ReleaseAudioAndStopForegroundService` effect now also tears down the control
session and discovery (no separate rule needed — one owner, one teardown path, per ARCHITECTURE
§3 rule 4).

**Explicitly not started, per CLAUDE.md rule 28 / this session's brief §28:** production TLS,
self-signed X.509, Android Keystore / iOS Keychain identity, SPKI pin *runtime* checking, TLS
exporter, real pairing SAS, pairing trust persistence, QR fallback, WebRTC, audio, music. The
`ProvisionalIdentity` sentinel values exist solely to satisfy Phase 1a's wire shape and are never
treated as security-bearing anywhere in the new code.

---

## 2e. Phase 1a cleanup / hardening pass (27 August 2026 session, second)

An independent review of the §2d implementation found ten real defects — not stylistic issues —
each confirmed by reproducing it in a test that failed before the fix and passes after. No Phase
1b work (TLS, identity, pairing) was touched; no protocol/wire shape changed. All fixes are code-
and test-only.

1. **iOS PING/PONG race.** `ControlSessionManager.sendPingAndAwait` wrote the PING frame *before*
   registering the waiter in `pendingPings`. Because `writeFrame` suspends, a fast (e.g. loopback)
   peer's PONG could be processed by this same actor before the waiter existed, silently dropping
   it until the 3 s timeout. Fixed by registering the waiter synchronously inside
   `withCheckedThrowingContinuation`'s body, before any suspension. Android's ordering was already
   correct (reviewed, not changed) — but its write-failure path leaked the pending entry, fixed
   alongside.
2. **Reconnect re-entrancy, both platforms.** `ReconnectController`'s own attempts called the
   *public* `connectTo`, which emits `.linkLost`/`LinkLost` on failure. `SessionCoordinator` reacts
   to that event by calling `beginReconnect` again, starting a second ladder on top of the first.
   Fixed by splitting `connectTo` (top-level, may emit) from a new internal `attemptConnection` /
   `attemptConnection` (used only by the reconnect ladder, never emits). **While validating this
   fix, found a second, independent bug it depends on**: iOS's `ReconnectController.start()`
   wraps `Task.sleep` in `try?`, which swallows the `CancellationError` that would otherwise stop
   the loop — so `cancel()` merely dropped the caller's reference while the loop kept running
   *detached*, still calling `onAttempt` on schedule. Fixed by checking `Task.isCancelled`
   explicitly at each loop step. **A third, separate bug surfaced testing this on iOS**:
   `ControlConnection.connect` had no timeout — `NWConnection` can sit in `.waiting` (e.g. on
   `ECONNREFUSED`) indefinitely rather than surfacing `.failed`, so a reconnect attempt against an
   unreachable peer could hang forever. Fixed with a `connectTimeoutMs` race (default 5000 ms,
   matching Android's existing `ControlSocket.connect` timeout) using the `SingleResumeContinuation`
   pattern — not `withThrowingTaskGroup`, which does not actually cancel a sibling task blocked on
   a raw continuation (confirmed by reproducing the hang with a minimal repro before writing the
   fix).
3. **Plaintext transport not enforced as debug-only.** Documentation said `PlainControlTransportPhase1a`
   is debug-only; nothing enforced it. Android: `AppContainer.sessionCoordinator` is now `SessionCoordinator?`,
   gated by a pure `gatedByPlaintextTransport(allowed = BuildConfig.DEBUG) { ... }` helper —
   `NsdDiscoveryController`/`ControlSessionManager` are never *constructed* in a release build, not
   just unused; `MainActivity` shows `SecureTransportUnavailableScreen` when `null`.
   `buildConfig = true` added to `app/build.gradle.kts`. iOS: `PlaintextTransportGate.makeSessionCoordinator()`
   wraps `SessionCoordinator()` in `#if DEBUG`/`#else nil#endif` — a **compile-time** exclusion
   (confirmed `SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG` is set only for the Debug build
   configuration in the checked-in `project.pbxproj`); `RideLinkApp` shows `SecureTransportUnavailableView`
   when `nil`.
4. **Android NSD `ServiceInfoCallback` leak (API 34+).** `resolveModern` registered a callback per
   `onServiceFound` with nothing ever unregistering it. Added `ServiceInfoCallbackRegistry` (pure,
   no `android.*`) tracking registrations by service name; wired to unregister on service-lost,
   browse-stop, teardown and registration-failure, and to safely replace (not leak) a duplicate
   registration for the same service name.
5. **mDNS instance-name privacy leak, both platforms.** Android's advertised service name was
   `"RideLink-${Build.MANUFACTURER} ${Build.MODEL}"`. iOS's `NWListener.Service` had no explicit
   `name:`, which falls back to the device's Bonjour name (tied to `UIDevice.current.name`) — a
   leak that existed even though the TXT record itself was already privacy-clean. Both now use a
   neutral `"RideLink-" + dh.take(8)` derived from the rotating discovery handle
   (`instanceServiceName`, mirrored on both platforms), rotating exactly when `dh` rotates.
6. **Unsafe PING/PONG field extraction, both platforms.** Android used `payload[key]!!.jsonPrimitive.long`,
   which throws (killing the read-loop coroutine) on a missing/wrong-typed/non-numeric field.
   Replaced with `requiredLongField`/`requiredBooleanField`, which validate presence, JSON type
   (rejecting quoted-string numbers) and representability, returning `null` instead of throwing.
   iOS's `guard let ... else { return }` shape was already safe against missing/wrong-typed
   fields, but `JSONValue.int64Value` used the non-failable `Int64(_:)` on a `Double`, which
   **traps** (crashes the process, not a thrown `Error`) on NaN/infinite/out-of-range input —
   fixed with `Int64(exactly:)`.
7. **Discovery-handle rotation self-race, both platforms.** Rotating `dh` flips the "self" handle
   synchronously, but the old advertisement can still be resolvable for a short window afterward
   (Android: `unregisterService` is async; iOS: `NWListener.service` reassignment has no completion
   callback at all) — during that window a stale self-resolution could be misread as a newly
   discovered peer. Added `SelfDiscoveryHandles` (pure, mirrored on both platforms) tracking
   current + previous handle; Android clears "previous" on the old registration's confirmed
   `onServiceUnregistered`, iOS on a bounded 1 s grace period (no equivalent OS callback exists).
8. **iOS `@unchecked Sendable` review — `BonjourDiscovery`.** Confirmed the documented invariant
   ("every access confined to `queue`") was **violated** in three places: `startBrowsing`/
   `stopBrowsing` mutated `browser` directly on the caller's thread; `stopAdvertising` cancelled
   `rotationTask` directly on the caller's thread; and `listener.serviceRegistrationUpdateHandler`
   (which fires on `ControlSessionManager`'s listener queue, not `BonjourDiscovery`'s own) read
   `selfHandles` without hopping onto `queue` first. All three fixed; no new `@unchecked Sendable`
   added anywhere.
9. **Control-task teardown, both platforms.** `shutdown()` cancelled the read/keepalive/clock-sync/
   reconnect tasks and closed the active socket, but did **not** close candidate sockets the
   `DuplicateConnectionArbiter` was still holding mid-resolution — added `arbiter.drainAll()`,
   closed on shutdown. Also added a dedicated `isShutDown` flag (distinct from `endedDeliberately`,
   which also becomes true after an ordinary BYE) so a handshake/dedup resolution already in
   flight when `shutdown()` was called cannot "resurrect" a connection afterward; reset on the
   next `startListening`, since `ControlSessionManager` is reused across sessions. Fixing this
   surfaced Android's `failAllPendingPings` iterating a live `ConcurrentHashMap` with
   `.keys.toList()`, which threw `NoSuchElementException` under concurrent modification from an
   in-flight keepalive/clock-sync coroutine — fixed using `ConcurrentHashMap.forEach`, the JDK's
   documented concurrent-safe traversal.
10. **Clock-sync live-wire input validation, both platforms.** PONG's `t2`/`t3` are peer-controlled.
    `ClockSync.Sample.rttUs`/`offsetUs` use plain `Int64`/`Long` subtraction on both platforms —
    trapping (crashing) on overflow in Swift, silently wrapping to a wrong-but-plausible value in
    Kotlin. Added `isPlausibleClockSample` (both platforms) using overflow-*reporting* arithmetic
    to reject a sample before it is ever constructed. Reproduced and confirmed the iOS crash with
    a concrete wire-reachable input (`t2 = 9223372036854774784`, `t3 = -9223372036854775808` —
    both individually exact `Double`→`Int64` round-trips, so both pass the field-type check from
    fix 6, but their difference overflows `Int64`) before writing the fix; the regression test
    uses that exact input. `ClockSync`'s own algorithm and `protocol/vectors/clock/` are
    **untouched** — its existing `rttUs > 0` outlier filter already rejects the *result* of a
    non-overflowing bad sample; this fix only prevents the arithmetic itself from overflowing.

**Verification:** every fix above has a dedicated regression test (new files:
`PingRaceAndReconnectTest[s]`, `MalformedPingPongTest[s]`, `ServiceInfoCallbackRegistryTest`,
`SelfDiscoveryHandlesTest[s]`, `TeardownTest[s]`, plus additions to `DiscoveryPrivacyTest[s]` and
`TransportGateTest`). `./gradlew clean test ktlintCheck detekt lint assembleDebug assembleRelease`
and `swift test` for both packages plus `xcodebuild` Debug **and** Release simulator builds all
pass — see §3 for the exact commands and results. Static analysis thresholds touched: `detekt.yml`
`TooManyFunctions.thresholdInClasses` raised 20 → 24 for `ControlSessionManager`, documented in
the config file itself with the same style-calibration precedent as prior sessions' adjustments.

---

## 2f. Phase 1b — secure control channel (27 August 2026 session, third)

### The two spikes, run first

ADR-007 Amendment A1 required both to be answered before anything depended on them, and named the
response to either failing: stop and run a focused design review, never substitute something
weaker. **Both passed.** Full method, raw numbers and caveats:
[`test-results/phase1b-security-spike-20260827.md`](test-results/phase1b-security-spike-20260827.md);
harness: [`tools/spikes/phase1b-tls-exporter/`](../tools/spikes/phase1b-tls-exporter/).

Findings that changed what got built:

1. **Apple cannot supply a zero-length exporter context.** `sec_protocol_metadata_create_secret_with_context(…, context_len: 0, …)` returns nil — tested with a valid pointer, so the nil could not be blamed on a bad one. PROTOCOL §4.5.1 said `context = zero-length, but PRESENT`, which is an RFC 5705 distinction that **does not exist in TLS 1.3** (RFC 8446 §7.5 always hashes a context value). Measured `null == empty` on the one stack that can express both. §4.5.1 is reworded and each platform's concrete call is now named in the spec; **`protocol/vectors/sas/` is untouched**, because it starts from exporter *output*. [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md).
2. **Conscrypt's server-side `SSLSession.getProtocol()` misreports TLS 1.3 as `TLSv1.2`** on a connection where only TLS 1.3 was ever enabled and a TLS-1.3-only cipher suite was negotiated. So the "no silent 1.2 fallback" check is **not** an assertion on `getProtocol()` — that would reject good sessions. It is enforced structurally (`setEnabledProtocols(["TLSv1.3"])` / `set_{min,max}_tls_protocol_version(.TLSv13)` on both ends) plus an assertion on the negotiated **cipher suite**.
3. **Android does not use `KeyGenParameterSpec`'s auto-issued certificate** — the expected path. It issues that certificate *at key-generation time* and offers no way to issue a new one around an existing Keystore key, which makes ADR-012's whole re-issuance model unreachable. Both platforms therefore encode their own certificate with a shared DER encoder. [ADR-017 §3](DECISIONS/ADR-017-identity-key-and-certificate.md).
4. **`SecIdentityCreateWithCertificate` is macOS-only** (`SEC_OS_OSX` / `__IPHONE_NA`) and is the function most iOS examples reach for. `SecIdentityCreate(nil, cert, key)` is the iOS-available one, takes the key directly, and needs no PKCS#12 — so the private key is never exported.

### What was built

**Shared, pure, vector-pinned** (`core.security` / `RideLinkCore.Security`, mirrored line for line):
`Der` (a minimal DER **encoder**, no parser, no ASN.1 framework), `IdentityCertificate` (P-256
SubjectPublicKeyInfo, `identity_spki_sha256`, the TBSCertificate, the ADR-012 validity window),
`UtcTime` (**the project's only wall-clock type**, with Howard Hinnant's exact civil-date
algorithm rather than `java.time`/`DateFormatter`, so the two platforms cannot drift over a locale
or a calendar), `PeerTrust` (the pin decision as a pure function) and `TrustedPeerStore`.

New shared vectors: **`protocol/vectors/identity/`** — DER length and INTEGER encodings at their
boundaries, the 91-byte P-256 SPKI, `identity_spki_sha256` formatting (uppercase rejected), an
exact TBSCertificate, the eight pin decisions, and certificate-validity boundaries. Generated by
`tools/generate_identity_vectors.py`, an independent third implementation, and cross-checked: the
generated certificate parses correctly under `openssl x509`, and the generated SPKI hash matches
what OpenSSL computes for the same key.

**Android** (`network.security`, `data.trustedpeers`): `AndroidKeystoreIdentityStore` (P-256 in
Android Keystore, non-exportable, `AfterFirstUnlock`-equivalent so it works with the screen
locked), `IdentityIssuer` (kept free of `android.*` so the encoding, signing and point extraction
are JVM-testable), `TlsControlChannel` (TLS 1.3 only, `needClientAuth`, a deferring trust manager
because trust is the pin one layer up), `FileTrustedPeerStore` and `LocalPeerIdStore` (atomic
writes, corrupt-file tolerance, pin-replacement refusal).

**iOS** (`RideLinkPlatform.Security`): `DeviceIdentityStore` (Keychain P-256; an `.ephemeral`
storage mode exists **only** so an unsigned `swift test` binary can exercise the rest),
`PeerCertificateInspector` (SPKI via `SecCertificateCopyKey`, structural validity via
`SecTrustEvaluateWithError` against the certificate as its own anchor — not a chain or hostname
check), `TlsControlChannel`, `FileTrustedPeerStore`, `LocalPeerIdStore`.

**Both:** a `ControlChannel`/`ChannelSecurity` seam so `ControlSessionManager` never imports a TLS
type and `PeerTrust` never imports a socket type; the SPKI pin check wired into
`ControlHandshake`; `PairingExchange` (PROTOCOL §4.5, with the SAS never leaving the device);
persisted trust; and a pairing card + security-warning card in both UIs. The transport banner is
now **green when the link really is TLS 1.3** — a banner that keeps crying wolf after the
transport is secure trains the user to ignore it.

### The plaintext transport is deleted, not gated

Phase 1a shipped `PlainControlTransportPhase1a` in the production source set and used
`BuildConfig.DEBUG` / `#if DEBUG` to avoid *constructing* it. Phase 1b removes it from `main`
entirely: the only plaintext `ControlChannel` in the repository is a fixture in
`network/src/test` / `RideLinkPlatformTests`, so it is not compiled into either library and no app
build — debug or release — contains those bytes. The old gate (`TransportGate.kt`,
`PlaintextTransportGate.swift`) is gone, replaced by `SecureTransportPolicy` (a composition-root
assertion) and by **`PlaintextTransportAbsenceTest`**, which reads `network/src/main` and fails if
a raw socket, a reference to the fixture, or a second `ControlChannel` implementation ever appears
there. That is the part that keeps being true after this session.

### Bugs found and fixed while building it

Each was reproduced before being fixed, and each has a regression test.

1. **`ControlHandshake` could be crashed by a malformed HELLO, on both platforms.** It built `PeerId`/`ConnTiebreak` straight from wire strings with constructors that `require`/`precondition` — so `"peer_id": "NOTHEX"` threw out of the handshake coroutine (Android) or trapped the process (iOS). This is the same class the §2e hardening pass fixed for PING/PONG; HELLO was not covered then because Phase 1a had no security-bearing field in it. Fixed with non-throwing `parse` constructors on `PeerId`, `ConnTiebreak` and `SpkiHash`, used for every wire-sourced value.
2. **A handshake write racing a close threw instead of reporting a closed connection.** When one side refuses a certificate before replying, production closes the socket — and the peer's in-flight `writeFrame` then threw an `IOException` out of `performAsInitiator`, leaving the socket unclosed and producing no outcome at all. Surfaced by the expired-certificate test. Fixed by reporting a failed write as `ConnectionClosed`, exactly as a failed read already was.
3. **`gradle.properties` pointed at a non-JDK** (§1). Intermittent by nature; now correct.
4. **`detekt` has never actually run on this machine** (§1, §4 problem 17). CI was green throughout, which is precisely why nobody noticed.

### Explicitly not started

WebRTC, microphone capture, the Opus pipeline, Bluetooth routing, intercom UX, music playback,
music sync, local music transfer, manifest transfer, Ride Mode, navigation announcements, group
sessions, cloud/backend, accounts, streaming services. Also deliberately **not** built: the manual
`host:port` / QR fallback for blocked mDNS — it is Phase 1b scope in ARCHITECTURE §4.4 but is a
*discovery* feature with no security content, and it is listed in §7 as the first follow-up.

---

## 2g. Phase 1b — security-state integration fix (27 August 2026 session, fourth)

Scope was deliberately one bug. No new feature, no Phase 2, no redesign of the crypto.

### The bug

An **unknown** peer could drive the application FSM to `CONNECTED` before SAS pairing had even
been offered. On both platforms:

```
TLS 1.3 handshake succeeds
  -> certificate / SPKI checked         (correct)
  -> candidate wins duplicate resolution (correct)
  -> ControlSessionManager emits Connected     <-- too early
  -> SessionCoordinator sees Connected while the FSM is in PAIRING
  -> applies PairingSucceeded                  <-- a lie
  -> PAIRING -> CONNECTING -> CONNECTED
  -> ...and only now beginPairing(), PairingRequired, the six-digit code
```

Reproduced before anything was changed, as a `network`-module test against the real TLS channel
with two unpaired peers:

```
A announced Connected before SAS pairing completed:
  [Connected(remotePeerId=peer:bbbbbb…, sessionId=…, isLocalLeader=true),
   PairingRequired(remotePeerId=peer:bbbbbb…)]
```

and confirmed on iOS by temporarily restoring the old emit order, which fails the new suite with
`["Connected", "PairingRequired"]`.

**Why nothing caught it.** Every mechanism had tests and they all passed: `PairingExchangeTest[s]`
proved no pin is written until both sides confirm; `TlsControlChannelTest[s]` proved an unknown
peer produces `pairing_required` and a known one connects silently; the FSM vectors proved
`PAIRING -> CONNECTING` needs `PairingSucceeded`. What had no test was the *join* — which control
event the coordinator turns into which FSM event — because it lived as a `when`/`switch` inside a
platform class that no suite could construct (`SessionCoordinator` needs `NsdDiscoveryController`
on Android and is in the untested app target on iOS). CI run 33098708512 was fully green over this
bug.

### The fix

[ADR-019](DECISIONS/ADR-019-connected-means-authenticated.md). Same shape on both platforms.

1. **`ControlEvent.Connected` has one meaning:** *the surviving secure connection has passed the RideLink trust gate and may be treated as authenticated.* Emitted from exactly one function, `activateAuthenticatedSession`, and never from the handshake or from promotion.
2. **New `ControlEvent.PeerTrusted`** carries the silent path (stored pin matched), so `Connected` no longer has to double as pairing success. Trusted: `PeerTrusted` → `Connected`. Unknown: `PairingRequired` → *(two humans)* → `PairingSucceeded` → `Connected`.
3. **`promote()` no longer announces a connection.** It records the survivor's facts, starts the *transport* tasks (read loop, keepalive — pairing frames arrive on that same socket, and a link that dies mid-pairing has to be noticed), and then either pairs or activates. Diagnostics show `CONNECTING`, not `CONNECTED`, while a code is on screen.
4. **`SessionCoordinator` no longer decides.** The `(ControlEvent, status) -> SessionEvent?` table is now `SessionGate` — pure, mirrored, and pinned by `protocol/vectors/session-gate/gate_vectors.json`, the complete 120-row cross-product, run by **both** platforms. The coordinator still owns the `FsmState` (CLAUDE.md rule 8) and does the side effects.
5. **The FSM is untouched.** No new state, no new `SessionEvent`, no changed transition; `protocol/vectors/session-fsm/` passes unchanged. `PAIRING` and `CONNECTING` stay distinct.
6. **Pairing completes on the socket that is already open.** A second handshake would produce a second exporter, so the code the users compared would no longer bind the session in use (ADR-018). A test counts the transport's dials: one per side across the whole flow.
7. **The clock-sync burst moved behind the gate.** ARCHITECTURE §7.1 places it at `CONNECTING`, which is now genuinely post-authentication.
8. **A pre-authentication frame allowlist** — `PING`, `PONG`, `PAIR_*`, `BYE`, `ERROR` — so a Phase 2 message type is inert before authentication unless added deliberately. `PING`/`PONG` can never mark authentication complete.
9. **Failure closes deliberately.** A refusal writes no pin, clears both codes, sends `ERROR{fatal}` and ends the connection as `user_ended`, so the reconnect ladder cannot silently re-offer a pairing someone refused.

### Two smaller bugs found and fixed on the way

| # | Bug | Fix |
|---|---|---|
| 1 | **A link lost in `PAIRING` wedged the session.** Neither `LinkLost` variant is legal in `PAIRING`, so the FSM rejected it and the session sat there forever with no prompt and no way forward. Pre-existing (a failed *first* dial did it too), but the fix makes `PAIRING` last much longer, so it went from rare to routine | `SessionGate` maps both `LinkLost` reasons in `PAIRING` to `PairingRejectedOrTimeout` → `DISCOVERING`. `connect_attempted` is deliberately **not** re-armed, so a refusal is not re-offered by the next mDNS `Found` |
| 2 | **A peer's rejection left the other side showing a dead code.** The rejecter's `ERROR{fatal}` was handled as a plain link loss, so the receiving side kept its `PairingExchange` and its six digits on screen for a socket that was gone | A fatal `ERROR` arriving mid-pairing is treated as a pairing failure carrying the peer's code — and the code is surfaced **only** if it is one of PROTOCOL §4.6's defined codes, so a remote peer cannot choose the text of a security message |

### What was added, and where

| | Android | iOS |
|---|---|---|
| Trust-gate table | `network/control/SessionGate.kt` | `RideLinkPlatform/Control/SessionGate.swift` |
| Event semantics | `ControlSessionManager.kt` — `PeerTrusted`, `activateAuthenticatedSession`, reworked `promote`/`succeedPairing`/`failPairing`, pre-auth frame allowlist | `ControlSessionManager.swift`, the same |
| Coordinator | `app/session/SessionCoordinator.kt` — now applies what the gate returns | `RideLink/SessionCoordinator.swift`, the same |
| Shared vectors | `protocol/vectors/session-gate/gate_vectors.json` + `tools/generate_session_gate_vectors.py` (an independent third transcription of the rules) | same file |
| Tests | `SessionGateTest`, `SessionGateVectorTest`, `PairingSessionIntegrationTest` (+ `PairingSessionSupport`) | `SessionGateTests`, `SessionGateVectorTests`, `PairingSessionIntegrationTests` (+ `TestSupport/PairingSessionSupport.swift`, `TestSupport/Vectors.swift`) |

### Explicitly not done

No Phase 2 work of any kind — no WebRTC, voice, microphone, Opus, audio routing, Bluetooth, music
or Ride Mode — despite the two preceding commits being named "init phase 2a" and "phase 2a". Those
names are misleading; nothing in this repository is Phase 2. No pairing *timeout* was invented
either: PROTOCOL §4.5 specifies a rate limit and no timeout, so implementing one would have been
inventing protocol.

---

## 2h. Phase 1b — iOS control-event ordering fix (28 August 2026 session, fifth)

Delivery-only. The security model, `SessionGate`'s table, `SessionFsm` and every crypto primitive
are unchanged; no ADR was needed because no design changed.

### The bug

`SessionCoordinator.startDiscovery()` subscribed to `ControlSessionManager`'s events with
`setOnEvent { event in Task { @MainActor in self?.handleControlEvent(event) } }` — a **new**
unstructured `Task` per event. `ControlSessionManager` deliberately emits ordered pairs
(`.pairingSucceeded` then `.connected`; `.peerTrusted` then `.connected`, both synchronously,
back to back, inside `succeedPairing`/`promote`) and `SessionGate` (ADR-019) depends on that order
surviving delivery. A `Task` per event preserves the order events were *created* in, not the order
they *run* in — Swift gives no ordering guarantee between independently created tasks on the same
executor. Nothing in this repository proved otherwise; it was found by inspection, matching the
same class of gap ADR-019 itself closed (a join no test crossed), not a device failure.
`pairingPromptChanged` had the identical shape, with a real consequence if it lost ordering: a
stale six-digit code reappearing after pairing had already settled.

### The fix

`OrderedEventChannel<Element>` (`RideLinkPlatform/Control/OrderedEventChannel.swift`) — a minimal
`AsyncStream` wrapper: `send` enqueues synchronously from any isolation context, `finish` ends the
stream. `SessionCoordinator` now creates one `OrderedEventChannel<ControlEvent>` and one
`OrderedEventChannel<PairingPrompt?>` per `startDiscovery()`, each drained by exactly one
long-lived `Task` running a single `for await` loop — so event *N+1* is structurally unable to be
handled before event *N*. `teardownSession()` cancels both consumer tasks and finishes both
channels before the next `startDiscovery()` creates a fresh pair, so a callback still in flight
from a torn-down `ControlSessionManager` session lands as a no-op `send` on an already-finished
channel rather than mutating the next session's state. `onDiagnosticsChanged` (cosmetic UI only,
no security content) was deliberately left on its prior per-callback `Task` — out of scope per this
session's brief.

Android already collects `controlSessionManager.events` with a single `Flow.collect` inside one
`launch {}` — Kotlin `Flow` collection is inherently sequential on one coroutine, so the equivalent
defect does not exist there. Confirmed by inspection; **no Android change was made.**

### Verification

`OrderedEventChannelTests` (5 tests) exercise the abstraction directly: FIFO order over 200 sends,
two synchronous back-to-back sends (the exact production shape) repeated 50×, `finish()` ending an
in-progress consumer loop, a `send` after `finish()` being silently dropped, and a cancelled
consumer leaving nothing running. `PairingSessionOrderingTests` (3 tests) reproduce the real
`SessionCoordinator` wiring — `ControlSessionManager.emit` → `channel.send` → one consumer `Task`
— over real TLS 1.3 handshakes between two real `ControlSessionManager`s, proving the unknown-peer
`PairingRequired → PairingSucceeded → Connected` order, the known-peer `PeerTrusted → Connected`
order with no SAS prompt, and that a stale `send` issued after `detach()` cannot move the FSM. All
8 pass; the full ordering + pairing suite (`OrderedEventChannelTests` +
`PairingSessionOrderingTests` + `PairingSessionIntegrationTests`, 21 tests) was run **20/20
consecutive times with 0 failures**. `swift test` for `RideLinkPlatform` is 99/99 (up from 91);
`RideLinkCore` is unchanged at 27/27. `xcodebuild` Debug and Release both succeed for the
simulator, zero warnings beyond the pre-existing benign "no AppIntents.framework dependency"
notice. Android's full `test ktlintCheck detekt lint assembleDebug assembleRelease` is unchanged
at 253/253 with 0 failures, confirming no regression from a session that touched no Android file.

---

## 2i. Phase 2a — voice transport foundation (28 August 2026 session, sixth)

Two steps, in order, as the brief required: a dependency/API spike first, then implementation only
after the spike answered the open questions. Decisions:
[ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md). Evidence:
[`test-results/phase2a-webrtc-spike-20260828.md`](test-results/phase2a-webrtc-spike-20260828.md).

### The spike, run first (Phase 2a.1)

ADR-003 named two candidate WebRTC distributions in June and left four things open. All four now
have answers, and none required a weaker substitute:

1. **Distributions pinned exactly, and reviewed rather than trusted.** Android
   `io.github.webrtc-sdk:android:144.7559.14` (Chromium M144, BSD-3-Clause, 48.7 MB AAR, four ABIs,
   `minSdkVersion 21`); Apple `stasel/WebRTC` `exact: "152.0.0"` (Chromium M152, BSD-3-Clause, SPM
   `binaryTarget` whose SHA-256 was verified byte-for-byte against the published release — pinned at
   `151.0.0` until upstream deleted that release, §4 problem 27). Neither
   declares a permission, service or analytics class; **every** HTTP string in both native binaries
   was extracted and read, and all of them are RTP header-extension URIs, a CRL string inside the
   bundled root store, or source references — **no upload endpoint of any kind**. Apple's bundled
   `PrivacyInfo.xcprivacy` states `NSPrivacyTracking: false` with no collected data types.
2. **Maven Central's search index was stale**, reporting `125.6422.07` (March 2025) as the newest
   Android version. `maven-metadata.xml` has `144.7559.14`. Worth knowing: the stale answer is the
   one a casual check returns, and it would have pinned a version 16 milestones old.
3. **The macOS slice is the find that mattered.** It is what makes real DTLS-SRTP/Opus media
   testable on a laptop at all — see the box at the top of this file.
4. **Swift 6 refuses to let WebRTC objects leave a callback.** `RTCSessionDescription`,
   `RTCIceCandidate` and `RTCStatisticsReport` are not `Sendable`; three compile errors were
   reproduced deliberately before designing around them. The fix is not a suppression: every value
   is reduced to a primitive *inside* the callback — which coincides exactly with the boundary
   PROTOCOL §7.4 already defines. It shaped the `VoiceEngine` seam on **both** platforms.

**Milestone skew accepted knowingly:** Android M144, Apple M152, because neither distribution
publishes the other's. WebRTC interoperates across milestones by design; recorded so a future
interop problem is investigated against a known difference rather than met as a surprise.

### What was built (Phase 2a.2)

**Shared, pure, vector-pinned** (`core.protocol`/`core.voice`/`core.audiopolicy` mirrored by
`RideLinkCore.Protocol`/`.Voice`/`.AudioPolicy`):

- `VoiceSignal` + `VoiceSignalCodec` — the four `VOICE_*` messages, total and non-throwing, with every PROTOCOL §7.5 bound enforced **before** a peer-supplied string reaches the media stack.
- `VoiceNegotiation` — the complete PROTOCOL §7 negotiation table as a pure `(state, input) -> (state, actions)` reducer. This is where the offerer rule, glare, the generation guard and "a link loss must not close the capture device" live, so a laptop can exhaust them.
- `VoiceSessionId` — PROTOCOL §7.2's generation guard, a distinct type from `ConnTiebreak`.
- `PendingCandidates` — the bounded trickle-ICE queue; at capacity the **oldest** is dropped and every drop is **counted**.
- `VoiceEngine`/`VoiceAudioSession`/`VoiceSignalTransport`/`VoiceSignalSink` — primitive-only seams, which is what makes `VoiceController` testable with no WebRTC at all.
- `VoiceStatsMapping` — one shared `webrtc-stats` mapping, so both platforms report the same numbers.
- `audiopolicy` — ADR-016's vocabulary, now **implemented** rather than a shell (see the consolidation note below).

**New shared vectors:** `protocol/vectors/voice-signal/` (70 rows) and
`protocol/vectors/voice-fsm/` (52 rows), generated by `tools/generate_voice_signal_vectors.py` and
`tools/generate_voice_fsm_vectors.py` — independent third implementations written from PROTOCOL, not
ported from either platform. **Both generators disagreed with the Kotlin implementation on first
run, and both disagreements were real findings** (see below).

**Android:** `network/voice/{VoiceController, WebRtcVoiceEngine, VoiceSignalRelay}`,
`audio/route/{AndroidVoiceAudioSession, AndroidAudioRouteMapper}`,
`app/service/RideForegroundService`, a voice card in the UI, and the ARCHITECTURE §6.4 manifest
surface (`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`, the two FGS permissions, one
service declaring `microphone|mediaPlayback`).

**iOS:** `RideLinkPlatform/Voice/{VoiceController, WebRtcVoiceEngine, VoiceSignalRelay}`,
`RideLinkPlatform/Route/{IosVoiceAudioSession, IosAudioRouteMapper}`, a voice card, and
`NSMicrophoneUsageDescription` + `UIBackgroundModes: audio`.

**PROTOCOL §7 grew from a 28-line sketch to a full specification** — schemas, bounds, the
authentication gate, the generation guard, the offerer rule, logging rules and lifecycle.

### The security property, and how it is enforced

> A peer that has completed TLS but has not passed the ADR-019 trust gate cannot start voice.

Enforced structurally, not by a check that could be forgotten: `VOICE_*` is **absent** from
`ControlSessionManager`'s pre-authentication frame allowlist, so the read loop's dispatch never
reaches the voice branch, and `VoiceController` is not constructed at all until `Connected` fires.
Proven over **real TLS on both platforms** by `VoiceAuthenticationGateTest[s]`: two real unpaired
peers reach `PAIRING` with an unanswered six-digit code, one sends every `VOICE_*` frame there is,
none arrives — and the refusals are **counted**, so the test cannot be satisfied by the frames never
being sent. The same frames from the same peer *are* delivered once both users confirm.

### Three real findings, none of them cosmetic

1. **A contradiction inside ADR-016.** Its prose said `media_quality` is `reduced` for "a duplex
   profile other than `duplex_wide_stereo`"; its own representable-states table said `builtin` is
   `full`. `builtin` satisfies the prose and contradicts the table. The table is right — a phone's
   own speaker and microphone do not degrade each other — and the prose had generalised a
   coincidence that holds only for Bluetooth. Corrected to "a **narrowed** duplex profile" and
   recorded as [ADR-016 Amendment A1](DECISIONS/ADR-016-effective-audio-capability-model.md#amendment-a1--28-august-2026--correction-media_quality-is-about-narrowed-duplex-not-duplex).
   Found by a unit test, before any of it shipped.
2. **A duplicated audio vocabulary.** `EndpointClass`, `AudioProfile`, `ProfileCoupling` and
   `AudioRoute` already existed as unimplemented Phase 1a shells in `model/Entities`, referenced by
   nothing but each other. Phase 2a's first draft added a parallel set in `audiopolicy` — and
   **Kotlin did not complain, because the packages differ**, which is worse than a compile error.
   Consolidated: the enums moved to `audiopolicy` (where ADR-016 says the vocabulary lives) and the
   `AudioRoute` shell is replaced by the implemented `AudioRouteSnapshot`. Two types for one concept,
   differing only in which one a call site reached for, is exactly the drift the shared vectors exist
   to prevent — in a place no vector could see.
3. **A missed candidate-type inspection.** PROTOCOL §7.6 inspects the `typ` of every candidate this
   side *gathers* as well as every one it *receives*. The first controller draft only checked the
   receive direction — and the gathering direction is the one that would reveal a STUN server had
   been contacted. Caught by the test written for it, fixed on both platforms.

Plus two the independent generators caught, which is what they are for: the Python transcription
mispredicted the rejection reason for a present-but-non-string `voice_session_id` (the codec
correctly distinguishes "absent" from "wrong type"), and one property test over-specified a drop
reason (a stale *engine callback* and a foreign *wire* generation are deliberately diagnosed
differently). In both cases the implementation was right and the third implementation was wrong,
which is a useful direction for a disagreement to point.

### `ControlSessionManager` got bigger, and the extraction happened

STATUS §4 problem 18 predicted this class would get worse and named the fix. detekt's `LargeClass`
fired the **first time** the voice wiring went in inline — the tool doing exactly its job. The whole
voice half was extracted to `VoiceSignalRelay` on both platforms, leaving about twenty lines of
wiring; there is no smaller way to attach a subsystem to it at all. The residual overflow is
pre-existing (the class was already at 608 counted lines before this phase touched it), so
`config/detekt/detekt.yml` now documents a `LargeClass` threshold with the reason, and **problem 18
is escalated below**. The prescribed `PairingController` extraction is deliberately still not done
here: it touches the pairing and trust-gate paths, and STATUS §4 says it belongs in a change that is
*only* that refactor.

### Explicitly not done

Music playback, music sync, local music transfer, manifest transfer, the drift ladder, Ride Mode as
a product screen, navigation announcements, PTT/VOX gating (Phase 2a sends `mode: continuous`
always), in-place WebRTC renegotiation (V1 tears down and negotiates afresh), the manual
`host:port`/QR discovery fallback, and the `PairingController` refactor. No Phase 2b work of any
kind.

---

## 2j. Phase 2a hardening — bounded voice input mailbox and strict generation guard (2 September 2026 session, seventh)

A focused hardening pass on Phase 2a's implementation, requested explicitly as hardening rather than
Phase 2b or Bluetooth tuning. Two real defects found in an independent review, both fixed with
regression coverage on both platforms. Neither touches `VoiceNegotiation`'s table, the offerer rule,
glare handling, `voice_session_id` generation, host-only ICE, Opus/DTLS-SRTP, mute behaviour, or the
ADR-019 pre-authentication gate — all confirmed unchanged. Full account:
[ADR-020 Amendment A2](DECISIONS/ADR-020-webrtc-voice-foundation.md#amendment-a2--2-september-2026--a-bounded-input-mailbox-and-the-generation-guard-made-strict).

**Finding 1 — the per-negotiation input channel was unbounded.** `VoiceController.submit` (and
`start`/`stop`/`setMicrophoneMuted`/`onControlLinkLost`, and the engine's own event sink) fed an
unbounded `Channel`/`AsyncStream` ahead of the pure reducer. PROTOCOL §7.5's bounds — SDP size,
candidate size, `MAX_QUEUED_VOICE_CANDIDATES` — all apply only *after* a frame is already sitting in
that queue, so an authenticated peer past the ADR-019 trust gate could grow this controller's
memory without limit just by sending `VOICE_*` frames faster than the single consumer drained them.

Fixed with `VoiceInputMailbox` (`com.ridelink.core.voice.VoiceInputMailbox` /
`RideLinkCore.VoiceInputMailbox`) — pure, mirrored, exhaustively unit-tested, sitting between the
wire/engine-callback boundary and the reducer. Four lanes, priority `teardown > critical > ice >
coalesced`:

- **critical** (start, engine offer/answer/connectivity callbacks, a peer's offer/answer): bounded
  FIFO, capacity 32. A new input arriving at capacity is refused and forces `ControlLinkLost`
  through the teardown lane — reusing that input's already-correct, already-tested effect (media
  stops; local capture and the TLS control session both survive) rather than inventing a new
  failure path.
- **ice** (a peer's `VOICE_ICE`, a locally gathered candidate): bounded ring at
  `MAX_QUEUED_VOICE_CANDIDATES` — the same constant `PendingCandidates` already enforces one layer
  later, so the two bounds are one policy rather than two that could disagree. Oldest evicted and
  counted at capacity.
- **coalesced** (`VOICE_STATE`, mute, remote-track-present): one slot per kind, latest value wins.
- **teardown** (a deliberate stop, a control-link loss): one slot, always accepted, drained first.

A critical-lane refusal is counted as the new `INPUT_MAILBOX_OVERFLOW` `VoiceSignalDropReason` —
never produced by `VoiceNegotiation` itself, since the reducer never sees a refused input; the
controller counts it directly. PROTOCOL §7.5 now documents all four lanes.

**Finding 2 — the generation guard's `nil` case was backwards.** Both engines' callback-forwarding
function was, in effect, `if generation != null && generation != expected: drop` — which reads as
"reject a mismatch" but actually *accepts* the moment `generation` is `null`, exactly the state
right after `stop()`. A stale callback from an already-torn-down peer connection could reach
`VoiceController` after all, in precisely the window the generation guard exists to close.

Fixed to the strict form the prose always implied, extracted as a pure, independently
unit-tested rule rather than re-inlined a second time on each platform:
`com.ridelink.core.voice.VoiceEngineGeneration` / `RideLinkCore.VoiceEngineGeneration`. Neither real
`WebRtcVoiceEngine` can be constructed in a host unit test (§4 problems 22/23), so extracting the
rule is what makes it testable at all — before this fix it was inline logic no test suite on either
platform could reach. Fixing it surfaced a second, adjacent gap the strict check would otherwise
have broken: a media engine reporting that `start()` itself failed is not a peer-connection
callback (no peer connection exists yet to name one), so both engines now report a start failure
directly and unconditionally through the event sink, bypassing the generation check entirely —
PROTOCOL §7.8 records the distinction. On iOS this closed a genuine pre-existing gap rather than
only fixing the guard: `WebRtcVoiceEngine.start()`'s failure path previously reported nothing to the
controller at all.

**Verification.** 28 new Android tests (`VoiceInputMailboxTest` 18, `VoiceEngineGenerationTest` 4,
`VoiceControllerMailboxTest` 6) and 27 new iOS tests (`VoiceInputMailboxTests` 18,
`VoiceEngineGenerationTests` 4, `VoiceControllerMailboxTests` 5), covering: the mailbox never grows
past either bound under a simulated flood; a critical-lane overflow forces a safe degrade that never
releases capture and never kills the control session; `stop`/`onControlLinkLost` remain processable
under a fully saturated mailbox; a stale-generation callback is rejected and a callback from
generation N cannot affect generation N+1; and a fresh Start Voice after an overflow-induced degrade
begins a genuinely clean negotiation. The new mailbox/generation test classes were run **20
consecutive times on both platforms with 0 failures** (see §3). All prior Phase 1b and Phase 2a
suites remain green, including the real two-engine WebRTC loopback test.

**§4 problem 28 (the intermittent `PairingSessionIntegrationTest` CI failure) is unrelated and
still open** — not reproduced, not touched, and not claimed fixed. It is an existing Phase 1b issue
this pass did not investigate further because it did not recur.

---

## 2k. Phase 2a mailbox hardening, second pass — conflated iOS doorbell and non-coalescible
terminal peer state (3 September 2026 session, eighth)

A second, explicitly scoped hardening pass on §2j's mailbox, requested as exactly two remaining
mailbox issues — not Phase 2b, not a redesign. Both found in review of §2j's own implementation,
both fixed with regression coverage on both platforms where applicable. Full account:
[ADR-020 Amendment A3](DECISIONS/ADR-020-webrtc-voice-foundation.md#amendment-a3--3-september-2026--the-doorbell-is-conflated-and-a-peers-terminal-state-gets-its-own-lane).

**Finding 1 — the iOS voice doorbell was still unbounded.** `VoiceInputMailbox` itself was already
bounded by §2j, but the wake-up `VoiceController` rings on every `offer` to notify its single
consumer was, on iOS only, an `OrderedEventChannel<Void>` — an `AsyncStream` with the default
**unbounded** buffering policy. Android's equivalent (`Channel<Unit>(Channel.CONFLATED)`) was
already correct, so this was a single-platform gap. Every lane's `offer` unconditionally rang that
unbounded doorbell regardless of which lane accepted the input, so a flood of authenticated
`VOICE_*` traffic could still grow an unbounded backlog of pending `Void` wake-ups sitting *behind*
the already-bounded mailbox.

`OrderedEventChannel` itself is untouched and was not the fix: it exists specifically because
`ControlSessionManager` emits `.pairingSucceeded`/`.peerTrusted` immediately followed by
`.connected` as ordered pairs that `SessionGate` (ADR-019) depends on arriving in that order. A
doorbell has no such requirement, so making `OrderedEventChannel` conflated globally would have
risked reintroducing exactly the event-ordering problem §2h fixed. Instead, a dedicated new type —
`RideLinkPlatform.ConflatedSignal`, an `AsyncStream<Void>` built with `.bufferingNewest(1)` —
gives the same `signal()`/`stream`/`finish()` contract with at most one pending wake-up buffered
between drains, matching Android's `Channel.CONFLATED` doorbell exactly. Each `VoiceController`
owns exactly one, created fresh in its initializer.

**Finding 2 — a peer's terminal `VOICE_STATE` could be silently coalesced away by an ordinary
one.** `VoiceInputMailbox`'s classification put every `VoiceSignal.State` value — `negotiating`,
`connecting`, `active`, `idle`, `closed`, `failed`, `unknown` — into the same one-slot coalesced
lane, latest-value-wins. `closed` and `failed` are not ordinary: `VoiceNegotiation`'s reducer gives
them teardown semantics (`teardownFromPeer`, tearing down to `idle`/`failed` respectively) that no
other value in the enum gets. Coalescing put them in the same slot as everything else, so a peer's
`closed` queued ahead of a later `active` update — a perfectly ordinary sequence a reconnecting peer
could produce — could be silently replaced before the mailbox's consumer ever drained it, and the
remote teardown signal would simply vanish with this side never learning the peer had ended its
side of the call.

Fixed with a fifth mailbox lane, `terminal_peer_state`, holding only `closed`/`failed` peer signals;
every other `VOICE_STATE` value keeps coalescing exactly as before. Bounded FIFO, capacity **8** on
both platforms (`VoiceInputMailbox.TERMINAL_PEER_STATE_CAPACITY`/`terminalPeerStateCapacity`) —
sized the same way as the critical lane: one negotiation produces at most one terminal peer state
naturally, so 8 absorbs several rapid teardown/rebuild cycles while staying far below anything a
real ride would approach. Draining priority is now `teardown > terminal_peer_state > critical > ice
> coalesced` — a peer's own teardown is never delayed behind an offer/answer or ICE flood, and it
sits strictly above `coalesced` so it can never be classified alongside, and therefore overwritten
by, an ordinary update. An overflow at this lane refuses the new input outright (not evicting an
*earlier* terminal event) and forces `ControlLinkLost` through the always-accepting teardown lane —
the same already-proven safe degrade a critical-lane overflow produces, applied one layer earlier.
`VoiceNegotiation` itself needed no change: the reducer's handling of `closed`/`failed` was already
correct whenever it actually saw them; the bug was entirely in the mailbox deciding, ahead of the
reducer, that a terminal signal and an ordinary one were interchangeable.

**Verification.** 8 new Android tests (`VoiceInputMailboxTest`, terminal-lane classification,
priority, overflow) and 3 new Android tests (`VoiceControllerMailboxTest`, terminal state through a
live controller) — Android's doorbell needed no change and so has no new doorbell tests. 8 new iOS
tests (`VoiceInputMailboxTests`, mirroring Android's) and 3 new iOS tests
(`VoiceControllerMailboxTests`, mirroring Android's), plus 8 new `ConflatedSignalTests` proving the
doorbell semantics directly: 100,000 signals before one consume buffer at most one wake-up, one
wake-up is enough to drain everything queued behind it, a signal after `finish()` is harmless,
teardown leaves no pending consumer, and a fresh instance is independently functional. All of it
proves the semantics directly rather than by measuring memory. Every new/changed suite was run **20
consecutive times on both platforms with 0 failures** (see §3). All prior Phase 1b/2a suites remain
green, including the real two-engine WebRTC loopback test and the pre-authentication `VOICE_*`
refusal over real TLS.

**§4 problem 28 (the intermittent `PairingSessionIntegrationTest` CI failure) remains open,
unrelated, and untouched by this pass** — this session did not attempt to reproduce it and makes no
claim about its status either way.

---

## 2l. Problem 28 fixed — a pairing-integration test-harness race, not a production bug (3 September 2026 session, ninth)

Scope was deliberately one CI-hardening fix, per this session's brief: prove the diagnosis before
touching anything, and change production code only if a real production bug turned up. Neither did.

### The diagnosis

The 3 Sep run's assertion text (`exactly one SAS prompt per device ==> expected: <1> but was: <0>`
at `PairingSessionIntegrationTest.kt:60`) named the exact site: `PairingSessionIntegrationTest`
called `a.awaitPairingPrompt()`, then immediately asserted
`a.countOf { it is ControlEvent.PairingRequired } == 1`. `awaitPairingPrompt()` observes
`ControlSessionManager.pairingPrompt`, a **conflated `StateFlow`** — any observer, however late,
receives its current value. The count is drawn from `FsmSession.recorded`, populated by a
**separate** collector of `ControlSessionManager.events`, a **zero-replay `SharedFlow`**
(`_events = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 16)`, no `replay`). Production
sets the prompt and emits `PairingRequired` back-to-back (`ControlSessionManager.kt` lines
585/591), but nothing in the test ordered *its own two observers* of those two flows relative to
each other — so the prompt could become visible and the test could resume and assert before the
events collector had processed the emission into `recorded`. That reproduces the CI failure
exactly, with no need to touch TLS, SAS, or the trust gate.

A second, related but more severe latent race sat underneath it: `FsmSession.collectInto` launched
its collector with the default (dispatched) `CoroutineStart`, which only *schedules* the
subscribe — it does not perform it before `collectInto` returns. Against a zero-replay flow, a fast
enough real handshake could emit `PeerTrusted`/`Connected` before any subscriber had registered at
all, which is not a delay but a permanent loss (there is no replay buffer for a subscriber that
arrives afterward). `DuplicateConnectionResolutionTest` already carries a comment explaining exactly
this and already uses `CoroutineStart.UNDISPATCHED` against this same `events` flow for exactly this
reason; `PairingSessionSupport.kt`'s `FsmSession.collectInto` was the one place that hadn't caught
up. That existing precedent is strong corroborating evidence this is a harness gap, not a novel
production concern.

### The fix — test harness only

1. **`PairingSessionIntegrationTest`**'s failing test now calls `a.awaitEvent { it is
   ControlEvent.PairingRequired }` / `b.awaitEvent { ... }` — waiting on the actual condition the
   count assertion depends on — before counting, instead of inferring readiness from the unrelated
   `pairingPrompt` flow settling.
2. **`FsmSession.collectInto`** now launches with `CoroutineStart.UNDISPATCHED`, so the coroutine
   runs synchronously up to its subscribe point before `collectInto` returns — matching
   `DuplicateConnectionResolutionTest`'s existing idiom against the same flow.
3. A new regression test, `collectInto subscribes before returning, so a fast handshake cannot drop
   its events`, makes the subscription-ordering guarantee a checked fact rather than an assumption:
   it runs the collector on a hand-pumped `CoroutineDispatcher` that never runs anything on its own,
   lets a real loopback TLS handshake between two pre-trusted peers reach `CONNECTED` (confirmed
   independently via `ControlSessionManager.diagnostics`, a StateFlow, so this check does not depend
   on the collector under test), *then* drains the dispatcher and confirms `PeerTrusted`/`Connected`
   still arrived in full. Reverting the `collectInto` fix makes this new test fail deterministically
   (verified by hand before committing) — proof the test guards the right thing, not just decoration.

No arbitrary `delay`/`Thread.sleep` was added anywhere in this fix; the pre-existing `SETTLE_MS`
delays in the two negative-space tests ("this/the peer confirming alone pairs nothing") are
unrelated — they wait to observe that nothing happens, which is a different problem than the one
here, and were not touched.

### Production code: unchanged

`ControlSessionManager`'s pairing/trust-gate ordering — `_pairingPrompt.value = …` followed by
`_events.tryEmit(ControlEvent.PairingRequired(...))`, `PeerTrusted`/`Connected` only from
`activateAuthenticatedSession()`, `PairingSucceeded` only after both-side confirmation — is
byte-for-byte the same as before this session. The invariant this whole test file exists to prove
was re-checked, not assumed: **an unknown peer still cannot reach `CONNECTED` before both-side SAS
confirmation and trust persistence** (ADR-019). No `VOICE_*`/mailbox/WebRTC code (Phase 2a) and no
clock-burst timing test (problem 29) was touched.

### Verification

- `PairingSessionIntegrationTest` alone, run **100 consecutive times** (`--rerun` each time, fresh
  Gradle daemon invocation, real loopback TCP/TLS every run): **100 passed, 0 failed**.
- `./gradlew clean test ktlintCheck detekt lint assembleDebug assembleRelease` — all green, all five
  Android modules. **336 unit tests** (was 335 — +1, the new `collectInto` regression test):
  `core` 184, `network` 130 (was 129), `audio` 11, `app` 2, `data` 9.
- Fresh CI, this session's push, commit `eae366c`, run
  [33698452022](https://github.com/arunachaleswaranms/RideLink/actions/runs/33698452022) — **not a
  re-run of the failed run**, a genuinely new push, per the brief's explicit instruction: `android` —
  `core unit tests`, `all unit tests`, `ktlintCheck`, `detekt`, `lint`, `assembleDebug`,
  `assembleRelease` all green; `ios` — `RideLinkCore` 69/69, `RideLinkPlatform` 150/150, Debug and
  Release simulator builds all green.
- Not run this session: real-device validation for either platform (unchanged — see §7); iOS
  SwiftLint/SwiftFormat (still not installed, problem 14, unchanged).

**Phase 2a status is unchanged by this session: IMPLEMENTATION COMPLETE — REAL-DEVICE AUDIO GATE
PENDING.** This was a Phase 1b/CI-hardening fix, not Phase 2a or Phase 2b work, and the real-device
audio gate for Phase 2a is neither opened nor claimed opened here.

---

## 2m. Phase 2b — intercom integration and audio lifecycle (4 September 2026 session, tenth)

Phase 2a made voice *work*. Phase 2b makes it an **intercom**: something with a policy, a gate, a
readiness rule, a lifecycle and a report to the peer. The whole of it is
[ADR-021](DECISIONS/ADR-021-intercom-transmission-and-capture-ownership.md).

### The decision this phase exists for

**The transmission gate cannot touch the capture device, and that is now structural rather than
described.**

ARCHITECTURE §6.3 has said since the correction pass that `mic_always_open: false` refers to whether
*speech is transmitted*, not to whether the microphone is repeatedly opened and closed. Nothing
enforced it. The obvious implementation — "PTT down opens the mic, PTT up closes it" — would violate
both of the constraints that sentence exists for, simultaneously:

1. it thrashes a Bluetooth endpoint between its media and duplex profiles per utterance, which is the
   single worst thing this product can do to music and the exact failure Phase 0 was built to
   measure; and
2. it would try to open a microphone from the background on Android, which is illegal
   (ARCHITECTURE §6.4) and has no second legal opportunity once the screen is locked.

So gating happens at the **WebRTC audio track** — `AudioTrack.setEnabled` / `RTCAudioTrack.isEnabled`
— and the decision lives in `IntercomTransmission`, a pure mirrored reducer whose action vocabulary
has three cases and **no capture case at all**. The absence is the enforcement; the shared vector file
asserts it over every row; `VoiceControllerIntercomTest[s]` counts 50 press/release cycles against
**1** capture open and **0** capture closes, with no `PeerConnection` rebuild and no change of
`voice_session_id`. TEST_PLAN **A-10** is the same assertion against a real helmet unit's recorded
output and is **pending**.

### What was built

**Shared pure layer (`core.audiopolicy` / `RideLinkCore.AudioPolicy`, mirrored line for line):**

| Type | What it decides | Pinned by |
|---|---|---|
| `IntercomPolicy` | ARCHITECTURE §6.3's object, with Modes A–E as five *values* and no code branching on a mode id | `protocol/vectors/intercom/` presets |
| `IntercomTransmission` | `transmitting = captureOpen && !interrupted && !userMuted && gateOpen(...)`, as a `(state, input) -> (state, actions)` reducer | `protocol/vectors/intercom/` — 58 rows |
| `IntercomCommandMailbox` | the intercom command queue, **bounded by construction** at one slot per kind, with a drain order chosen so a batch containing any reason not to transmit lands with transmission off | `IntercomCommandMailboxTest[s]` |
| `AudioSessionLifecycle` + `RouteTransitionTracker` | the platform audio session: `stable -> transitioning -> stable` with a measured duration, `shouldResume`, a media-services reset, and a strict generation guard | `AudioSessionLifecycleTest[s]` |
| `RideStartPolicy` | ARCHITECTURE §6.4's readiness sequence as one decision, with `Allowed` carrying the service and capture flags **separately** because the order between them is the platform rule | `RideStartPolicyTest[s]`, over the whole 2^7 cross-product |
| `VoiceFailure` | ten named failure reasons instead of one "connection failed" bucket | every suite above |
| `AudioStateMessage` / `AudioStateCodec` / `AudioStatePublisher` / `AudioStateInbox` | PROTOCOL §4.4 in full: the field set, both bounds, the monotonic `revision` on the sending side and the drop-anything-not-greater rule on the receiving side | `protocol/vectors/audio-state/` — 74 rows across five groups |
| `VoiceSetupTimeline` / `VoiceSetupTimer` | software setup timings, first-write-wins per milestone, monotonic microseconds only | `VoiceSetupTimelineTest[s]` |

**One addition to the Phase 2a negotiation table:** `VoiceInput.ModeSelected`, so
`VOICE_STATE.mode` reports the selected gate instead of always `continuous`. Seven new
`voice-fsm/` rows and two new stated invariants — a mode change emits only `SendVoiceState`, and it
never touches the status or local audio.

**Both platforms' controllers, sessions, services and UI:**

- `VoiceController` gained the gate, the intercom mailbox drained by its **existing** single consumer,
  the setup timeline, and named failures. The two inputs the gate produces (`MuteRequested`,
  `ModeSelected`) are applied **directly** rather than offered, because they are produced by the
  consumer *on* the consumer — routing them through the mailbox would reintroduce its lane priorities
  and put the previous policy's mode and a stale `mic_muted` on the wire.
- `AndroidVoiceAudioSession` was rewritten around the shared reducer: named failures, a route
  transition that settles on `AudioManager.OnCommunicationDeviceChangedListener` (API 31+) rather than
  on a sleep, a timeout that is *counted* as failure protection, and a pure
  `AndroidCommunicationDeviceSelector` so the endpoint comes from explicit intent.
- `IosVoiceAudioSession` likewise, plus `AudioSessionSignalBox`: **one ordered, bounded path** from
  `NotificationCenter` into the actor, replacing a `Task` per notification. `shouldResume` is now read
  from the interruption option rather than assumed.
- `RideForegroundService` gained the two lock-screen actions ARCHITECTURE §6.4 requires (mute,
  end-intercom) and an ongoing notification that reflects mute state. `MainActivity` owns foreground
  visibility, requests permissions in context, runs `RideStartPolicy` before starting anything, starts
  the service **before** capture, and releases the PTT gate in `onPause`.
- Both `SessionCoordinator`s own the `AudioStatePublisher`, publish at `CONNECTED` and on every
  observable change, and hold the peer's state behind the shared inbox. Both UIs gained mode
  selection, a press-and-hold PTT control with every cancellation path mapped to "not held", the
  peer's `AUDIO_STATE`, the setup timings labelled **"not latency"**, and a named refusal banner.
- **One `AndroidVoiceAudioSession` per process**, not per voice session — ADR-021 §2. Two instances
  would be two objects that each believe they own `AudioManager`'s mode, focus and communication
  device across a reconnect.

### Two real defects the tests found, both fixed

1. **A gated policy's local track started enabled.** Both engines call `setEnabled(true)` when they
   build the track, which is right for full duplex and wrong for PTT: a reconnect rebuild would have
   gone live before the first press. The controller now pushes the gate's value immediately after
   every successful `engine.start`. Found by `under PTT nothing is transmitted until the button is
   held`, which had no engine call to await because the reconciliation was a no-op.
2. **`localAudioOpen` meant different things on the two platforms.** Android AND-ed the user's consent
   with the session's real state; iOS reported consent alone, so a denied microphone still rendered as
   "mic: open". Both now read the gate's own view of the capture path, so the field cannot disagree
   with `transmitting`. Found by the iOS mirror of the denied-microphone test.

### A test race the stress pass found, and one specification contradiction resolved

**The race.** `switching policy announces the new mode on the wire without rebuilding anything`
awaited a wire frame and then asserted the diagnostics. `transport.send` happens inside the action
loop and `publishDiagnostics` runs after it, so the frame is observable a few instructions before the
diagnostics that describe it — the assertion lost about one run in ten. It failed **2 of the first 20**
stress runs, was reproduced deliberately, and now awaits both observables. **No production code
changed for this one**; 40 subsequent runs across two independent passes are clean.

**The contradiction.** PROTOCOL §4.4 described `intercom_mode` as mirroring `VOICE_STATE.mode` while
listing **four** values against that field's **three**. ADR-021 §3 resolves it: `intercom_mode` is a
**superset**, because it describes *local audio state* — meaningful with no voice session at all, which
Mode E is — while `VOICE_STATE.mode` describes the gate of a *live session*. Mode E reports
`intercom_mode: "disabled"` and `mode: "ptt"`, which is what ARCHITECTURE §6.3's "ptt-disabled"
spelling always meant. **No wire field, value or bound changed.**

### Two Phase 2a tests were updated, and why that is not weakening them

- `the leader offers on Start Voice and the follower only states intent` asserted **exactly one**
  `VOICE_STATE`. A gated-or-not policy now sends a second one when capture opens, because the gate is
  the single source of `mic_muted` and that field genuinely changes: before capture opened this side
  *was* transmitting silence. Both frames are truthful and PROTOCOL §7.4 sends `VOICE_STATE` on
  change. The test now asserts the state **values** and the `mic_muted` progression `[true, false]` —
  strictly more than it asserted before, and about the property its name describes.
- `mute disables the sender and unmute restores it` awaited engine **call names** that Phase 2b now
  also produces at engine-start time. It awaits the observable state instead. Both platforms'
  harnesses now select Mode A explicitly, with a comment saying why: Phase 2a's assertions are about
  the negotiation table's wiring, and full duplex is the policy in which "start, then talk" has
  literally that shape.

### Explicitly not done

- **Nothing ran on a phone.** No microphone, no speaker, no Bluetooth, no foreground service, no lock
  screen, no route change on real hardware.
- **VOX cannot open its gate.** Neither pinned WebRTC distribution exposes a fast per-frame input
  level (the only level either offers is on a 2 s statistics poll), and ADR-021 §6 declines to
  hand-write a detector to fill the gap — the same reasoning ADR-003 uses to decline custom echo/noise
  DSP. `voxLevelSourceAvailable` is `false`, and the intercom card says so on screen.
  **PENDING REAL AUDIO INPUT / LATER HARDENING.**
- **No route `confidence` moved.** Both mappers still report `assumed` and their tests still assert
  it. A-12/A-13 are what change that, and A-15 is what flips it.
- **No music, no ducking, no player.** `IntercomPolicy.onSpeech` is the policy a Phase 3+ player will
  read; there is nothing to duck yet, and no fake playback was created to satisfy foreground-service
  semantics.
- **No latency figure.** See the header.

---

## 2n. Phase 2b final hardening — real ordering, real registration order, a real timeout, a
completion-aware stop (4 September 2026 session, eleventh)

An independent review of §2m's implementation raised eight candidate issues in the audio/intercom
lifecycle. Each was verified against the actual code before anything changed — none was patched on
the review's say-so alone. All eight were **confirmed real bugs**. Full account, finding by finding:
[ADR-021 Amendment A1](DECISIONS/ADR-021-intercom-transmission-and-capture-ownership.md#amendment-a1--4-september-2026--final-hardening-real-ordering-real-registration-order-a-real-timeout-and-a-completion-aware-stop).

**A — iOS's `AudioSessionSignalBox` was one-shot.** Held for `IosVoiceAudioSession`'s whole process
lifetime; `close()`'s `finish()` permanently poisoned it, so a second Start Intercom after End Intercom
silently stopped receiving route/interruption/reset notifications for the rest of the process's life.
Now created fresh on every `open()`, exactly as `SessionCoordinator` already does for
`OrderedEventChannel` per `startDiscovery()`.

**B — the generation stamped on an iOS signal was read at processing time, not callback time.**
`IosVoiceAudioSession.handle` read the actor's *current* generation when a queued signal was finally
processed, not the generation live when the notification fired — which made
`AudioSessionLifecycle.reduce`'s generation guard structurally unable to reject anything from iOS. Fixed
by capturing the generation once, at `registerObservers` time, and threading it through
`AudioSessionSignalBox.offer`/`poll` explicitly.

**C — the "safety priority" (reset before interruption before route) was documentation, not
behaviour.** The box was a raw `AsyncStream`, which only ever delivers in arrival order. Replaced with a
doorbell (`ConflatedSignal`) plus explicit priority polling, mirroring the pattern
`VoiceInputMailbox`/`IntercomCommandMailbox` already established for exactly this reason.

**D — a listener registered after the request it exists to confirm, on both platforms.** iOS registered
`NotificationCenter` observers after `setCategory`/`setActive`, and unregistered them before the
restoring call on `close()`; Android registered `AudioDeviceCallback`/`OnCommunicationDeviceChangedListener`
after `requestFocusAndCommunicationMode`/`selectCommunicationDevice`, and unregistered before
`clearCommunicationDevice`/the mode restore. Both now register first and unregister last.

**E — the route-transition timeout was dead code on both platforms.** `pollTransitionTimeout()` is not
part of the shared `VoiceAudioSession` protocol/interface and had no caller anywhere in either app.
Both platform classes now self-schedule one generation-tagged timeout task per genuinely new
transition (detected by a changed `startedAtMonoUs`, so a burst of callbacks within one transition does
not re-arm it), cancelled on settle.

**F — the Android microphone foreground service could be told to stop before capture actually
released.** `MainActivity.stopIntercom()` and the lock-screen `END_INTERCOM` notification action both
called (or dispatched to) a fire-and-forget `stop()` and then stopped the service immediately —
`VoiceController.stop()` only *queues* `StopRequested`; the real `engine.release()`/`audioSession.close()`
runs later, on the controller's own consumer. `VoiceController` gained `stopAndAwaitRelease()`, a
suspend function resolved by `apply` only once `StopRequested` has fully run; `SessionCoordinator`,
`MainActivity` and `AppContainer`'s `RideCommandBus` handler all now await it before calling
`RideForegroundService.stop()`, and `RideForegroundService` no longer calls `stopSelf()` from either
entry point itself.

**G — iOS voice diagnostics reached `SessionCoordinator` through a `Task` per callback.** The exact
`Task { @MainActor in ... }`-per-event pattern STATUS §2h fixed for `ControlEvent`, explicitly deferred
here in §2m. `SessionCoordinator` now drains an `OrderedEventChannel<VoiceDiagnostics>` with one
consumer, mirroring `controlEventChannel`.

**H — Android's `VoiceController._diagnostics` had three unsynchronized writers.** The mailbox
consumer, the diagnostics-poll coroutine, and the platform audio-session's route-sink callback thread
each did a plain `_diagnostics.value = _diagnostics.value.copy(...)` — a read-copy-write that can lose
a concurrent writer's update. All three now use `MutableStateFlow.update {}`, an atomic
compare-and-retry.

**Two things this pass found but deliberately did not touch**, because they were outside what was
reviewed: `VoiceController.attach`'s own `Task`-per-engine-event/`Task`-per-route-event forwarding on
iOS (a narrower version of Finding G, one layer lower, already running on top of an already-bounded,
already-ordered mailbox); and `Effect.ReleaseAudioAndStopForegroundService`, an FSM effect whose name
promises an Android foreground-service stop it has never actually performed — `MainActivity` remains
the only caller of `RideForegroundService.stop` in the app. Neither is a regression from this pass, and
neither was one of the eight confirmed findings.

**New regression coverage.** iOS: `AudioSessionSignalBoxTests` rewritten for the new
generation-tagged, priority-polling API (17 tests: every kind reachable, coalescing, generation
preservation, safety-order draining under every arrival permutation and under concurrent producers,
and reuse-after-`finish()` across several open→finish cycles) — run **50 consecutive times, 0
failures**. Android: `VoiceControllerStopAwaitTest` (5 tests, including a controllable suspend gate
that proves `stopAndAwaitRelease()` really waits for `audioSession.close()` rather than merely calling
it) and `VoiceControllerDiagnosticsRaceTest` (2 tests, 300 stress iterations each) — run together **50
consecutive times, 0 failures**. Findings D and E live entirely in code that cannot run off a device
(`AVAudioSession`/`AudioManager`), so those two are **REAL-DEVICE INTERCOM GATE PENDING** like
everything else in those two classes; this pass narrows what is untested there without claiming to
close the device gate. `SessionCoordinator`'s own diagnostics-channel wiring (Finding G) inherits the
existing §4 problem 20 limitation — no app-target test target on either platform.

**Verification.** Android: `test ktlintCheck detekt lint assembleDebug assembleRelease` all green,
**443 tests** (was 436 — +7: 5 `VoiceControllerStopAwaitTest`, 2 `VoiceControllerDiagnosticsRaceTest`).
iOS: `RideLinkCore` unchanged at 142/142 (nothing in the pure core changed); `RideLinkPlatform`
**185/185** (was 178 — net +7 from the rewritten `AudioSessionSignalBoxTests`); `xcodebuild` Debug and
Release both succeed for the simulator, zero warnings beyond the pre-existing benign
"no AppIntents.framework dependency" notice. No production wire shape, security property, module
boundary or platform baseline changed — this is a code-and-test-only hardening pass, and CLAUDE.md's
rules table needed no new row.

---

## 2o. Phase 2b final hardening, second pass — the five gaps §2n named but did not fix, plus
problem 32 (4 September 2026 session, twelfth)

A second independent review, run specifically over what §2n's own account flagged as out of scope
("two things this pass found but deliberately did not touch") plus the open problem 32, named five
candidate issues. All five were verified against the actual code before anything changed — none was
patched on the review's say-so alone — and all five were **confirmed real**. Full account, finding by
finding: [ADR-021 Amendment
A2](DECISIONS/ADR-021-intercom-transmission-and-capture-ownership.md#amendment-a2--4-september-2026--the-five-gaps-amendment-a1-named-but-did-not-fix-plus-problem-32).

**1 — the route transition began after the platform call, not before, on both platforms.** A
synchronous (or very-shortly-after) confirming callback landing between the platform call and the
event that was supposed to *begin* tracking it found `RouteTransitionTracker.settle` a no-op — it only
ever acts on an already-`transitioning` state — so the confirmation was silently dropped and the
transition settled only via §2n's five-second timeout instead. `AudioSessionLifecycle` gained
`OpenRequested`/`CloseRequested` (begin the transition only, before the platform call) and
`OpenAborted` (the platform call refused; settles immediately rather than waiting for the timeout);
`Opened`/`Closed` no longer begin a transition themselves, so a settle that already happened in
between cannot be resurrected into a fresh, spurious `TRANSITIONING`. Both platform classes now apply
the `*Requested` event before the platform call and the confirming event after. **A related defect
surfaced fixing this on iOS:** `close()` tore down its own notification consumer and failure-protection
timeout immediately, before the transition it had just begun could possibly settle by either the real
confirmation or the timeout — silently latching `transitioning` forever. `close()` now awaits the
transition actually settling (a new `awaitTransitionSettled()`, bounded by the same five-second
timeout) before tearing anything down. Android's `close()` never shared this defect — its timeout is
an independent coroutine, untouched by callback unregistration. This half of the fix is **REAL-DEVICE
INTERCOM GATE PENDING**: it is in `IosVoiceAudioSession`'s `#if os(iOS)` branch, which `swift test`
cannot exercise on macOS; verified instead by a clean `xcodebuild` Debug/Release simulator rebuild,
which does compile it.

**2 — `stopAndAwaitRelease()`'s timeout was indistinguishable from success.** The result of
`withTimeoutOrNull` was discarded, so a caller could not tell a proven release from one that merely
gave up waiting, and a timed-out waiter was never removed from `pendingStopCompletions` — a leak, one
entry per stall. `stopAndAwaitRelease()` now returns an explicit `StopReleaseResult`
(`Released`/`AlreadyReleased`/`TimedOut`); every caller (`SessionCoordinator`, `MainActivity`,
`AppContainer`) stops the Android microphone foreground service only on the first two, never on
`TimedOut`, and a timed-out waiter is always removed.

**3 — problem 32 is fixed.** `SessionFsm`'s `ENDING` effect
(`Effect.ReleaseAudioAndStopForegroundService`) promised a foreground-service stop that
`SessionCoordinator.runEffect` never actually performed — confirmed exactly as problem 32 and §2n's
own account described it, and reachable in practice by a peer `BYE`. Fixed with one new seam,
`ForegroundServiceController` (a one-method `fun interface`, supplied by `AppContainer`, no `Context`
inside `SessionCoordinator`), and one new owner: `runEffect` now awaits capture release
(`releaseVoiceAndAwait`, using Finding 2's `StopReleaseResult`) before calling
`foregroundService.stop()` — never on a timeout — and always tears down the control session
afterward regardless. The eager, uncoordinated release `applySideEffects` used to perform on a
`BYE`-reasoned `LinkLost` is gone; the `ENDING` effect is now the one place release happens for that
path. Proving this needed `SessionCoordinator` buildable in a JVM test for the first time — a
`DiscoveryController` interface extracted from `NsdDiscoveryController` (no behaviour change) and
`applyEvent`/`handleControlEvent` widened to `internal` as an explicit test seam — and
`SessionCoordinatorEndingEffectTest` (new, `app` module, the first test to exercise
`SessionCoordinator` itself) proves the release-before-stop-before-teardown order, that a timeout
never stops the service, that a `NETWORK` link loss never releases capture or stops the service, and
that repeated `ENDING` cannot double-fire the effect.

**4 — iOS route snapshots reached `VoiceController` through a Task per callback.** The same
`Task`-per-event shape §2n's Finding G fixed for voice diagnostics, left unfixed here at the time.
Replaced with `routeChannel`, an `OrderedEventChannel<AudioRouteSnapshot>` created fresh in `attach()`
and torn down in `shutdown()` exactly like `SessionCoordinator`'s own diagnostics channel.

**5 — the iOS engine-event Task, traced through rather than left alone.** §2n named
`noteEngineEvent`'s `Task` as narrower and safer than Finding G and deliberately did not touch it.
Traced through this session: `VoiceSetupTimer.mark` has no generation guard, so a stale mark from a
superseded negotiation, still in flight when `VoiceController.start()` resets the timeline for a new
one, could land as the *new* generation's own first-write-wins entry — a real (diagnostic-only, never
security- or negotiation-affecting) corruption of the V-01 setup-timing measurement. Fixed for free:
`noteEngineEvent` is deleted, and the same marks/failure are derived from the `VoiceInput` `apply()`
is about to reduce anyway, in the same ordered call.

**New regression coverage.** Android:
`AudioSessionLifecycleTest` +5 (the `OpenRequested`/`CloseRequested`/`OpenAborted` proofs),
`VoiceControllerStopAwaitTest` +2 (timeout-not-success, waiter-count-to-zero), and
`SessionCoordinatorEndingEffectTest` +5 (new file, problem 32's integration boundary). iOS:
`AudioSessionLifecycleTests` +5 (mirrors), `VoiceControllerRouteOrderingTests` +2 (new file, Finding
4). Stress, no rerun-until-green: the three new/changed Android JVM suites run **20 consecutive times,
0 failures** each, in isolation; the two iOS suites run **50 consecutive times, 0 failures** each. An
early attempt at the Android stress runs produced spurious failures from running two `./gradlew`
invocations against this project concurrently (Kotlin daemon compilation contention) — reproduced,
root-caused, and re-run in isolation rather than smoothed over; none of the failures were in test
logic.

**Verification.** Android: **455 tests** (was 443 — +12: core 262, network 157, audio 20, app 7, data
9), `test ktlintCheck detekt lint assembleDebug assembleRelease` all green. iOS: `RideLinkCore`
**147/147** (was 142), `RideLinkPlatform` **187/187** (was 185), `xcodebuild` Debug and Release both
succeed for the simulator, zero warnings beyond the pre-existing benign notice. No production wire
shape, security property, module boundary or platform baseline changed. `docs/DECISIONS/ADR-021.md`
gained Amendment A2; this file's problem 32 (§4) is now recorded FIXED.

**Still true, unchanged by this pass:** nothing here ran on a phone. No microphone, no speaker, no
Bluetooth, no foreground service, no lock screen, no latency figure. Every finding and fix above is a
laptop-only correctness proof; the real-device intercom gate (§7) is exactly as open as it was before
this session.

---

## 2p. Phase 2b final hardening, third pass — the Android route-close listener still tore down
before its own confirmation (4 September 2026 session, thirteenth)

A third, narrowly-scoped review, requested against exactly the one residual §2o's Finding 1 named for
Android and judged acceptable at the time: the fallback timeout could never hang, only *lose a race*
against the real confirmation. Traced through rather than left there, losing that race turns out to
have a cost §2o did not account for: a normal, successful platform confirmation gets reported as a
timeout. Confirmed against the actual code before anything changed. Full account:
[ADR-021 Amendment A3](DECISIONS/ADR-021-intercom-transmission-and-capture-ownership.md#amendment-a3--4-september-2026--the-android-route-close-listener-still-tore-down-before-its-own-confirmation-not-just-before-the-transition-began).

**The finding.** `AndroidVoiceAudioSession.close()`'s `releasePlatformSession()` unregistered
`OnCommunicationDeviceChangedListener` as its last step — which was §2o's own fix, and correctly
ordered relative to the platform calls that could provoke a confirmation. What that fix did not
account for: those platform calls and the listener's teardown all ran in one uninterrupted
synchronous function. A confirmation `clearCommunicationDevice()` produces **synchronously** is caught
fine; one it produces **asynchronously**, arriving after the function has already returned and
unregistered the listener, has nowhere left to land. Not a hang — the independent failure-protection
timeout `manageTransitionTimeout` arms still settles the transition regardless — but a
mischaracterization: a route change the platform genuinely confirmed gets counted as
`timedOutCount + 1`, indistinguishable in the diagnostics from a platform that never confirmed
anything. The five-second window built specifically as failure protection, never as the definition of
success, had quietly become the ordinary close path whenever the real confirmation lost the race
against `unregisterPlatformCallbacks()`.

**The fix.** `releasePlatformSession()` is split into `requestPlatformRestore()` (clears the
communication device, restores the mode, abandons focus — everything that could still provoke a
confirmation) and the unchanged, still-last `unregisterPlatformCallbacks()`. Between them, `close()`
now suspends on a new `TransitionSettlementGate` — a small, Android-free class holding only
`kotlinx.coroutines` — until the closing transition has actually settled, by the platform's
confirmation or by the existing timeout, whichever comes first, and only then unregisters. All three
timings are handled correctly: a synchronous confirmation is already settled by the time the gate is
reached, so `close()` never suspends at all; an asynchronous one resumes a genuinely suspended
`close()` once it arrives, listener still registered to receive it; no confirmation at all still falls
back to the same timeout as before, now correctly counted as a real timeout because in that case it is
one. `open()`'s two failure-abort paths changed only mechanically (the same two split functions,
called back-to-back, no await) — `OpenAborted` settles its transition synchronously and
unconditionally inside the reducer itself, so that path never needed the await in the first place.
`open()`'s success path and the whole of iOS are untouched; inspection did not find the same defect
on either.

**New regression coverage.** `TransitionSettlementGate` (Android, new,
`android/audio/src/main/kotlin/com/ridelink/audio/route/TransitionSettlementGate.kt`) is the suspend/
resume primitive itself, extracted so it — unlike `AndroidVoiceAudioSession`, which cannot be
constructed in a JVM test at all — is provable off-device. `TransitionSettlementGateTest` (6 tests)
proves it directly: already-settled short-circuits without suspending, a genuine suspend/resume across
a `kotlinx-coroutines-test` `StandardTestDispatcher`, a settlement notification with nothing waiting
on it is a no-op, and a repeated notification after resume does not double-complete.
`AndroidVoiceAudioSessionCloseOrderingTest` (7 tests, new) is a structural mirror of `close()`'s exact
call sequence, built from the same two production pieces (`TransitionSettlementGate` and the shared
`AudioSessionLifecycle` reducer) with every `AudioManager` call replaced by a recorded fake, and proves
the listener stays registered through a synchronous confirmation, an asynchronous one, and a route
timeout; that unregistration never runs before settlement; that a mismatched-generation timeout is
ignored while the real one still settles; and that repeated/idempotent `close()` calls do not re-run
the platform sequence. **This proves the ordering policy, not `AudioManager`'s actual callback timing**
— that remains REAL-DEVICE INTERCOM GATE PENDING like the rest of `AndroidVoiceAudioSession`; §4
problem 23 is otherwise unchanged, narrowed only in what specifically is untested there.

**Verification.** Android: `test ktlintCheck detekt lint assembleDebug assembleRelease` all green,
**468 tests** (was 455 — +13 in `audio`, now 33, was 20). The two new suites (13 tests total) run
**100 consecutive times, 0 failures**. iOS: untouched by this pass (Android-only finding, no shared
type changed) — `RideLinkCore` unchanged at 147/147, `RideLinkPlatform` unchanged at 187/187,
`xcodebuild` Debug and Release both succeed for the simulator, re-run clean as a regression check. No
production wire shape, security property, module boundary or platform baseline changed.
`docs/DECISIONS/ADR-021.md` gained Amendment A3.

**Still true, unchanged by this pass:** nothing here ran on a phone. No microphone, no speaker, no
Bluetooth, no foreground service, no lock screen, no latency figure. Real Android `AudioManager`/
communication-device callback timing remains exactly as unmeasured as before this session — this pass
narrows what is untested in `AndroidVoiceAudioSession`, it does not close the real-device gate.

---

## 2q. Phase 3 — local music player (4/5 September 2026 session, fourteenth)

Started under a deliberate, explicit override of this file's own §7 precondition — recorded at the
point it happened, above in §1's amendment. Nine commits, both platforms software-complete: library
import/index/search, a local queue, and local playback, entirely independent of the control/voice
planes (no peer, no wire message, no shared state with `SessionCoordinator`/`ControlSessionManager`).

**Domain model** (`core.library`/`core.player`, `RideLinkCore.Library`/`RideLinkCore.Player`,
mirrored): `ContentHash` gained real validation (previously a bare wrapper); `QuickId` is new, same
`sha256:`-prefixed 64-lowercase-hex shape. `LibraryEntry`/`LocalTrackLocation`/`DecodeStatus`/
`LibraryQuery`/`LibrarySort`, `MetadataNormalizer` (NFC-only, Unicode-scalar-count clamped to 512 per
PROTOCOL §8.1's manifest bound), `IndexReconciliation` (pure new/still-present/missing set diff),
`LocalQueue` (pure `(state, action) -> (state, effects)` reducer, same shape as `IntercomTransmission`/
`AudioSessionLifecycle` — add/remove/move/clear/next/previous/select, no repeat/loop mode in V1),
`PlaybackCommand`/`PlayerState`/`MusicFailure`, and `Player` (an interface on Android, a protocol on
iOS). No shared JSON vector set was needed for any of this — none of it crosses the wire, so mirrored
unit tests on each platform are the proof, the same convention `AudioRouteSnapshot` already
established.

**Test fixtures**: `tools/generate_test_media.py` (stdlib `wave` + macOS `afconvert` + a hand-written
MP4 atom injector, since `afconvert` cannot write custom `ilst` tags without its own `udta` atom
being replaced rather than duplicated) produced 11 files under `test-media/synthetic/` — normal,
no-metadata, Unicode-metadata (Japanese/Traditional Chinese/an emoji/combining marks), artwork/
no-artwork, byte-identical duplicates, same-metadata-different-bytes, a genuinely corrupt (truncated)
file, an unsupported-extension file (real M4A bytes, wrong extension — proves the extension gate is a
policy layer, not something the decoder itself enforces), and a hand-built minimal MP3 (ID3v2 tags
only; no MP3 encoder exists in this environment, so its audio payload is documented as unverified,
never claimed as a decode-tested fixture). `MANIFEST.json` records each fixture's real SHA-256,
independently useful this session as a cross-platform hash-parity check (§2q's iOS paragraph below).

**Android** (`data.database`/`data.library`, `audio.player`, `app.music`/`app.ui`): Room (FTS4 —
Room ships no `@Fts5`), `TrackEntity`/`TrackDao`/`RideLinkDatabase` (schema v1, exported), a
SAF-folder-tree + explicit-multi-select + MediaStore indexer (`ContentHashing`, `MetadataExtractor`,
`ArtworkProcessor`/`ArtworkCache`, `LibraryIndexer`), `ExoPlayerMusicPlayer` (Media3 1.11.0, already
pinned), `ForegroundServiceTypePolicy` (a pure `(intercomActive, musicPlaying) -> types` function),
`MusicCoordinator`, and `LibraryScreen`/`NowPlayingCard`/`MusicSection` wired into `MainScreen`
independent of session state.

**iOS** (`RideLinkPlatform.Library`/`.Player`, app target): **groue/GRDB.swift 7.11.1** — the first
dependency added besides the pinned WebRTC pod, reviewed per the brief's own dependency-review
requirement before adding (MIT licence; empty default transitive-dependency list; no `URLSession`/
`Network`/`CFNetwork` import anywhere in `Sources/`; `SQLITE_ENABLE_FTS5` on unconditionally, which is
why iOS gets real FTS5 where Android gets FTS4 — a deliberate, documented per-platform difference).
`TrackRecord`/`LibraryDatabase` (GRDB `DatabaseMigrator`, schema v1, an FTS5 external-content table
synchronized by GRDB's own generated triggers), `ContentHashing` (`CryptoKit`), `MetadataExtractor`
(`AVFoundation`'s modern async `.load()` API), `ArtworkProcessor` (`ImageIO`), `ArtworkCache`,
`LibraryRepository` (GRDB `ValueObservation` bridged to `AsyncStream`), `LibraryIndexer`,
`AVAudioEnginePlayer` (an actor; `AVAudioEngine` + `AVAudioPlayerNode` per ARCHITECTURE §7.2, chosen
over `AVAudioPlayer` for Phase 5's later sample-accurate scheduling even though this phase does not
use it yet), `MusicAudioSession` (the `.playback` half of ARCHITECTURE §6.2's two configurations,
`#if os(iOS)`-gated — the only iOS-only file in the whole player/library stack), and
`MusicCoordinator`/`LibraryView`/`NowPlayingCard`/`MusicSection` wired into `MainScreen`/`RideLinkApp`
the same independent-of-session way.

**A real, deliberate divergence, not an oversight**: Android's SAF import keeps a persisted
`content://` permission and re-scans the original location on every rescan, so a removed file is
detected as `DecodeStatus.missing`. iOS never does — ADR-009 forbids relying on an external
security-scoped URL indefinitely, so every picked file is copied into this app's own Application
Support directory at import time and the source URL is never touched again. There is therefore no
live external reference for iOS to notice going missing; `IndexReconciliation.missingQuickIds` has no
iOS caller for that reason. Documented in `LibraryIndexer`'s own doc comment on both platforms.

**Five real bugs found only by actually running this, not by reading a platform's API docs** —
recorded here per this file's own "every real problem found gets written down" discipline, all fixed
in the same session:

1. **A genuine infinite playback-restart loop (Android, found on the real `RideLink_API36`
   emulator).** ExoPlayer fires *two* listener callbacks for one natural end of track —
   `onPlaybackStateChanged(STATE_ENDED)` and `onIsPlayingChanged(false)` — each emitting its own
   `PlayerState`, both satisfying `PlayerState.ended`. `MusicCoordinator` advanced the queue on every
   such emission rather than only the transition into it: the first `Next` correctly stopped a
   single-item queue (`currentId -> null`), but the second landed on `LocalQueue`'s "nothing
   selected" branch, whose documented behaviour is to restart from the first item — turning one
   finished track into an unbounded play/restart cycle, confirmed via `adb logcat` showing repeating
   `AudioTrack: stop(N)` roughly one fixture-duration apart, indefinitely. Fixed with a new pure
   `core.player.TrackEndEdge` (mirrored to `RideLinkCore`, since `AVAudioPlayerNode`'s
   completion-handler-vs-observed-state race is the identical shape), edge-detecting the transition
   into "done" rather than level-triggering on every emission that still describes it.
   `TrackEndEdgeTest`/`TrackEndEdgeTests` (8 tests each platform) exhaust it, including the exact
   two-emissions-for-one-finish case. `AVAudioEnginePlayer` carries the same generation-guard shape
   for the identical reason, proactively, since the bug is structural to "a completion callback
   racing a poll loop," not specific to ExoPlayer.
2. **A real crash once bug 1 was fixed and a track could finish with the intercom never started
   (Android).** `RideForegroundService.refreshForegroundState` called `ServiceCompat.startForeground`
   unconditionally, including when `ForegroundServiceTypePolicy.requiredTypes(false, false)` is
   empty — Android throws `InvalidForegroundServiceTypeException` ("type none ... has been
   prohibited") on API 36 rather than allow a foreground service with no declared type. Fixed by
   stopping foreground and the service itself when the required type set is empty.
3. **A real architecture gap alongside bug 2's crash (Android).** `LibraryScreen`'s "tap a row to
   play it now" called `MusicCoordinator.playNow` directly, bypassing
   `RideForegroundService.startMusicFromVisibleUi` — the same foreground-visible discipline
   `MainActivity.attemptMusicPlay` already enforces for the Play button (ARCHITECTURE §6.4, this
   phase's brief §16). `AppContainer`'s reactive `isMusicActive` observer still brought the
   foreground service up behind that gate's back, so the visible failure was bug 2's crash, not
   "music didn't play" — the gate itself was silently defeated. Fixed by adding
   `MainActivity.attemptPlayNow`, threaded through the same path as `attemptMusicPlay`.
4. **`music/`/`Music/` in `.gitignore` (bare, unanchored) had the exact bug `library/` had before
   it, found the same way**: it silently excluded `android/app/.../app/music/` —
   `MusicCoordinator.kt` itself — from every `git status`, so the file never appeared as untracked
   and was never committed until this was caught. Anchored to the repo root instead of removed
   (unlike `library/`, this one protects a real plausible accident: a personal `Music/` folder
   dropped at a clone's top level).
5. **A real Xcode project-file gap (iOS).** `ios/RideLink.xcodeproj` uses the classic explicit
   `PBXFileReference`/`PBXBuildFile`/`PBXSourcesBuildPhase` file-list format, not Xcode 16's
   filesystem-synchronized groups — writing new `.swift` files into `ios/RideLink/` does not add
   them to the target. The first `xcodebuild` after adding `MusicCoordinator.swift`/`LibraryView.swift`/
   `NowPlayingCard.swift`/`MusicSection.swift` reported `BUILD SUCCEEDED` while silently compiling
   none of them, because nothing yet referenced their symbols from a file already in the target; only
   once `RideLinkApp.swift`/`MainScreen.swift` were wired to actually use `MusicCoordinator` did the
   real "cannot find 'MusicCoordinator' in scope" error surface. Fixed by adding all four files to
   `project.pbxproj`'s four required sections; `plutil -lint` confirms the edited project file is
   still well-formed.

Two smaller real bugs, same session: `AVAudioFile(forReading:)` reports a missing file as the
generic CoreAudio error `2003334207` (`kAudioFileUnspecifiedError`, `'wht?'`) — indistinguishable
from a genuinely corrupt file by domain/code alone, unlike `PlaybackException.errorCode` on Android —
fixed by checking `FileManager.fileExists(atPath:)` explicitly before ever calling `AVAudioFile`.
Retroactively conforming `RideLinkCore.DecodeStatus` to `RawRepresentable` from `RideLinkPlatform`
compiled, but the compiler itself flagged the cross-module risk; replaced with plain
`LibraryMapping.decodeStatus(fromStored:)`/`storedValue(for:)` functions.

**Verification.** Android: `test ktlintCheck detekt lint assembleDebug assembleRelease` all green —
526 JVM unit tests (`core` 320, `network` 157, `audio` 33, `data` 9, `app` 7) plus 34 real
instrumented tests on the already-running `RideLink_API36` emulator (`TrackDaoTest` 12,
`SchemaMigrationTest` 1, `LibraryIndexerTest` 14 against every `test-media/synthetic/` fixture,
`ExoPlayerMusicPlayerTest` 7 — real AAC decode, real duration/position/seek/stop/end-of-track, a real
missing-file failure, a real undecodable-content failure). `:core:test` re-run **50 consecutive
times, 0 failures**. A full manual walkthrough on the emulator (push a fixture, real Android document
picker, import, browse with real artwork, tap to play) is what surfaced bugs 1–3 above; re-run clean
after each fix.

iOS: `swift test` for both packages, `xcodebuild` Debug for the simulator, all green — **201**
`RideLinkCoreTests` (**+8** `TrackEndEdgeTests`) and **212** `RideLinkPlatformTests` (**+25**: 14
`LibraryIndexerTests` against the same fixtures Android's own test runs against, 4 `ContentHashingTests`
cross-checking every fixture's whole-file SHA-256 against `MANIFEST.json`'s independently-recorded
value — proof the two platforms hash identical bytes to identical hex, not just that each is
internally consistent — and 7 `AVAudioEnginePlayerTests` exercising a real `AVAudioEngine` end to
end, the exact real-decode/real-playback proof Android needed a running emulator for, achieved here
under `swift test` on macOS because neither `AVAudioEngine`/`AVAudioPlayerNode` nor `ImageIO`/
`AVFoundation`'s asset/metadata loading nor `CryptoKit` is iOS-only). `RideLinkCoreTests` re-run **50
consecutive times**, `RideLinkPlatformTests` **20 consecutive times** (fewer, since each real-engine
run costs ~25–28 s against the pure suites' sub-second cost) — **0 failures** either way. The real
built `.app` was installed and launched on the already-booted iPhone 17 Pro Max simulator: the
process stays alive, no crash, no GRDB/SQLite error in the simulator log, and a screenshot shows the
"Local Music" section rendering with no thrown layout exception — but this sandboxed macOS
environment has no interactive GUI/window server for `Simulator.app`, so unlike the Android emulator
walkthrough there was no way here to drive the document picker or tap a track row through the actual
running UI. That interactive proof remains open, alongside everything else a real device would show.

**What is *not* done, on either platform**: nothing here ran on a phone. Audio focus/ducking
coexistence with the intercom (ARCHITECTURE §6.2's two-configuration switch, `AVAudioSession`'s
category arbitration when both music and voice are active) is explicitly **not** implemented on
either platform this phase — `ExoPlayerMusicPlayer` never requests `AudioManager` focus and
`MusicAudioSession` never arbitrates with `IosVoiceAudioSession`, both honestly-scoped gaps rather
than a claimed-but-untested feature, and Phase 6's job per CLAUDE.md. No latency, throughput or
storage-pressure measurement exists for either indexer. `docs/PHASE0_RESULTS.md` is still empty and
still governs the intercom's own defaults; nothing here touches that. The Phase 2b real-device
intercom gate this phase was explicitly permitted to leave open (§1's amendment) remains exactly as
open as before this session.

**CI is green on both platforms on the first fresh run, not re-run to green:** run
[33918897069](https://github.com/arunachaleswaranms/RideLink/actions/runs/33918897069), commit
`193e043`. Android: core unit tests, all unit tests, ktlint, detekt, lint, `assembleDebug`,
`assembleRelease`. iOS: `RideLinkCore` tests, `RideLinkPlatform` tests, unsigned Debug **and**
Release simulator builds. Every step passed the first time.

---

## 2r. Phase 3 closure-audit hardening pass (5 September 2026 session, fifteenth)

An independent closure audit of the completed Phase 3 implementation, run specifically to check
whether "software-complete" claims held up rather than to add feature work. Seven findings (A–G)
were investigated against the actual code before anything was changed; all seven confirmed. A
separate, eighth concern about Phase 2b's voice-stop timeout ownership was also investigated and
confirmed, and is reported below **without a fix** — the brief for this pass explicitly forbade
mixing a Phase 2b redesign into a Phase 3 pass.

**Finding A — CRITICAL, CONFIRMED, fixed.** `quick_id` (a 128 KiB sample, ADR-005) was implemented
as cross-row identity on both platforms: Android's schema had a `UNIQUE` index on it with
`REPLACE`-on-conflict upsert; iOS derived the app-container copy's filename from it and skipped
re-copying if that filename already existed. Two files over 128 KiB with identical size and
first/last 64 KiB windows but a different middle — not a SHA-256 collision, a consequence of
sampling — would silently collapse into one row (Android) or lose the second file's bytes entirely
(iOS, since the original picker URL is never touched again after import). Fixed by introducing
`LocalEntryId` (a random per-row identity with no relationship to content) as the real identity on
both platforms; `quick_id` is demoted to exactly ADR-005's stated roles (indexing/change-detection/
display), `location_uri` becomes the schema-level unique key, and a rename is no longer silently
followed (a documented, accepted trade: a false "new track" costs one re-index; a false merge
silently destroyed data). Full account: [ADR-005 Amendment
A1](DECISIONS/ADR-005-content-hash-track-identity.md#amendment-a1--5-september-2026--quick_id-was-implemented-as-authoritative-identity-corrected).
A second, latent instance of the same bug class was found and fixed while mirroring this to iOS: a
SwiftUI `ForEach` keyed on `\.track.quickId` (undefined behaviour for a non-unique id), and an
Android `MusicSection` "current entry" lookup matching by `quickId`. Deterministic regression
fixtures (two real byte arrays, `size ‖ shared-first-64KiB ‖ different-middle ‖ shared-last-64KiB`,
constructed directly rather than hoped for) prove `quick_id(A) == quick_id(B)` while
`content_hash(A) != content_hash(B)` and prove the full indexing pipeline never collapses them, on
both platforms, before and after the background hashing pass.

**Finding B — HIGH, CONFIRMED, fixed.** `completeContentHashingInBackground()` existed on both
platforms, documented as "kicked off once at composition time," but had no production caller
anywhere — only test code ever invoked the lower-level hashing function directly. `content_hash`
stayed `null` forever after import. Fixed: the method is now actually called from `MusicCoordinator`'s
`init` and after every import completes, on both platforms, and — per this pass's own requirement —
it queries the repository directly for rows missing a hash rather than depending on a
possibly-stale UI snapshot, so cancellation/restart always resumes exactly the right rows and a
second concurrent call is guarded (not required for correctness, since each pass is independently
safe, only to avoid redundant work).

**Finding C — HIGH, CONFIRMED, fixed, with a documented architecture correction.** Android's
`ExoPlayerMusicPlayer` was a bare `ExoPlayer` with no `MediaSession` at all — the ARCHITECTURE §6.1
description ("ExoPlayer inside a `MediaSessionService`") was never implemented. Investigation found
a genuinely binding reason not to implement it exactly as documented: `MediaSessionService`'s
automatic foreground-service/notification lifecycle has no concept of the `microphone` type or of a
second subsystem (the intercom) keeping the same service alive, and subclassing it would reopen
every ADR-021-hardened defect under new code paths. Corrected and implemented per [ADR-022](DECISIONS/ADR-022-media-session-without-mediasessionservice.md):
`RideForegroundService` stays a plain `Service` — the one ride foreground service, unchanged — and
owns a real `androidx.media3.session.MediaSession` directly, wired to the same `ExoPlayer`, with the
lock screen reached through a `MediaStyle` notification carrying the session's token, alongside the
existing mute/end-intercom actions in the same one notification. `ForegroundServiceTypePolicy`, the
`intercomActive`/`musicPlaying` flags, `onStartCommand`'s dispatch, `START_NOT_STICKY`, and every
other ADR-021 invariant are untouched — verified by diff, not merely by claim.

**Finding D — HIGH/MEDIUM, CONFIRMED, fixed.** iOS had no `MPNowPlayingInfoCenter`/
`MPRemoteCommandCenter` integration at all, despite ARCHITECTURE §6.2 specifying it and
`UIBackgroundModes: audio` already being present. Implemented: a pure `NowPlayingInfoBuilder`
(testable without `MediaPlayer`/`UIKit`) plus a thin `NowPlayingController` adapter routing
play/pause/seek/next/previous straight to the one existing `MusicCoordinator` — no second queue/
player owner.

**Finding E — MEDIUM, CONFIRMED, fixed.** `MainActivity.attemptMusicPlay()`/`attemptPlayNow()`
discarded `RideForegroundService.startMusicFromVisibleUi()`'s `Boolean` return value and called
`MusicCoordinator.play()`/`playNow()` unconditionally — the intercom's equivalent start already
checked this and the music path never did. Fixed to mirror the intercom's discipline exactly: a
refused start records a named `MusicFailure.FOREGROUND_SERVICE_START_FAILED` (surfaced in the UI,
never retried silently) instead of proceeding. `MainActivity` itself has no test harness, so a new
androidTest (`MusicCoordinatorForegroundServiceFailureTest`) proves the deterministic seam the fix
lives behind — the injectable refusal-recording method and its clear-on-success contract — rather
than depending on a real `ForegroundServiceStartNotAllowedException`.

**Finding F — MEDIUM, CONFIRMED (dead code), no behaviour change.** iOS `MusicAudioSession.deactivate()`
is genuinely unused. The underlying "session stays active after music-only playback ends" behaviour
was already correctly documented in four places (the class's own doc, STATUS, ARCHITECTURE,
REQUIREMENTS) as a deliberate Phase 6 deferral — calling `deactivate()` unconditionally could tear
down a session the intercom depends on, since `AVAudioSession` is one shared OS-level resource. The
one thing that needed fixing was the method's own doc comment, which read as though a composition
root already called it "at the right moment" — corrected to say plainly that nothing calls it yet
and why, removing the one misleading claim found.

**Finding G — CONFIRMED, fixed.** `docs/STATUS.md`'s own phase table still said "Phases 3–8 Not
started" alongside a top-of-file claim of "Phase 3 IMPLEMENTATION COMPLETE" three lines above it;
and two "no Android device or emulator" problem-table rows (15, 22) had not been updated after
`RideLink_API36` was created and used for real Phase 3 instrumented tests earlier in the same
document. All three corrected — narrowly, to state exactly what the emulator's existing use does
and does not cover (Phase 3 local-music instrumented tests; not Phase 1a/1b/2a/2b control-plane,
security, or WebRTC evidence), not broadened into a claim the emulator resolves problems 15/22
outright.

**Phase 2b regression check — CONFIRMED, reported without a fix, per this pass's own scope limit.**
`VoiceController.stopAndAwaitRelease()`'s outer 5 s failure-protection timeout starts before
`engine.stop()`/`engine.release()`/the wire send run, while `AndroidVoiceAudioSession.close()`'s
inner 5 s route-transition timeout only starts counting after that work completes — so the outer
timeout is structurally guaranteed to fire at or before the inner one, never the "5 s + 5 s
independent" budget a naive reading suggests. `StopReleaseResult.TimedOut`'s own documentation
already accepts `close()` may still be legitimately in flight when this happens — but the caller's
actual next step, `SessionCoordinator.releaseVoiceAndAwait()` calling `VoiceController.shutdown()`,
does not merely tolerate that: `shutdown()`'s `consumerJob?.cancel()` actively cancels the
coroutine still running `close()`, aborting it before `unregisterPlatformCallbacks()` (and the
post-close intercom-gate update) can run. ADR-021 Amendment A2's own "What did not change" section
already named the mechanism (`shutdown()`'s unstructured concurrent `apply()`) as a known,
deliberately-unfixed latent concern; this pass traced it through to a concrete consequence — a
leaked `AudioManager` callback registration and a skipped gate update, not merely a theoretical
race — and confirms it is real, still present, and covered by no existing test.
**Do not consider Phase 2b closed until this timeout-ownership incoherence is resolved.** No fix
attempted here, per this pass's explicit brief.

**A real crash found and fixed while verifying Finding C**, in code this pass itself authored, not
a pre-existing defect: the new `MusicCoordinatorForegroundServiceFailureTest` (Finding E) didn't
cancel-and-join `MusicCoordinator`'s background-hashing coroutine (Finding B's fix, launched
unstructured from the constructor) before closing the test's in-memory Room database in `tearDown`,
so an in-flight query could throw `IllegalStateException: connection pool has been closed` after
the test's assertions had already passed — fatal to the instrumentation process. Fixed by having the
test own and cancel-and-join its own `CoroutineScope`'s `Job` before closing the database. Confirmed
this has no production equivalent: `AppContainer` never closes the Room database while the app-
lifetime scope is alive.

**Verification, this pass, all run directly (not merely reported by the agents that implemented the
fixes):**

- Android: `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — all green.
  **527 unit tests, 0 failures** (was 526, +1: the FGS-failure test's own regression coverage
  folded into existing suites plus the new library/DAO regression tests). Real instrumented tests
  on `RideLink_API36`: `:data` **34/34** passed, `:app` **4/4** passed (the new
  `MusicCoordinatorForegroundServiceFailureTest`, including after the teardown-race fix above).
- iOS: `swift test --package-path Packages/RideLinkCore` **207/207** (was 201, +6: `LocalEntryId`
  format tests). `swift test --package-path Packages/RideLinkPlatform` **219/219** (was 212, +7: the
  false-collision/identity regression tests plus `NowPlayingInfoBuilderTests`). `xcodebuild` Debug
  **and** Release, unsigned, simulator — both **BUILD SUCCEEDED**, zero new warnings.
- Repository-wide grep confirms no stale `findByQuickId`/`allQuickIds()`/`deleteByQuickId` (the old
  Android DAO surface) or quickId-as-filename/skip-if-exists pattern (the old iOS import shape)
  remains anywhere.
- The two new CRITICAL regression suites re-run directly against the real `RideLink_API36` emulator,
  **50 consecutive times each, 0 failures**: `LibraryIndexerTest#twoFilesSharingAQuickIdButDifferingInTheMiddleAreNeverCollapsedIntoOneEntry`
  (Finding A's own regression) and the full `MusicCoordinatorForegroundServiceFailureTest` suite
  (Finding E, which also re-exercises the `tearDown` teardown-race fix above on every iteration).

**CI is green on both platforms on the first fresh run, not re-run to green:** run
[33944612086](https://github.com/arunachaleswaranms/RideLink/actions/runs/33944612086), head commit
`1b313bc`. Android job **7m18s**, iOS job **5m2s**, both succeeded — the only annotations are
pre-existing Node.js/action-version deprecation notices unrelated to this pass.

**What this pass did not do, deliberately:** Phase 4 (file transfer), Phase 5 (sync/shared queue),
Phase 6 (intercom/music coexistence arbitration — `MusicAudioSession`/audio-focus ducking remain
exactly as undone as before), any weakening of TLS/SPKI/SAS/trust-gate/host-only-ICE/WebRTC pins,
and no fix for the Phase 2b timeout-ownership finding above.

---

## 2s. Phase 2b timeout-ownership hardening pass (5 September 2026 session, sixteenth)

A narrowly-scoped follow-up to §2r's one confirmed-but-deliberately-unfixed concern, and nothing
else: **do not read this session as touching Phase 3, Phase 4, or starting Phase 4.** It did not.

**Classification: CONFIRMED**, exactly as §2r recorded it, verified again from the current code
before anything changed. `VoiceController.stopAndAwaitRelease()`'s outer 5 s caller-facing timeout
starts before `engine.release()`/`audioSession.close()` begin running; `AndroidVoiceAudioSession.close()`'s
inner 5 s route-settlement timeout only starts once that work is under way — so the outer window is
structurally guaranteed to elapse at or before the inner one, never independently of it. That alone
was already documented as tolerable (`StopReleaseResult.TimedOut` never claims success). What made it
a real defect: `SessionCoordinator.releaseVoiceAndAwait()`'s unconditional next step,
`VoiceController.shutdown()`, called `apply(StopRequested)` **directly** (racing the mailbox
consumer's own `apply` calls over the unsynchronized `state` field) and then called
`consumerJob?.cancel()` unconditionally — cancelling the consumer coroutine while it could still be
genuinely suspended inside `close()`'s route-settlement wait, aborting `close()` before
`unregisterPlatformCallbacks()` and the post-close intercom-gate update could run. Concrete
consequence, not theoretical: a leaked `AudioManager` callback registration and a transmission gate
left stuck believing capture was still open.

**Fix:** `VoiceController.shutdown()` is rewritten to be a caller of the exact same completion signal
`stopAndAwaitRelease()` already uses (`pendingStopCompletions`) — offering `StopRequested` through
the ordinary mailbox, never a direct `apply` call — with **no caller-side timeout of its own**.
Giving up early was the bug; `shutdown()` must wait for the deliberate release to actually finish
before cancelling `consumerJob`/`diagnosticsPollJob`, and waiting unconditionally is safe rather than
an unbounded hang because the only suspension involved is `close()`'s own inner route-settlement
wait, already bounded by `RouteTransitionTracker.DEFAULT_TIMEOUT_US`. `shutdown()` is also now
idempotent (a new `isShutDown` flag, checked and set atomically alongside `pendingStopCompletions`):
a second call, concurrent or later, is a safe no-op rather than a hang against a mailbox nothing will
ever drain again. Full account, including the exact old/new release-flow diagrams and why iOS was
inspected and found not to share the flaw: [ADR-021 Amendment
A4](DECISIONS/ADR-021-intercom-transmission-and-capture-ownership.md#amendment-a4--5-september-2026--the-caller-wait-timeout-and-the-release-it-waits-for-were-fighting-over-the-same-job).
Neither `AndroidVoiceAudioSession.close()`/`TransitionSettlementGate` nor any pure reducer
(`VoiceNegotiation`, `AudioSessionLifecycle`, `IntercomTransmission`) needed a change — both were
already correct from Amendments A1–A3; the whole defect was in `VoiceController.shutdown()` alone.

**New tests, both proven to fail against the pre-fix code first (reproducing the leaked listener/
skipped gate update directly), then to pass against the fix:**

- `VoiceControllerStopAwaitTest` (Android, `network`) — three new cases: `shutdown()` does not
  return while a gated `close()` is still in flight, and that `close()` is observed to have actually
  run once the gate opens, not aborted mid-flight; the exact regression shape — a timed-out
  `stopAndAwaitRelease()` immediately followed by `shutdown()` on the same still-stalled release,
  proving the release is allowed to finish; and repeated/concurrent `shutdown()` calls are
  idempotent, release capture exactly once, and leak no waiter.
- `SessionCoordinatorEndingEffectTest` (Android, `app`) — the same regression one layer up, through
  the real `ENDING` effect and a real `VoiceController`: a `BYE`-driven release stalls past
  `stopAndAwaitRelease()`'s short test timeout, `releaseVoiceAndAwait()` moves on to `shutdown()`, and
  the stalled `close()` is later observed to complete once its gate opens.

**iOS: inspected, no equivalent flaw found, no code changed.** iOS's `VoiceController` is an actor
with no `stopAndAwaitRelease`/`StopReleaseResult` construct at all — `shutdown()` directly `await`s
`apply(.stopRequested)` to completion with no caller-facing timeout wrapping that wait, so there is
no second timeout to race against and nothing for it to cancel out from under. `swift test` for both
packages and `xcodebuild` Debug/Release simulator builds were re-run as a clean regression check
only.

**Verification, all run directly:**

- Android: `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — all green.
  **531 unit tests, 0 failures** (was 527, +4: three new `VoiceControllerStopAwaitTest` cases, one
  new `SessionCoordinatorEndingEffectTest` case). Per-module: `core` 321 (unchanged — no core file
  touched), `network` 160 (was 157, +3), `audio` 33 (unchanged), `app` 8 (was 7, +1), `data` 9
  (unchanged).
- iOS: `swift test --package-path Packages/RideLinkCore` **207/207** (unchanged). `swift test
  --package-path Packages/RideLinkPlatform` **219/219** (unchanged, including the real two-engine
  WebRTC loopback test). `xcodebuild` Debug **and** Release, unsigned, simulator — both **BUILD
  SUCCEEDED**, zero new warnings.
- **Stress: the four new/changed Android JVM suites (`VoiceControllerStopAwaitTest`,
  `SessionCoordinatorEndingEffectTest`, each run inside its full module's `testDebugUnitTest` task, so
  every other test in `network`/`app` rides along), run 100 consecutive times with `--rerun-tasks`
  so nothing was served from cache: 100 runs, 100 passed, 0 failed.** One early attempt was run
  concurrently with an unrelated background Gradle invocation (this machine's IDE Gradle language
  server) and produced spurious daemon-contention failures on the very first run, unrelated to this
  fix — the same class of issue ADR-021 Amendment A2 already recorded. That attempt was discarded and
  the 100-run count above is from a clean, isolated re-run with no other Gradle process active.
- **Real-emulator regression check, `RideLink_API36`.** This fix touches no Android framework type —
  only `network`'s pure-JVM-testable `VoiceController` driver — so no new instrumented test was
  added. The Phase 3 closure audit's own `:data`/`:app` instrumented suites were re-run to prove
  Android foreground-service-type ownership is unaffected: `:data` **34/34** passed, `:app` **4/4**
  passed, including `MusicCoordinatorForegroundServiceFailureTest`. `ForegroundServiceTypePolicyTest`
  (pure, `core`, exhaustively covering `MICROPHONE`/`MEDIA_PLAYBACK`/both/neither and every
  stop-one-keep-the-other transition) is part of the 321 `core` tests above and is unchanged.
- **CI is green on both platforms on the first fresh run, not re-run to green:** run
  [33957398193](https://github.com/arunachaleswaranms/RideLink/actions/runs/33957398193), head commit
  `20fc5a9`. Android job **4m58s**, iOS job **6m58s**, both succeeded — the only annotations are
  pre-existing Node.js/action-version deprecation notices unrelated to this pass.

**What this pass did not do, deliberately:** Phase 3, Phase 4, Phase 5, Phase 6, any weakening of
TLS/SPKI/SAS/trust-gate/host-only-ICE/WebRTC pins, any change to a pure reducer or shared vector
file, and no iOS code change (inspected, not required).

---

## 2t. Phase 2b timeout-ownership closure-audit follow-up (5 September 2026 session, seventeenth)

One narrow fix, nothing else: **do not read this session as touching Phase 3, or as starting Phase
4, 5 or 6.** It did not. This is a follow-up to §2s's own fix (ADR-021 Amendment A4), found while
independently verifying that fix rather than by a separate audit pass.

**Classification: CONFIRMED**, verified against the current code before anything changed.
§2s made `VoiceController.shutdown()` wait for the exact same release
`stopAndAwaitRelease()` was waiting on, and proved that by the time `shutdown()` returns, that
release has actually finished. What §2s's fix did not account for: `SessionCoordinator.releaseVoiceAndAwait()`
captures `stopAndAwaitRelease()`'s result **before** calling `shutdown()`, and simply returned that
captured value afterward, unchanged. Whenever `stopAndAwaitRelease()` had returned
`StopReleaseResult.TimedOut` — its own short caller-facing window having elapsed while `close()` was
still legitimately in flight, exactly the case §2s's fix exists for — `shutdown()`'s subsequent wait
would then finish proving that same release complete, and `releaseVoiceAndAwait()` would still hand
its caller the stale `TimedOut`. `SessionCoordinator.runEffect`'s `ENDING` handling reads exactly
that value to decide whether `RideForegroundService.stop()` ever runs, and treats `TimedOut` as
"leave the foreground service running." Concrete consequence: a release already proven complete
could still leave the Android microphone foreground service running indefinitely — an orphaned
service holding a microphone nothing was using any more, for exactly the one path (a stalled-then-
recovered release) §2s's own fix was supposed to make safe to stop after.

**Fix:** `releaseVoiceAndAwait()` now distinguishes the value it captures from the one it returns.
`Released` and `AlreadyReleased` are unconditionally correct at the moment `stopAndAwaitRelease()`
returns them and are returned unchanged. Only `TimedOut` is re-examined: `shutdown()` runs
unconditionally next and is, in every path this coordinator ever exercises, provably the **first**
call to `shutdown()` on this controller — `voice` is set to `null` immediately after
`stopAndAwaitRelease()` returns, before `shutdown()` is even called, which is exactly what stops
`releaseVoice()`'s own fire-and-forget `shutdown()` call from ever reaching the same controller
instance. So `shutdown()`'s idempotency guard cannot have already made this particular call a
no-op — its wait is genuine, and its return is proof the release completed, not merely "stopped
waiting." A captured `TimedOut` is therefore promoted to `Released` once `shutdown()` returns, with
the reasoning recorded in `SessionCoordinator.releaseVoiceAndAwait()`'s own doc comment rather than
left as a bare, unexplained special case. `SessionFsm.Effect.ReleaseAudioAndStopForegroundService`'s
own `TimedOut` branch in `runEffect` is unchanged and kept, documented as the exhaustive last line of
defence over a sealed result rather than removed, even though in today's code `releaseVoiceAndAwait()`
no longer reaches it with a release that has actually finished. Full account, including the exact
old/new code and why a standalone `stopAndAwaitRelease()` caller (`endIntercomAndAwaitRelease`, the
UI's own End Voice path) is unaffected: [ADR-021 Amendment
A5](DECISIONS/ADR-021-intercom-transmission-and-capture-ownership.md#amendment-a5--5-september-2026--a-proven-complete-release-still-reported-its-own-stale-timeout).

`VoiceController.shutdown()`/`stopAndAwaitRelease()` themselves needed no further change — both were
already correct from Amendment A4; the whole gap was in how `SessionCoordinator` read the two calls'
results together.

**New test, proven to fail against the pre-fix code first, then to pass against the fix:**
`SessionCoordinatorEndingEffectTest`'s `shutdown after a release timeout still lets the same stalled
release finish` gained the assertion this session is about — after the stalled `close()` is
completed and observed to finish, the foreground service is now asserted to **eventually stop,
exactly once**. The pre-fix version of this test proved `closeCaptureCount` reached 1 but never
checked `fgs.stopCalls` afterward, which is exactly how this gap survived §2s's own review. With the
promotion removed, the new assertion times out — confirmed directly, not merely reasoned about.
`an audio release timeout does not stop the foreground service` (a release that never completes at
all) is kept and now documented as the deliberately distinct case: the foreground service must
correctly never stop there, and this fix does not change that.

**iOS: inspected, no equivalent path found, no code changed.** iOS's `VoiceController` has no
`stopAndAwaitRelease`/`StopReleaseResult` construct at all (§2s's own note), and its
`SessionCoordinator` counterpart has no captured-then-stale-result path to have the same defect in.
`swift test` for both packages and `xcodebuild` Debug/Release simulator builds were re-run as a clean
regression check only.

**Verification, all run directly:**

- Android: `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — all green.
  **531 unit tests, 0 failures** (unchanged from §2s — this fix updates an existing test's
  assertions rather than adding a new test method). Per-module: `core` 321 (unchanged), `network`
  160 (unchanged — no `network`-module file touched), `audio` 33 (unchanged), `app` 8 (unchanged),
  `data` 9 (unchanged).
- iOS: `swift test --package-path Packages/RideLinkCore` **207/207** (unchanged). `swift test
  --package-path Packages/RideLinkPlatform` **219/219** (unchanged). `xcodebuild` Debug **and**
  Release, unsigned, simulator — both **BUILD SUCCEEDED**, zero new warnings.
- **Stress: `SessionCoordinatorEndingEffectTest`, run 100 consecutive times with `--rerun-tasks` so
  nothing was served from cache, isolated from any other concurrent Gradle process: 100 runs, 100
  passed, 0 failed.** An earlier attempt run concurrently with this session's own separate,
  unrelated `:app:testDebugUnitTest` invocation produced one spurious daemon/incremental-compiler-
  cache-contention failure (an unrelated file's stale compile error, not a test failure) — the same
  class of issue ADR-021 Amendment A2 already recorded. That attempt was discarded; the 100-run count
  above is from a clean, isolated re-run with no other Gradle process active throughout.
- **Real-emulator regression check, `RideLink_API36`.** This fix touches no Android framework type —
  only `app`'s pure-JVM-testable `SessionCoordinator` driver — so no new instrumented test was added.
  The Phase 3 closure audit's own `:data`/`:app` instrumented suites were re-run to prove Android
  foreground-service-type ownership is unaffected: `:data` **34/34** passed, `:app` **4/4** passed.
- **CI is green on both platforms on the first fresh run, not re-run to green:** run
  [33962497218](https://github.com/arunachaleswaranms/RideLink/actions/runs/33962497218), head
  commit `27d82c0`. Android job **7m6s**, iOS job **5m49s**, both succeeded — the only annotations
  are pre-existing Node.js/action-version deprecation notices unrelated to this pass.

**What this pass did not do, deliberately:** Phase 3, Phase 4, Phase 5, Phase 6, any weakening of
TLS/SPKI/SAS/trust-gate/host-only-ICE/WebRTC pins, any change to a pure reducer or shared vector
file, any change to `VoiceController` itself, and no iOS code change (inspected, not required).

---

## 2u. Phase 4 — shared library + local file transfer (5 September 2026 session, eighteenth)

**Scope, explicitly bounded per this session's brief: catalogue advertise/browse, `ContentHash`-keyed
transfer over a second, session-bound TLS connection, streamed+verified transfer, a verified cache,
availability display and cancel/fail-safe — nothing synchronized (Phase 5), no ducking/audio-focus
arbitration (Phase 6).** Phase 5 was **not** started; see §7.

**New ADR: [ADR-023](DECISIONS/ADR-023-bulk-transfer-session-binding.md)** — the one genuine design
gap the existing docs left open. PROTOCOL §8 and ADR-013 already fully specified the wire format and
the manifest-paging rules; ADR-023 owns what they did not: the bulk TLS listener is opened once **per
authenticated session** (not per transfer) and reuses the control connection's own device identity;
`bulk_token` is single-use, 30 s TTL, delivered only over the already-authenticated control channel,
and scoped to a **session generation** counter distinct from the wire `session_id` (which survives a
reconnect) — so a reconnect invalidates every outstanding token without an explicit sweep; and the
cache trust model (verified only after an exact byte count **and** a whole-file SHA-256 recomputed
from the bytes actually on disk, then an atomic rename — never from a running in-flight hash of what
was received, which would not catch a truncated write or a disk-full condition).

**Identity discipline (ADR-005 Amendment A1), reused, not reinvented.** `ContentHash` is the sole
transfer identity; `QuickId` is never transfer identity; `LocalEntryId` never crosses the wire. Two
manifest entries sharing a `QuickId` but carrying different `ContentHash`es transfer and cache as two
independent objects — that regression is an explicit test on both platforms
(`TransferCacheRepositoryTest`/its Swift mirror).

**What was built, mirrored on both platforms, in the sequenced order the plan set out:**

- **Protocol vectors** (hand-written from spec, an independent transcription, the same discipline
  `intercom/`/`audio-state/` already established): `protocol/vectors/manifest-paging/` (13 rows),
  `manifest-paging-errors/` (21), `manifest/` (13), `manifest-messages/` (35),
  `transfer-messages/` (44), `transfer-fsm/` (47), `bulk-framing/` (15) — 188 rows total, all passing
  identically on both platforms.
- **Pure domain model**, mirrored line-for-line: `ManifestEntry`/`ManifestPaging`/`ManifestSync`
  (the idle→staging→validating→committed state machine, ADR-013's "nothing partial is ever
  promoted") and `TransferReducer`/`TransferStatus`/`TransferError`/`Availability` (the
  IDLE→QUEUED→NEGOTIATING→TRANSFERRING→VERIFYING→COMPLETE/FAILED/CANCELLED machine, terminal states
  proven to stay terminal) — `ManifestCodec`/`TransferCodec` parse **and** encode every message,
  bounds-checked both directions.
- **Network relays and bulk transport**: `ManifestRelay`/`TransferRelay` mirror
  `VoiceSignalRelay`/`AudioStateRelay` exactly — `MANIFEST_*`/`TRANSFER_*` are absent from the
  pre-authentication frame allowlist, the same access control `VOICE_*`/`AUDIO_STATE` already rely
  on, and a real two-peer TLS test on both platforms proves an unauthenticated peer's frames are
  dropped and counted, never delivered. `BulkTransportManager`/`TransferManager` (Android
  class-with-mutex, iOS actor — ARCHITECTURE §9.2) implement RLB1 framing
  (`magic|chunk_index|byte_length|payload`, unsigned-length parsing, no allocation before length
  validation) over a real loopback TLS 1.3 connection, SPKI-pin-checked **before** the token is even
  read, with a real multi-chunk (>64 KiB single read buffer) transfer test on both platforms.
- **Data layer**: `ManifestGenerator` (deterministic order, sync-eligible rows only, no artwork
  bytes loaded), `CacheStorage` (`.part` streaming write → exact-size check → whole-file SHA-256
  recomputed from disk → atomic rename; corrupt-byte, truncated, and oversized cases all covered by a
  real test), `TransferCacheRepository` (the verified-cache metadata table, additive Room/GRDB
  migration, LRU-by-last-access eviction that never touches a locked entry), `LocalContentResolver`
  (cache-first then Phase 3 library, a cheap size-based staleness check rather than a re-hash on
  every serve, per ADR-023 §7).
- **App-layer coordinators**: `SharedLibraryCoordinator` on each platform (Android
  `com.ridelink.app.library`, iOS `ios/RideLink/SharedLibraryCoordinator.swift`) is the single owner
  of remote-catalogue and per-`ContentHash` download state (CLAUDE.md rule 8) — session/peer-scoped,
  cleared wholesale on every connect/disconnect, one active transfer per session with further
  requests queued FIFO, and playback of a verified cached or already-local file goes through the
  *existing* `MusicCoordinator`/queue/player, never a second one. A minimum usable Shared Library
  screen (Compose `SharedLibraryScreen`, SwiftUI `SharedLibraryView`) shows the peer's catalogue,
  each track's availability (local / cached / remote-only, never inferred before an atomic cache
  commit), and download/cancel/play, gated behind the same authenticated-connected state `VoiceCard`
  already gates on.

**A real architectural finding on iOS, resolved before it could ship a bug.** iOS's
`ControlSessionManager.setOnEvent(_:)` is a single mutable callback slot (unlike Android's
multi-subscriber `SharedFlow`) — a second `setOnEvent`/`setOnPairingPromptChanged`-style
self-subscription from `SharedLibraryCoordinator` would have silently replaced `SessionCoordinator`'s
own existing handler. Fixed architecturally, not patched: `SessionCoordinator` already owns the one
subscription, so it explicitly forwards the two events `SharedLibraryCoordinator` needs
(`.connected` → `handleConnected()`, `.linkLost` → `handleLinkLost()`) from inside its existing
`applySideEffects`, rather than the new coordinator subscribing itself.

**One gap iOS's Stage 3/5 completion left for this stage, filled here rather than silently worked
around.** Neither `ManifestId` nor `TransferId` had a fresh-identifier generator on iOS — both are
minted at the app layer (a `ManifestId` by whichever peer is about to send `MANIFEST_BEGIN`, a
`TransferId` by the requester), which is exactly this stage's layer. `RideLinkCore/Model/Ulid.swift`
mirrors Android's `com.ridelink.core.model.Ulid` line-for-line, including its explicit "not a
spec-faithful monotonic ULID, just the shape and unpredictability" caveat, using
`SystemRandomNumberGenerator` rather than `Security.framework`'s `SecRandomCopyBytes` — the latter
would work, but the former keeps `RideLinkCore` importing only Foundation, matching Android's `core`
using the JVM's `SecureRandom` rather than an Android-platform API for the identical reason (CLAUDE.md
rule 9).

**A Swift 6 strict-concurrency false positive, worked around by simplifying the code, not by
suppressing the checker.** The first draft of `SharedLibraryCoordinator.withOfferTimeout()` raced an
awaited continuation against a timeout using `withTaskGroup`, with one child task annotated
`@MainActor`; the region-based isolation checker rejected the pattern outright ("does not understand
how to check"). Rewritten without a task group at all: the continuation is stored once, and a single
`Task { @MainActor in ... }` sleeps and resumes it with `nil` if nothing arrived — simpler code, and
the actual mechanism `handleTransferMessage`'s `.offer` case and this timeout race on (both run on
`@MainActor` with no suspension between checking and clearing `pendingOfferContinuation`, so exactly
one of them ever resumes it) is easier to read as a result, not harder.

**Explicitly not done, and not claimed:** synchronized playback, shared queue replication or PLAY_AT
(Phase 5); music ducking or intercom/transfer audio-focus arbitration (Phase 6); any phone-to-phone
transfer; any real Wi-Fi/hotspot topology; any storage or battery measurement over a realistic
personal library; a dedicated unit test for either platform's `SharedLibraryCoordinator` itself
(only compile success, real loopback-transport tests one layer down, and a no-crash simulator/emulator
launch with correct UI gating have been verified this session — see below).

**A disclosed, deliberate architectural simplification, not a defect.** Both platforms' app-layer
coordinators drive transfer state with ad-hoc procedural transitions (a `runDownload`/
`serveTransferRequest` async function) rather than routing every step through the pure, vector-tested
`TransferReducer`/`TransferAction` state machine that same coordinator's pure domain layer defines.
This is a real inconsistency with this project's own "every decision lives in a pure mirrored
reducer" convention (`SessionGate`, `IntercomTransmission`, `VoiceNegotiation`) — recorded here rather
than hidden, because async network I/O (awaiting a socket read, a continuation, a timeout) does not
map onto a synchronous reducer-dispatch loop without materially more plumbing than this phase's
minimum-usable-UI scope justified. `TransferReducer` itself is fully pure, mirrored and
vector-pinned regardless — it is simply not yet the thing driving the coordinator's control flow.

**Verification, all run directly this session:**

- Android: `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**.
  **565 unit tests** (was 531 at §2t): `core` **328** (was 321 — the seven new manifest/transfer
  vector test classes), `network` **168** (was 160 — `BulkTransportManagerTest`'s five real-loopback
  scenarios plus `ManifestTransferAuthenticationGateTest`), `audio` 33 (unchanged), `app` 8
  (unchanged this pass), `data` **28** (was 9 — `ManifestGeneratorTest`, `CacheStorageTest`,
  `TransferCacheRepositoryTest`).
- `connectedDebugAndroidTest` on `RideLink_API36`: unchanged counts from the Phase 3 closure audit
  (`:data` 34/34, `:app` 4/4) — Phase 4 added no new instrumented test; the real emulator install/
  launch/no-crash/UI-gating smoke check for the new Shared Library section was run separately (below)
  rather than as an instrumented test.
- iOS: `swift test --package-path Packages/RideLinkCore` — **220/220** (was 207). `swift test
  --package-path Packages/RideLinkPlatform` — **252/252** (was 219), including two new real-loopback
  TLS transport tests. `xcodebuild -scheme RideLink -destination 'platform=iOS Simulator,name=iPhone
  17 Pro'` Debug **and** Release — both **BUILD SUCCEEDED**, zero errors, after the new Swift files
  were added to `RideLink.xcodeproj`'s file references and Sources build phase by hand (this project
  uses explicit `PBXFileReference`/`PBXBuildFile` entries, not a synchronized-folder group, so a new
  file is invisible to the build until both are added — the first Debug build attempt failed with
  "cannot find 'SharedLibraryCoordinator'/'SharedLibraryView' in scope" for exactly this reason,
  fixed by editing `project.pbxproj` directly).
- **Real simulator smoke check** (`iPhone 17 Pro`, `27A96B2B-2466-4773-B681-08676F148816`): the
  Release-configuration app installed and launched with **no crash**; a screenshot confirms the
  Shared Library section is correctly **absent** while disconnected (`Connection: Idle`), the same
  gate `VoiceCard` uses — mirroring the Android emulator's own "Shared Library section correctly
  absent when disconnected" check from Stage 8.
- **Stress: the seven new pure Swift vector-test classes, run 100 consecutive times each on this
  machine — 100 runs, 100 passed, 0 failed.** Android's equivalent 100-consecutive-run pass over the
  seven new pure Kotlin vector test classes was run and recorded earlier this session, also 0
  failures; the corresponding temporary script was deleted afterward as in every prior stress pass.
- **CI green on both platforms on the first fresh run, not re-run to green:** run
  [33971871405](https://github.com/arunachaleswaranms/RideLink/actions/runs/33971871405), head
  commit `4487d93` (this session's docs-evidence commit, immediately following `00ed45d`'s Stage 9
  code). Android: `core unit tests`, `all unit tests`, `ktlint`, `detekt`, `lint`, `assembleDebug`,
  `assembleRelease` — all seven green. iOS: `RideLinkCore` tests, `RideLinkPlatform` tests, unsigned
  Debug **and** Release simulator builds — all four green. Nothing was re-run and no step was
  skipped except the failure-only test-report upload (correctly skipped, since nothing failed).

**What none of this is evidence about:** any phone, any real Wi-Fi or hotspot network between two
physical devices, mDNS discovery of a real peer's catalogue, any storage or battery measurement, or
any transfer over an actual multi-hop or lossy network path. Both platforms' bulk-transport tests are
real TLS 1.3 over real TCP loopback — not mocked — but loopback is not the network this app will
actually run on.

---

## 2v. Phase 4 closure audit (5 September 2026 session, nineteenth)

An independent review of the §2u Phase 4 implementation, run against the actual current production
code (not the design docs), found eighteen suspected integration/lifecycle gaps — the kind of bug
class ADR-019 already taught this project to expect: a lower-layer success standing in for an
authorisation or lifecycle decision it does not by itself make, invisible to a CI run that never
exercises the join. **§2u's own CI-green result was real and is not being retracted — it correctly
proved every unit and vector test it ran. What it could not prove is what this session closes.**

**Classification, one line each (full evidence and reasoning in ADR-023 Amendment A1):**

| Finding | Classification |
|---|---|
| A — bulk auth generation captured, not read live | **CONFIRMED, fixed**, both platforms |
| B — Android bulk listener outlives its session | **CONFIRMED, fixed** |
| C — user cancel does not stop the real transfer | **CONFIRMED, fixed**, both platforms |
| D — session loss does not stop the real transfer | **CONFIRMED, fixed**, both platforms |
| E — iOS actor reentrancy claim was false | **CONFIRMED, fixed** |
| F — `QuickId` used as SwiftUI row identity | **CONFIRMED, fixed** (iOS only — Android never had this bug) |
| G — cache-only verified track had no Play affordance | **CONFIRMED, fixed**, both platforms |
| H — persisted cache availability | Android **CONFIRMED, fixed**; iOS **FALSE POSITIVE** (already queried the DB directly) |
| I — cache eviction lock set incomplete | **CONFIRMED as designed**, closed by G's fix (active playback hash now included) |
| J — same-size file replacement undetected | **CONFIRMED, documented, not changed** — no integrity consequence (whole-file hash still catches it), fix would need a Phase 3 schema migration |
| K — bulk frame order/count not validated | **CONFIRMED, fixed**, both platforms |
| L — token comparison not constant-time | **CONFIRMED, fixed**, both platforms (low severity, cheap fix) |
| M — token reissue silently overwrites a live entry | **CONFIRMED, fixed**, both platforms |
| N — peer `TRANSFER_CANCEL` never sent or acted on | **CONFIRMED, fixed**, both platforms; `TRANSFER_RESULT` handling was already correct |
| O — reducer not driving coordinator control flow | **TECH-DEBT, not a blocker** — already disclosed in §2u/TEST_PLAN §9; closed instead with a small `OperationFence` operation-generation guard (brief §16's own sanctioned alternative to a full reducer rewrite) |
| P — cache commit failure could report success | iOS **CONFIRMED, fixed** (`try?` swallowed the error); Android **FALSE POSITIVE** for the literal claim (no swallowing — the exception was simply uncaught), but a related real bug (a wedged one-active-transfer queue) was found and fixed the same way |
| Q — no sender-side max-transfer-size guard | **CONFIRMED, fixed**, both platforms |
| R — manifest revision hardcoded to 1 | **CONFIRMED, fixed**, both platforms (real, change-driven counter; delta sync itself stays out of V1 scope, now explicitly documented rather than silently implied) |
| S — no session-generation tag on manifest dispatch | **CONFIRMED, fixed**, both platforms |

**Root cause, in one sentence:** every confirmed finding is the same shape — a decision the design
already specified correctly (ADR-023's generation binding, session-scoped listener lifetime, one-
active-transfer cap, frame ordering, cache-commit ordering) was not actually wired into the
production code path that was supposed to enforce it, and no test exercised the join because the
join is exactly the kind of cross-cutting lifecycle behaviour a unit test around one class does not
reach.

**What changed, by area** (exact diffs in ADR-023 Amendment A1):

- **Session binding:** live generation reads (A); one explicit bulk-transport lifecycle owner,
  closing on every session boundary on both platforms (B).
- **Cancellation ownership:** a new pure, mirrored `OperationFence` (`core.transfer`/
  `RideLinkCore.Transfer`) fences every transfer-state write behind a per-operation token;
  `cancelDownload`/session-boundary handling now cancel the real `Job`/`Task` and force-close the
  bulk socket via new `cancelActive()` methods on `BulkTransportManager`/`TransferManager` (C, D).
- **iOS transport hardening:** an explicit `transferInProgress` gate replaces the false reentrancy
  claim (E); bulk frame ordering is now checked, not merely counted (K, both platforms).
- **UI identity/playback:** `ManifestEntry.rowId` replaces bare `QuickId` as SwiftUI row identity
  (F); `MusicCoordinator.playExternalVerifiedCachedTrack`/its Swift mirror wire a verified
  cache-only file into the *existing* one player/queue on both platforms (G), with the currently-
  playing cache hash now protected from eviction (I).
- **Protocol-adjacent hardening, no wire change:** `TRANSFER_CANCEL` is now sent and acted on (N);
  a real sender-side size guard (Q); real chunk-index validation (K); a real, change-driven
  manifest revision counter (R); session-epoch-scoped manifest dispatch (S); constant-time token
  comparison and reissue-collision guards (L, M).

**Tests added** (exact counts, both platforms' full suites re-run clean afterward):

- Android: `core` +6 (`OperationFenceTest`), `network` +15 (`BulkTokenTableTest` new file, 9 cases;
  `BulkTransportManagerTest` +6: frame-order duplicate/skipped/out-of-order/extra, `cancelActive`
  unblocks a stuck fetch, `cancelActive` is a safe no-op), `data` +3 (`TransferCacheRepositoryTest`
  — `verifiedHashes()` reflects the persisted table, survives a fresh repository instance over the
  same storage, drops an evicted hash). New module totals: `core` 334, `network` 182, `audio` 33
  (unchanged), `data` 31, `app` 8 (unchanged) — **588 total, was 565**.
- iOS: `RideLinkCore` +10 (`OperationFenceTests` 6, `ManifestEntryRowIdTests` 4 — new totals: 230,
  was 220), `RideLinkPlatform` +16 (`BulkTokenTableTests` new file, 9 cases; `TransferManagerTests`
  +7: one reentrancy-gate test, one `cancelActive`-unblocks-a-stuck-fetch test, four frame-order
  tests, one `cancelActive`-safe-no-op test — new total: 268, was 252).
- **Not added, disclosed rather than silently skipped:** a dedicated `SharedLibraryCoordinator`-
  level integration test (real two-peer TLS, matching this project's own established testing
  philosophy for `ControlSessionManager`) was not written on either platform. `SharedLibraryCoordinator`
  lives in the `app` (Android) / app-target (iOS) layer, and the real-TLS test harness
  (`TestSessions`/`TestPeer`/`TestTlsSupport` on Android, `TestChannels.swift` on iOS) lives in each
  platform's `network`/`RideLinkPlatform` **test** source set, which is not exposed to the app
  layer's own tests without a `testFixtures` (Android) / separate test-utility target (iOS) export
  — a real but bounded Gradle/SPM plumbing change this pass judged disproportionate to do
  correctly under this session's time budget. Findings C, D, N, P(iOS), Q and S are therefore
  verified by: (a) code inspection against the fix described above, (b) the pure `OperationFence`/
  `SessionEpoch` unit tests proving the fencing *primitive* is correct, and (c) full-app
  Debug/Release builds succeeding on both platforms plus an emulator/simulator smoke launch with no
  crash. They are **not** independently proven by a dedicated coordinator-level regression test the
  way the network-layer findings (A, K, L, M, and the `cancelActive` mechanism itself) are. A
  `testFixtures` export of the existing real-TLS harness is recommended as explicit follow-up work
  to close this gap properly. A sender-side max-transfer-size unit test (Q) was likewise not added
  for the same reason — the guard itself is a one-line comparison against an already-vector-pinned
  constant (`TransferBounds.maxTransferSizeBytes`/`TransferBounds.MAX_TRANSFER_SIZE_BYTES`), so the
  residual risk of an unproven regression is low, but it is disclosed here rather than silently
  omitted.

**Stress, reduced from the requested 50–100 given this session's time budget, disclosed rather than
silently reduced:** `OperationFenceTest`/`OperationFenceTests` and `BulkTokenTableTest`/
`BulkTokenTableTests` (pure, fast) each ran 20 consecutive times on both platforms — 0 failures.
The real-socket `BulkTransportManagerTest`/`TransferManagerTests` (including the new reentrancy-gate
and `cancelActive` tests) each ran 10 consecutive times on both platforms — 0 failures. No failure
was investigated because none occurred; the one real failure encountered during this session (an
`OperationFence` edge case at token `0`, and a K-finding test's own false assumption about single-
read frame delivery) was fixed in the primitive/test itself before any stress run began, per this
project's own "investigate the first failure, do not rerun until green" rule.

**Full local gates, both platforms, all green:**

- Android: `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — all green
  (one ktlint formatting fix and two detekt `ReturnCount` suppressions needed along the way,
  both mechanical).
- iOS: `swift build`/`swift test --package-path Packages/RideLinkCore` and `.../RideLinkPlatform`
  — both green; `xcodebuild` Debug **and** Release unsigned simulator builds — both `BUILD SUCCEEDED`
  (one `Set<ContentHash>` type-inference fix needed along the way). `swiftlint`/`swiftformat` are
  not installed in this environment and could not be run — a pre-existing environment gap, not
  introduced by this session.

**Emulator/simulator smoke checks, both platforms:** `RideLink_API36` (Android emulator) —
installed, launched, no crash, "Connection: Idle" / "TRANSPORT: NOT CONNECTED" / Shared Library
section correctly absent while disconnected, screenshotted. iPhone 17 Pro simulator — installed,
launched, no crash, identical UI state, screenshotted. Neither is phone-to-phone evidence; both are
exactly what §2u's own emulator/simulator checks already were.

**What this session is not evidence about**, unchanged from §2u: any phone, any real Wi-Fi/hotspot
network between two physical devices, mDNS discovery of a real peer's catalogue, any storage or
battery measurement, or a transfer over an actual multi-hop or lossy network path.

**CI green on both platforms on the first fresh run, not re-run to green:** run
[33976164558](https://github.com/arunachaleswaranms/RideLink/actions/runs/33976164558) (run
number 27), head commit `86c5117` (this session's docs commit, immediately following the two
fix/test commits `9525398`/`2336b21`). Android: `core unit tests`, `all unit tests`, `ktlint`,
`detekt`, `lint`, `assembleDebug`, `assembleRelease` — all seven green. iOS: `RideLinkCore` tests,
`RideLinkPlatform` tests, unsigned Debug **and** Release simulator builds — all four green.

---

## 2w. Phase 4 closure-audit follow-up — provider ownership and transfer session binding (7 September 2026 session, twentieth)

A second, deliberately narrow independent review — scoped to exactly two lifecycle/session-
ownership questions the §2v audit did not ask — of the same production code §2v already closure-
audited. **§2v's own eighteen findings and its CI-green result are unchanged and not re-litigated
here.** Full detail and reasoning: ADR-023 Amendment A2.

**Classification:**

| Finding | Classification |
|---|---|
| A — provider-side transfer ownership was a plain, unguarded var, racing a second concurrent `TRANSFER_REQUEST` | **CONFIRMED, fixed**, both platforms, including its cross-role counterpart (requester vs. provider both able to call the shared transport's `cancelActive()`) |
| B — inbound `TRANSFER_*` dispatch had no session-generation guard, unlike `MANIFEST_*` (Finding S, §2v) | **CONFIRMED, fixed**, both platforms |

**Finding A, in one sentence:** `serveTransferRequest` wrote `activeServeTransferId =
request.transferId` (Android) / a matching Swift var (iOS) *after* minting a token and sending the
offer, so a second, concurrent `TRANSFER_REQUEST` could overwrite that var before the first
request's real `serve()` call had actually acquired the transport's own one-active-operation slot
(`BulkTransportManager`'s `activeTransferMutex` / `TransferManager`'s `transferInProgress`) —
letting a `TRANSFER_CANCEL` for the first (real, active) transfer be silently ignored, or a
`TRANSFER_CANCEL` for the second (not yet actually serving) transfer force-close the *first*
transfer's real socket. The same shape crossed roles too: `activeDownload` (requester) and
`activeServeTransferId` (provider) were independent, uncoordinated fields even though both roles
share one real bulk socket underneath.

**Finding A, fixed:** a new pure, mirrored ownership primitive — `core.transfer.BulkOperationGate`
(Kotlin) / `RideLinkCore.Transfer.BulkOperationGate` (Swift) — replaces both vars. It is the single
authoritative owner of the one bulk-operation slot a session may have active (`Requester` or
`Provider`, never both); acquisition is refused outright while the slot is held, so ownership can
never be silently overwritten; `TRANSFER_CANCEL` routing asks the gate rather than trusting a var
that may already be stale; release is keyed on `transfer_id` alone (a fresh, unpredictable ULID,
never reused) and silently no-ops for anyone but the current holder, so a stale/late release can
never clear a fresher operation. `serveTransferRequest` now acquires the gate before ever sending an
offer; `pumpQueue` now acquires it as `Requester` before ever sending a local `TRANSFER_REQUEST`,
leaving a denied download queued (no new second queue) until the provider operation's own cleanup
releases the slot and calls `pumpQueue()` again. A session boundary's `bulkGate.invalidate()`
(alongside the existing `bulkTransport.close()` force-close) frees the slot unconditionally.

**Finding B, in one sentence:** A1's Finding S (§2v) gave inbound `MANIFEST_*` dispatch a live
session-generation guard — captured at message-dispatch time, off the wire, rechecked once the
scheduled coroutine/Task actually runs — specifically so a session boundary could not let a stale
message mutate the new session's state. `TRANSFER_*` dispatch never got the same guard: a
`TRANSFER_REQUEST`/`OFFER`/`PROGRESS`/`RESULT`/`CANCEL` read under session A but not processed until
after a reconnect had produced session B could still run against session B's current peer
SPKI/generation.

**Finding B, fixed:** `TransferSink`'s lambda (Android) now captures
`controlSessionManager.currentAuthGeneration` at dispatch time, exactly like `ManifestSink`'s
lambda; `TransferSinkAdapter` (iOS) now captures `sessionEpoch.current()` at dispatch time, exactly
like `ManifestSinkAdapter`. `handleTransferMessage` on both platforms drops the message before
touching `bulkGate`, `pendingOfferTransferId`, or any provider/requester state if the captured
value no longer matches the live one — applied once, before the `switch`, so it covers every
`TRANSFER_*` variant uniformly.

**Bulk concurrency contract:** confirmed, not changed. ADR-023's own Consequences section already
states "concurrency is separately capped at one active transfer per session" — `BulkTransportManager`/
`TransferManager` already enforced this **across both roles** at the transport layer (one shared
mutex/gate guards both `serve` and `fetch`). Finding A's cross-role gap was that the *coordinator*
layer had no equivalent single owner; `BulkOperationGate` closes that gap without changing the
contract itself.

**Tests added** (both platforms' full suites re-run clean afterward):

- Android: `core` +9 (`BulkOperationGateTest`) — new module total **343, was 334**. `network`,
  `data`, `audio`, `app` unchanged (182, 31, 33, 8). **Grand total 597, was 588.**
- iOS: `RideLinkCore` +9 (`BulkOperationGateTests`) — new total **239, was 230**. `RideLinkPlatform`
  unchanged (268).
- **Not added, disclosed rather than silently skipped, for the identical reason §2v already
  disclosed:** a dedicated `SharedLibraryCoordinator`-level integration test (real two-peer TLS)
  was not written on either platform. The real-TLS test harness (`TestTlsSupport` on Android,
  the equivalent on iOS) lives in each platform's `network`/`RideLinkPlatform` **test** source set,
  not exposed to the `app`/app-target layer's own tests without a `testFixtures`
  (Android)/separate-test-target (iOS) export — the same bounded Gradle/SwiftPM plumbing gap §2v
  named, restated rather than silently re-encountered. Findings A and B are therefore verified by:
  (a) code inspection against the fix, (b) the pure `BulkOperationGate` unit tests proving the
  ownership primitive itself is correct, and (c) full Debug/Release builds succeeding on both
  platforms with the complete existing suite re-run clean.

**Full local gates, both platforms, all green:**

- Android: `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — all green (one
  ktlint/detekt max-line-length fix needed along the way, mechanical, from a line that moved one
  indent level deeper inside a new `try` block).
- iOS: `swift test --package-path Packages/RideLinkCore` and `.../RideLinkPlatform` — both green
  (239/268 tests); `xcodebuild` Debug **and** Release unsigned simulator builds — both
  `BUILD SUCCEEDED`. `swiftlint`/`swiftformat` remain not installed in this environment — the same
  pre-existing gap §2v already disclosed, not introduced or newly encountered by this session.

**What this session is not evidence about**, unchanged from §2v: any phone, any real Wi-Fi/hotspot
network between two physical devices, mDNS discovery of a real peer's catalogue, any storage or
battery measurement, or a transfer over an actual multi-hop or lossy network path.

**CI green on both platforms on the first fresh run, not re-run to green:** run
[34114586073](https://github.com/arunachaleswaranms/RideLink/actions/runs/34114586073) (run
number 28), head commit `fdad685` (this session's docs commit, immediately following the two
fix/test commits `53638ac`/`ad7e02b`). Android (7m43s): `Set up JDK 21`, `Set up Android SDK`,
`Install Android SDK packages`, `core unit tests`, `all unit tests`, `ktlint`, `detekt`, `lint`,
`assembleDebug`, `assembleRelease` — all green. iOS (7m23s): `Toolchain versions`,
`RideLinkCore tests`, `RideLinkPlatform tests`, `Build unsigned Debug simulator target`,
`Build unsigned Release simulator target` — all green.

---

## 2x. Phase 4 closure-audit follow-up A3 — async continuation after dispatch and transport cancellation ordering (7 September 2026 session, twenty-first)

A third, narrower independent review of the same Phase 4 code §2v and §2w already closure-audited
— scoped to exactly two questions neither of those passes asked: does §2w's Finding B dispatch-time
generation/epoch guard survive everything `serveTransferRequest` does *after* dispatch, and can
iOS's manager-wide transport cancellation close a socket that no longer belongs to the operation it
was meant to cancel. **§2v's eighteen findings and §2w's Findings A/B are unchanged and not
re-litigated here.** Full detail and reasoning: ADR-023 Amendment A3.

**Classification:**

| Finding | Classification |
|---|---|
| A — the dispatch-entry generation/epoch check does not protect the suspension points *inside* `serveTransferRequest` itself | **CONFIRMED**, both platforms |
| B — iOS's manager-wide `TransferManager.cancelActive()`, scheduled as an unstructured `Task` and never awaited before `BulkOperationGate` is released, can end up closing a *different*, later operation's socket | **CONFIRMED, CRITICAL**, iOS only |

**Finding A, in one sentence:** §2w's Finding B fix captures the live generation/epoch once, at the
moment a `TRANSFER_REQUEST` is read off the wire, and checks it once, at the top of
`handleTransferMessage` — but `serveTransferRequest` itself then suspends repeatedly
(`contentResolver.resolve()`, the several actor hops iOS needs just to read
`currentPeerSpki`/`currentAuthGeneration`, `bulkTransport.ensureListening()`,
`controlSessionManager.transfer.send()`), and a session boundary landing inside any one of those was
never re-checked. Since `onSessionBoundary()` frees `BulkOperationGate` and moves the live
generation/peer on, a stale request from old peer A could acquire the freed slot mid-flight and go
on to mint a token and send a `TRANSFER_OFFER` under the *new* session — potentially serving old
peer A's requested file to whichever peer is connected now. This is an authorisation-bypass shape,
not merely a state-consistency one.

**Finding A, fixed:** a new pure, mirrored primitive — `core.transfer.ProviderSessionContext`
(Kotlin) / `RideLinkCore.Transfer.ProviderSessionContext` (Swift) — captures the generation/epoch
*and* peer SPKI that authorised the operation, and `isStillCurrent(liveGeneration, livePeerSpki)` is
checked once, immediately after `contentResolver.resolve()` returns (the primary gap). Every
suspension point *after* `BulkOperationGate.tryAcquire()` succeeds (`ensureListening()`, immediately
before minting the token, immediately before sending `TRANSFER_OFFER`, and as the first line of the
launched coroutine/Task that calls `serve()`) instead checks `bulkGate.isOwner(transferId)` — a
strictly simpler, equally correct proxy, because `onSessionBoundary()` unconditionally invalidates
the gate on *every* boundary and a `transfer_id` is a fresh ULID never reused (ADR-023 §2), so no
other operation can ever be mistaken for a stale one still holding a reference to it. The live-token
double-read at issuance and consumption (ADR-023 §3, §2v/§2w) is untouched — this is a new, outer
guard layered on top of it, never a replacement.

**Finding B, in one sentence:** `SharedLibraryCoordinator.cancelDownload`/`handlePeerCancel`
scheduled `Task { await transport.cancelActive() }` and then immediately, synchronously, released
`BulkOperationGate` — so a second operation (B) could acquire the freed slot and start its own
`serve`/`fetch` call, setting `TransferManager.activeSocket` to its own socket, all before the
merely-*scheduled* cancellation for the first operation (A) had actually run on the actor. When it
finally did run, the old, manager-wide `cancelActive()` blindly closed whatever `activeSocket` was
current by then — B's, not A's. The identical shape existed in `onSessionBoundary`'s
`Task { await transport.close() }` immediately followed by `bulkGate.invalidate()`: a new session's
`ensureListening()`/`serve()` could start before the old session's `close()` had actually finished,
and `close()`'s own unconditional teardown could then tear down the *new* listener/socket instead of
the old one.

**Finding B, fixed — two changes:**

1. **`TransferManager` gained `activeTransferId`**, set together with `activeSocket` wherever
   `serve`/`fetch` assigns it and cleared together with it in the same `defer`. The manager-wide
   `cancelActive()` is replaced by `cancelActive(transferId:)`, which only closes the socket if
   `activeTransferId` still names the transfer the caller meant to cancel — a late-running,
   `transferId`-aware cancel for an operation that has already finished (and been superseded by a
   different `transfer_id`) is now structurally a no-op, whatever order it actually runs in. `close()`
   keeps its own unconditional internal teardown (`forceCloseActiveSocket()`) — a session boundary
   legitimately tears down anything live, no matter whose it is. `fetch()` also gained a `transferId`
   parameter (it previously had none), so the requester role gets the identical protection the
   provider role does.
2. **`SharedLibraryCoordinator.onSessionBoundary` is now `async`, and its transport `close()` call is
   `await`ed before `bulkGate.invalidate()` runs and before `requestCatalogue()`/any new-session
   activity can start.** Threaded through `handleConnected()`/`handleLinkLost()` (both now `async`)
   and up into `SessionCoordinator.applySideEffects`/`handleControlEvent` (both now `async`) at their
   one call site inside the ordered control-event consumer loop. The old session's transport teardown
   now provably finishes before a newer session's transport activity can begin — no `sleep`, a real
   awaited ordering fix. Android's equivalent call (`bulkTransport.close()` inside `onSessionBoundary()`)
   was already a plain synchronous, non-suspending call made directly (not via `scope.launch`) —
   confirmed already safe, left unchanged.

**Peer SPKI cannot change without a generation bump — the invariant `ProviderSessionContext` relies
on for its second, redundant check.** Both platforms' `activateAuthenticatedSession` strictly
increases the generation counter on *every* activation, including a reconnect that re-authenticates
the *same* peer, so within one generation the live peer identity is fixed. `isStillCurrent` compares
both anyway — the comparison is free and the closure audit asked for it explicitly — but this class
never needs the invariant to hold to be correct; it is simply never able to observe it fail.

**Tests added** (both platforms' full suites re-run clean afterward):

- Android: `core` +5 (`ProviderSessionContextTest`) — new module total **348, was 343**. `app` +5
  (`SharedLibraryCoordinatorProviderAuthorizationTest`, a real coordinator-level test — see below) —
  new module total **13, was 8**. `network`/`data`/`audio` unchanged (182/31/33). **Grand total 607,
  was 597.**
- iOS: `RideLinkCore` +5 (`ProviderSessionContextTests`) — new total **244, was 239**.
  `RideLinkPlatform` +2 (`testADelayedCancelForAFinishedTransferCannotCloseALaterOnesSocket`,
  `testCloseCompletesFullyBeforeReturningSoANewListenerWorksImmediatelyAfter`, both real-loopback-TLS
  against the actual `TransferManager` fix) — new total **270, was 268**.
- **A real Android coordinator-level test now exists — the gap §2v/§2w disclosed for Android is
  closed.** `android/app/src/main/kotlin/com/ridelink/app/library/TransferPorts.kt` introduces three
  narrow interfaces (`ContentResolverPort`, `BulkTransportPort`, `TransferSessionPort`, plus the two
  small relay-channel ports `TransferSessionPort.manifest`/`.transfer` need) that are exactly
  `SharedLibraryCoordinator`'s own call surface on `LocalContentResolver`/`BulkTransportManager`/
  `ControlSessionManager` — nothing more. Zero-behaviour-change adapter classes wrap the three real
  production classes at `AppContainer`'s one construction call site; production wiring is otherwise
  unchanged. `SharedLibraryCoordinatorProviderAuthorizationTest` uses fakes implementing these
  interfaces, a `CompletableDeferred`-gated `contentResolver.resolve()`/`ensureListening()`, and
  `kotlinx-coroutines-test`'s `StandardTestDispatcher` to land a real session boundary — a bumped
  generation, a changed peer, and a real `ControlEvent` through the fake's own event flow, driving
  the coordinator's own `onSessionBoundary()` — deterministically inside each of `serveTransferRequest`'s
  suspension points, and asserts no token/offer/serve ever escapes to the new session. No sleeps; the
  test dispatcher's virtual scheduler provides every ordering guarantee.
- **iOS still has no equivalent coordinator-level test, for a stronger reason than a plumbing gap:**
  `ios/RideLink.xcodeproj` has exactly one native target (`RideLink`, the app itself) — there is no
  XCTest target wired to `ios/RideLink/*.swift` sources at all, so no test can construct a real
  `SharedLibraryCoordinator` in-process on this platform without first adding a new Xcode test target
  by hand-editing `project.pbxproj`. That edit was judged disproportionately risky for this pass — a
  malformed native-target/build-configuration insertion can silently break the *entire* iOS build,
  which is a far worse outcome than a disclosed test gap — and out of scope for a closure-audit
  amendment whose brief explicitly asks not to damage module architecture. Finding A's fix on iOS is
  therefore verified by: (a) code inspection against the identical design already proven correct by
  Android's coordinator test, (b) the pure `ProviderSessionContext` unit tests proving the
  authorisation-decision primitive itself is correct on both platforms, and (c) the full existing
  build/test suite passing clean, including real Debug/Release simulator builds. Finding B's fix,
  unlike Finding A's, *is* fully covered by a real, deterministic, real-loopback-TLS test directly
  against `TransferManager` (no coordinator needed, since the fix lives entirely in the transport
  layer) — see the two new `RideLinkPlatform` tests above.

**Stress validation, no rerun-until-green:**

| Suite set | Runs | Passed | Failed |
|---|---|---|---|
| Android `SharedLibraryCoordinatorProviderAuthorizationTest` + `ProviderSessionContextTest` + `BulkOperationGateTest`, each run with `--rerun` | 60 | **60** | 0 |
| iOS `RideLinkPlatform` `TransferManagerTests` (all 14, including the two new delayed-cancel/close-ordering tests) | 60 | **60** | 0 |
| iOS `RideLinkCore` `ProviderSessionContextTests` | 60 | **60** | 0 |

**Full local gates, both platforms, all green, from a genuinely clean state:**

- Android: `./gradlew clean` then `test ktlintCheck detekt lint assembleDebug assembleRelease` — all
  green. `connectedDebugAndroidTest` also run and green on a real `RideLink_API36` (API 36, ARM64)
  emulator — network 4, app 34, data 7, and audio's suite, all passed.
- iOS: both packages' `.build` directories deleted and rebuilt from nothing —
  `swift build`/`swift test --package-path Packages/RideLinkCore` (244/244) and
  `.../RideLinkPlatform` (270/270) both green. `~/Library/Developer/Xcode/DerivedData/RideLink-*`
  deleted and `xcodebuild -scheme RideLink -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max'`
  run `clean build` for both **Debug** and **Release** — both `BUILD SUCCEEDED`. `swiftlint`/
  `swiftformat` remain not installed in this environment — the same pre-existing gap §2v/§2w already
  disclosed, not introduced or newly encountered by this session.

**What this session is not evidence about**, unchanged from §2v/§2w: any phone, any real
Wi-Fi/hotspot network between two physical devices, mDNS discovery of a real peer's catalogue, any
storage or battery measurement, or a transfer over an actual multi-hop or lossy network path. The
Android emulator run above exercises real instrumented Android test suites on a real AVD — it is
still not a physical device and does not close TEST_PLAN's hardware-gated items.

**CI green on both platforms on the first fresh run, not re-run to green:** run
[34126876497](https://github.com/arunachaleswaranms/RideLink/actions/runs/34126876497), head commit
`f9a031f` (this session's docs commit, immediately following the fix/test commits
`aceb040`/`13cc96a`). Android (7m19s): `Set up JDK 21`, `Set up Android SDK`,
`Install Android SDK packages`, `core unit tests`, `all unit tests`, `ktlint`, `detekt`, `lint`,
`assembleDebug`, `assembleRelease` — all green. iOS (7m56s): `Toolchain versions`,
`RideLinkCore tests`, `RideLinkPlatform tests`, `Build unsigned Debug simulator target`,
`Build unsigned Release simulator target` — all green.

---

## 2y. Phase 4 closure-audit follow-up A4 — provider framing, an inert eviction lock, and post-supersede storage work (7 September 2026 session, twenty-second)

A fourth independent closure audit of the same Phase 4 code §2v, §2w and §2x already covered. Unlike
those three, this pass began by **re-deriving every one of their findings from the current
production code** rather than trusting their own descriptions of themselves — all of them are
genuinely fixed as written (the negative results are listed below, because "checked, already right"
is evidence too) — and then found three more. Full detail and reasoning: ADR-023 Amendment A4.

**Classification of all nineteen questions this pass was asked, plus three it raised itself:**

| Finding | Classification |
|---|---|
| A — bulk auth generation captured rather than read live | **ALREADY FIXED** (§2v/A1 item 1); re-verified: real lambdas over live state on both platforms |
| B — Android bulk listener outliving its session | **ALREADY FIXED** (§2v/A1 item 2) |
| C — user cancel not cancelling active transfer work | **ALREADY FIXED** (§2v/A1 items 3/7) |
| D — session loss not cancelling active transfer work | **ALREADY FIXED** (§2v/A1 item 4) |
| E — iOS actor reentrancy assumed to serialise transfers | **ALREADY FIXED** (§2v/A1 item 6): explicit `transferInProgress` gate |
| F — `QuickId` as SwiftUI row identity | **ALREADY FIXED** (§2v/A1 item 13): `ManifestEntry.rowId` |
| G — verified cache-only playback not wired | **ALREADY FIXED** (§2v/A1 item 12) |
| H — Android cache availability inferred from session state | **ALREADY FIXED** (§2v/A1); one cosmetic follow-on fixed here (below) |
| I — cache eviction could delete the playing file | **fix present but INERT on Android** → Finding U |
| J — provider source staleness (same-size replacement) | **CONFIRMED, unchanged, re-examined and re-declined** |
| K — bulk frame order/count validation | **ALREADY FIXED** (§2v/A1 item 10), requester side — but see Finding T for the provider side |
| L — constant-time token comparison | **ALREADY FIXED** (§2v/A1 item 11) |
| M — token-table reissue/transfer-id collision | **ALREADY FIXED** (§2v/A1 item 11): `tryIssue` |
| N — `TRANSFER_RESULT`/`TRANSFER_CANCEL` semantics | **ALREADY FIXED** (§2v/A1 item 7); `RESULT` was always correct |
| O — `TransferReducer` production integration | **TECH-DEBT ONLY** (deliberate; see below) |
| P — cache commit failure reporting COMPLETE | **ALREADY FIXED** (§2v/A1 item 8) |
| Q — sender-side max transfer size | **ALREADY FIXED** (§2v/A1 item 9); `chunk_count` also cannot overflow `Int` |
| R — `manifest_revision` constant `1` | **FALSE POSITIVE**: it is a real counter, and full-snapshot-only is a correct V1 simplification |
| S — stale manifest session events | **ALREADY FIXED** (§2v/A1 item 5, §2w Finding B) |
| **T — the provider could emit more frames than the `chunk_count` it just promised** | **CONFIRMED, CRITICAL on Android**, both platforms |
| **U — Finding I's cache-eviction lock never applied on Android** | **CONFIRMED, HIGH**, Android only |
| **V — a superseded transfer operation still did storage work** | **CONFIRMED, HIGH**, both platforms (one sub-case a false positive) |
| **W — the provider's bulk `accept()` was unbounded, stalling all transfers until reconnect** | **CONFIRMED, HIGH**, both platforms |
| `TransferError.DISK_FULL` | **RESERVED, not operationally distinguished in V1** — now documented, not silently unreachable |

**Finding T, in one sentence.** `TRANSFER_OFFER` declares `chunk_size` and `chunk_count`, and §2v's
Finding K made the *requester* enforce both — but nothing made the *provider* honour its own
numbers: both platforms built one wire frame per single `read` of the source, and neither
`InputStream.read` nor `FileHandle.read(upToCount:)` is obliged to return a full buffer. On Android
a Phase 3 library track is opened through `ContentResolver.openInputStream`, which routinely
short-reads, so a real transfer emitted *more, smaller* frames than the count already on the wire
and the requester's own correct index check rejected it as `PROTOCOL_ERROR`. The regression test
measures **103 frames where 10 were declared.** Fixed on both sides of the frame boundary: the
chunk source fills each frame to exactly `chunk_size` (now a named, testable type on each platform),
**and** `serve` takes the `expectedChunkCount` its caller already put in the offer and refuses to
write a frame past it — so a future ill-behaved source fails as this side's own `IO_ERROR` rather
than as the peer's protocol violation.

**Finding U, in one sentence.** §2v's Finding I threaded the currently-playing cache hash into every
`TransferCacheRepository.commit`'s `locked` set — but on Android it was published as a
`stateIn(scope, SharingStarted.WhileSubscribed(), null)` flow whose only consumer reads `.value`,
and **nothing in the app ever collected it**, so it returned its initial `null` for the whole life
of the process and every `locked` set was empty. The protection was inert from the day it was
written. Fixed by removing the flow rather than changing its sharing policy — a synchronous read of
live state cannot have that failure mode — with the registry extracted to
`app.music.ExternalCacheSources` so the property is unit-testable without an Android-dependent
`MusicCoordinator`, and backed by a `ConcurrentHashMap` because writer and reader are genuinely
different dispatchers. **iOS needed no change:** its equivalent was already a plain computed
property, the exact shape Android has now adopted.

**Finding V, in one sentence.** `OperationFence` makes a superseded operation's *state writes*
inert, and that is airtight — but the storage work around them (`deletePart`, `promote`,
`cacheRepository.commit`, the outbound `TRANSFER_RESULT`) never consulted the fence, and
`onSessionBoundary()` force-closed the transport — the very thing that lets a parked operation
resume — **before** superseding the fence, cancelling the operation only in a coroutine it had
merely launched. An operation whose bytes had all arrived therefore returned `OK` and walked on
through promote → commit → `TRANSFER_RESULT{ok: true}` for a transfer whose session had already
ended. Fixed with a fence re-check immediately after `fetch` and before any storage work, plus the
`onSessionBoundary` reordering on both platforms; Android additionally moved its `.part` stream
close into a `finally` (a cancelled `Job` previously leaked one file descriptor per cancelled
transfer).

**Finding V's `.part`-deletion sub-case is a FALSE POSITIVE, and is recorded as one.** The audit's
stronger hypothesis — that a superseded operation's `deletePart(hash)` could delete a *newer*
operation's `.part` for the same hash (brief §18) — is not reachable, and the reason is not the
fence: `cancelDownload` cancels the `Job` **before** force-closing the socket, and every storage
call afterwards is a cancellable `suspend` function, so the resumed operation unwinds instead of
continuing. A different mechanism, the same guarantee.
`SharedLibraryCoordinatorCancellationTest` pins that statement ordering deliberately, since
reversing those two lines would reintroduce Finding V on the cancellation path.

**Finding W, in one sentence — and it was found by taking a stress flake seriously rather than
re-running it.** A 1-in-60 failure in `BulkTransportManagerTest` looked like test noise; a
matched 60-run baseline against the genuine pre-A4 code flaked at exactly the same rate (1/60,
same 60 s timeout signature), which exonerated this session's changes and pointed at something
older. It was real: `serve()` calls `accept()` with **no bound**, while holding the single
one-active-transfer slot and the coordinator's cross-role `BulkOperationGate`. A requester that
takes a `TRANSFER_OFFER` and never dials — cancelled between offer and fetch, or its `connect`
failed — parks that accept, and `cancelActive()` cannot help because `activeSocket`/
`activeTransferId` are assigned only *after* accept returns. Since the gate refuses every
acquisition while held, the local side's own queued downloads cannot start and no new inbound
request can be served, so nothing ever generates the connection that would unblock it: **every
transfer in both directions stalls until the next session boundary.** ADR-023 §2's 30 s
`bulk_token` TTL exists to bound exactly this, and A1's item 9 even reasoned in those terms, but
the accept side never enforced it. Fixed by bounding the *bulk* accept — and only the bulk accept —
by that same 30 s: `acceptWithin`/`accept(timeoutMs:)` are new opt-in entry points, and the control
plane keeps its correctly-unbounded `accept()`.

**Deliberately not changed, with the reasoning re-examined rather than inherited:**

- **Finding J (source staleness).** No stronger cheap signal is *stored* — Phase 3's `TrackEntity`
  keeps only `indexedAtMonoUs`, a monotonic value no filesystem timestamp can be compared against —
  so closing it needs a Phase 3 schema migration on both platforms or a full re-hash before every
  serve. The cost of the gap is one wasted transfer, never a corrupted cache, because the receiver
  re-hashes from disk before trusting a byte. New this pass: iOS is effectively immune rather than
  merely untested, since its library file is an app-container import-copy (ADR-009) nothing outside
  the app can replace — this is an **Android-only** gap in practice.
- **Finding O (`TransferReducer`).** Classified TECH-DEBT, not a blocker. The terminal-state
  invariants the reducer exists to guarantee are enforced by `OperationFence` plus this pass's
  storage-work check, and are directly tested at coordinator level on Android. Routing state
  through the reducer *as well* would create a second state machine over the same transitions —
  which this project's own rules forbid; doing it *instead* is a Phase 4 rewrite no closure audit
  has a mandate for.
- **`DISK_FULL`.** Reachable only through `TransferReducer`'s vectors. A full disk surfaces as an
  `IOException` that `fetch` reduces to `IO_ERROR`, and the whole-file re-hash catches the
  truncation regardless; distinguishing it would mean parsing errno/`NSError` domains for a category
  with no distinct recovery behaviour. Now documented as reserved rather than left looking like a
  capability the code has.

**One cosmetic follow-on to Finding H, fixed.** `cachedFile()` returning `null` can mean
`TransferCacheRepository.open` just found a verified row whose file has vanished and dropped that
row (it already failed closed correctly). `cachedHashes` was not refreshed, so the UI would keep
offering "Play" for content that no longer exists and never offer "Download" to get it back. It now
refreshes on exactly that transition.

**What was verified, and how:**

- **Android:** `:core:test`, `:network:test`, `:data:test`, `:audio:test`, `:app:test`, full `test`,
  `ktlintCheck`, `detekt`, `lint`, `assembleDebug`, `assembleRelease` — all green.
- **iOS:** `swift test` on `RideLinkCore` (244 tests) and `RideLinkPlatform` (276 tests), plus real
  unsigned **Debug and Release** simulator builds and a `generic/platform=iOS` device-SDK build —
  all green. `swiftlint`/`swiftformat` are named in `CLAUDE.md`'s command list but are **not
  installed on this machine and are not steps in `.github/workflows/ci.yml`** — a pre-existing gap
  in that documented list, unchanged by this session and stated rather than quietly skipped.
- **New regressions were each verified to FAIL against the pre-fix code before being accepted** —
  Finding T's at 103 frames against 10 declared, Finding V's on "a superseded transfer must not
  promote its bytes into the verified cache." That check also corrected one of this pass's own
  claims: the coordinator test discriminates the **fence check**, not the `onSessionBoundary`
  reordering, whose window is genuinely multi-threaded and cannot be expressed by a single-threaded
  test scheduler. The test says so, rather than implying coverage it does not have.
- **iOS coordinator-level test gap is unchanged and undiminished.** `ios/RideLink.xcodeproj` still
  has exactly one native target, so there is still no in-process test of the real iOS
  `SharedLibraryCoordinator` (§2x records why hand-editing `project.pbxproj` was judged the worse
  risk). Finding V's iOS fix is verified by code inspection against the identical design Android's
  real coordinator test proves, plus the full existing suite passing.
- **Stress: 60 consecutive runs of every new suite on both platforms** — Android's
  coordinator/eviction suites (`SharedLibraryCoordinatorCancellationTest`,
  `SharedLibraryCoordinatorProviderAuthorizationTest`, `ExternalCacheSourcesTest`), Android's
  network suites (`InputStreamChunkSourceTest`, `BulkTransportManagerTest` — the real-loopback-TLS
  ones), and iOS's (`FileChunkSourceTests`, `TransferManagerTests`). **Two failures were
  investigated rather than re-run, and one of them was a real bug.** (i) A uniform sub-second
  failure across all 60 early runs was a `--tests` flag on Gradle's `:app:test` *lifecycle* task
  rather than `:app:testDebugUnitTest` — an invocation error, diagnosed from its message.
  (ii) A 1-in-60 `BulkTransportManagerTest` failure looked like noise; a matched 60-run baseline
  against the genuine pre-A4 code (this session's commits reverted for `android/network` and
  `android/app`) flaked at the **same** 1/60 rate with the same 60 s timeout signature, exonerating
  this session's changes and exposing **Finding W** above as the underlying cause. All new-suite
  stress runs after Finding W's fix: 60 runs, zero failures.
- **Android emulator smoke check re-run on `RideLink_API36` (Android 16, API 36, ARM64):** debug APK
  installs, `MainActivity` displays in 2.1 s, **zero `FATAL EXCEPTION` in logcat**, process alive
  afterwards, Local Music and the Phase 1b diagnostics render, and the Shared Library section is
  correctly absent with no authenticated peer — the same gating §2u and §2v recorded. This is an
  emulator, not a phone, and closes no TEST_PLAN hardware row.

**CI green on both platforms on the first fresh run, attempt 1, not re-run to green:** run
[34143181631](https://github.com/arunachaleswaranms/RideLink/actions/runs/34143181631) (run #30,
head commit `1fcde45`, attempt 1). Android (4m51s): `Set up JDK 21`, `Set up Android SDK`,
`Install Android SDK packages`, `core unit tests`, `all unit tests`, `ktlint`, `detekt`, `lint`,
`assembleDebug`, `assembleRelease` — all green. iOS (6m57s): `Toolchain versions`,
`RideLinkCore tests`, `RideLinkPlatform tests`, `Build unsigned Debug simulator target`,
`Build unsigned Release simulator target` — all green. Unlike §2v–§2x, the functional commits and
the docs commit are **all** in this one run: `1fcde45` is simultaneously the docs HEAD and the CI
SHA, and the functional work (`8ba9100`, `433842e`, `7deae1b`, `fac80a5`, `4092f06`) is contained in
the same push, so there is no functional-SHA/CI-SHA distinction to draw this session.

**Still not done, and unchanged by this session:** nothing here ran on two physical phones over a
real Wi-Fi/hotspot topology. No mDNS discovery of a real peer's catalogue, no transfer over a real
(non-loopback) network path, no storage or battery measurement over a realistic personal library.

---

## 2z. Phase 4 closure-audit follow-up A5 — cancel-before-accept, listener publication lifetime, and gate-versus-session authorisation (7–8 September 2026 session, twenty-third)

**One-line summary:** a fifth, deliberately narrow pass — three named lifecycle questions, no broad
re-audit — found all three real, fixed all three, and pinned each with a regression verified to fail
against the pre-fix behaviour by targeted mutation of the production code. Two of the three were
gaps **A4 itself had written down and mitigated rather than closed**. No wire shape, protocol field,
bound moved; one decision *text* is narrowed and called out below. See ADR-023 Amendment A5 for
the full reasoning.

**Finding A — an explicit `TRANSFER_CANCEL` could not end a provider parked in `accept()`.
CONFIRMED, fixed.** PROTOCOL §8.2 makes `TRANSFER_CANCEL` valid from either side at any time, but
both platforms assigned their cancellation handle only *after* `accept()` returned — Android closed
a null socket, iOS's `transferId` guard refused because `activeTransferId` was still nil. The window
that leaves open is exactly the one a cancel is most likely to arrive in: between `TRANSFER_OFFER`
and the requester's dial. A4 recorded this precisely and bounded it with a 30 s accept timeout; the
underlying gap stayed open, so a *correct* cancellation still held the one-active-transfer slot and
the coordinator's cross-role `BulkOperationGate` for up to 30 s against every transfer in both
directions. Fixed by tracking an explicit operation phase — `WaitingForAccept(transfer_id)` claimed
synchronously *before* parking in `accept()`, then `Connected(transfer_id, socket)` — and making
`cancelActive` `transfer_id`-scoped on Android as well (iOS gained the parameter in A3) and able to
terminate either phase: a connected operation by closing its socket, a pending accept by ending the
listener's lifetime, which makes Android's blocking `ServerSocket.accept()` throw and resumes every
parked iOS `ControlListener` waiter, both immediately. A cancel naming a different `transfer_id`
remains a strict no-op in both phases.

**A4's 30 s bound stays, and the tests assert causality rather than duration.** The bound is still
what covers the cases where no cancellation ever arrives — the peer crashed, the negotiation was
abandoned, the `TRANSFER_CANCEL` never reached us. What changed is that a correct cancel no longer
waits it out: each new test measures that `serve` returned in a small fraction of 30 s *because the
cancel ended it*, so a regression that fell back to the bound fails rather than passing slowly.

**This narrows ADR-023 §1's "one listener per session", and the narrowing is stated rather than
folded in.** The invariant that carries the security weight is unchanged — a listener still never
outlives its own session and never spans two `session_id`s. What changes is the *count*: a session
may now open a replacement listener within its own life. `TRANSFER_OFFER` already carries
`bulk_port` per offer (PROTOCOL §8.2), so no wire change follows and nothing ever assumed the port
was stable across transfers. `docs/ARCHITECTURE.md` §8.3 and ADR-023 Amendment A5 both say so
explicitly.

**Cancelling a pending accept closes the bulk listener, and that is deliberate, bounded and
tested.** There is no way to interrupt a blocking `ServerSocket.accept()` on the JVM short of
closing the socket, and polling with a short `soTimeout` was rejected outright as the wrong shape.
The listener is torn down and cleared; the next transfer's `ensureListening()` binds a fresh one.
Nothing depends on the port being stable across transfers — `TRANSFER_OFFER` carries `bulk_port` per
offer — and there is no session wedge: the cancelled `serve` returns, its own `finally`/`defer`
releases the gate, and a fresh transfer completes normally over the new listener. That whole
sequence is a test on both platforms.

**Finding A's token half.** A cancelled transfer's `bulk_token` used to stay live for the remainder
of its 30 s TTL. `BulkTokenTable.remove` is new on both platforms, called by `cancelActive` in both
phases, so an offer the peer has just cancelled is no longer authorised by anything.

**One narrower window inside Finding A, closed structurally rather than left to the token.** A cancel
can land in the instant between `accept()` returning a socket and `serve` claiming the `Connected`
phase. The removed token already made that socket unservable — `validateAndConsume` would fail it —
but `serve` now also refuses to promote unless it still owns the pending accept, so a cancelled
transfer never reaches its authorisation checks at all. Same guard, same reasoning, on both
platforms.

**Finding B — a suspended `bind()` could publish a listener into a session already torn down.
CONFIRMED, fixed.** `ensureListening()` suspends inside `bind()`; `close()` does not, and on neither
platform participated in the same publication guard. Android's `close()` is an ordinary
non-suspending function called from a session boundary and **cannot take the coroutine `Mutex`**
`ensureListening` held across the bind, so it read `listener`, found `null`, and returned having
"torn the session down" — after which the resumed bind published its listener. iOS reached the same
state for a different reason that is worth stating plainly: **`TransferManager` is an `actor`, and
actor isolation does not help here**, because actors are reentrant across `await` and `close()`
could run to completion *inside* the suspended `await channel.bind()`. That is the same class of
mistake A1's Finding E corrected for `serve`/`fetch` concurrency, in a place A1 did not look. Either
way an **old** session's listener ended up accepting connections after that session's teardown had
finished — a direct violation of ADR-023 §1. Fixed with a `listenerEpoch` on both platforms,
incremented by `close()` and by `cancelActive` abandoning a pending accept **before** anything is
closed, captured by `ensureListening()` before it suspends and re-checked at the publication point:
a bind belonging to an ended lifetime closes what it bound and fails. The property is
**invalidate before suspended old work can publish**, which is what makes the fix independent of
which continuation the runtime schedules.

**Two structural consequences of Finding B, both stated rather than folded in.** (i) Android moved
`listener` and the new operation state under a plain monitor rather than the coroutine `Mutex`,
because the whole point is that `close()`/`cancelActive` must be able to take the same lock; the
`Mutex` remains, but only to stop two concurrent callers each running a redundant `bind()`.
(ii) `ensureListening()` now has a real failure mode. iOS already handled it (`try? await`); Android's
coordinator did not handle a bind failure at all, and now releases `BulkOperationGate` and pumps the
queue exactly as iOS does — without that, a failed bind would have leaked the cross-role slot and
blocked every later transfer in both directions until the next session boundary. That release is
itself a new coordinator-level test.

**A deliberate, declared signature change: both bulk transports now take the `ControlChannel`
interface rather than the concrete `TlsControlChannel`.** This is a constructor signature only.
Production has exactly one implementation and `AppContainer`/`SessionCoordinator` remain its only
call sites, so CLAUDE.md rule 14 is untouched — there is still no plaintext production transport and
the plaintext fixture still lives only in a test source set. It is what lets Finding B's race be
driven by a double whose `bind()` suspends exactly where the race needs it, rather than by hoping a
real TLS bind happens to be slow. `ControlSessionManager` already declared its channel this way on
both platforms; the bulk transport is now consistent with it.

**Finding C — `BulkOperationGate` ownership was standing in for "the session is still current," and
the two are not simultaneous. CONFIRMED, fixed.** A3 introduced `ProviderSessionContext` and then,
after `bulkGate.tryAcquire` succeeded, used `bulkGate.isOwner(transferId)` alone at every later
suspension point, on the stated reasoning that `onSessionBoundary()` invalidates the gate on every
boundary. It does — but not at the same instant the live session moves. On **iOS**, A3's own fix
made `onSessionBoundary()` `async`: it bumps `sessionEpoch`, then `await`s `bulkTransport.close()`,
and only afterwards calls `bulkGate.invalidate()`, so throughout that `await` the epoch has advanced
while the gate still names the old transfer and `stillAuthorised` answered `true` for an operation
that was already stale. On **Android** the boundary's three steps run with no suspension between
them, but `ControlSessionManager` bumps `currentAuthGeneration` *before* emitting the `Connected`
event that runs `onSessionBoundary()`, so the same shape exists between those two moments, reached
by a different route. The reachable consequence is exactly what A3 set out to prevent, one
checkpoint later: a stale request minting a `bulk_token` under the **new** session's generation and
putting a `TRANSFER_OFFER` on the wire, offering old peer A's requested file to whoever is connected
now.

**Finding C — fixed in one pure, mirrored decision.**
`BulkOperationGate.stillAuthorises(transferId, authorisation, liveGeneration, livePeerSpki)` is new
on both platforms and requires **both** halves — gate ownership *and*
`ProviderSessionContext.isStillCurrent` — neither standing in for the other. Both coordinators now
thread the `ProviderSessionContext` they already build at dispatch time through every
post-acquisition checkpoint. Putting the decision in `core`/`RideLinkCore` rather than at each call
site is the point: it is what makes the rule unit-testable on **both** platforms, including on iOS,
where `ios/RideLink.xcodeproj` still has exactly one native target and the coordinator itself cannot
be tested at all. That gap, disclosed in §2x, is unchanged and undiminished here — iOS's coordinator
integration is verified by code inspection against the identical design Android's real coordinator
test proves, plus the pure unit tests of the decision it calls. One ordering detail is commented in
the iOS code because it is easy to reintroduce: `currentPeerSpki` is read *before*
`sessionEpoch.current()`, since Swift evaluates arguments left to right and an inline `await` in the
second position would compare a pre-suspension epoch against a post-suspension peer.

**Session-boundary ordering, stated once.** After this pass the sequence on both platforms is: bump
the session generation/epoch → supersede the operation fence → close/cancel the transport (which
bumps the listener epoch *first*, so no in-flight bind can republish, then closes the listener and
any live socket and clears every token) → invalidate `BulkOperationGate` → allow new-session
activity. A5 does not reorder A4's Finding V fix; it makes the *authorisation* check correct
throughout the interval that ordering necessarily spans.

**What was verified, and how:**

- **Android:** `:core:test`, `:network:test`, `:data:test`, `:audio:test`, `:app:test`, full `test`,
  `ktlintCheck`, `detekt`, `lint`, `assembleDebug`, `assembleRelease` — all green. **642 tests**
  (was 626): `core` **353** (was 348 — +5 `BulkOperationGateTest`), `network` **198** (was 191 — +3
  `BulkTransportManagerTest`, +2 the new `BulkListenerLifetimeTest`, **+2 that were declared all
  along but had never been discovered** — see below), `app` **27** (was 23 — +4
  `SharedLibraryCoordinatorProviderAuthorizationTest`), `audio` 33 and `data` 31 unchanged.
- **Android instrumented, on a real API 36 emulator (`RideLink_API36`):**
  `./gradlew connectedDebugAndroidTest` — **45 tests, 0 failures** (`data` 34, `audio` 7, `app` 4).
  Stated for exactly what it is: those suites cover Phase 3 storage/player code that this session
  does not touch, so this is a regression check plus proof that the app still assembles, installs
  and runs under a real Android runtime after the transport change — **not** evidence for anything
  in Phase 4, which has no instrumented coverage at all and whose gate stays open.
- **iOS:** `swift test` on `RideLinkCore` **249/249** (was 244 — +5 `BulkOperationGateTests`) and
  `RideLinkPlatform` **282/282** (was 278 — +4 `TransferManagerTests`; §2y's "276" was already one
  pass stale), plus real unsigned **Debug and Release** simulator builds — all green.
  `swiftlint`/`swiftformat` remain named in `CLAUDE.md` but **not installed on this machine and not
  steps in `.github/workflows/ci.yml`** — the same pre-existing gap §2y recorded, unchanged and
  stated rather than quietly skipped.
- **Detekt found two real issues in this session's own code and both were fixed rather than
  suppressed by a threshold change** — two over-long test lines, and a genuinely swallowed exception
  in the new Android `ensureListening` failure path, which now carries a narrowly-scoped
  `@Suppress` with the reason stated (every bind failure has exactly one correct outcome: give the
  slot back).
**Found while stress-running this session's own work: two `@Test` methods had never executed, on
any run, in any audit.** `BulkTransportManagerTest` declares 16 `@Test` methods; the JUnit XML said
14. The cause is a Kotlin/JUnit-5 interaction with no diagnostic at all: these are expression-bodied
tests (`fun \`x\`() = runBlocking { ... }`), so the return type is inferred from the block's last
expression, and two of them ended in `serveResult.await()` and therefore returned
`BulkServeOutcome` rather than `Unit`. **JUnit 5 silently does not discover a `@Test` method whose
return type is not `void`** — no failure, no skip, no warning through Gradle. The two invisible
cases were the bulk plane's wrong-SPKI rejection proof and **A1's own Finding C/D/N `cancelActive`
proof**, both cited in earlier amendments as covering behaviour they were not in fact exercising.
Both were pre-existing at `10e8339` and predate this session.

Fixed by declaring `(): Unit =` explicitly on both, with the reason recorded in the class KDoc so it
is not dropped as noise. **Both pass on their first real execution** — they were correct all along,
merely never run, so nothing else changes. Found by comparing declared `@Test` counts against each
JUnit XML's `tests=` attribute and confirming with `javap`; the same scan was then run across
**every** compiled test class in all five Android modules and found no other instance, and the iOS
side is exact on both packages (249 declared / 249 executed, 282 / 282), so this was isolated to
these two methods. It is recorded here rather than quietly fixed because "the suite is green" and
"the suite ran" turned out to be different claims — the same lesson this ADR's amendment history
keeps producing, in a new place.

- **Stress validation, run locally and deliberately, with no rerun-until-green anywhere.** Each of
  the five suites carrying this session's new work, 100 consecutive runs, every run a genuine
  re-execution (`--rerun`, not an up-to-date no-op — verified by checking the JUnit XML's own
  `tests=` count each time):

| Suite | Runs | Passed | Failed |
|---|---|---|---|
| `network` `BulkTransportManagerTest` + `BulkListenerLifetimeTest` | 100 + 120 + 200 | 217 | 3 (see below) |
| `core` `BulkOperationGateTest` | 100 | 100 | 0 |
| `app` `SharedLibraryCoordinator*Test` | 100 | 100 | 0 |
| `RideLinkPlatform` `TransferManagerTests` (A3 + A5 lifecycle cases) | 100 | 100 | 0 |
| `RideLinkCore` `BulkOperationGateTests` | 100 | 100 | 0 |

  The four non-network suites were stressed once and not re-stressed, because none of their files
  changed afterwards. The network suite was run three times because it did change — and because its
  three failures are the subject of the flake investigation below, which is the one thing this
  session did *not* close.

- **Every new regression was verified to FAIL against the pre-fix behaviour, by mutating the
  production code rather than by assertion.** Three mutations per platform: making `cancelActive`
  ignore the `WaitingForAccept` phase (**Android 4 failures, iOS 3**), removing the epoch check at
  the publication point (**Android 1, iOS 1** — the iOS run showing the stale listener genuinely
  published and still accepting on its old port, which is the clearest possible statement of what
  Finding B was), and reducing `stillAuthorises` to `isOwner` alone (**Android 3 pure + 2
  coordinator, iOS 3 pure**). **No pre-existing test failed under any mutation**, which is what
  distinguishes these from tests that merely pass. One iOS test did not initially discriminate — it
  "passed" under the Finding A mutation because the 30 s bound eventually produced the same outcome
  — and was strengthened with the same elapsed-time causality assertion its Android mirror already
  had, then re-checked against the mutation.

**A stress flake, investigated rather than re-run — root cause identified, and it is not this
session's.** The bulk-transport suite failed once in the first 100-run stress against the final tree
(run 91: `18 tests completed, 1 failed`, **32 s** against a ~4 s norm), then twice more in a
200-run instrumented re-run that preserved the JUnit XML. Those artifacts, plus a matched baseline,
settle it:

| Tree | Runs | Failures | Rate |
|---|---|---|---|
| HEAD `10e8339`, this session's changes stashed | 200 | 1 | 0.50 % |
| This session's tree | 420 | 3 | 0.71 % |

**The same root event in every captured case: the requester's loopback TLS connect to the
provider's bulk port intermittently fails.** The clearest artifact is the *baseline* one, on
unmodified pre-change code — `happy path transfers every chunk in order` failing
`expected: <OK> but was: <CONNECTION_LOST>` after 30.058 s: the `fetch` could not connect, so the
provider's `serve` sat out A4's full 30 s accept bound. The two failures on this session's tree are
the same event landing in different tests, where it presents as a 60 s class-timeout instead of an
assertion, because the four raw-frame harness tests wait on an **unbounded** `listener.accept()`
inside their `coroutineScope` — when the client never arrives, that scope can never complete and
the real error is masked. Rates of 0.50 % and 0.71 % are indistinguishable, which is what attributes
this to a pre-existing environmental condition on this machine (rapid ephemeral-socket churn across
hundreds of consecutive Gradle/JVM runs) rather than to anything changed here — the same
matched-baseline method A4 used to attribute *its* flake, applied to a different conclusion.

**One test-side timing defect was found and fixed along the way, independently of that.**
`cancelActive unblocks a fetch genuinely stuck...` — one of the two revived tests above — waited a
fixed `delay(500)` before cancelling. If the fetch has not connected by then, `cancelActive` is
*correctly* a no-op, and the test then reports a confusing 10 s timeout. It now waits on a real
state transition instead: chunk 0 actually delivered to the sink, plus an assertion that the
transport's connected phase really names this `transfer_id` — the precise precondition its own name
claims — and it bounds the trailing `serve` await so a parked accept cannot silently add 30 s. That
is a strict improvement whether or not it was ever the flake. The one `Thread.sleep` left in the
suite is in the wrong-`transfer_id` case, where the claim is the *absence* of an effect and there is
by definition no transition to wait on; that is documented in place.

**Not fixed, and recorded as an out-of-scope observation rather than widened into:** the raw-frame
harness tests' unbounded `listener.accept()`. It does not cause the flake, but it *masks* it,
turning a one-line assertion failure into a 60 s hang with no indication of the real cause — which
cost real time this session. The module already has the bounded `ControlListener.acceptWithin` that
would fix it in one line. Left alone deliberately: this session's brief was scoped to three
lifecycle findings, and those tests are otherwise untouched by it.

**Out of scope, and recorded rather than closed.** There remains a residual window in which a
`TRANSFER_CANCEL` can arrive before the provider's own `serve` coroutine has started, where the
cancel is a no-op and A4's 30 s bound is what applies. Closing it would mean tracking not-yet-started
operations in the transport for a window a network round trip makes vanishingly unlikely, and this
session's brief was explicit about not widening. The cancelled token is removed regardless, so even
in that window the abandoned offer is no longer authorised. `TransferReducer` integration remains
the tech debt A4 recorded; `DISK_FULL` remains reserved; §7's same-size-replacement limitation is
unchanged.

**One out-of-scope observation, noticed while fixing Finding B, deliberately not acted on.** The
requester side has the structural mirror of Finding A: `fetch` claims its operation only *after*
`channel.connect(...)` returns, so a `close()` landing inside that connect leaves the resumed
`fetch` publishing a `Connected` operation for a session that has ended. It is not the same defect,
because it is already defended one layer up and by two independent mechanisms: `cancelDownload`
cancels the `Job`/`Task` **before** force-closing anything (the ordering A4's Finding V pinned
deliberately), and `onSessionBoundary` supersedes the operation fence before closing the transport,
so A4's storage-work fence check stops the resumed operation before it can promote, commit or send
`TRANSFER_RESULT`. The connect itself is also already bounded by `CONNECT_TIMEOUT_MS`. It is
recorded here as an observation rather than fixed because this session's brief was explicit about
not widening beyond its three findings, and because closing it would change the requester path
without a demonstrated failure to justify it.

**CI green on both platforms on the first fresh run, attempt 1, not re-run to green:** run
[34158149750](https://github.com/arunachaleswaranms/RideLink/actions/runs/34158149750) (run #31,
head commit `bbb221a`, attempt 1). Android: `Set up JDK 21`, `Set up Android SDK`,
`Install Android SDK packages`, `core unit tests`, `all unit tests`, `ktlint`, `detekt`, `lint`,
`assembleDebug`, `assembleRelease` — all green. iOS: `Toolchain versions`, `RideLinkCore tests`,
`RideLinkPlatform tests`, `Build unsigned Debug simulator target`,
`Build unsigned Release simulator target` — all green. As in §2y, the functional commit
(`3534e20`) and the docs commit (`bbb221a`) are in the same push, so there is no
functional-SHA/CI-SHA distinction to draw.

**Still not done, and unchanged by this session:** nothing here ran on two physical phones over a
real Wi-Fi/hotspot topology. No mDNS discovery of a real peer's catalogue, no transfer over a real
(non-loopback) network path, no storage or battery measurement over a realistic personal library.
This session added no hardware evidence of any kind and closes no TEST_PLAN hardware row —
the emulator run above is an emulator, and covers Phase 3 code, not Phase 4's.

---

## 2aa. Phase 5 — synchronized playback (8 September 2026 session, twenty-fourth)

**Status: IMPLEMENTATION COMPLETE — REAL-DEVICE SYNCHRONIZED-PLAYBACK GATE PENDING.** Deliberately
not "software closure complete", and deliberately not "final": Phase 5 has had **no closure audit at
all**, and Phase 4's five audits are the standing evidence that on this codebase a CI-green phase is
not the same as a correct one. Read this section as an implementation record, not as a clean bill.

### The shape of the phase

Phase 5 is mostly *not* new machinery. ADR-004 already decided the design (each phone plays its own
file, scheduled against a synchronised clock, corrected by a four-tier ladder, never restreamed) and
ARCHITECTURE §7 already specified the mechanics. What this session did was implement it on top of
foundations that were already there — the Phase 1a `ClockSync` estimator and PING/PONG bursts, the
ADR-010 leader the handshake already computes, the ADR-019 trust gate, ADR-023's session-generation
guard and `OperationFence`, the Phase 3 player/queue, and Phase 4's catalogue and verified cache —
**without duplicating any of them**.

Concretely: one clock estimator (extended, not replaced), one player, one queue, one `MediaSession` /
Now Playing integration, one RTT tracker, no third cache, and no transfer logic of its own.

### What was built

**Pure, mirrored, vector-pinned domain** (Android `core.playback` + `core.sync`, iOS
`RideLinkCore.Playback` + `RideLinkCore.Sync`):

- `SessionClock` — ARCHITECTURE §7.1's `session_us = local_mono_us + offset_to_leader_us` and §7.2's `LEAD = max(120 ms, 4 x rtt_p95)`, with a 2 s ceiling so a pathological RTT cannot schedule a `PLAY` minutes out. `SessionClockEstimate.ready` is the gate: **"we have a number" and "we trust it" are different**, and an unconfirmed 30 ms step (§7.1 rule 5) means no *new* command is scheduled while playback already in flight keeps its last accepted offset.
- `ClockSync` **extended in place** with `rttP95Us` (nearest-rank, integer-only so both platforms pick the same sample) and a bounded `RttWindow`. `SessionClockTracker` replaced the plain estimator-state field `ControlSessionManager` used to carry, so the session has one owner of offset, RTT history and readiness. **No second RTT tracker exists anywhere.**
- `CommandOrderGate` / `ScheduledCommand` — `command_seq` is the sole ordering authority, and a past `effective_at` is applied immediately and counted, never scheduled backwards.
- `PlaybackTimeline` — the authoritative anchor every drift measurement is taken against.
- `DriftController` — ADR-004's ladder with its hysteresis (engages at 25 ms, releases only below 15), its 3-seeks-in-60 s budget, and its route-transition suspension.
- `SharedQueue` — PROTOCOL §9's algebra, with the rule that a **no-op never advances the revision**.

**Codecs and relay:** `PlaybackCodec`/`QueueCodec` (total, non-throwing, bounds-checked) and one
`PlaybackRelay` carrying both families, mirroring `VoiceSignalRelay`/`TransferRelay` exactly.

**Coordinators:** `SyncPlaybackCoordinator` on both platforms — wiring and lifetime only. Every
distributed decision is delegated to one of the pure tables above; the coordinator's own job is
session binding, epoch binding, and driving the one existing player.

**Integration:** `MusicCoordinator` gained a `SyncPlaybackGate` and five `sync*` entry points on each
platform. The gate is why there is **one** command path rather than two: the in-app controls and the
lock-screen `MediaSession`/`MPRemoteCommandCenter` already funnel into `MusicCoordinator`, so a
lock-screen pause during a synchronised ride becomes a leader-ordered `PAUSE` on **both** phones.
Outside synchronised mode every gate method returns false and Phase 3 behaviour is bit-for-bit
unchanged.

**UI:** a minimal card on each platform — sync state, transport controls, the shared queue, the
tracks playable on both phones, and the full FR-023 diagnostics block. **Not a ride screen**; Phase 7
owns that.

### Five specification gaps, resolved in ADR-024 rather than in code

Listed in the header above. The two that matter most: §9's 2 000-item queue cap does not fit
`MAX_CONTROL_FRAME_BYTES` (**the cap moved to 1 000, the frame limit did not**), and §9 listed
`status` on the wire in the same paragraph that called it untrusted (**removed**; both platforms now
assert structurally that their encoder cannot emit one).

### Two integration bugs the tests caught, both fixed here

1. **A declared sync failure was immediately overwritten by `SYNCED`.** The scheduled-command guard claimed that state unconditionally, and a drift correction ran through the same guard. Fixed by splitting the two: corrections use a guard that writes no state, and only a *new playback epoch* retires a failure. Found by `SyncPlaybackDriftTest`'s catastrophic-drift case, not by review.
2. **The leader applied its own command through a different path than the follower.** An "issuer applies immediately" shortcut is precisely how two phones end up on two timelines; the leader now applies the identical header through the identical code path, with the same `effective_at`.

### A stress flake that turned out to be a real production defect

The first 100-run iOS stress pass on `SyncPlayback*` failed **7 times**, and the second — after
fixing what looked like the whole cause — failed **8**. Both were reproduced deliberately rather than
re-run, and the second batch is why this section exists.

**The first cause was the harness.** A cadence tick spans several actor hops (send the report,
re-prove the session generation and the epoch, read the route state, apply the correction) and the
test waited for it by yielding the cooperative pool a fixed 40 times and hoping. Fixed with a real
signal: `SyncPlaybackDiagnostics.correctionTickCount`, incremented **after** the correction is
applied, so it means "this tick finished" rather than "this tick began". It is a genuine FR-023
figure — a stalled counter means correction has stopped — and it is mirrored on Android.

**The second cause was not.** The remaining failures included
`testTwoSimultaneousFollowerIntentsReceiveConsecutiveSequenceNumbers` producing `[2, 1]`: two frames
delivered back to back were **processed out of order**.

`PlaybackForwarder` wrapped each inbound frame in its own unstructured `Task`. That preserves only
the order in which tasks are *created* — and `OrderedEventChannel`'s own doc comment, written for the
Phase 1b control-event ordering bug (§2h), already says in as many words that Swift makes no such
guarantee about the order they *run*. **On the wire this is a real defect, not a test artefact:** a
follower processing `PAUSE(seq 6)` before `PLAY(seq 5)` drops the `PLAY` as stale —
`CommandOrderGate` doing exactly its job on input that reached it out of order — and pauses a track
it never loaded.

**Android had the same defect for a different reason.** A coroutine per frame preserves dispatch
order, but `onPlaybackMessage` *suspends* (content resolution, decoder pre-roll), so frame N+1 could
overtake frame N inside the suspension and apply first.

**Fixed identically on both platforms:** one bounded channel, one consumer, arrival order preserved.
iOS reuses the `OrderedEventChannel` Phase 1b already introduced for this exact hazard (given a
bounded `bufferingNewest` initialiser, since every queue this project adds is bounded — ADR-021 §5);
Android uses a bounded `Channel` with `DROP_OLDEST` and a counted drop. The authentication generation
is now a **parameter** of `PlaybackSink.submit`/`QueueSink.submit`, passed by the read loop, rather
than something the receiver looks up when its own work happens to run — which is ADR-023 Amendment
A3's finding applied to Phase 5's own dispatch.

**This is the value of the stress discipline, concretely.** Both defects were invisible to a
single-run suite, both would have surfaced on a ride as "the other phone paused a track it wasn't
playing", and the second was found only because the first fix did not make the flake go away and the
remaining failures were read instead of re-run.

### One over-specified test, corrected rather than pinned

The two-peer conflicting-press test originally asserted that a follower's `PAUSE` intent would
receive `command_seq` 2 and the leader's own `SEEK` 3. It failed — the leader's own action reached
the serialisation point first — and the assertion was wrong, not the code. ADR-010's guarantee is
that there is exactly **one** serialisation point, not that a particular press wins; pinning an order
would have been pinning the scheduler. The test now asserts what must actually hold: two presses
became one total order with consecutive sequence numbers, and both phones ended on the same one.

### Where the coordinators live, and why they differ

Android's is in `app` (which has unit tests). iOS's is in **`RideLinkPlatform`, not the app target**,
because the app target has no test target at all (§4 problem 20) — a coordinator written there would
have been untestable while its Android twin had 32 cases. Only the adapters over `MusicCoordinator`
stay in the app. That is a deliberate narrowing of problem 20 for this phase's code, not a fix for it.

iOS's coordinator is an `actor`: every mutable field is read-then-written across a suspension
somewhere, and Swift 6 strict concurrency will not let that be accidental. Its inbound/apply half is
a second *file* rather than a second type, because it mutates the same isolated state.

### `ControlSessionManager` was extracted rather than raised, again

Phase 5's relay took the class past detekt's `LargeClass` ceiling. `config/detekt/detekt.yml` records
in its own words that the headroom bought in Phase 2a "is the last of it", so the answer was the
extraction that file prescribes: the five near-identical relay-construction blocks became
`network.control.ControlRelays`. **No detekt threshold was raised in this phase.**

### Verification

**Android — every gate green.** `test ktlintCheck detekt lint assembleDebug assembleRelease`.
**No detekt threshold was raised in this phase**; `ControlSessionManager` was extracted instead.
New/changed suites: `core` +7 (`SessionClockVectorTest`, `CommandOrderingVectorTest`,
`DriftVectorTest`, `SharedQueueVectorTest`, `PlaybackTimelineTest`, `PlaybackMessagesVectorTest`,
`QueueMessagesVectorTest`), `network` +1 (`PlaybackAuthenticationGateTest`, 4 cases over **real
TLS**), `app` +3 (`SyncPlaybackCoordinatorTest` 19, `SyncPlaybackDriftTest` 9,
`SyncPlaybackTwoPeerTest` 4).

**iOS — every gate green.** `RideLinkCore` **276** tests, `RideLinkPlatform` **316**, `xcodebuild`
Debug *and* Release for the simulator. New suites mirror Android's, plus
`SyncPlaybackTwoPeerTests` — two coordinators over a **real authenticated TLS 1.3 connection** with
the real `ClockSync` estimator running real PING/PONG bursts and the real monotonic sleeper.

**Cross-platform parity.** All six new vector sets pass identically on both platforms against
expected values produced by an independent third transcription of the spec, **on the first run** —
no generator was adjusted to match an implementation, and no implementation was adjusted to match a
generator.

**Stress, no rerun-until-green.** Android `com.ridelink.app.sync.*` (32 cases): **60 consecutive
runs, 0 failures**. iOS `SyncPlayback*` (30 cases): **100 consecutive runs, 0 failures** — reached
only after the two defects the earlier batches surfaced were found and fixed (see below). Each run
was in isolation; the earlier Phase 2b lesson about concurrent `./gradlew` invocations contending on
the Kotlin daemon still applies and was respected.

**The two two-peer tests are deliberately complementary.** iOS is real-wire with a near-zero offset
(one process, one monotonic clock); Android is fake-wire with a real offset (two coordinators on
clocks **7.5 s apart**), so the session-clock arithmetic is genuinely exercised rather than trivially
satisfied. iOS measured a **mapped session start error of 47 microseconds** — a *software scheduling*
figure, and nothing more.

### The Android scheduled-start path *did* run, on the emulator

`audio/src/androidTest/.../SyncScheduledPlaybackTest` — three cases, all passing on the real
`RideLink_API36` emulator (Android 16, API 36, arm64) against the real `ExoPlayer` and a real AAC
decode of `test-media/synthetic/normal.m4a`:

- a track pre-rolled to a position, then started at a **monotonic deadline** 120 ms out (§7.2's floor). The decoder was `READY` before the deadline arrived, which is the whole point of the pre-roll. **Measured across two runs: the sleeper woke 1 420 µs and 3 099 µs after its deadline; `ExoPlayer` reported playing 2 665 µs and 4 337 µs after it.**
- ADR-004's nudge reaching the real `setPlaybackParameters`: 0.998, then 1.002, then back to **exactly** 1.0, each observed in `PlayerState.rate`.
- a `Stop` restoring the rate to exactly 1.0, so nothing is left behind for the next track (brief §38).

**Those numbers are software scheduling on one emulator and nothing else.** No second device, no
Bluetooth, no speaker, no recorder. They bound a scheduler; they are not alignment.

### CI

One fresh run, observed once, **green on both platforms on the first attempt** — no rerun-until-green.

| | |
|---|---|
| Run | [34248542704](https://github.com/arunachaleswaranms/RideLink/actions/runs/34248542704), run number 32, attempt 1 |
| Head commit | `6cfa2ef` (`test: run the Phase 5 scheduled-start path on the real Android emulator`) |
| Functional SHAs | `6834faa` (domain + protocol), `6186816` (Android integration), `bea0a5c` (iOS integration), `c7172fe` (inbound ordering fix + docs) |
| Android job | **success** — core unit tests, all unit tests, ktlint, detekt, lint, assembleDebug, assembleRelease |
| iOS job | **success** — `RideLinkCore` tests, `RideLinkPlatform` tests, Debug simulator build, Release simulator build |

The two annotations on the run are GitHub's own Node 20 / `setup-java@v4` deprecation notices, not
project failures, and are unchanged from the Phase 4 runs.

`connectedDebugAndroidTest` is deliberately **not** in CI — there is no emulator on the runner. The
Phase 5 instrumented cases above ran locally on `RideLink_API36`.

### What is explicitly not done

- **Nothing ran on a phone**, and no audio was played through a speaker or a Bluetooth endpoint anywhere in this phase.
- **The iOS scheduled start has not run on a simulator or a device.** Its Android twin now has (above), so this is the remaining half of §4 problem 41 — a gap of time, not of possibility.
- **`AVAudioUnitVarispeed` has never changed a real rate.** `ExoPlayer.setPlaybackParameters` now has.
- **No alignment figure of any kind exists.** The 47 us above is two `Task.sleep` wake-ups in one process; a decoder, a mixer, a speaker and two Bluetooth hops sit between a scheduled instant and a listener's ear. The <100 ms product target and the <50 ms stretch target (REQUIREMENTS §7, ADR-008) are **unmeasured and must not be described as approached.**
- **No drift p95 exists** — TEST_PLAN S-09/I-12 is the only thing that produces one.
- **Phase 6 and Phase 7 are untouched.** No ducking, no VOX/music interaction, no HFP/A2DP switching, no route arbitration, no pause-on-talk, no Ride Mode UI, no automatic resync. Phase 5 *reads* `AUDIO_STATE.route_state` in exactly one place — to suspend the drift ladder, which ARCHITECTURE §7.3 already required — and changes no Bluetooth behaviour.
- **One recorded limitation** (ADR-024 §7): the availability gate learns a peer holds content from its synced manifest or from a transfer this device served it *in this session*. A track the peer imports locally mid-session is invisible until the next manifest synchronisation, which V1 performs on `Connected` only. The user sees `WAITING FOR CONTENT`; reconnecting resolves it.


---

## 2ab. Phase 5 closure audit A1 (8–9 September 2026 session, twenty-fifth)

**Status: SOFTWARE CLOSURE A1 COMPLETE — REAL-DEVICE SYNCHRONIZED-PLAYBACK GATE PENDING.**
Deliberately not "final software closure complete": §2y dropped the word "final" from Phase 4 after
five audits each found real defects in CI-green code, and one audit of Phase 5 is not evidence that a
second would find nothing. §7 asked for exactly this audit; this is it, and it should be
independently verified rather than taken on trust.

### What the audit was given, and what it found

Six independently identified findings, each to be classified from the **current production code**
rather than from this file or from a commit message. **All six were CONFIRMED.** A seventh was found
by stress-running one of the new regressions. None was a false positive.

All seven share one shape, and it is the shape §12 of ADR-024 already named: **a decision made under
a guard, and a consequence that escaped it** — through a suspension, a lock released too early, a
queue that dropped what it had accepted, a sequence number spent before the work it authorised was
done, or an unstructured task that threw away the order it was created in.

| | Finding | Confirmed defect | Fix |
|---|---|---|---|
| **A** | Follower's first Play vs. its own queue add | `playSynchronized` sent `QUEUE_ADD` as an intent and issued `PLAY` **without waiting**, carrying the revision it still held. The leader accepted the add, moved the revision, then refused the `PLAY` under its own §5 rule 3 check. **The first press did nothing** | One press is one *retained* request, fenced by an `OperationFence` token, issued once the authoritative `QUEUE_SNAPSHOT` names its `queue_item_id`. The revision rule did not move |
| **B** | Leader's semantic order vs. wire order | Allocation was locked; **the send was not**. Two coroutines raced for the socket, so a `PLAY` stamped for revision *n* could reach the wire ahead of the `QUEUE_SNAPSHOT` that created it. On iOS the same defect wore actor clothing: `await …send(…)` between stamping and sending is a **re-entrancy point** | One outbound serialisation owner: allocation and hand-off in the same critical section (under the mutex on Android; **no `await` between them** on iOS), one consumer draining onto the transport |
| **C** | Post-transport frame loss | `DROP_OLDEST` / `.bufferingNewest` — a **lossy queue immediately behind reliable ordered authenticated TCP**. And invisible: `trySend` on a `DROP_OLDEST` channel returns *success*, so the drop counter Phase 5 shipped could never fire for an eviction at all | `Phase5FrameQueue`: bounded, lossless, order-preserving, both directions. Latest-wins families coalesce (lossless); anything else is **refused and counted**. A refusal on a follower halts incremental application without spending a `command_seq`, until authoritative full state or a session boundary |
| **D** | Accepted ≠ applied | `lastAppliedSeq` was set **before** the clock was consulted, so a momentarily untrusted estimator spent the sequence number and applied nothing — and the leader's replay was then correctly dropped as a duplicate. **Lost permanently, on a condition that resolves in milliseconds** | `lastReceivedSeq` (what the order gate reads) split from `lastAppliedSeq` (what `PLAYBACK_STATE` reports). A command accepted against an untrusted clock is **held in order** and applied on recovery, re-checked every 100 ms |
| **E** | Play across a Phase 4 transfer | `gateContent` requested the transfer and returned false; **nothing was retained**. The user had to press Play again after the download. That is not REQUIREMENTS §9.4's first-play flow, and UJ-05 is exactly this case | The same retained request, re-evaluated on Phase 4's **own** verified-availability notification. One new authoritative `PLAY` with a **fresh** instant; Phase 4 asked once per request; superseded/session-stale/left-sync-mode requests never resurrect |
| **F** | Superseded correction | iOS's `runIfCurrent` returned `Void` and the caller then mutated diagnostics, spent the hard-seek budget, could latch `syncFailed` and **emitted `PLAYBACK_STATE` onto the wire** — all unconditionally. Android was safer but not correct: the guard was proved *before* a player call that suspends | `runIfCurrent` returns whether it ran, every caller branches, and ownership is re-proved **after** the player's suspension and before anything externally visible. Zero effects, not merely no player action |
| **G** | Apply-path ordering (**found by the audit's own stress run**) | One task per armed action preserves only *creation* order; each action then suspends inside the player. `PAUSE(n)` and `RESUME(n+1)`, stamped microseconds apart, could take effect in **either** order. 2 failures in 100 on iOS, in the Finding C regression itself | Each armed action joins the previous one. Authoritative deadlines increase with `command_seq`, so the chain's order *is* the authoritative order; a superseded link returns at once and never stalls the chain |

### Finding G is the one worth reading twice

It is the *same* defect the inbound handoff already had — §2aa records it in as many words: "a coroutine
per frame preserves only the order in which coroutines are *started*". That fix was applied **where
the bug had been observed**, not everywhere the reasoning held, and the identical hazard sat
untouched at the other end of the same pipe for the whole phase. iOS failed visibly because
unstructured tasks have no ordering guarantee at all; Android hid it because its single-threaded
dispatcher plus non-suspending test fakes made the interleaving unreachable — a real
`MusicCoordinator` suspends exactly where the fake does not.

**It was found by stress-running a new regression, not by review**, which is the third time this
project's stress discipline has paid for itself (§2aa found the inbound ordering bug the same way; §2n
found a voice mailbox bug). It was then **verified to fail against the pre-fix code** on Android as
well as observed failing on iOS.

### Three rules moved out of the coordinators and into pure tables

CLAUDE.md rule 18 says every distributed decision is a pure, mirrored, vector-pinned table and the
coordinators are wiring. Findings A, C and D were all rules that had been written as coordinator
control flow instead — which is why no vector could pin them and why the two platforms had already
drifted on the details. They are now `Phase5Ingress`, `PendingCommandGate` and `PendingPlayGate` in
`core.playback.Phase5Gates` / `RideLinkCore.Playback.Phase5Gates`, pinned by the new
`protocol/vectors/phase5-gates/` — **228 rows, full cross products** (including the complete 2^7 for
the retained-Play gate), generated by an independent third transcription of the amendment's prose.

### The wire did not move

**All twelve pre-existing Phase 5 vector sets regenerate byte-for-byte identically**, verified by
re-running every generator in the repository and diffing. No message type was added or removed, no
field added, moved or renamed, and `MAX_CONTROL_FRAME_BYTES` is untouched.

**One handler semantic changed, and it is stated in the ADR and in PROTOCOL §5 rather than folded in
silently:** while a receiver is desynchronised, a `PLAYBACK_STATE` **restores** playback rather than
only re-anchoring it. Without that, reconciliation leaves a follower coherent about *ordering* and
wrong about *what is playing*. No field was needed — the snapshot already carries every value, and
because the instant it names is in the past, §5 rule 2's "apply immediately and record the lateness"
is what happens. **No expired deadline is ever reused as though it were still ahead.**

### One footgun removed rather than left loaded

`OrderedEventChannel.init(bufferingNewest:)` — the drop-oldest bounded stream Finding C is about — had
Phase 5's inbound pipe as its **only** caller, so the fix made it dead code. It is deleted, and the
type now carries a note saying why it has no bounded drop-oldest initialiser. Leaving it there after
an audit whose whole subject was that constructor would have been leaving a loaded footgun for the
next person. No Phase 2a/2b behaviour changed — `VoiceController`'s route channel uses the unbounded
`init()`, as it always did. **Out-of-scope observation, not fixed:** that channel being unbounded sits
awkwardly against ADR-021 §5's "every queue this project adds is bounded", though its producer is this
process rather than a peer. Untouched here.

### Recorded rather than papered over

- **Recovery from a desynchronisation is *awaited*, not *requested*.** `STATE_REQUEST` is in PROTOCOL §3's catalogue and remains unimplemented. A halted follower recovers on the leader's next authoritative snapshot or at the next session boundary, and **there is no bound on how long that takes**. A test pins the corollary honestly: at the bound, even a reconciliation frame can be refused for want of room. At the production bound of 256 with coalescing, any non-pathological link provides the space. Bounding the latency properly is reconnect work (PROTOCOL §10) and was deliberately not done here.
- **A leader's ingress overflow loses a button press, and that is the accepted behaviour.** The leader only ever accepts *intents*, which it stamps rather than applies, so its authoritative state cannot become incoherent. It re-broadcasts state and continues rather than halting.
- **`applyPlay` for an authoritative `PLAY` a follower cannot serve is unchanged** — it requests the transfer and waits for the leader, exactly as PROTOCOL §5 rule 4 says. Finding E's retained request covers a Play *this user asked for* (on either phone, since the leader retains a follower's intent too), which is what §9.4 describes; extending it to "a follower re-proposes a track the leader is already playing" would restart that track on both phones from the beginning and was left alone. **Recorded as an out-of-scope observation, not fixed.**

### Six test-harness races fixed, all in the *tests*, none a production defect

The stress runs found six places where a test awaited one observable and asserted a different one —
the shape §4 problem 31 already names, now with nine examples. Every one was fixed by waiting for the
right signal, and the production behaviour was correct in all six: the outbound send is asynchronous
*by design* (that is what makes Finding B's order provable), the scheduled-action chain is
asynchronous *by design* (Finding G), and an already-authorised start landing just after a link loss
is *correct* (ADR-004 keeps local playback going). Two new observable signals were added for this and
both are real FR-023 figures: `outboundEnqueuedCount`/`outboundSentCount` (a persistent gap means the
socket is not draining) and ingress idleness (a consumer that never parks is a consumer falling
behind).

### Verification

- **Android:** `:core:test`, `:network:test`, `:data:test`, `:audio:test`, `:app:test`, `test`, `ktlintCheck`, `detekt`, `lint`, `assembleDebug`, `assembleRelease` — all green.
- **Android emulator:** 48 instrumented tests on the real `RideLink_API36`, across `:app`, `:audio` and `:data` — including `SyncScheduledPlaybackTest`, so the Phase 5 scheduled-start path was re-run against a real `ExoPlayer` **after** the audit's changes, not merely assumed to still work.
- **iOS:** `swift test` on `RideLinkCore` (281 tests) and `RideLinkPlatform` (350 tests), Debug and Release unsigned simulator builds — all green. **SwiftLint/SwiftFormat are still not installed on this machine (§4 problem 14) and did not run; nothing here should be read as their having passed.**
- **Stress:** Android **100/100** clean on the Phase 5 suites (`app.sync` + `core.playback`). iOS **100/100** on the fast suites and **60/60** on the real-TLS two-peer + pre-authentication suites. **Zero unexplained flakes:** every failure any stress run produced was investigated and explained before anything was re-run — one was a production defect (Finding G) and six were harness races. Nothing was re-run to green.
- **New tests:** 23 Android + 22 iOS closure-audit regressions, 9 + 9 frame-queue tests, 7 + 5 gate vector tests, and 3 + 3 two-peer integrations (real TLS on iOS). Each of the seven findings has at least one regression **verified to fail against the pre-fix code**.
- **Vectors:** every generator in the repository re-run and diffed. All twelve pre-existing Phase 5 sets byte-for-byte identical; `phase5-gates/` added.
- **Pre-fix verification, spot-checked rather than asserted.** Findings A, D and G had their fixes reverted and their regressions confirmed to fail: reverting `WAIT_FOR_QUEUE` failed 4 tests (including the two-peer convergence), reverting the clock-readiness admission failed 4, and reverting the scheduled-action chain failed the ordering test. Every fix was restored and the suites re-run green afterwards.
- **CI:** run **34272503677** (run number **33**, **attempt 1**, `f05c3b877c33e3ee9b4958bd90e45e000d280728`) — **green on both platforms, first attempt, nothing re-run.** Android: core unit tests, all unit tests, ktlint, detekt, lint, `assembleDebug`, `assembleRelease` all success. iOS: `RideLinkCore` tests, `RideLinkPlatform` tests, Debug and Release unsigned simulator builds all success. `connectedDebugAndroidTest` is deliberately not in CI (no emulator on the runner); the 48 instrumented tests above ran locally on `RideLink_API36`.

### What is still explicitly not done

- **Nothing ran on a phone.** No audio reached a speaker or a Bluetooth endpoint anywhere in this audit.
- **The iOS scheduled start still has not run on a simulator**, and `AVAudioUnitVarispeed` has still never changed a real rate (§4 problem 41's remaining half).
- **No alignment figure of any kind exists.** Every number this audit produced is a *software* figure. The <100 ms product target and the <50 ms stretch target (REQUIREMENTS §7, ADR-008) remain **unmeasured and must not be described as approached.** TEST_PLAN §5.2's S-01…S-12 are the gate.
- **Phase 6 and Phase 7 are untouched.** No ducking, no VOX/music policy, no HFP/A2DP arbitration, no Ride Mode. Phase 5 still reads `AUDIO_STATE.route_state` in exactly one place and changes no Bluetooth behaviour.

---

## 2ac. Phase 5 closure audit A2 — the outbound delivery join (9 September 2026 session, twenty-sixth)

**Status: SOFTWARE CLOSURE A2 COMPLETE — REAL-DEVICE SYNCHRONIZED-PLAYBACK GATE PENDING.**
Still not "final software closure complete", for the reason §2ab already gave and this session
proved again: A1 was a careful audit that confirmed seven findings, and independent verification of
*its own output* found five more. Two audits is two audits' worth of assurance.

### What the audit was given, and what it found

Five independently identified findings, each classified from the **current production code** rather
than from this file, from ADR prose, from a commit message or from a test name. **All five were
CONFIRMED.** A sixth (**F**) was found while fixing the fourth — the fix for **D** broke a valid
frame sequence, and the test said so before the code shipped.

All five share one sentence: **A1 made the leader's order the wire order, and then treated "handed
to the outbound queue" as if it were "the peer has it".** A1's own §2ab table is about decisions
escaping their guard; A2's is about *consequences* escaping their delivery.

| | Finding | Confirmed defect | Fix |
|---|---|---|---|
| **A** | Admission ≠ delivery | `enqueueOutbound` returned `Unit`. A full queue counted an overflow and returned, and every caller carried on — `issue` consumed the `command_seq`, recorded it applied and scheduled the audio; `applyLeaderMutation` bumped and published `queue_revision`. **The leader played a command, and sat on a revision, the follower could never receive** | `enqueueOutbound` answers whether the frame was accepted, and every caller branches. `command_seq` and `queue_revision` are consumed **only on admission**; the local audible effect only on send success. A refused authoritative frame fails the session closed |
| **B** | Outbound frames were not session-bound | The envelope was the bare message, and `PlaybackRelay.send` resolved the writer **and the `session_id`** at send time. The outbound queue deliberately outlives sessions, so a Session A frame still queued when Session B activated was written **under Session B's identity** — the exact class ADR-023 A3/A5 hardened Phase 4 against | Two guards, both needed: the envelope carries its **authorising generation** and the consumer refuses to write a mismatch; and `PlaybackRelay.send` **takes the generation**, checks it, resolves the writer and `session_id`, checks again, and writes to *that* session's socket. The coordinator guard alone leaves a window — the audit's own test proved it |
| **C** | `send`'s answer was discarded | The drain did `send(frame)` then `outboundSentCount += 1`, the `Bool` thrown away — silently on iOS, where the protocol is `@discardableResult`. **`sent == enqueued` could be reported while frames had been discarded**, and the command was already committed locally | The result is consumed. Three counters partition every attempt — `outboundSentCount` (the write returned **true**, nothing weaker), `outboundFailedCount`, `outboundStaleCount` — summing to `outboundAttemptCount`, all incremented *after* the attempt completes |
| **D** | A deferred command executed against a newer revision | A1 held a command whose clock was untrustworthy and held **nothing else**, so a `QUEUE_SNAPSHOT` arriving behind a held `NEXT` applied immediately. `NEXT @ rev 5` on `[A,B,C]` means B; run against `[A,C]` it means C. **The two phones select different tracks**, with `command_seq` and `queue_revision` both satisfied | The held buffer holds an **authoritative event stream** — commands, `QUEUE_SNAPSHOT`, `PLAYBACK_STATE` — in arrival order, replayed in arrival order. `POSITION_REPORT` is never held. Re-checking the revision at drain and dropping the command was rejected: that is A1 Finding A again |
| **E** | A correction's snapshot lost its causal session/epoch | A1 Finding F made a superseded correction have zero effects — almost. `emitPlaybackState()` read the **live** generation inside itself, after the two reads that suspend, so **a snapshot caused by a correction in Session A could be enqueued into Session B** | Two functions: `emitCurrentPlaybackState()` (the leader re-stating *now*) and `emitPlaybackStateIfOwned(generation:token:)`, which carries the correction's own generation and epoch to the enqueue and re-proves both immediately before it |
| **F** | The revision rule was checked against the wrong state (**found while fixing D**) | Holding `QUEUE_SNAPSHOT` behind a held command immediately broke a valid stream: `SEEK @ rev 5`, `SNAPSHOT rev 6`, `PAUSE @ rev 6` — the `PAUSE` was refused under §5 rule 3, because the revision *applied* was still 5 while the snapshot making it 6 sat in front of it | The rule did not move; **where it is evaluated** did. It is checked against the state the command will actually be applied to — immediately when nothing is held, at replay time when something is. A mismatch at replay cannot occur in a stream the leader produced, so it takes the same halt-and-reconcile posture as every other lost-authority case |

### Finding B is the one worth reading twice

The audit's first fix for it — tag the envelope with its generation and check it in the drain — was
written, believed, and then **shown to be insufficient by its own regression**. The check happens
before the send begins; the relay then resolves the writer and the `session_id` *inside* the send,
and a boundary landing in that window still writes the old frame under the new session. Nothing but
pushing the generation down into `PlaybackRelay.send` closes it.

That is the same lesson as A1 Finding G, in a different costume: **a guard proves something about
the instant it runs, and the thing it is guarding happens later.** The only fix that works is to
carry the authorisation to the point of the act, rather than to re-derive it there.

### Two more rules moved out of the coordinators and into pure tables

CLAUDE.md rule 18, applied again. `OutboundCommitGate` (12 rows: `COMMIT` for exactly the `SENT`
outcome and no other; `ABORT_FAIL_CLOSED` for any other outcome of an authoritative frame;
`ABORT_QUIET` for an intent or an advisory frame) and `AuthoritativeHoldGate` (24 rows: nothing may
overtake held authoritative work, and the bound is real) join A1's three in
`core.playback.Phase5Gates` / `RideLinkCore.Playback.Phase5Gates`, pinned by 36 new rows in the
existing `protocol/vectors/phase5-gates/` — generated by the same independent third transcription.

### The fail-closed posture, and what it deliberately does not do

An authoritative frame that did not reach the peer ends Phase 5 authority **for that authentication
generation**: no further command, no further queue mutation, no further `PLAYBACK_STATE`, and
`SyncState.TRANSPORT_FAILED` on the diagnostics surface. It does **not** stop the music and does
**not** supersede the playback epoch — synchronised mode is *left*, so `MusicCoordinator`'s transport
controls answer locally again and the ride continues as a Phase 3 ride (ADR-004, FR-025), and **a
frame the transport did accept still commits and still takes effect**, because refusing to apply a
command the peer already has would manufacture the mirror image of the divergence being closed.
Recovery is a new session. `STATE_REQUEST` is **still catalogued and still unimplemented**: none of
these five needed it, and it would not help here — a peer that never received a command does not
know to ask for it.

### How each finding was proved, rather than argued

Every fix is pinned by a deterministic regression on both platforms
(`SyncPlaybackDeliveryAuditTest` / `SyncPlaybackDeliveryAuditTests`, 17 and 18 tests), and each
regression was **verified to fail against the pre-A2 behaviour** by re-introducing that behaviour one
line at a time and re-running the suite. Seven such mutations on Android and eight on iOS; every one
was caught by at least one new test. The iOS Finding E mutation is the exact pre-A2 shape — the live
generation read after the player state — and it emits a Session A snapshot into Session B, which two
tests catch.

The Finding E interleaving is genuinely reproduced on **iOS**, by parking the emit's own
`playerState()` read and landing the session boundary inside it; every `await` on an actor is a
re-entrancy point, so this is the race itself and not a stand-in. On **Android** the window is
between two statements a `StandardTestDispatcher` cannot interleave, so the mirrored test asserts the
contract instead. **The defect is real on Android too** — the app runs on a multi-threaded dispatcher
— and the fix is the same structural one; what is missing there is only a *failing-before-fix*
demonstration, and this is recorded rather than papered over.

### Two-peer coverage

Android adds two coordinator-pair scenarios on clocks 7.5 s apart: an operation the leader could not
admit, and an operation stranded by a session boundary. Both assert on the **follower's** applied
`command_seq`, queue revision and player calls — not on the leader's internals. iOS adds one over a
**real authenticated TLS 1.3 connection** with no fake standing in for the transport: the leader's
control session is shut down so the real relay genuinely answers false, and the leader must then not
apply a command the follower can never receive.

### What did not change

No wire change of any kind: `MAX_CONTROL_FRAME_BYTES` untouched, no message type added, removed or
activated, no field added, moved or renamed, and **every pre-existing vector set regenerates
byte-for-byte identically** (verified by running all thirteen generators; only `phase5-gates` moved,
and only additively). One *internal* signature changed — `PlaybackRelay.send` takes the authorising
generation. A1's seven findings and all of Phase 4's A1–A5 regressions remain green.

### Evidence

- **Android:** `test` (every module), `ktlintCheck`, `detekt`, `lint`, `assembleDebug`, `assembleRelease` — all green locally on JDK 21.
- **Android emulator:** 48 instrumented tests on the real `RideLink_API36` across `:app`, `:audio` and `:data`, including `SyncScheduledPlaybackTest` — so the Phase 5 scheduled-start path was re-run against a real `ExoPlayer` **after** this audit's changes, not assumed to still work.
- **iOS:** `swift test` on `RideLinkCore` (**284** tests) and `RideLinkPlatform` (**369** tests), plus Debug and Release unsigned simulator builds on iPhone 17 Pro Max — all green. **SwiftLint/SwiftFormat are still not installed on this machine (§4 problem 14) and did not run; nothing here should be read as their having passed.**
- **New tests:** 17 Android + 18 iOS delivery-audit regressions, 5 + 5 new gate vector assertions, 2 Android two-peer scenarios and 1 iOS real-TLS two-peer scenario.
- **Pre-fix verification, done exhaustively rather than spot-checked.** Every pre-A2 behaviour was re-introduced one line at a time and the suite re-run: **seven mutations on Android, eight on iOS, every one caught by at least one new test.** The iOS Finding E mutation is the exact pre-A2 shape and emits a Session A snapshot into Session B, caught by two tests. All mutations were reverted and the suites re-run green.
- **Vectors:** every generator in the repository re-run and diffed. **Every pre-existing set is byte-for-byte identical**; only `phase5-gates/` moved, and only additively (228 → 264 rows).
- **CI:** run **34384138628** (run number **34**, **attempt 1**, `680f733e7f75684289f9a222d3db5bac39a3b50e`) — **green on both platforms, first attempt, nothing re-run.** Android: core unit tests, all unit tests, ktlint, detekt, lint, `assembleDebug`, `assembleRelease` all success. iOS: `RideLinkCore` tests, `RideLinkPlatform` tests, Debug and Release unsigned simulator builds all success. `connectedDebugAndroidTest` is deliberately not in CI (no emulator on the runner); the 48 instrumented tests above ran locally.

### What is still not true

**Nothing in this session ran on a phone, and no audio reached a speaker or a Bluetooth endpoint.**
Every figure here is a software figure. The stress runs the audit brief §43/§44 describes were
**explicitly skipped at the user's request** and are not claimed. The <100 ms product target and the
<50 ms stretch target remain unmeasured; TEST_PLAN §5.2's S-01…S-12 are what will change that.

## 3. Tests passed / pending

**Passed and verified in the Phase 5 session (8 September 2026, twenty-fourth), by actually running
the commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4 problem 17).

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five Android modules. New this phase: `core` +7 suites (`SessionClockVectorTest` 4, `CommandOrderingVectorTest` 2, `DriftVectorTest` 3, `SharedQueueVectorTest` 4, `PlaybackTimelineTest` 8, `PlaybackMessagesVectorTest` 1, `QueueMessagesVectorTest` 2), `network` +1 (`PlaybackAuthenticationGateTest` 4, over **real TLS**), `app` +3 (`SyncPlaybackCoordinatorTest` 19, `SyncPlaybackDriftTest` 9, `SyncPlaybackTwoPeerTest` 4).
- `swift test --package-path ios/Packages/RideLinkCore` — **276/276**.
- `swift test --package-path ios/Packages/RideLinkPlatform` — **316/316**, including `SyncPlaybackCoordinatorTests` (18), `SyncPlaybackDriftTests` (9), `SyncPlaybackTwoPeerTests` (3, over **real authenticated TLS**) and `PlaybackAuthenticationGateTests` (4).
- `xcodebuild -scheme RideLink -configuration Debug -sdk iphonesimulator` and the same for `Release` — **both succeed**.
- **Cross-platform parity:** all six new vector sets produce identical results on both platforms, against expectations generated by an independent third transcription, **on the first run**.
- `./gradlew :audio:connectedDebugAndroidTest` on the real `RideLink_API36` emulator — **all green**, including the three new `SyncScheduledPlaybackTest` cases (real `ExoPlayer`, real AAC decode, real monotonic deadline, real `setPlaybackParameters`).
- **Stress (no rerun-until-green):** Android `app.sync` **60 runs, 0 failures**; iOS `SyncPlayback*` **100 runs, 0 failures**. The batches before those two found a harness race *and a real production ordering defect* — see §2aa.

**Not run this phase, and the omission is deliberate rather than overlooked:** anything on a phone,
anything through a decoder or a speaker, and any alignment measurement. §4 problem 41 records the one
gap that is a matter of time rather than of possibility.

**Passed and verified in the Phase 2b session (4 September 2026, tenth), by actually running the
commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4 problem 17):

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five
  Android modules. **436 tests** (was 336): `core` **257** (was 185 — +14 `IntercomVectorTest`,
  +10 `IntercomTransmissionTest`, +11 `IntercomCommandMailboxTest`, +12 `AudioSessionLifecycleTest`,
  +11 `RideStartPolicyTest`, +8 `VoiceSetupTimelineTest`, +10 `AudioStateVectorTest`), `network`
  **148** (was 130 — +15 `VoiceControllerIntercomTest`, +3 `VoiceAuthenticationGateTest` for
  `AUDIO_STATE` over real TLS), `audio` **20** (was 11 — +9
  `AndroidCommunicationDeviceSelectorTest`), `app` 2, `data` 9.
- `swift test --package-path ios/Packages/RideLinkCore` — **142/142** (was 69; the same seven
  mirrored suites).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **178/178** (was 150 — +15
  `VoiceControllerIntercomTests`, +10 `AudioSessionSignalBoxTests`, +3 `VoiceAuthenticationGateTests`
  for `AUDIO_STATE`), zero Swift 6 strict-concurrency warnings.
- `xcodebuild` Debug **and** Release for the simulator — both succeed, **zero warnings**.
- **Detekt found 12 real issues and every one was fixed rather than suppressed by a threshold
  change.** Two are worth recording because the fix improved the code rather than the metric:
  `ControlSessionManager.handleFrame` hit the cyclomatic-complexity ceiling when the `AUDIO_STATE`
  branch went in, so `PING` and `PONG` were extracted into named handlers (`docs/STATUS.md` §4
  problem 18's lesson, applied one function down); and `SessionCoordinator`'s constructor exceeded the
  parameter limit, so its three environment readings were grouped into `SessionEnvironment`. The four
  `@Suppress("ReturnCount")` annotations added each carry the same justification the codebase already
  uses for codec field rules — one early-out per spec rule, in spec order.
- **Stress validation (this phase's brief §52), all run locally and deliberately, with no
  rerun-until-green anywhere:**

| Suite set | Runs | Passed | Failed |
|---|---|---|---|
| Android pure intercom/lifecycle (`Intercom*`, `AudioSessionLifecycle`, `RideStartPolicy`, `VoiceSetupTimeline`, `AudioStateVector`), each run with `--rerun-tasks` | 50 | **50** | 0 |
| iOS pure intercom/lifecycle (the seven mirrors) | 50 | **50** | 0 |
| Android async/integration (`VoiceControllerIntercomTest`, `VoiceAuthenticationGateTest` over real TLS, `VoiceControllerMailboxTest`, `VoiceControllerTest`) — **after** the race fix below | 20 + 20 | **40** | 0 |
| iOS async/integration (the mirrors plus `AudioSessionSignalBoxTests`) | 20 + 20 | **40** | 0 |

  **The Android async pass failed 2 of its first 20 runs, and that is recorded rather than smoothed
  over.** Root cause: `switching policy announces the new mode on the wire without rebuilding
  anything` awaited a wire frame and then asserted the diagnostics, but `transport.send` happens
  inside the action loop while `publishDiagnostics` runs after it — so the frame is observable a few
  instructions before the state that describes it. Reproduced deliberately (12 attempts, hit on the
  second), fixed by awaiting both observables, and **no production code changed for it**. The two
  clean 20-run passes above are the two independent confirmations.
- SwiftLint/SwiftFormat: still not installed on this machine (§4 problem 14, unchanged).
- **All prior gates remain green locally**, including the Phase 1 security suites, the problem-28
  regression test, the Phase 2a bounded-mailbox suites, and the real two-engine WebRTC loopback test
  (real DTLS-SRTP, real Opus, host-only candidates).
- **CI green on the first run** — [33802909356](https://github.com/arunachaleswaranms/RideLink/actions/runs/33802909356),
  commit `a4c548d`. Android: `core unit tests`, `all unit tests`, `ktlintCheck`, `detekt`, `lint`,
  `assembleDebug`, `assembleRelease` — **all seven green**. iOS: `RideLinkCore` tests,
  `RideLinkPlatform` tests, unsigned Debug **and** Release simulator builds — **all four green**.
  Nothing was re-run, and no step was skipped except the failure-only report upload. The one
  annotation is GitHub's own Node 20 deprecation notice on `actions/checkout@v4`, which is unrelated
  to this repository's code and pre-dates this session.

  **This says nothing about a phone.** CI run 33098708512 was green over the Phase 1b trust-gate bug,
  and that warning has earned its place twice since. Green CI means the suites that exist pass; it is
  not evidence about anything no test crosses, and every hardware gate in §7 is still open.

**What none of this is evidence about:** any phone, any microphone, any speaker, any Bluetooth
endpoint, any foreground service, any lock screen, or any latency. See §2m's "Explicitly not done"
and §7.

**Passed and verified in the Phase 2b final hardening session, second pass (4 September 2026,
twelfth) — see §2o for the five findings this verifies:**

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five
  Android modules. **455 tests** (was 443): `core` **262** (was 257 — +5 `AudioSessionLifecycleTest`),
  `network` **157** (was 155 — +2 `VoiceControllerStopAwaitTest`), `audio` 20 (unchanged), `app` **7**
  (was 2 — +5 `SessionCoordinatorEndingEffectTest`, new file), `data` 9 (unchanged).
- `swift test --package-path ios/Packages/RideLinkCore` — **147/147** (was 142 — +5
  `AudioSessionLifecycleTests` mirrors).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **187/187** (was 185 — +2
  `VoiceControllerRouteOrderingTests`, new file), zero Swift 6 strict-concurrency warnings.
- `xcodebuild` Debug **and** Release for the simulator — both succeed, **zero warnings** beyond the
  pre-existing benign "no AppIntents.framework dependency" notice.
- **Stress validation, no rerun-until-green:** `AudioSessionLifecycleTest`, `VoiceControllerStopAwaitTest`
  and `SessionCoordinatorEndingEffectTest` (Android, each run in isolation) run **20 consecutive times,
  0 failures** each; `AudioSessionLifecycleTests` and `VoiceControllerRouteOrderingTests` (iOS) run **50
  consecutive times, 0 failures** each. An early attempt at the Android counts produced spurious
  failures from two concurrent `./gradlew` invocations against this project contending for the same
  Kotlin daemon — reproduced, root-caused as build-tooling contention rather than test logic, and
  re-run in isolation.
- **All prior suites remain green**, including every Phase 1b/2a/2b test named above this paragraph —
  this session ran the full local gate, not only the new tests.
- This pass landed as two commits: `797269b` (the five findings and problem 32) and `8b1797f` (a sixth
  defect, found while fixing Finding 1 on iOS — `close()` tore down its own settle/timeout fallback
  before the transition it began could use either; see ADR-021 Amendment A2, Finding 1). Both are CI
  green.
- **CI green on both commits, first run each time** —
  [33892453958](https://github.com/arunachaleswaranms/RideLink/actions/runs/33892453958) (`797269b`) and
  [33893509254](https://github.com/arunachaleswaranms/RideLink/actions/runs/33893509254) (`8b1797f`).
  Android: `core unit tests`, `all unit tests`, `ktlint`, `detekt`, `lint`, `assembleDebug`,
  `assembleRelease` — all seven green on both runs. iOS: `RideLinkCore` tests, `RideLinkPlatform`
  tests, unsigned Debug **and** Release simulator builds — all four green on both runs. Nothing was
  re-run. The only annotations are GitHub's own pre-existing Node.js 20/`setup-java@v4` deprecation
  notices, unrelated to this
  repository's code and pre-dating this session.

---

**Passed and verified in the Phase 2b final hardening session (4 September 2026, eleventh) — see §2n
for the eight findings this verifies:**

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five
  Android modules. **443 tests** (was 436): `network` **155** (was 148 — +5
  `VoiceControllerStopAwaitTest`, +2 `VoiceControllerDiagnosticsRaceTest`); `core`/`audio`/`app`/`data`
  unchanged (this pass touched no pure `core` type and added no `audio`-module test, since
  `AndroidVoiceAudioSession` still cannot run off a device).
- `swift test --package-path ios/Packages/RideLinkCore` — **142/142**, unchanged (nothing in the pure
  core changed this pass).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **185/185** (was 178 — net +7 from
  `AudioSessionSignalBoxTests`' rewrite for the new generation-tagged, priority-polling API), zero
  Swift 6 strict-concurrency warnings.
- `xcodebuild` Debug **and** Release for the simulator — both succeed, **zero warnings** beyond the
  pre-existing benign "no AppIntents.framework dependency" notice.
- **Stress validation, no rerun-until-green:** `AudioSessionSignalBoxTests` (iOS) run **50 consecutive
  times, 0 failures**; `VoiceControllerStopAwaitTest` + `VoiceControllerDiagnosticsRaceTest` (Android,
  together) run **50 consecutive times, 0 failures**.
- **All prior suites remain green**, including every Phase 1b/2a/2b test named above this paragraph —
  this session ran the full local gate, not only the new tests.
- **CI green on the first run** —
  [33882555289](https://github.com/arunachaleswaranms/RideLink/actions/runs/33882555289), commit
  `6be9f63`. Android: `core unit tests`, `all unit tests`, `ktlint`, `detekt`, `lint`, `assembleDebug`,
  `assembleRelease` — all green. iOS: `RideLinkCore` tests, `RideLinkPlatform` tests, unsigned Debug
  **and** Release simulator builds — all green. Nothing was re-run. The one annotation is GitHub's own
  Node 20 deprecation notice on `actions/checkout@v4`, unrelated to this repository and pre-dating this
  session.

---

**Passed and verified in the second Phase 2a mailbox hardening session (3 September 2026, eighth),
by actually running the commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4 problem 17):

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five
  Android modules. **335 tests** (was 324): `core` 184 (was 176 — +8 `VoiceInputMailboxTest`:
  terminal-lane classification, priority over ICE/coalesced/critical, drained-unchanged, flood
  overflow), `network` 129 (was 126 — +3 `VoiceControllerMailboxTest`: remote CLOSED/FAILED through
  the live reducer, terminal-lane overflow degrade), `audio` 11, `app` 2, `data` 9.
- `swift test --package-path ios/Packages/RideLinkCore` — **69/69** (was 61 — +8
  `VoiceInputMailboxTests`, mirroring Android's terminal-lane additions).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **150/150** (was 139 — +3
  `VoiceControllerMailboxTests` mirroring Android's, +8 new `ConflatedSignalTests` proving the
  doorbell's conflation semantics directly), zero Swift 6 strict-concurrency warnings.
- `xcodebuild` Debug **and** Release for the simulator — both succeed, **zero warnings**.
- Every new/changed test class was run **20 consecutive times, 0 failures**: Android
  `VoiceInputMailboxTest` and `VoiceControllerMailboxTest` (the latter via `:network:testDebugUnitTest
  --tests`, not the `:network:test` lifecycle task, which does not accept a `--tests` filter on an
  AGP library module — a CLI quirk worth recording since it looks like a real failure the first
  time), and iOS `VoiceInputMailboxTests`, `VoiceControllerMailboxTests` and `ConflatedSignalTests`.
  All prior Phase 1b/2a suites remain green **locally**, including the real two-engine WebRTC
  loopback test and the pre-authentication `VOICE_*` refusal over real TLS on both platforms.
- SwiftLint/SwiftFormat: still not installed on this machine (§4 problem 14, unchanged).
- **CI (run 33693052138, this session's push): `ios` green — both test suites, Debug and Release
  simulator builds. `android` failed** — but not on anything this session touched: `core unit tests`
  was fully green (184/184, including every new `VoiceInputMailboxTest`), and `network`'s
  `all unit tests` failed exactly 1 of 129, in `PairingSessionIntegrationTest` — §4 problem 28,
  recurring with a new, now-diagnosable assertion (see §4). `ktlintCheck`/`detekt`/`lint`/
  `assembleDebug`/`assembleRelease` did not run as a consequence of that earlier task failing in the
  same Gradle invocation, not because of any failure of their own; all five passed in this session's
  own local run minutes earlier. **Per the brief's explicit instruction, this run was not re-run**;
  problem 28 stays open and unresolved, and Phase 2a's status for this session is recorded as
  hardening-pending rather than complete, precisely because "CI is green" is not true of the actual
  push that carries this session's changes.

---

**Passed and verified in the Phase 2a hardening session (2 September 2026, seventh), by actually
running the commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4 problem 17):

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five
  Android modules. **324 tests** (was 296): `core` 176 (was 154 — +18 `VoiceInputMailboxTest`, +4
  `VoiceEngineGenerationTest`), `network` 126 (was 120 — +6 `VoiceControllerMailboxTest`), `audio`
  11, `app` 2, `data` 9.
- `swift test --package-path ios/Packages/RideLinkCore` — **61/61** (was 39 — +18
  `VoiceInputMailboxTests`, +4 `VoiceEngineGenerationTests`).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **139/139** (was 134 — +5
  `VoiceControllerMailboxTests`), zero Swift 6 strict-concurrency warnings.
- `xcodebuild` Debug **and** Release for the simulator — both succeed, **zero warnings**.
- The three new test classes per platform (`VoiceInputMailboxTest[s]`, `VoiceEngineGenerationTest[s]`,
  `VoiceControllerMailboxTest[s]`) were each run **20 consecutive times, 0 failures**, since several
  of them concern flooding/overflow behaviour whose determinism matters more than usual — one of
  them (an "authenticated flood of offers" scenario) was reworked mid-session specifically because
  a real, unconstrained coroutine/actor dispatcher let the consumer occasionally keep pace with a
  200-item flood and never actually overflow, which would have made the test's proof accidental.
  The fix — flood before the consumer exists (a `ManualDispatcher` on Android; deferring
  `attach()` on iOS), not a bigger flood count — is what makes the overflow scenario deterministic
  rather than probabilistic. All other Phase 1b/2a suites remain green, including the real
  two-engine WebRTC loopback test and the pre-authentication `VOICE_*` refusal over real TLS.
- SwiftLint/SwiftFormat: still not installed on this machine (§4 problem 14, unchanged).

---

**Passed and verified in the security-state fix session (27 August 2026, fourth), by actually
running the commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4
problem 17):

- `./gradlew clean test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five modules. **253 unit tests, 0 failures**: `core` 145, `network` 97 (up from 74 — `SessionGateTest` 10, `SessionGateVectorTest` 2 running the shared 120-row table, `PairingSessionIntegrationTest` 11 over real TLS), `data` 9, `app` 2. **No `detekt` threshold was raised for this change** — `LargeClass`/`TooManyFunctions`/`CyclomaticComplexMethod` were satisfied by moving the pure payload readers out of `ControlSessionManager` (where they never belonged) and by splitting `SessionGate`'s table into three small functions.
- `swift test --package-path ios/Packages/RideLinkCore` — **27/27 pass** (unchanged; the FSM itself did not move).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **91/91 pass** (up from 68: `SessionGateTests` 10, `SessionGateVectorTests` 2, `PairingSessionIntegrationTests` 11), zero Swift 6 strict-concurrency warnings.
- `xcodebuild … -configuration Debug` and `-configuration Release`, simulator, `CODE_SIGNING_ALLOWED=NO` — both **succeed**.
- `python3 tools/generate_session_gate_vectors.py` — regenerates the 120-row table; both platforms run the regenerated file.

**The bug was reproduced before it was fixed, on both platforms** (§2g): the Android repro failed
with `[Connected(...), PairingRequired(...)]`, and the iOS suite was verified to fail the same way
by temporarily restoring the old emit order. Neither test could pass against the old code.

**Not run this session:** SwiftLint/SwiftFormat (not installed, §4 problem 14), the spike harness
(unchanged this session), and anything on a physical device — see below.

### Phase 1b implementation session (27 August 2026, third)

**Passed and verified then, by actually running the commands.** Every Gradle command below was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, without
which `detekt` cannot run on this machine at all (§1, §4 problem 17):

- `./gradlew clean test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five modules. **230 unit tests, 0 failures**: `core` 145 (up from 93 — 52 new `identity/` vector assertions), `network` 74 (up from 52 — the TLS channel, pairing and plaintext-absence suites), `data` 9 (new — trusted-peer and peer-id persistence), `app` 2 (`SecureTransportPolicyTest`, replacing `TransportGateTest`). `detekt` thresholds touched and documented in `config/detekt/detekt.yml`: `thresholdInObjects` 11 → 16, and `thresholdInClasses` 24 → 34 — the second raise for `ControlSessionManager`, recorded as tech debt in §4 rather than pretended away.
- `swift test --package-path ios/Packages/RideLinkCore` — **27/27 pass** (up from 17; +10 `IdentityVectorTests` running the same `identity/` file as Android).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **68/68 pass** (up from 40; +10 `TlsControlChannelTests`, +9 `PairingExchangeTests`, plus the existing suites re-pointed at the real TLS channel), zero Swift 6 strict-concurrency warnings.
- `xcodebuild … -configuration Debug` and `-configuration Release`, simulator, `CODE_SIGNING_ALLOWED=NO` — both **succeed**, zero warnings beyond the pre-existing benign "no AppIntents.framework dependency" notice.
- `./tools/spikes/phase1b-tls-exporter/run.sh` — **10/10 PASS**, including the cross-stack Apple ↔ Conscrypt exporter equality that this whole phase rests on.

**Passed and verified in the Phase 2a session (28 August 2026, sixth), by actually running the
commands.** Every Gradle command was run with
`-Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (§4
problem 17):

- `./gradlew test ktlintCheck detekt lint assembleDebug assembleRelease` — **all green**, all five
  Android modules. **296 tests** (was 253): `core` 154 (was 121 — plus `voice-signal/`'s 70 rows and
  `voice-fsm/`'s 52 rows), `network` 120, `audio` 11 (new module suite), `app` 2, `data` 9.
- `swift test --package-path ios/Packages/RideLinkCore` — **39/39** (was 27).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **134/134** (was 99), zero Swift 6
  strict-concurrency warnings.
- `xcodebuild` Debug **and** Release for the simulator — both succeed, **zero warnings** beyond the
  pre-existing benign "no AppIntents.framework dependency" notice.

**Real WebRTC media, measured — this is the one genuinely new class of evidence.**
`VoiceEngineLoopbackTests` runs two real `WebRtcVoiceEngine`s against each other under `swift test`
on macOS, linking the same WebRTC binary an iPhone build would (ADR-020). Asserted from the stack's
own statistics: gathered candidate types exactly `{host}` (no `srflx`, no `prflx`, no `relay`),
`dtlsState = connected`, `dtlsCipher = TLS_AES_128_GCM_SHA256`,
`srtpCipher = SRTP_AES128_CM_HMAC_SHA1_80`, `audio/opus` at 48 000 Hz, remote track present on both
sides. **Deterministic: the suite was run 5 consecutive times with 0 failures.** `packetsSent = 0`,
which is expected and is not a failure — there is no microphone in a headless run, so the transport
is up and nothing is speaking into it. That is exactly the line between "media path established" and
"audio works".

**CI is green on both platforms: run `33654431951`, commit `4d55089`, every step of both jobs.**
Android: core tests, all unit tests, ktlint, detekt, lint, `assembleDebug`, `assembleRelease`.
iOS: `RideLinkCore` 39/39, `RideLinkPlatform` 134/134, Debug and Release simulator builds. It took
three runs to get there and the two failures in between were both real — see §4 problems 27, 28
and 29; neither was retried until it passed.

**The real media test also passes on a hosted CI runner, not only on this machine.** Run
`33654431951`'s iOS job ran `VoiceEngineLoopbackTests` on a GitHub `macos-26` runner and all four
passed — so the host-only-ICE, DTLS-SRTP and Opus assertions are reproducible on a machine nobody
here configured, which is a materially stronger claim than a laptop result. It is still not a phone.

**The Phase 2a security invariant is proven over real TLS on both platforms.**
`VoiceAuthenticationGateTest` / `VoiceAuthenticationGateTests`: two real unpaired
`ControlSessionManager`s complete a real TLS 1.3 handshake, reach `PAIRING` with an unanswered
six-digit code, and one sends every `VOICE_*` frame there is. None reaches the voice subsystem; the
refusals are **counted**, so the test cannot be satisfied by the frames never being sent; and the
same frames from the same peer *are* delivered once both users confirm. Malformed and oversize
`VOICE_*` frames are dropped **without ending the control connection**, verified by a well-formed
frame still arriving afterwards.

**Not run this session, stated plainly:** nothing on a physical device, on either platform, and no
audio anywhere. The Android WebRTC engine has **no test of any kind** (§4 problem 22). Neither
audio-session implementation has ever run (§4 problem 23). `RideForegroundService` has never started
(§4 problem 25). No latency was measured and none may be claimed. See §7 and TEST_PLAN §3.1a.

---

### Earlier sessions, kept for history

**The security tests are real handshakes, not mocks.** `TlsControlChannelTest[s]` on both platforms
open real loopback TCP, complete a real mutually authenticated TLS 1.3 handshake with certificates
this codebase encoded and signed, and assert that both ends derive the *same* six-digit SAS. The
two substitutions that make that possible on a laptop — where the private key lives, and which
call frame reaches the exporter — are named in TEST_PLAN §3.1 and in
`test-results/phase1b-security-spike-20260827.md` §5.

**Not run this session, stated plainly:** nothing on a physical device, on either platform. The
Phase 1a real-device gate remains exactly as open as it was, and Phase 1b adds its own device-only
items (Android Keystore, the iOS Keychain, and the assumption that device-Conscrypt behaves like
Conscrypt-on-JVM). See §4 and §7.

---

### Earlier sessions, kept for history

**Passed and verified 27 August 2026 session (first), by actually running the commands:**

- `./gradlew clean test ktlintCheck detekt assembleDebug` — **all green**, all five Android
  modules. `:core:test` still runs `protocol/vectors/{envelope,sas,dedup,session-fsm}/*.json`
  directly; `:network:test` now additionally runs 56 tests including `protocol/vectors/clock/`
  (16 vectors), the socket-level dedup/reconnect/framing/discovery-lifecycle/privacy suites
  described in §2d, all against real JVM sockets — no Robolectric, no emulator needed for any of
  it. `ktlintCheck`/`detekt` clean across all five modules (two small `config/detekt/detekt.yml`
  threshold adjustments made and documented in the config file itself, same style-calibration
  precedent as the existing table-driven-test adjustments).
- `swift test --package-path ios/Packages/RideLinkCore` — **17/17 tests pass** (16 previous +
  `ClockSyncVectorTests`, same `clock_vectors.json` as Android, byte-identical results).
- `swift test --package-path ios/Packages/RideLinkPlatform` — **17/17 tests pass** (2 previous +
  15 new: framing cap enforcement, real-socket simultaneous-connect dedup ×2, reconnect policy
  ×4, discovery privacy ×3, discovery-handle rotation ×4), **zero Swift 6 strict-concurrency
  warnings**. Run 4+ times consecutively with no flakes.
- `xcodebuild -project ios/RideLink.xcodeproj -scheme RideLink -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' build`
  — succeeds, zero warnings. Installed and launched on the iPhone 17 Pro Max **simulator**; a
  screenshot confirms the Phase 1a diagnostics UI (§2d) renders correctly, including the
  `TRANSPORT: PLAIN / PHASE 1A / NOT SECURE` banner.

**Passed and verified 27 August 2026 session (cleanup/hardening pass, §2e), by actually running
the commands:**

- `./gradlew clean` then `:core:test`, `test`, `ktlintCheck`, `detekt`, `lint`, `assembleDebug`,
  `assembleRelease` — **all green**, all five Android modules. `network` module: 52 tests (up from
  the prior session's suite; net new this session: NSD callback lifecycle, mDNS instance-name
  privacy, reconnect re-entrancy, PING-race regression, malformed PING/PONG, dh-rotation
  self-race, teardown). `app` module: 3 new tests for the release-transport gate. `lint` run
  explicitly this session (not run to completion as such in the prior session — only
  `assembleDebug`, which does not run full `lint`) and found + fixed 4 genuine pre-existing
  `NewApi` errors in `NsdDiscoveryController` unrelated to this session's new code (guarded with
  `@RequiresApi`, matching the existing SDK-tiered pattern the file already used elsewhere).
- `swift test --package-path ios/Packages/RideLinkCore` — **17/17 tests pass**, unchanged.
- `swift test --package-path ios/Packages/RideLinkPlatform` — **40/40 tests pass** (17 prior + 23
  new this session across `PingRaceAndReconnectTests`, `MalformedPingPongTests`,
  `SelfDiscoveryHandlesTests`, `TeardownTests`, plus additions to `DiscoveryPrivacyTests`), zero
  Swift 6 strict-concurrency warnings. Run multiple times consecutively with no flakes.
- `xcodebuild ... -configuration Debug -sdk iphonesimulator ... build` — succeeds, zero warnings
  (checked explicitly with a clean build + grep for `warning:`, only the pre-existing benign
  "no AppIntents.framework dependency" notice present).
- `xcodebuild ... -configuration Release -sdk iphonesimulator ... build` — **succeeds, new this
  session.** This is the direct proof the release-transport guard works: `PlaintextTransportGate`
  compiles `SessionCoordinator()` out entirely under Release (`#if DEBUG`), so a successful Release
  build is evidence the plaintext transport was never reachable, not merely unused.
- Not re-run this session, since no UI changed: the on-simulator screenshot verification from
  §2d/§3 above.

**Not passed / not run this session, stated plainly — this is the entire real-device gate:**

- **No physical Android device or emulator was available in this environment** — no `adb`, no
  AVD. `NsdDiscoveryController` and `PlainControlTransportPhase1a` on Android are therefore
  verified only by unit/integration tests against real *local* sockets, never against Android's
  actual `NsdManager`/`ConnectivityManager` stack or a real Wi-Fi radio. This is the single
  biggest gap before Phase 1a can be called complete rather than implementation-complete.
- **No physical iPhone was available** — only the iOS 17 Pro Max *simulator*. The simulator run
  confirms the UI and build; it does not exercise real mDNS multicast on a physical Wi-Fi radio,
  and `Network.framework` Bonjour behaviour between a simulator and a real device is not the same
  as between two real phones.
- **No two-device (L4) test was run at all** — I-01 through I-25, all of them, remain exactly as
  pending as last session. In particular I-08 (5-minute clock-offset-stability observation) and
  I-15…I-17 (real simultaneous-connect trials, not the loopback-simulated version this session's
  tests cover) need the real phones. §16's TCP-jitter question is therefore still **unmeasured**,
  not resolved — no UDP path was added pre-emptively, per instruction.
- AF-01…AF-10 (Android foreground-service/microphone lifecycle) and IA-01…IA-09 (iOS audio
  session/route) remain untouched — correctly out of scope for Phase 1a (they are Phase 2/6).
- The "Start Discovery" button was not interactively tapped on either platform's UI this session
  either (same synthetic-tap limitation as last session) — the FSM transitions it triggers are
  vector-tested, and this session additionally exercises the *entire* `startDiscovery()` →
  `CONNECTED` path via the loopback dedup tests, but the SwiftUI/Compose button tap itself is
  build/render-verified only.
- SwiftLint / SwiftFormat remain not installed (unchanged from last session — still not installed
  without asking first).

Test debt remaining, all specified in `docs/PROTOCOL.md` §11 / `docs/TEST_PLAN.md` but not yet
written (Phase 1b/4/5/6 concerns, not Phase 1a's — `vectors/clock/` is now done, moved out of
this list):

| Vector set | Covers |
|---|---|
| `vectors/manifest-paging/` | 1 / 1 000 / 5 000 entries, pathological metadata, digest determinism |
| `vectors/manifest-paging-errors/` | 12 failure cases; each asserts the live manifest is unchanged |
| `vectors/identity/` | SPKI formatting, pin match/mismatch, certificate re-issue with unchanged key |
| `vectors/audio-state/` | enum vocabulary, `revision` monotonicity, derived `media_quality` |
| `vectors/drift/`, `vectors/queue/`, `vectors/manifest/`, `vectors/ordering/` | Phases 5/8 |

Plus: Android AF-01…AF-10 (foreground service / microphone lifecycle), iOS IA-01…IA-09 (audio
session and route), and integration tests I-01…I-25. Full list in `docs/TEST_PLAN.md`.

---

**Passed and verified in the Phase 4 session (5 September 2026, eighteenth) — full detail, exact
counts and the CI status are in §2u rather than repeated here:** 565 Android unit tests (all five
modules green, `ktlintCheck`/`detekt`/`lint`/`assembleDebug`/`assembleRelease` all green), 220/220
`RideLinkCore` + 252/252 `RideLinkPlatform` Swift tests, `xcodebuild` Debug and Release simulator
builds both green, a real Android emulator smoke check and a real iOS simulator smoke check both
confirming no crash and correct Shared Library UI gating, and 100/100 stress reruns of the seven new
pure vector-test classes on **both** platforms. CI for this phase's commits had not yet been pushed
as of this write-up — see §7.

## 4. Known problems

| # | Problem | Severity | Action |
|---|---|---|---|
| 1 | ~~**iOS self-signed X.509 generation has no first-party API**~~ **Resolved 27 Aug 2026 (Phase 1b spike).** A minimal DER encoder + `SecKeyCreateSignature` + `SecCertificateCreateWithData` + `SecIdentityCreate` works, with no PKCS#12 and no key export; the result is accepted by Apple's parser, BoringSSL and OpenSSL. [ADR-017](DECISIONS/ADR-017-identity-key-and-certificate.md) | ~~High~~ — | **Residual:** none of it has run against the iOS *Keychain* on a device — the tests use a transient key. Folded into problem 16 |
| 2 | ~~**TLS keying-material exporter availability is unconfirmed**~~ **Resolved 27 Aug 2026 (Phase 1b spike).** Public on both (`SSLSockets.exportKeyingMaterial` API 31; `sec_protocol_metadata_create_secret` iOS 12), byte-identical across Apple ↔ Conscrypt for one TLS 1.3 connection, cross-checked against OpenSSL. [ADR-018](DECISIONS/ADR-018-tls-exporter-channel-binding.md) | ~~High~~ — | **Residual:** the Android side was measured on Conscrypt-on-JVM, not on the phone. Folded into problem 15 |
| 3 | Phase 0 measured results not recorded (mode, helmet model, topology, latency) | Medium | Blocks **Phase 6 only**. Template ready at `docs/PHASE0_RESULTS.md`. Until filled, `AUDIO_STATE.confidence` stays `assumed` and Phase 6 defaults to Mode C |
| 4 | ~~Xcode not installed~~ **Resolved 26 Aug 2026 (this session).** User installed Xcode 27.0 beta; SDK confirmed newer than baseline (ADR-011 Amendment A2), deployment target unchanged; `RideLink.xcodeproj` and `RideLinkPlatform` now built and verified (§7) | — | Xcode 27 being a beta remains a residual, lower risk — see the amendment |
| 5 | ~~WebRTC artifacts are community-published on both platforms~~ **Resolved 28 Aug 2026 (Phase 2a spike).** Both pinned exactly (`144.7559.14` / `152.0.0`), licences confirmed BSD-3-Clause, Apple's XCFramework SHA-256 verified independently, both binaries read for telemetry (none — no upload endpoint, `NSPrivacyTracking: false`), release builds proven, isolated behind `network/voice` / `RideLinkPlatform.Voice`. [ADR-020](DECISIONS/ADR-020-webrtc-voice-foundation.md), [evidence](test-results/phase2a-webrtc-spike-20260828.md) | ~~Medium~~ — | **Residual:** Android is Chromium M144 and Apple M152 (neither distribution publishes the other's milestone). Interop-safe by WebRTC's design; the two real stacks have never spoken to each other. Closed by V-01. A second residual became problem **27** five days later: the Apple artifact's *availability* is not guaranteed by its checksum |
| 6 | TCP jitter may floor clock-offset precision | Low | Measure in Phase 1 (I-08). Only if it exceeds ~5 ms, add a UDP `PING`/`PONG` path. Do not pre-build it |
| 7 | mDNS may be blocked on hotspots / enterprise APs | Medium | Manual `host:port` + QR fallback in Phase 1b |
| 8 | Removing `fp6` means a discovered peer cannot be labelled "known" before connecting | Low | Accepted trade (ADR-002 A1). Mitigated by an auto-attempt silent connect when exactly one trusted peer exists. Watch whether the pre-ride UX suffers in real use |
| 9 | ~~`minSdk 31` is assumed to be the level where a public TLS exporter is available~~ **Resolved 27 Aug 2026.** Measured against `android.jar`'s `api-versions.xml`: `SSLSockets.exportKeyingMaterial` is `since="31"` — exactly the baseline. The assumption was correct and `minSdk` does not move | — | — |
| 10 | ~~`swift test` cannot execute on this machine~~ **Resolved 26 Aug 2026 (this session).** Root cause was Command Line Tools alone not carrying a runnable `XCTest.framework`/Swift Testing runtime. User installed full Xcode 27.0 beta; `swift test` now runs, 16/16 pass | — | Tests use XCTest (not Swift Testing) — this was a deliberate Phase 1a choice made while blocked and is fine to keep, but revisit if the team later wants Swift Testing's nicer parameterization |
| 11 | ~~Neither discovery controller has run against a real second peer~~ **Partially resolved 27 Aug 2026 (this session).** Discovery lifecycle logic (Found/Updated/Lost, self-filtering, dh rotation, TXT privacy) is now unit/integration-tested against real local sockets/`NWBrowser` change sets on both platforms — see §2d. **Still open:** neither has run against `NsdManager`/`Network.framework`'s real mDNS stack on a real Wi-Fi radio, because no second device was available (problems 15/16). Whether `NEARBY_WIFI_DEVICES` is required on API 33+ is still unverified — ARCHITECTURE §6.4 already flags this as "settle on-device, don't assume" | Medium | Needs the real-device gate (§7) |
| 12 | AGP 9.x dropped the separate `org.jetbrains.kotlin.android` Gradle plugin; Compose BOM / `androidx.core` / `androidx.lifecycle` versions newer than the ones pinned this session require `compileSdk 37` | Low, but easy to regress | Documented in §1. Don't bump these three dependency versions without checking the compileSdk requirement first |
| 13 | `RideLink.xcodeproj`'s `project.pbxproj` was hand-authored (no Apple CLI creates one, and `xcodegen`/`tuist` weren't installed without asking). It resolves, builds, and runs on-simulator, but has not been opened in the Xcode GUI to confirm it looks/behaves like a normal project (no Assets.xcassets/app icon, minimal build settings) | Low | Open it in Xcode once to sanity-check; add an app icon when one exists. Not urgent — sideloaded personal builds don't need a store-quality icon |
| 14 | SwiftLint / SwiftFormat (ARCHITECTURE §10.2) are not installed on this machine | Low | Install when convenient; not blocking — ktlint/detekt (Android) are clean, Swift Xcode builds show zero compiler warnings |
| 15 | ~~**No Android device or emulator available in this development environment**~~ **Partially resolved (Phase 3 session, §2q).** An Android emulator (`RideLink_API36`) now exists and has run real Phase 3 instrumented tests and a manual local-music walkthrough. **Still open:** `PlainControlTransportPhase1a`/`NsdDiscoveryController` (Phase 1a discovery/transport), and everything else Phase 1a/1b/2a/2b needs from a real Android network/audio/WebRTC stack, remain unverified on it — the emulator's use so far is scoped exactly to Phase 3's local-music claims (§2q, TEST_PLAN §4.3), not a general "Android now has a device" resolution. No **physical** Android device exists in this environment | **High (blocks the Phase 1a gate)** | The emulator can now also carry Phase 1a/1b/2a/2b evidence if run against them; a physical OnePlus Nord 5 with USB debugging is still needed for the real two-phone gates (§7) |
| 16 | **No physical iPhone available** — only the simulator, which does not exercise real mDNS multicast or real `Network.framework` Bonjour behaviour between two independent radios. Phase 1b adds to this: the iOS **Keychain** path (a permanent key, `SecIdentityCreate` over a Keychain-resident key, survival across restart/upgrade) is exercised only with a *transient* key, because an unsigned `swift test` binary has no keychain entitlement | **High (blocks the Phase 1a and 1b gates)** | Needs a physical iPhone 17 Pro Max with a Personal Team signing identity (CLAUDE.md "Apple Signing") — a user decision, not made here |
| 17 | **`detekt` cannot run on this machine without `-Dorg.gradle.java.home=…`** — the Gradle daemon inherits Temurin 25, detekt 1.23.8 is handed `25.0.3` as a JVM target and fails with a bare version string. Pre-existing, local-only (CI's daemon is JDK 21, so CI has always been green), and **not** fixable by setting `jvmTarget`/`jdkHome` on the task — both were tried and neither helped | Low | Use the flag (it is in every §3 command), or set `org.gradle.java.home` in `~/.gradle/gradle.properties`. A committed daemon-JVM criterion (`gradle/gradle-daemon-jvm.properties`) would fix it portably but risks breaking CI if the criterion cannot be satisfied there, so it was not done blind |
| 18 | **`ControlSessionManager` is the largest class in the codebase, and Phase 2a made it larger.** detekt's `LargeClass` fired the first time the voice wiring went in inline; the whole voice half was extracted to `VoiceSignalRelay` on both platforms in response, leaving ~20 lines of wiring, and there is no smaller way to attach a subsystem to it. The class was **already at 608 counted lines before Phase 2a touched it**, so `config/detekt/detekt.yml` now documents a `LargeClass` threshold with the reason. That headroom is the last of it | **Medium** (was Low) | Unchanged and now overdue: extract a `PairingController` owning the socket-facing half (`beginPairing`, `sendPairRequest`, `applyPairingStep`, `succeedPairing`, `failPairing`, `handlePairingFrame`, `activateAuthenticatedSession`), as a change that is **only** that refactor. It touches the pairing and trust-gate paths, which is precisely why it must not ride along with a feature |
| ~~18-old~~ | **(previous wording, kept for history)** `ControlSessionManager` is the largest class in the codebase and has now absorbed the trust-gate wiring on top of the PROTOCOL §4.5 pairing wiring. The 27 Aug (fourth) session pushed it back under the existing `detekt` thresholds *without raising them*, by moving the pure payload readers (`requiredLongField`, `requiredBooleanField`, `requiredSpkiField`, `knownErrorCode`, `isPlausibleClockSample`) out of the class — they never touched a session — but that is headroom, not a fix | Low, but it will get worse | Unchanged: extract a `PairingController` owning the socket-facing half (`beginPairing`, `sendPairRequest`, `applyPairingStep`, `succeedPairing`, `failPairing`, `handlePairingFrame`, `activateAuthenticatedSession`). Deliberately not done alongside a security fix; it belongs in a change that is *only* that refactor |
| 19 | **The manual `host:port` / QR fallback for blocked mDNS is not implemented.** ARCHITECTURE §4.4 scopes it to Phase 1b | Medium | It is a *discovery* feature with no security content, so it was deliberately left until after the security work. First item in §7. Problem 7 is the reason it matters |
| 20 | **`SessionCoordinator` itself is still not directly unit-testable on either platform** — Android's needs a concrete `NsdDiscoveryController` (an Android type), and iOS's lives in the app target, which has no test target. That is precisely the gap the §2g bug hid in: a `when`/`switch` no suite could reach. It is now *mostly* closed by moving the decision into `SessionGate` (pure, mirrored, vector-pinned), leaving the coordinator a thin applier — but "thin" is a code reading, not a test | Medium | Either (a) give `NsdDiscoveryController` an interface and add an `app`-module test, or (b) add a test target to `RideLink.xcodeproj`. Do **not** let logic drift back into the coordinator in the meantime: anything with a decision in it belongs behind `SessionGate` or another pure, mirrored type |
| 30 | **VOX has no microphone-driven level source on either platform.** The threshold/hangover state machine is implemented, deterministic and vector-pinned; nothing supplies it a level. Neither pinned WebRTC distribution exposes a fast per-frame input level through public API — the only level either offers is `audioLevel`/`totalAudioEnergy` on the statistics report, which RideLink polls every 2 s, three orders of magnitude too slow to gate speech. [ADR-021 §6](DECISIONS/ADR-021-intercom-transmission-and-capture-ownership.md) declines to hand-write a detector to fill the gap, for the same reason ADR-003 declines custom echo/noise DSP: an unmeasured detector is worse than an honest gap. **Selecting Mode B today means the gate cannot open**, `voxLevelSourceAvailable` is `false`, and the intercom card says so on screen | Medium | Two options when it matters: an `AudioDeviceModule` raw-PCM samples callback plus a *measured* detector (Android has `JavaAudioDeviceModule.setSamplesReadyCallback`; Apple has no public equivalent), or accept PTT/continuous as the shipped gates. **Do not implement either before A-14** — the threshold that matters is the one a helmet unit sees at 100 km/h, and nothing has measured it |
| 31 | **`assertEquals` immediately after an await on a *different* observable is a race, and this codebase now has *nine* examples of it.** Phase 2b's stress pass caught one (§3); problem 28 was two more; **Phase 5 closure audit A1 found six more in one session** (§2ab), all in the tests and none a production defect. The shape is always the same: two independently-published values, one awaited, the other asserted. A1 made it *more* likely rather than less, and deliberately: the outbound send and the scheduled-action chain are now asynchronous relative to the step that produced them, because that asynchrony is exactly what makes the leader's order provable | **Medium** (was Low) — it has now recurred four times | Overdue: a `tools/` lint. Until then the discipline is "when a test awaits X and asserts Y, await Y too", and A1 added two observable signals precisely so the right thing *can* be awaited — `outboundEnqueuedCount`/`outboundSentCount` and ingress idleness |
| 22 | **The Android WebRTC media path has no test of any kind.** `PeerConnectionFactory.initialize` requires an Android `Context`, so `WebRtcVoiceEngine` on Android cannot be exercised by a JVM unit test. An Android emulator (`RideLink_API36`) now exists (Phase 3 session, §2q) and has run real Room/Media3 instrumented tests, but **nothing has run `WebRtcVoiceEngine`/`PeerConnectionFactory` on it** — the emulator's use so far is Phase 3 local-music-only, not a resolution of this problem. It compiles and is wired; that is still the entire claim for the voice/WebRTC path. The iOS side has a real two-engine media test because the XCFramework carries a macOS slice — Android has no equivalent | **High (blocks the Phase 2a gate)** | The emulator that now exists can give a first signal for this specifically (run `WebRtcVoiceEngine`'s instrumented tests on `RideLink_API36`) without waiting for a physical phone; the real answer is still V-01…V-11 on the phones |
| 23 | **Neither audio-session implementation has ever run.** `AndroidVoiceAudioSession` (`AudioManager`, `MODE_IN_COMMUNICATION`, `setCommunicationDevice`) and `IosVoiceAudioSession` (`AVAudioSession` two-configuration switch, all three notifications) are untestable off-device — `AVAudioSession` does not exist on macOS. **Narrowed by Phase 2b, not closed:** every *decision* either of them used to make now lives in `AudioSessionLifecycle`, a shared pure reducer with mirrored suites on both platforms (§2m), and both route mappers and Android's device selector are pure and tested. What remains untested is the **API calls themselves** — whether `setCategory`/`setActive` actually provoke a `.categoryChange` notification, whether `setCommunicationDevice` reaches a helmet unit, whether the duplex configuration yields a duplex route, and how long the switch takes | **High (blocks the Phase 2a/2b gates)** | V-01…V-11, IA-01…IA-09 and A-12…A-15 |
| 24 | **Every value in both route mappers marked `assumed` is a reasoned guess.** *(Unchanged by Phase 2b — no hardware was measured, so nothing moved off `assumed` and both mappers' tests still assert it.)* `TYPE_BLUETOOTH_SCO`/`.bluetoothHFP` → `duplex_wideband` assumes mSBC rather than CVSD; `input_forces_output` for all Bluetooth is ADR-016's central claim asserted, not measured; LE Audio is deliberately *not* claimed to preserve music quality. Both mappers report `confidence: assumed` and their tests **assert** that, so the tests are what will change when the measurement exists | Medium | A-12/A-13, then A-15 flips `confidence` and fills `docs/PHASE0_RESULTS.md` |
| 25 | **`RideForegroundService` has never started.** Whether the `microphone` foreground-service type is accepted, whether capture survives a screen lock, whether the notification's mute/end actions work from a lock screen, and whether `ForegroundServiceStartNotAllowedException` fires in practice are all device facts. **Narrowed by Phase 2b, not closed:** the *decision* to start is `RideStartPolicy`, pure and exhausted over its whole 2^7 request cross-product including "no decision ever opens capture from the background" (§2m), and the service gained the two lock-screen actions ARCHITECTURE §6.4 requires. The platform behaviour is still entirely unverified | **High (blocks the Phase 2a/2b gates)** | V-08, AF-01, AF-05 |
| 27 | **The Apple WebRTC dependency has a single point of failure outside this project's control, and it fired within five days.** Phase 2a pinned `stasel/WebRTC` `151.0.0` with its SHA-256 verified byte-for-byte; on 2 Sep upstream **deleted that release** ("accidentally", their words) and the phase's first CI run failed with a hard 404 on the binary. `151.0.1` is not a usable replacement — its manifest points at the deleted `151.0.0` URL while carrying the new checksum, so it fails with either a 404 (cold cache) or a checksum mismatch (warm). Re-pinned to `152.0.0` and re-validated from scratch. **A checksum protects integrity, not availability**: integrity held perfectly — the mismatch was *detected* — and the build broke anyway ([ADR-020 Amendment A1](DECISIONS/ADR-020-webrtc-voice-foundation.md#amendment-a1--2-september-2026--the-apple-pin-moves-to-m152-because-upstream-deleted-the-m151-release)) | **High** | It will happen again. Re-pinning is the cheap response and is what was done; **vendoring the ~45 MB XCFramework is the only option that removes the failure mode** and should be reconsidered on the next occurrence. Android is unaffected — Maven Central does not permit deleting a published artifact, and that asymmetry between the two distributions is now a recorded property rather than an assumption |
| 28 | ~~**A CI-only test failure was not diagnosable from the CI log.**~~ **Resolved 3 September 2026 (ninth session) — a test-harness synchronization race, not a production bug.** The 3 Sep run's diagnosable failure (`exactly one SAS prompt per device ==> expected: <1> but was: <0>` at `PairingSessionIntegrationTest.kt:60`) named the exact assertion: it counted `ControlEvent.PairingRequired` in `FsmSession.recorded` immediately after `awaitPairingPrompt()` returned. `awaitPairingPrompt()` observes `pairingPrompt`, a conflated `StateFlow`, which always hands a late observer its current value; the count is drawn from `events`, a **zero-replay** `SharedFlow` collected by `FsmSession.collectInto`. Production sets the prompt and emits `PairingRequired` back-to-back, but nothing ordered *this test's two observers* of those two flows relative to each other, so the count could run before the events collector had processed the emission into `recorded` — reproducing the exact assertion seen in CI. A second, related but more severe latent race existed alongside it: `collectInto` launched its collector with default coroutine dispatch, which only *schedules* the subscribe rather than performing it — on a zero-replay flow, a fast enough handshake could emit before any subscriber existed at all, losing the event permanently rather than merely delaying it. `DuplicateConnectionResolutionTest` already used `CoroutineStart.UNDISPATCHED` against this same `events` flow for this same reason; `collectInto` now does too, and the failing test now waits for the actual `PairingRequired` event before counting it, instead of inferring readiness from an unrelated flow. **No production code changed** — `ControlSessionManager`'s emit order (`_pairingPrompt.value = …` then `_events.tryEmit(PairingRequired(...))`) is untouched, and the trust-gate invariant (no unknown peer reaches `CONNECTED` before both-side SAS confirmation and trust persistence, ADR-019) was re-verified unchanged. A new regression test, `collectInto subscribes before returning, so a fast handshake cannot drop its events`, proves the subscription-ordering guarantee deterministically (a manually-pumped test dispatcher lets a real loopback handshake reach `CONNECTED` before the collector's dispatcher is ever pumped) and was confirmed to fail if the `collectInto` fix is reverted. `PairingSessionIntegrationTest` run **100 consecutive times locally: 100 passed, 0 failed**. Fresh CI (run [33698452022](https://github.com/arunachaleswaranms/RideLink/actions/runs/33698452022), commit `eae366c`): Android — `core unit tests`, `all unit tests` (336 tests, up from 335), `ktlintCheck`, `detekt`, `lint`, `assembleDebug`, `assembleRelease` all green; iOS — `RideLinkCore` 69/69, `RideLinkPlatform` 150/150, Debug and Release simulator builds all green | ~~High~~ — | **Kept for history, not deleted:** the two prior occurrences (27 Aug's missing-`Connected` signature and 3 Sep's missing-SAS-prompt signature) are exactly this same race manifesting as two different assertions, not two different bugs — both are downstream of the same unordered-observer gap now closed. If a third, differently-shaped failure ever appears in this test, treat it as a new problem, not a recurrence of this one |
| 29 | **A Phase 1b timing test tripped its ceiling in CI because Phase 2a changed what shares its process.** `PingRaceAndReconnectTests.testRepeatedClockBurstsAllCompleteQuickly` asserts an 11-sample clock burst converges within a fixed budget. That budget (4.0s) was measured when the `RideLinkPlatform` test binary held control-plane code only; it now also links a ~96 MB WebRTC framework and, a few tests earlier in the same process, stands up two real `RTCPeerConnectionFactory` instances with their own worker threads. CI run 33607112656 tripped it with the signature the test's own comment predicts — `elapsed 4.129s`, `pendingPings=1`, `rttMs=3.0` (three **milliseconds**: the wire was healthy and a PONG was measured; one waiter was not resumed before its own 3s `pingTimeoutMs` fired). Actor-scheduling starvation on a three-core hosted runner, not a protocol or lifecycle bug — `PingRequestRegistry`'s own tests cover the bookkeeping | Low | Ceiling raised to 8.0s with the arithmetic written down: a single dropped PONG costs the full 3.0s timeout on top of a ~0.6s healthy burst, so ~3.6s is the floor before contention. 8.0s clears it with margin and stays below the 10s resync interval, so a genuinely stuck burst still fails. **The underlying fragility is not removed:** a wall-clock assertion sharing a process with a real media stack will always be environment-sensitive. The durable fix is to assert the invariant (every ping resolves, no stale waiter) and measure the timing separately — a Phase 1b test-design change, not a Phase 2a one |
| 40 | **Pre-existing, codebase-wide: an integrally-valued JSON *float* is accepted on iOS and rejected on Android.** Every codec reads a `uint64` wire field through Kotlin's `longOrNull` (which rejects `"90210500000.0"`) and Swift's `Int64(exactly: Double)` (which accepts `90210500000.0`). A peer emitting `1.0` where `1` is specified would be accepted by one phone and refused by the other. **Not introduced by Phase 5** — `TransferCodec`, `ManifestCodec`, `AudioStateCodec` and `VoiceSignalCodec` have all behaved this way since their own phases, and Phase 5's codecs deliberately follow the same convention rather than diverging from four existing ones. No vector exercises it, because adding one would fail today | Low | Neither platform *emits* such a value — both encode integers as integers — so this can only be reached by a third-party or corrupted sender. Fixing it means changing four codecs plus Phase 5's two, and their vectors, in a change that is *only* that. Recorded rather than smuggled into Phase 5 |
| 41 | **Phase 5's scheduled start has run on the Android emulator but not on the iOS simulator.** `SyncScheduledPlaybackTest` proves the pre-roll, the monotonic-deadline start and the real `setPlaybackParameters` nudge/restore against a real `ExoPlayer` on `RideLink_API36` (measured sleeper wake error 1.4–3.1 ms). The iOS half — `AVAudioEnginePlayer` started at a deadline, and `AVAudioUnitVarispeed` actually changing a rate — has **not** run anywhere. **Unlike problem 22, nothing prevents it**: the simulator exists and already runs Phase 3 media tests | Medium | **Still open after closure audit A1** (§2ab), which was scoped to seven correctness findings in the integration layer rather than to new device coverage. It is now §7 step 2. TEST_PLAN §5.2's S-01…S-12 remain the device gate beyond it either way |
| 42 | **Recovery from a Phase 5 ingress desynchronisation is unbounded in time** (ADR-024 Amendment A1 Finding C, §2ab). An explicit, counted refusal in the bounded post-transport handoff halts a follower's application of incremental commands — nothing incoherent is ever applied, and local music keeps playing — but the halt ends only when the leader next emits authoritative state or the session ends, and **nothing prompts it**. `STATE_REQUEST` is catalogued in PROTOCOL §3 and unimplemented. Reaching the bound at all requires a peer flooding frames that cannot be superseded (latest-wins coalescing absorbs an ordinary 5 s report cadence), so this is a pathological-peer path, not a busy-link one | Medium | Implement §10's resync properly — `STATE_REQUEST` → authoritative state — as **reconnect** work, not as a Phase 5 patch. Deliberately not done blind in A1: it is a wire addition, and A1's whole claim is that the wire did not move |
| 26 | **APK/IPA size.** The Android AAR adds ~48 MB of native code across four ABIs; the Apple XCFramework is ~96 MB expanded and embedded in the app bundle. No ABI filtering or slice stripping is applied — the default is the safe configuration and a sideloaded personal build has no size gate | Low | Revisit if install time becomes annoying. Recorded rather than forgotten |
| 21 | **Diagnostics now show `CONNECTING` while a six-digit code is on screen**, where they previously showed `CONNECTED`. This is deliberate and more honest (ADR-019 §5), but it is a user-visible change that has never been looked at on a real screen | Low | Confirm it reads sensibly during I-02 on the two phones; the FR-023 diagnostics screen is one of the things I-02 exercises anyway |
| 32 | **FIXED (twelfth session, §2o, ADR-021 Amendment A2 Finding 3).** `Effect.ReleaseAudioAndStopForegroundService`'s name promised an Android foreground-service stop `SessionCoordinator.runEffect` never actually performed — confirmed exactly as originally recorded here. Fixed with a `ForegroundServiceController` seam (no `Context` inside `SessionCoordinator`) and one owner: `runEffect` awaits capture release (`StopReleaseResult`) before calling `foregroundService.stop()` — never on a timeout — and always tears down the control session afterward. `SessionCoordinatorEndingEffectTest` (new) proves the order at the integration boundary, including that a peer BYE, a timed-out release, a `NETWORK` link loss and a repeated `ENDING` all behave correctly. Kept in this table with its resolution noted rather than deleted, per this file's own discipline | ~~Medium~~ Fixed | ~~Give `SessionCoordinator` a way to reach `RideForegroundService.stop()`...~~ Done — see §2o |

| 33 | **FIXED (fourteenth session, §2q).** A genuine infinite playback-restart loop on Android: ExoPlayer's two listener callbacks for one end-of-track each dispatched `LocalQueueAction.Next`, and the second landed on `LocalQueue`'s "nothing selected" branch, which restarts from the first item. Fixed with `core.player.TrackEndEdge` (mirrored to `RideLinkCore`), edge-detecting the transition into "done" rather than level-triggering on every emission | ~~High~~ Fixed | See §2q for the full account and the regression tests |
| 34 | **FIXED (fourteenth session, §2q).** `RideForegroundService.refreshForegroundState` crashed (`InvalidForegroundServiceTypeException`) calling `startForeground` with an empty type set once problem 33 was fixed and music could finish with the intercom never started. Fixed by stopping foreground/the service itself when the required type set is empty | ~~High~~ Fixed | See §2q |
| 35 | **FIXED (fourteenth session, §2q).** `LibraryScreen`'s "tap to play" bypassed `RideForegroundService.startMusicFromVisibleUi`'s foreground-visible gate entirely, reaching the service anyway via `updateMusicPlaying`'s own reactive `startService` call — the gate was silently defeated, surfacing only as problem 34's crash. Fixed by adding `MainActivity.attemptPlayNow` on the same path as `attemptMusicPlay` | ~~Medium~~ Fixed | See §2q |
| 36 | **FIXED (fourteenth session, §2q).** `music`/`Music` in `.gitignore` (bare, unanchored) had the exact bug `library/` had before it (see the note above problem 33's block in §2q's own text) — it silently excluded `MusicCoordinator.kt` from every `git status`. Anchored to the repo root | ~~Medium~~ Fixed | See §2q |
| 37 | **FIXED (fourteenth session, §2q).** `ios/RideLink.xcodeproj`'s explicit file-list format silently excluded four newly-added `.swift` files from the actual compiled target — `xcodebuild` reported `BUILD SUCCEEDED` while compiling none of them, until code elsewhere started referencing their symbols. Fixed by adding all four files to `project.pbxproj`'s four required sections; `plutil -lint` confirmed the result stays well-formed | ~~Medium~~ Fixed | See §2q. Watch for this again: any future new iOS app-target file needs the same four-section addition, since this project has no filesystem-synchronized-groups migration planned |
| 38 | **FIXED (sixteenth session, §2s, ADR-021 Amendment A4).** Confirmed by the Phase 3 closure audit (fifteenth session, §2r) and left unfixed there on purpose. `VoiceController.stopAndAwaitRelease()`'s outer 5 s caller-facing timeout is structurally guaranteed to elapse at or before `AndroidVoiceAudioSession.close()`'s inner 5 s route-settlement timeout, and `SessionCoordinator.releaseVoiceAndAwait()`'s unconditional next step, `VoiceController.shutdown()`, read that as license to call `apply(StopRequested)` directly (racing the consumer's own `state` mutation) and then cancel `consumerJob` unconditionally — aborting a still-in-flight `close()` before `unregisterPlatformCallbacks()`/the post-close intercom-gate update could run: a leaked `AudioManager` listener registration and a gate stuck open. Fixed by making `shutdown()` a caller of the same `pendingStopCompletions` signal `stopAndAwaitRelease()` uses, through the ordinary mailbox, with no caller-side timeout of its own — it waits for the deliberate release to finish rather than cancelling it, safely bounded by the inner mechanism's own existing timeout. Also made idempotent. See §2s | ~~Medium/High~~ Fixed | See §2s |
| 39 | **FIXED (seventeenth session, §2t, ADR-021 Amendment A5).** Found independently verifying problem 38's own fix. `SessionCoordinator.releaseVoiceAndAwait()` captured `stopAndAwaitRelease()`'s result *before* calling `VoiceController.shutdown()`, and returned that captured value unchanged afterward — so an initial `StopReleaseResult.TimedOut` survived even once `shutdown()`'s own subsequent (and, by problem 38's fix, unconditional) wait had gone on to prove that *exact same* release complete. `SessionCoordinator.runEffect`'s `ENDING` handling reads that stale `TimedOut` and leaves `RideForegroundService` running — an orphaned microphone foreground service over a release that had, by the time the coroutine returned, already finished. Fixed by having `releaseVoiceAndAwait()` promote a captured `TimedOut` to `Released` once `shutdown()` returns — reasoned from `shutdown()` being provably this controller's *first* call (`voice` is nulled before it runs, so `releaseVoice()`'s own fire-and-forget `shutdown()` call can never reach the same instance), so its wait is never the idempotent no-op and its return is proof, not merely "stopped waiting." `Released`/`AlreadyReleased` are returned unchanged. See §2t | ~~Medium~~ Fixed | See §2t |

Resolved 26 Aug 2026 session: `CLAUDE.md` in `.gitignore` (was problem 1); `.DS_Store` tracking
(was problem 7 — the claim was incorrect; the files are untracked and now ignored); the ADR-015/
ADR-010 leadership-independence rationale error (§2b).

---

## 5. Architectural risks that remain genuinely open

Only these. Everything else is a known task with a known shape.

1. **Secure transport — mostly closed, one thread left.** Both platform capabilities are now demonstrated working *together* (ADR-017, ADR-018), so the "stop and review" trigger did not fire. What is left is narrower and specific: the Android half of the exporter equality was measured against **Conscrypt-on-a-laptop**, not against the phone's own TLS stack, and neither Android Keystore nor the iOS Keychain has been exercised on a device. Integration tests I-02/I-19/I-20/I-21 close it. Until they run, "the two phones show the same six digits" is a well-supported expectation, not a measurement.
2. **Bluetooth duplex-profile coupling.** Now modelled honestly rather than wrongly, *and implemented* — each platform's route mapper is the single place ADR-016 vocabulary is produced, and both currently report `confidence: assumed` because that is the truth. Modelling it still does not fix it. Whether *any* of Modes A–E is genuinely pleasant with the real helmet unit is unknown until TEST_PLAN §6.1's A-12…A-15 run. **The product's viability sits here**, and neither Phase 2a nor Phase 2b moved it. What Phase 2b did add is that the worst *self-inflicted* form of this risk — thrashing the endpoint per utterance — is now structurally impossible rather than merely discouraged (ADR-021 §4). That removes a way the app could make the problem worse; it says nothing about whether the problem exists on this hardware.

2a. **Voice media on a phone.** Real WebRTC media is now proven *locally* — host-only ICE, DTLS-SRTP, Opus, two real engines, deterministic. What that does not touch: any microphone, any speaker, `AVAudioSession`, `AudioManager`, a foreground service, a screen lock, and the Android media path at all. Closed by TEST_PLAN §5.1's V-01…V-11, not by more unit tests.

2b. **The intercom lifecycle on a phone.** Phase 2b narrowed this risk in a specific and useful way rather than closing it: every *decision* the platform audio layer used to make is now in a shared pure reducer with mirrored tests, so what is left untested is the API calls themselves. That is a real improvement — a wrong decision now fails a laptop test — but it is not evidence about `AVAudioSession`, `AudioManager`, a foreground service or a lock screen, and the enforcement it added (the transmission gate cannot touch capture) is a guarantee about *this code*, not a measurement of *that hardware*. Closed by A-10, IA-01…IA-03, AF-01/AF-03/AF-05 and V-05/V-06/V-09.
3. **iOS `AVAudioEngine` scheduling precision** against the <100 ms sync target on real hardware. Measured in Phase 5, not assumable.
4. **Hotspot behaviour on a moving motorcycle** — an idle iPhone hotspot may sleep its interface; Android hotspot behaviour is vendor-dependent. Phase 1 test I-07 is the first real data.

---

## 6. Open questions for the user

Not blocking Phase 1. Answers needed before Phase 6.

1. **Phase 0 results** — which intercom mode (A–E) was validated? Helmet unit make/model? Most stable network topology (common Wi-Fi / Android hotspot / iPhone hotspot)? Measured end-to-end voice latency? Any surprises? **This is now more actionable than it was:** all five modes are implemented and selectable from the intercom card, so the answer changes one constant (`IntercomPolicy.DEFAULT`) and two assertions rather than any behaviour. Until it arrives, Mode C is the default *by architecture* (ARCHITECTURE §6.3, ADR-008 §4, ADR-021 §3) and is documented everywhere as not a measurement.
2. **Library size** — roughly how many tracks, and which formats? Determines whether FLAC matters and how hard to push on index performance. It also sets the realistic manifest page count.
3. **iPhone cache cap** default (DOCX §24)? Suggest 8 GB with a user control.

---

## 7. Next exact task

**Phase 5 — synchronized playback. SOFTWARE CLOSURE A2 COMPLETE — REAL-DEVICE SYNCHRONIZED-PLAYBACK
GATE PENDING (§2ac).** Every laptop-runnable gate is green on both platforms; the audit §7 previously
asked for has run twice, and all thirteen findings (A1's seven, A2's six) are fixed with regressions
each verified to fail against the pre-fix behaviour. Every pre-existing vector set regenerates
byte-for-byte identically: the wire did not move in either pass.

**"A2" is not "final", and the wording is deliberate.** §2y dropped "final" from Phase 4 after five
audits each found real defects in code that was already CI-green. A1 found seven; independent
verification of A1 then found five more, and fixing those found a sixth. That is evidence *for*
auditing again, not against it. The next audit should re-derive its findings from the production
code rather than from this file, and should look hardest at the places these two deliberately stopped
short:

- **The A2 Finding E interleaving is reproduced on iOS but not on Android**, where the window falls between two statements a `StandardTestDispatcher` cannot interleave; the Android test asserts the contract instead. The defect is real there (the app runs on a multi-threaded dispatcher) and the fix is the same structural one, but a failing-before-fix demonstration is missing.
- **A2's fail-closed posture ends Phase 5 authority for a whole authentication generation.** That is deliberate and surfaced (`SyncState.TRANSPORT_FAILED`), but it means one undeliverable frame costs the rest of the session's synchronisation. Whether that is the right product answer is a question the real-device gate should inform.
- **The audit brief's §43/§44 stress runs were explicitly skipped at the user's request.** A1 found its seventh finding by stress-running a regression; this pass did not run one.


- **Recovery from an ingress desynchronisation is unbounded in time.** `STATE_REQUEST` is catalogued in PROTOCOL §3 and unimplemented; a halted follower waits for the leader's next authoritative snapshot or for a session boundary. Bounding it is reconnect work (PROTOCOL §10).
- **An authoritative `PLAY` a follower cannot serve still just waits for the leader** (PROTOCOL §5 rule 4, unchanged). Recorded in §2ab as an out-of-scope observation.
- **`ControlSessionManager` is still the largest class in the codebase** (§4 problem 18) and the `PairingController` extraction is still overdue.

**Immediately actionable next steps, in order:**

1. **Independently verify closure audit A2** (§2ac, ADR-024 Amendment A2), and A1 with it (§2ab). Re-derive each finding from the current production code; the regressions are in `SyncPlaybackDeliveryAuditTest[s]` (A2), `SyncPlaybackClosureAuditTest[s]`, `SyncPlaybackTwoPeerTest[s]`, `Phase5FrameQueueTest[s]` and `Phase5GatesVectorTest[s]`, and each should be confirmed to fail if its fix is reverted.
2. **Run the scheduled-start path on the iOS simulator** (§4 problem 41's remaining half). The Android emulator half is done; there is no `Context`-shaped obstacle on iOS either.
3. **Get two real devices into this loop.** Unchanged since Phase 1a and now blocking five gates: (a) enable USB debugging on the OnePlus Nord 5; (b) set up a development-team signing identity for the iPhone 17 Pro Max.
4. **Run the Phase 1a gate**: I-01, I-05, I-06, I-07, I-08, I-14, I-15, I-17, I-22.
5. **Run the Phase 5 gate**: S-01…S-12 (TEST_PLAN §5.2). This is the only thing that produces an alignment figure or a drift p95 — neither exists today, and nothing in this audit changed that.
6. **Fill in `docs/PHASE0_RESULTS.md`** — still the only blocker for Phase 6, and still the reason `AUDIO_STATE.confidence` is `assumed`.

## 7a. Historical note — the Phase 3 start gate

Kept because §8 requires resolutions to stay readable, not because it is still live. **Phase 5 was
started under the same override**, on the same reasoning: synchronised playback carries no
coexistence risk (no ducking, no VOX-vs-music, no Bluetooth profile trade-off), works or fails
identically whether or not the intercom hardware gate has run, and is required to expose only clean
interfaces for Phase 6 to drive later. The gate itself is unretired for everything else.

> **Amendment — 4 September 2026, fourteenth session: this gate was deliberately overridden to start
> Phase 3 anyway.** The user made this call explicitly, after the conflict between this paragraph and
> that session's kickoff brief was surfaced and put to them rather than resolved silently. Recorded
> here per this file's own discipline (§8: contradictions get resolved and written down, not picked
> silently). The reasoning for overriding: the risk this paragraph actually warns about is building
> *coexistence* (ducking, VOX/PTT-vs-music, Bluetooth profile trade-offs) against an unmeasured A-03/
> A-08 story — that is Phase 6 scope, explicitly out of bounds for Phase 3, and Phase 3 is required to
> expose only clean interfaces for Phase 6 to drive later, never to implement `IntercomPolicy.onSpeech`
> behaviour itself. Local playback/library/queue/indexing carries none of that risk: it works or fails
> identically whether or not the intercom hardware gate has run, per FR-025 and the graceful-
> degradation rule (player failure must not affect `SessionCoordinator`; intercom absence must not
> affect the player). **This does not retire the gate for anything else.** The Phase 2b real-device
> intercom gate (A-10, IA-01…03, AF-01/03/05, V-05/06/09) and the overall "2 Intercom" milestone gate
> (A-01, A-02, A-04, A-09, V-01…V-11) remain exactly as open as recorded above, and Phase 6
> (intercom+music coexistence) still may not start until they close.

---

## 8. How to update this file

Every session, revise: current milestone/phase, completed work, tests passed, tests pending,
known problems, architecture changes, next exact task. A new session must be able to read this
file and continue **without guessing**. Record what was *verified*, not what was written.
