# ADR-020 — Phase 2a voice foundation: WebRTC distributions, the offerer rule, host-only ICE, and the audio-session split

**Status:** Accepted · 28 Aug 2026

Supersedes nothing. Builds on [ADR-003](ADR-003-webrtc-voice-transport.md) (WebRTC for the voice
plane only), [ADR-010](ADR-010-internal-leader-election.md) (leadership), and
[ADR-019](ADR-019-connected-means-authenticated.md) (`Connected` means authenticated).

## Context

ADR-003 chose WebRTC for voice in June and left four things open, each of which Phase 2a had to
answer with a decision rather than a preference:

1. **Which distribution.** ADR-003 named `io.github.webrtc-sdk:android` and `stasel/WebRTC` as
   candidates and recorded "community-published artifacts" as a Medium risk
   (ARCHITECTURE §12, STATUS §4 problem 5). Neither was pinned, verified or built against.
2. **Who offers.** PROTOCOL §7's Phase 1 sketch showed `leader ── VOICE_OFFER ──► follower` but
   said nothing about what happens when both users press Start Voice at the same moment.
3. **How ICE stays local.** "Empty server list" was stated; nothing enforced or checked it.
4. **What owns the microphone.** ADR-003 noted that WebRTC's `AudioDeviceModule` "wants to own
   microphone and speaker" and called the interaction with our own session management a
   "known Phase 2/6 risk to measure, not assume."

Phase 2a is also the first phase to add a *subsystem* to the authenticated control session, which
makes the ADR-019 gate a question with a concrete answer for the first time: what exactly stops an
unauthenticated peer starting voice?

## Decision

### 1. Dependencies, pinned exactly, verified, and reviewed

| | Android | Apple |
|---|---|---|
| Coordinate | `io.github.webrtc-sdk:android` | `https://github.com/stasel/WebRTC.git` |
| Version | **`144.7559.14`** (exact, in `gradle/libs.versions.toml`) | **`152.0.0`** (`exact:`, in `RideLinkPlatform/Package.swift`) — was `151.0.0`; see **Amendment A1** |
| Upstream | Chromium **M144** | Chromium **M152** (Amendment A1) |
| License | BSD-3-Clause (artifact POM); packaging repo MIT | BSD-3-Clause (`LICENSE.md`) |
| Distribution | Maven Central AAR, 48.7 MB | GitHub release XCFramework, 44.6 MB zipped / 96 MB expanded |
| Integrity | Maven Central checksums (and Maven Central does not permit deleting a published artifact) | **SHA-256 in the dependency's own manifest**, verified byte-for-byte against the published release: `115cb9944248a3302c0c8af17462e2576a28ccc7adef9f6a1fe66ee75d9e1cc8`. **A checksum protects integrity, not availability** — see Amendment A1 |

**No floating versions and no ranges.** Android uses a single pinned string in the version
catalogue; Apple uses `.package(url:exact:)`, deliberately not `upToNextMajor` — a WebRTC minor bump
changes a media stack, and it should be a commit, not a resolution.

Supply-chain review, performed rather than assumed
([evidence](../test-results/phase2a-webrtc-spike-20260828.md)):

- **Native contents.** Android: four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), and the `classes.jar` contains only `org/webrtc` and `org/jni_zero`. Apple: four slices — `ios-arm64`, `ios-x86_64_arm64-simulator`, **`macos-x86_64_arm64`**, `ios-x86_64_arm64-maccatalyst` (re-verified on M152, Amendment A1).
- **No telemetry.** Neither artifact declares a permission, service, receiver or analytics class. Apple's bundled `PrivacyInfo.xcprivacy` states `NSPrivacyTracking: false`, no collected data types and no tracking domains. Every URL in both binaries is an RTP header-extension URI, a CRL string inside the bundled root store, or a source-tree reference — **no upload endpoint of any kind**. WebRTC's own `Metrics` histograms are local-only, are not enabled, and have no network path in the library.
- **API floors.** Android AAR declares `minSdkVersion 21`, below the ADR-011 `minSdk 31`. Apple slices: iOS `minos 12.0`, macOS `minos 13.0` — both below the ADR-011 iOS 26.0 target and `RideLinkPlatform`'s `.macOS(.v14)`.
- **Release builds work.** Android `assembleRelease` and Apple `xcodebuild -configuration Release` both succeed with the dependency in place.

**The milestone skew is accepted knowingly.** Android is on M144 and Apple on M152 (M151 at the time
of writing; see Amendment A1), because neither distribution publishes the other's milestone. WebRTC is designed for cross-version interoperability
— browsers several milestones apart interoperate continuously — and both ends negotiate the same
Opus and the same DTLS-SRTP profiles. It is recorded here so that a future interop problem is
investigated against a known difference rather than discovered as a surprise.

### 2. The macOS slice is load-bearing, not incidental

`stasel/WebRTC`'s XCFramework carries a **macOS** slice. Because `RideLinkPlatform` already builds
and tests for macOS (ADR-014's mechanical boundary enforcement), that means `swift test` links the
same WebRTC binary an iPhone build would — same commit, same BoringSSL, same Opus.

This is checked on every version bump rather than assumed: Amendment A1's re-pin re-verified the
macOS slice's presence explicitly, because losing it would silently take the real media test with it.

So Phase 2a has **real media evidence on a laptop**: `VoiceEngineLoopbackTests` stands up two real
`WebRtcVoiceEngine`s, negotiates them against each other, and asserts host-only candidates, DTLS
`connected`, an `SRTP_*` cipher, and `audio/opus` at 48 kHz. Deterministic over 5 consecutive runs.

This is the strongest local evidence available and it is still **not a phone**. `RTCAudioSession`,
a Bluetooth route, a helmet unit and a screen lock are all absent. Android has no equivalent at
all: `PeerConnectionFactory.initialize` requires an Android `Context`, so its media path is
untested outside a device. Both are recorded as
**REAL-DEVICE AUDIO GATE PENDING** in `docs/STATUS.md` §7.

### 3. The offerer is the internal leader, and glare is resolved by there being no collision

> **The peer with the lexicographically smaller `peer_id` (ADR-010's leader) is always the WebRTC
> offerer.**

Both sides compute it from `HELLO_ACK.leader_peer_id`, which they already agree on. It is
deliberately **not** derived from which side dialled the TCP connection: `conn_tiebreak` and
`peer_id` are uncorrelated by construction (ADR-015 Amendment A2), so an implementation that
inferred the offerer from the initiator would work by coincidence in a lab and fail on a ride.

**Glare needs no tie-break, because only one side may ever offer.** A follower's Start Voice sends
`VOICE_STATE { state: "negotiating" }` — an *intent*, exactly as ADR-010 has a follower send intent
to the leader — and waits. The offerer receiving that intent while its own status is `idle` begins
the negotiation; receiving it while already negotiating is **idempotent**. Two simultaneous presses
therefore produce one `voice_session_id`, one offer and one answer, in every arrival order (asserted
as a property, not one case).

An offerer that receives `VOICE_OFFER`, or an answerer that receives `VOICE_ANSWER`, has met a peer
that disagrees about leadership. That is the condition PROTOCOL §4.1 already calls
`leader_mismatch`, and the frame is dropped and counted.

**Consent is symmetric, and that is a platform consequence rather than a preference.** Neither side
opens its microphone because the *peer* asked: ARCHITECTURE §6.4 makes that illegal on Android from
the background, and it would be wrong on iOS too. An offer arriving before this user has consented is
**held** (one nullable field) and surfaced as "your peer wants to talk"; the user's own Start Voice
then answers the offer already in hand rather than asking for it again.

### 4. `voice_session_id` — a generation guard, not a peer identity

Every `VOICE_*` frame carries a 32-hex `voice_session_id`, generated by the offerer per negotiation.
A receiver drops any frame whose id is not the one it holds.

That one rule makes a class of race impossible rather than unlikely: a late `VOICE_ICE` from a
torn-down negotiation, a duplicate `VOICE_OFFER` starting a second parallel one, an answer for the
previous generation applying to the current offer. It is applied to the **media stack's own
callbacks** as well as to the wire, which is what stops a delegate call from a closed peer
connection touching the next session.

It is ephemeral, never persisted, and not derived from `peer_id`, `session_id` or the identity key —
a distinct type from `ConnTiebreak` for the reason ADR-015 gives about reusing one random value for
two jobs.

### 5. Host candidates only, and the check is not just configuration

ICE is configured with an empty server list on both platforms, and `VoiceEngineConfig` has **no
field** that could carry a STUN or TURN server — so one cannot be added by accident in a later
phase; it would take a protocol and ADR change, which is the point.

Enforcement is additionally *observational*: every candidate gathered and every candidate received
is reduced to its `typ` and anything reflexive or relayed is counted and surfaced in the diagnostics
as unexpected. Reported rather than fatal — a false alarm on a ride is worse than a red row — and
proven by the loopback test asserting the gathered set is exactly `{host}`.

Only the candidate **type** is ever extracted. Addresses and ports are not parsed out at all, so
PROTOCOL §7.7's "no log path" is achieved by the value never existing rather than by remembering not
to print it.

### 6. `stop()` and `release()` are two calls, because the audio device is the expensive thing

This is the decision that ADR-003's "known Phase 2/6 risk" turned into:

- **`VoiceEngine.stop()`** closes the peer connection, the remote track and the ICE state, and **keeps** the media factory, the audio device module and the local track.
- **`VoiceEngine.release()`** disposes those and releases the capture device.

A control-plane link loss calls only `stop()`. Two independent reasons agree:

1. **Audio quality.** WebRTC's audio device module owns the capture path, and closing it makes a Bluetooth endpoint renegotiate between its media and duplex profiles — a 0.5–2 s audible route change (ARCHITECTURE §6.2), on every link blip. That is the single worst thing this product can do to music (§6.3).
2. **Platform legality.** ARCHITECTURE §6.4: on Android the capture device is opened once while the app is foreground-visible, and there is no second legal opportunity once the screen is locked.

So `localAudioOpen` — this user's consent for the ride segment — survives a link loss, and
`ControlLinkLost` provably never emits `ReleaseLocalAudio` (asserted over the whole role × status
cross-product on both platforms). Only a deliberate End Voice, or `ENDING`, releases capture.

### 7. Every WebRTC value crosses the boundary as a primitive

`RTCSessionDescription`, `RTCIceCandidate` and `RTCStatisticsReport` are **not** `Sendable`. Under
Swift 6 strict concurrency the compiler therefore refuses to let them leave a WebRTC callback, and
the reduction to `String`/`Int`/enum has to happen *inside* the callback.

That constraint turned out to coincide exactly with the boundary the protocol already has: PROTOCOL
§7.4 puts SDP on the wire as a string and a candidate as a string plus two scalars. So the
`VoiceEngine` seam is defined entirely in primitives on both platforms — which additionally means
`RideLinkCore`/`core` stay free of platform types (CLAUDE.md rule 9) and `VoiceController` is
testable with no WebRTC at all.

The same rule flattens statistics: both platforms reduce their report type to
`[statsId: [member: String]]` at the callback boundary and share one pure mapping
(`VoiceStatsMapping`). A side effect worth stating: the fields §7.7 forbids are never carried
around, so they cannot be logged later by accident.

### 8. Where the decisions live, and why not in the controller

The negotiation table is `VoiceNegotiation` — pure, mirrored line for line, and pinned by
`protocol/vectors/voice-fsm/` (52 rows, both platforms). `VoiceController` is a driver: it applies
the actions the table returns and performs the effects.

This is a direct response to STATUS §4 problem 20 and to the shape of the Phase 1b bug: a decision
that lived in a `when`/`switch` inside a class no test suite could construct. Everything decidable
about voice — who offers, glare, the generation guard, what a link loss does to capture — is in a
table a laptop can exhaust.

## Consequences

- Three shipped third-party dependencies remain three (ARCHITECTURE §10.3): WebRTC ×2 platforms, and GRDB later. WebRTC is the only large one and it is the one the brief mandates.
- **APK and IPA size.** The Android AAR adds ~48 MB of native code across four ABIs; the Apple XCFramework is ~96 MB expanded and is embedded in the app bundle. No ABI filtering or slice stripping is applied in Phase 2a — the default is the safe configuration, and a sideloaded personal build has no size gate. Worth revisiting if install time becomes annoying; it is recorded, not forgotten.
- `PROTOCOL.md` §7 grows from a 28-line sketch to a full specification: schemas, bounds, the authentication gate, the generation guard, the offerer rule, logging rules and lifecycle. Two new vector sets and two new generators come with it.
- **One field is removed from the protocol sketch:** `VOICE_OFFER.ice_ufrag_hint`. The ICE ufrag is already inside the `sdp` the same frame carries, so the field was a second copy of a value that could disagree with the first. It was never implemented and no vector referenced it. Recorded in PROTOCOL §12.
- **`ControlSessionManager` grew again and the extraction it needed happened.** detekt's `LargeClass` fired the first time the voice wiring went in inline, so the whole voice half became `VoiceSignalRelay` on both platforms. The residual overflow is pre-existing; STATUS §4 problem 18 is escalated and `detekt.yml` documents the threshold rather than hiding it.
- **A contradiction inside ADR-016 was found and corrected** while implementing the shared audio vocabulary: its prose rule made `builtin` `reduced` while its own table made it `full`. See [ADR-016 Amendment A1](ADR-016-effective-audio-capability-model.md#amendment-a1--28-august-2026--correction-media_quality-is-about-narrowed-duplex-not-duplex).
- **The Phase 1a `AudioRoute` entity shell is gone**, replaced by the implemented `AudioRouteSnapshot` in `audiopolicy`. Two types for one concept, differing only in which one a call site reached for, is exactly the drift the shared vectors exist to prevent — in a place no vector could see.
- Voice is a subsystem behind the ADR-019 gate, so "may this peer start voice?" is answered structurally: the controller does not exist before the trust gate passes, and `VOICE_*` is absent from the pre-authentication frame allowlist. Both halves are asserted over real TLS on both platforms.
- Phase 2a's UI is a diagnostics card, not a Ride Mode screen. Nothing about a real riding interface is decided before anyone has ridden with it.

## Alternatives considered

| Option | Rejected because |
|---|---|
| `dev.flutter`/LiveKit/Jitsi WebRTC forks, or building WebRTC from source | The two chosen distributions are the maintained, current, checksum-pinned builds of *unmodified* upstream WebRTC, published by the projects the ecosystem actually uses. Building from source is a multi-hour toolchain per platform for bytes that would be the same |
| A version *range* (`upToNextMajor`) so security fixes arrive automatically | A WebRTC minor bump changes a media stack. Arriving automatically is the problem, not the feature: it should be a commit that a build proves, which is what an exact pin forces |
| Match milestones by pinning Android to M137 (the newest both distributions share) | Trades a documented, interop-safe skew for a materially older stack on the phone that has the harder Bluetooth problem. WebRTC interoperates across milestones by design |
| Offerer = whoever pressed Start Voice first | Not deterministic — both may press within one RTT — and it makes the role depend on timing, which is the class of bug ADR-015 Amendment A2 exists to prevent |
| Offerer = the TCP initiator | Available and tempting, and wrong: `conn_tiebreak` and `peer_id` are uncorrelated on purpose, so this holds by coincidence in some lab runs and fails when the coincidence breaks |
| Full WebRTC "perfect negotiation" with rollback | Solves a problem RideLink does not have. Perfect negotiation exists for peers that may both offer; making only the leader offer removes the collision instead of resolving it, and V1 does not renegotiate in place at all |
| The leader auto-starts voice when the follower asks | Illegal on Android from the background (ARCHITECTURE §6.4) and wrong anywhere: it opens a microphone because a remote peer asked. Holding the request and surfacing it costs one field |
| A separate `VOICE_END` message | A second way to say what `VOICE_STATE { state: "closed" }` already says, and two ways to say one thing can disagree |
| One `VoiceEngine.stop()` that also releases the audio device | Simpler, and it renegotiates the Bluetooth profile on every control-plane blip — the exact failure ADR-016 and §6.3 are about |
| `iceTransportPolicy = .noHost`-style restriction instead of an empty server list | Restricts the wrong thing. The empty server list is what makes a non-local candidate impossible to gather; a transport policy is a filter over candidates a server was still contacted to obtain |
| Keep SDP as `RTCSessionDescription` across the seam with `@unchecked Sendable` | Defeats Swift 6's checking for no benefit, and the primitive form is what the wire needs anyway. `@unchecked Sendable` is confined to two immutable-value observers where the compiler genuinely cannot see the confinement |
| Put the negotiation logic in `VoiceController` | Exactly the mistake ADR-019 was written about: a decision in a class no test can construct. STATUS §4 problem 20 says not to, and this is the first chance to obey it |


---

## Amendment A1 — 2 September 2026 — the Apple pin moves to M152 because upstream deleted the M151 release

**Status of the ADR: still Accepted.** Every decision above stands. What changes is one version
number, and one risk assessment that was too optimistic.

### What happened

Phase 2a pinned `stasel/WebRTC` at `exact: "151.0.0"` on 28 August, having verified the XCFramework's
SHA-256 byte-for-byte against the published release. On 2 September the first CI run of the phase
failed on both platforms, and the iOS half failed like this:

```
error: failed downloading
  'https://github.com/stasel/WebRTC/releases/download/151.0.0/WebRTC-M151.xcframework.zip'
  which is required by binary target 'WebRTC': badResponseStatusCode(404)
```

Not a transient network error — the asset was gone. Upstream's own replacement release says so:

> ⚠️ Note: The original 151.0.0 release got accidentally deleted. This is a re-release of M151 with
> the same parameters but **the checksum is different from the original**.

The git **tag** `151.0.0` still exists; the GitHub **release** and therefore its asset do not.

### Why `151.0.1` is not the fix

The obvious move — bump to the re-release — does not work, and this is worth recording because it
looks like it should. `151.0.1`'s own `Package.swift` still points its `url` at the deleted
**`151.0.0`** path while carrying the **new** checksum, so resolving it fails one of two ways:

```
# cold cache (CI):
error: failed downloading '.../releases/download/151.0.0/...': badResponseStatusCode(404)

# warm cache holding the original bytes (this machine):
error: checksum of downloaded artifact of binary target 'WebRTC'
  (64a218fa…) does not match checksum specified by the manifest (6f3f5693…)
```

Verified empirically both ways before choosing.

### The decision

**Pin `exact: "152.0.0"`** — Chromium **M152**, published 31 August 2026, checksum
`115cb9944248a3302c0c8af17462e2576a28ccc7adef9f6a1fe66ee75d9e1cc8`, and a manifest whose `url`
points at its own tag. Re-validated from scratch rather than assumed, because it is a different
milestone from the one the spike measured:

| Check | Result |
|---|---|
| SHA-256 of the downloaded asset vs the manifest | ✅ matches |
| **macOS slice present** — the thing that makes real media testable on a laptop | ✅ `macos-x86_64_arm64` |
| iOS device + simulator + maccatalyst slices | ✅ all four as before |
| `PrivacyInfo.xcprivacy` | ✅ `NSPrivacyTracking: false`, no collected data types, no tracking domains |
| Telemetry endpoints in the macOS binary | ✅ none beyond the same RTP-URI / CRL / source-reference set |
| `RideLinkPlatform` tests, including the real two-engine DTLS-SRTP/Opus loopback | ✅ 134/134 |
| `xcodebuild` Debug **and** Release, clean | ✅ both, zero warnings |

The milestone skew against Android widens from M144↔M151 to **M144↔M152**, which changes nothing
about the reasoning: WebRTC interoperates across milestones by design, both ends negotiate the same
Opus and DTLS-SRTP profiles, and the two real stacks still have never spoken to each other. Recorded,
not hidden.

### The risk assessment this corrects

The original §1 said the Apple dependency's integrity was verified "byte-for-byte" and treated that
as the end of the supply-chain question. It was not. **A checksum protects integrity; it does not
protect availability**, and an SPM `binaryTarget` resolves a URL that a third party can delete.
Integrity held perfectly here — the mismatch was *detected*, exactly as designed — and the build
still broke.

So the honest statement is: the Apple WebRTC dependency has a **single point of failure outside this
project's control**, it fired within five days of being introduced, and it will fire again. Recorded
as a High-severity open problem in `docs/STATUS.md` §4 rather than left as a footnote.

Mitigations considered:

| Option | Assessment |
|---|---|
| **Re-pin when it breaks** (chosen) | Zero cost, and the checksum still guarantees that whatever *is* fetched is what the manifest describes. Costs a broken build and a session's attention each time it happens |
| Vendor the XCFramework into the repository | Immune to upstream deletion, and the only option that actually removes the failure mode. Costs ~45 MB of binary in git history for a two-device personal project, and `.gitignore` exists partly to keep large binaries out. **Reconsider if this recurs** |
| Mirror the asset somewhere we control | Same effect as vendoring without the git-history cost, but it needs a hosting location this project deliberately does not have (no cloud, no backend) |
| Move to CocoaPods (`WebRTC-lib`) | Trades a GitHub release for a CDN'd pod, which is more durable — but adds a second package manager to a project that has exactly one, and the pod is published by the same maintainer from the same binaries |
| Track `branch: "latest"` as the upstream README suggests | Directly contrary to §1's pinning decision. An unpinned media stack is worse than an occasionally-unavailable pinned one |

The Android side is unaffected: Maven Central does not permit deleting a published artifact, so
`io.github.webrtc-sdk:android:144.7559.14` cannot vanish the same way. That asymmetry is now a
recorded property of the two distributions rather than an assumption.

---

## Amendment A2 — 2 September 2026 — a bounded input mailbox, and the generation guard made strict

**Status of the ADR: still Accepted.** Both decisions this amendment records are corrections to how
§4 (the generation guard) and the effects around it were *implemented*, not changes to what §4
decided. Found and fixed in the same hardening pass, on both platforms.

### Finding 1: the generation guard's `nil` case was backwards

Both engines' `emit`/callback-forwarding function was, in effect:

```
if generation != null && generation != expected: drop
```

Read quickly this looks like "reject a mismatch." It does not: the moment `generation` is `null` —
which is exactly the state right after `stop()` — the left-hand side of the `&&` is `false`, so the
whole condition is `false`, and the callback is **delivered**. A stale callback from an
already-torn-down peer connection could reach `VoiceController` after all, precisely in the window
§4 exists to close.

The fix is the strict form the prose always implied — `generation != expected` (equivalently,
`generation == expected` to accept) — extracted as a pure, independently unit-tested rule rather
than re-inlined on both platforms a second time: `com.ridelink.core.voice.VoiceEngineGeneration` /
`RideLinkCore.VoiceEngineGeneration`. Neither `WebRtcVoiceEngine` can be constructed in a host unit
test (one needs an Android `Context`, the other the Apple audio stack — see §2 above), so the rule
being pure and separate is what makes it testable anywhere at all; before this amendment it was
inline logic no test suite could reach on either platform.

Extracting the rule surfaced a second question the inline version had blurred: a media engine
reporting that `start()` itself **failed** is not a peer-connection callback — no peer connection
exists yet for it to name — so gating it behind the same strict check would silently swallow every
start failure (`generation` is never installed on a failing `start()`). Both engines now report a
start failure directly, unconditionally, through the same event sink but bypassing the generation
check entirely; PROTOCOL §7.8 records the distinction.

### Finding 2: the per-negotiation input queue was unbounded

`VoiceController.submit` (and `start`/`stop`/`setMicrophoneMuted`/`onControlLinkLost`, and the
engine's own event sink) fed an unbounded `Channel`/`AsyncStream` ahead of the pure
`VoiceNegotiation` reducer. PROTOCOL §7.5's bounds — SDP size, candidate size,
`MAX_QUEUED_VOICE_CANDIDATES` — all apply *after* a frame is already sitting in that queue. An
authenticated peer past the ADR-019 trust gate could still grow this controller's memory without
limit simply by sending `VOICE_*` frames faster than the single consumer drained them; nothing
downstream would ever see the backlog to bound it.

The fix is `VoiceInputMailbox` (`com.ridelink.core.voice.VoiceInputMailbox` /
`RideLinkCore.VoiceInputMailbox`): pure, mirrored, vector-independent (it has no wire shape of its
own, so it needs no protocol vectors) but exhaustively unit-tested on both platforms, sitting
between the wire/engine-callback boundary and the reducer. PROTOCOL §7.5 now documents its four
lanes and their bounds. The design choices worth recording:

- **Classification by kind, not one bound for everything.** An offer or answer cannot be dropped
  without wedging a negotiation with no error anywhere — the exact failure `VoiceSignalSink`'s own
  doc comment already warned about — so those, plus local start/engine-callback inputs, get a
  bounded FIFO lane that is refused-not-evicted at capacity. ICE candidates get the existing
  `MAX_QUEUED_VOICE_CANDIDATES` bound applied one layer earlier, so the two bounds describe one
  policy instead of two that could quietly disagree. Repeated `VOICE_STATE`/mute/remote-track
  updates coalesce to their latest value, since only the newest is ever meaningful.
- **A stop or link loss cannot be starved.** They share a dedicated single-slot lane that is always
  accepted and always drained first, regardless of how full everything else is.
- **A critical-lane refusal is not swallowed.** It forces `ControlLinkLost` through the
  always-accepting teardown lane — reusing that input's already-correct, already-tested effect
  (media transport stops; local capture and the TLS control session both survive) rather than
  inventing a new failure path. `VoiceNegotiation` itself needed no change for this: the existing
  table already had the right answer for "something about voice signalling failed, degrade safely."
- **The bound is enforced synchronously, at the producer.** `offer()` is called from the control
  read loop, a WebRTC callback, or the UI, and never suspends and never blocks its caller — the
  property `submit` always had. Consuming the mailbox happens on a single drain loop woken by a
  conflated signal (`Channel<Unit>` on Android; a second `OrderedEventChannel<Void>` on iOS,
  alongside a small lock-guarded box around the otherwise non-thread-safe `VoiceInputMailbox` value
  type), so ordering *within* a lane is preserved exactly as it was before this amendment.

### What did not change

`VoiceNegotiation`'s table, `protocol/vectors/voice-fsm/`, the offerer rule, glare handling,
`voice_session_id` generation, host-only ICE, and the ADR-019 pre-authentication gate are all
untouched. `INPUT_MAILBOX_OVERFLOW` is a new `VoiceSignalDropReason` value, but it is never produced
by the reducer — `VoiceController` counts it directly, one layer earlier than every reason the table
itself can produce — so no existing vector needed to change to add it.

---

## Amendment A3 — 3 September 2026 — the doorbell is conflated, and a peer's terminal state gets its
own lane

**Status of the ADR: still Accepted.** Both fixes are corrections to Amendment A2's mailbox, found in
a follow-up hardening pass explicitly scoped to the mailbox and nothing else. Neither touches
`VoiceNegotiation`'s table, the offerer rule, glare handling, `voice_session_id` generation,
host-only ICE, or the ADR-019 pre-authentication gate.

### Finding 1: the iOS doorbell was still unbounded

Amendment A2 bounded every lane of `VoiceInputMailbox`, but the wake-up signal `VoiceController`
rings on every `offer` — separate from the mailbox itself — was, on iOS, an
`OrderedEventChannel<Void>`: an `AsyncStream` with the default **unbounded** buffering policy.
Android's equivalent doorbell was already correct (`kotlinx.coroutines.channels.Channel<Unit>(Channel.CONFLATED)`),
so this was a single-platform gap, not a design gap in the mailbox itself. Every lane's `offer`
unconditionally rang that unbounded doorbell regardless of which lane accepted the input, so a flood
of authenticated `VOICE_*` traffic could still grow an arbitrarily large backlog of pending `Void`
wake-ups sitting *behind* the now-bounded mailbox — no single wake-up carried a payload, but nothing
stopped an unconsumed pile of them from accumulating.

`OrderedEventChannel` was not the fix, and is not made conflated globally: it exists specifically
because `ControlSessionManager` emits `.pairingSucceeded`/`.peerTrusted` immediately followed by
`.connected` as **ordered pairs**, and `SessionGate` (ADR-019) depends on that order surviving
delivery. A doorbell has no such requirement — it means only "there is work available," never "this
is a distinct occurrence" — so collapsing a flood of rings into one pending wake-up loses nothing
`VoiceController`'s own drain-to-empty consumer loop needs.

The fix is a new, dedicated primitive: `RideLinkPlatform.ConflatedSignal`, an `AsyncStream<Void>`
built with `.bufferingNewest(1)`, exposing the same `signal()`/`stream`/`finish()` contract
`OrderedEventChannel` already gives (safe to call from any isolation context including
concurrently, no `Task` per call, harmless after `finish()`). At most one pending wake-up survives
between drains regardless of how many times `signal()` is called — proven directly with 100,000
calls before one consume — matching Android's `Channel.CONFLATED` doorbell exactly. Each
`VoiceController` owns exactly one, created fresh in its initializer, so a new voice session never
inherits an already-finished signal from a previous one.

### Finding 2: a peer's terminal `VOICE_STATE` could be coalesced away by an ordinary one

`VoiceInputMailbox`'s classification put every `VoiceSignal.State` — `negotiating`, `connecting`,
`active`, `idle`, `closed`, `failed`, `unknown` — into the same one-slot-per-kind coalesced lane,
latest-value-wins. That is correct for the five ordinary, informational values, but `closed` and
`failed` are not ordinary: `VoiceNegotiation`'s reducer gives them **teardown** semantics
(`teardownFromPeer`, tearing down to `idle` or `failed` respectively), distinct from every other
value in the enum. Coalescing put them in the same slot as everything else, so a peer's `closed`
queued ahead of a later, otherwise-unremarkable `active` update could be silently replaced before
the mailbox's single consumer ever drained it — the remote teardown signal would simply vanish,
and this side would never learn the peer had ended its side of the call.

The fix adds a fifth lane, `terminal_peer_state`, holding only `SignalReceived` inputs whose
`VoiceSignal.State.state` is `closed` or `failed`; every other wire value keeps coalescing exactly
as before. It is a bounded FIFO — capacity **8** on both platforms
(`VoiceInputMailbox.TERMINAL_PEER_STATE_CAPACITY` / `VoiceInputMailbox.terminalPeerStateCapacity`),
sized from the same reasoning as the critical lane: a single negotiation produces at most one
terminal peer state naturally (`closed` xor `failed`, once), so 8 absorbs several rapid
teardown/rebuild cycles within one control session while staying far below anything a real ride
would approach. It sits directly below `teardown` and above `critical` in draining priority — a
peer's own teardown must never queue behind a flood of offers/answers or trickle ICE — and strictly
above `coalesced`, which is what makes it impossible to classify a terminal signal alongside, and
therefore be overwritten by, an ordinary one. An overflow at this lane is handled exactly like a
critical-lane overflow: the new input is refused outright (not evicting an *earlier* terminal event
to make room, which would risk discarding the one signal the lane exists to protect) and forces
`ControlLinkLost` through the always-accepting teardown lane — the same already-proven safe degrade,
applied one layer earlier.

`VoiceNegotiation` itself needed no change: the reducer already treated `closed`/`failed` correctly
whenever it actually saw them. The bug was entirely in the mailbox deciding, before the reducer ever
ran, that a terminal signal and an ordinary one were interchangeable.

### What did not change (this amendment)

`VoiceNegotiation`'s table and `protocol/vectors/voice-fsm/` are untouched — every terminal-state
vector already existed and continues to pass unmodified, because the reducer's own handling of
`closed`/`failed` was already correct; only the mailbox's classification in front of it was wrong.
The critical and ICE lanes, their capacities, and their overflow behaviour are unchanged. The
generation guard (Amendment A2, finding 1) is unchanged and its tests remain green.


## Amendment A4 — 12 September 2026 — `VOICE_*` carries its control-session provenance

*(Renumbered from "A3" on 13 September 2026: two amendments were both written as A3. The 3 September
entry above is the one `docs/STATUS.md` links to by anchor, so this later one takes A4. No content
changed.)*

[ADR-025 §2](ADR-025-inbound-control-frame-provenance.md) applies ADR-024 Amendment A7's rule to this
ADR's message family: `VoiceSignalRelay.deliver` now takes the frame's authorising generation and
refuses — counting `droppedRetiredGeneration` — a frame whose control session has been replaced.

**This ADR's decisions are unchanged.** The leader is still always the offerer, ICE is still an empty
server list, `stop()` and `release()` are still two calls, and `VOICE_*` is still absent from the
pre-authentication allowlist. `VoiceController` is still deliberately **retained across a control
reconnect** so the capture device stays open for the ride segment — that is the behaviour this
amendment exists to keep safe, not to change.

**Why the existing generation guards were not enough.** They answer a different question.
`VoiceNegotiation`'s `voice_session_id` checks prove *voice-session* ownership; ADR-025's generation
proves *control-session* ownership, and conflating the two would be wrong in both directions. Two of
the reducer's correct behaviours are exactly what made a stale frame harmful:

- `VOICE_STATE { state: "closed" }` may legally omit `voice_session_id`, and `peerStateReceived`
  treats an absent id as carrying no generation claim — not a mismatch. It is `teardownFromPeer`. A
  Session A frame therefore stopped **Session B's** live media.
- after `ControlLinkLost` the reducer resets to `IDLE` with `voiceSessionId == null`, which is exactly
  the state in which `offerReceived` **accepts** an offer naming any generation. A Session A offer
  would start a negotiation whose answer went out on Session B's connection.

Both are pinned by `RetiredSessionProvenanceTest[s]`, which verify-fail against unmodified `326a145`.
No wire change; `protocol/vectors/voice-signal/` and `voice-fsm/` regenerate byte-identically.


## Amendment A5 — 13 September 2026 — a retired control lifetime's queued work may not negotiate, and an offer that was never sent may not look sent

*Thirty-fifth session, the final Phase 5 software-closure audit. Two confirmed defects, both
reproduced deterministically on both platforms before anything was changed, and both **outside**
ADR-025's scope — which is the point of recording them here.*

### The distinction that matters

Amendment A4 (ADR-025) closed the question "was this frame's control session still live **when the
frame arrived**?" Both defects below answer a different question, and neither is a provenance defect:

> A frame that was admitted **entirely legitimately** — read while its control generation was live,
> correctly passed by `VoiceSignalRelay.deliver` — can still be sitting in `VoiceInputMailbox` when
> that lifetime ends.

`VoiceInputMailbox` drains `TEARDOWN` before `CRITICAL` **on purpose**, so a stop or link loss is
never delayed behind a flood. The consequence nobody had traced is that `ControlLinkLost` is applied
*first*, resetting `VoiceNegotiation` to `IDLE`/`voiceSessionId = null` — and the queued frame is
then reduced against that reset state.

### Finding 1 (STATUS problem 50) — CONFIRMED, and reachable

The mailbox's own doc claimed anything queued below a teardown "becomes inert on its own (the
existing `VoiceEngineGeneration` / `voice_session_id` guard)". **That claim was false**, and
precisely for the two branches that *begin* a negotiation rather than advance one:

| branch | guard | after `controlLinkLost` |
|---|---|---|
| `answerReceived` | `state.voiceSessionId != answer.voiceSessionId` → drop | `null != id` → **dropped** ✅ |
| `candidateReceived` | same shape | **dropped** ✅ |
| `offerReceived` | `if (voiceSessionId != null && … && isNegotiationLive)` | `voiceSessionId` **is** null → guard **skipped** ❌ |
| `peerWantsVoice` | `if (status.isNegotiationLive)` … `if (!localAudioOpen)` | `IDLE` + capture still open → **starts a negotiation** ❌ |

Both vulnerable branches are guarded only *when there is a generation to compare*, and a teardown
removes exactly that. `localAudioOpen` is deliberately preserved across a link loss (ARCHITECTURE
§6.3/§6.4 — the capture device stays open for the ride segment), which is what puts the state into
the one shape `offerReceived` accepts any generation in.

Observed, on both platforms, after `StopMediaTransport` had already run: `engine.start(…)` rebuilt
the peer connection, `applyRemote(OFFER)` applied the retired peer's SDP, `createAnswer` answered it,
and the controller reported `negotiating` for a peer it had no link to.

**Fix — the teardown that jumps the queue owns the remote work it jumped.** `VoiceInputMailbox.offer`
discards every queued `SignalReceived` when a `ControlLinkLost` is offered, counting them as
`VoiceSignalDropReason.RETIRED_CONTROL_LIFETIME`.

Why **offer** time and not apply time, which is what makes this exact rather than a race:
`ControlSessionManager.endConnection` clears `authenticatedConnection` **before** it emits
`LinkLost`, and `VoiceSignalRelay.deliver` refuses any frame whose generation is not the live one.
So nothing remote can enter the mailbox between the lifetime ending and this call, and a *later*
lifetime's frames are offered strictly after it and are untouched. This is therefore not a blanket
flush: it cannot discard a valid fresh generation's work even if the consumer is starved for an
entire reconnect. A queue-contents check at *apply* time would have rested on exactly that timing
assumption — the kind ADR-024 Amendment A6 explicitly rejected.

> **Correction, 13 September 2026 — see Amendment A6 below.** The paragraph above is left verbatim as
> the record of what was decided and why, but its central claim is **wrong**: offer time is the
> least-wrong instant, not a race-free one. `endConnection` clears `authenticatedConnection` before it
> emits `LinkLost`, but nothing spans `VoiceSignalRelay.deliver`'s liveness read and its `sink.submit`,
> and `LinkLost` reaches `onControlLinkLost` through an event consumer rather than synchronously —
> while an *inbound* promotion can authenticate a successor without passing through that consumer at
> all. The discard is therefore scoped by **arrival order**, not by lifetime identity. Recorded as
> `docs/STATUS.md` §4 problem 60; the fix itself stands unchanged.

Local inputs are deliberately kept. This user's consent, the engine's own callbacks and the intercom
gate's state are not the retired peer's to withdraw, and the engine callbacks carry their own
`voice_session_id` guard already. `StopRequested` shares the teardown lane and discards **nothing**:
a user pressing End Voice is not a control-lifetime boundary.

The overflow-induced synthetic `ControlLinkLost` discards too, and consistently so: in both cases the
reducer is about to be reset to `IDLE` and must not then be handed a queued offer.

### Finding 2 (STATUS problem 56) — CONFIRMED, and it needs no race at all

Found while tracing Finding 1. `VoiceController.perform` **discarded the `Boolean`** that
`VoiceSignalTransport.send` returns, for every action.

`VoiceSignalRelay.send` returns false whenever there is no authenticated writer — which is the whole
window between a link loss and PROTOCOL §10's ladder reconnecting. So:

1. the user presses Start Voice while the ladder is reconnecting;
2. an offer is created and "sent" into a `null` writer; the send fails silently;
3. the table still advances to `NEGOTIATING`;
4. `SessionCoordinator.attachVoice` rebuilds voice on the next `Connected` — and
   `VoiceNegotiation.start` is **idempotent against a live negotiation**, deliberately, so that two
   Start presses make one offer. The rebuild is a **no-op**;
5. the peer's own `negotiating` intent hits the same idempotence coming back.

**Voice is wedged for the rest of the ride segment, with no error anywhere.** STATUS's own note on
problem 50 had reasoned that the resulting negotiation "has no writer and its `SendAnswer` fails
closed" — this is that assumption tested, and it does not hold: failing closed on the wire left the
*local* state advanced, which is the more damaging half.

**Fix.** `SendOffer` and `SendAnswer` now force the degrade `offer` already uses for a critical-lane
overflow: a `ControlLinkLost`, which resets the table to `IDLE` and drops the media transport while
**keeping this user's capture device open**, which is exactly the state a reconnect rebuild needs to
find. Deliberately **not** applied to `SendVoiceState` or `SendCandidate` — a lost state update is
carried by the next one and trickle ICE is designed to lose candidates; neither strands a
negotiation, and tearing media down for one would turn a recoverable blip into a rebuild.

### What did not change

No wire change, no new message type, no new `VoiceInput`, no change to `VoiceNegotiation`'s table and
therefore **no vector change** — `protocol/vectors/voice-fsm/` passes unmodified on both platforms.
The leader is still always the offerer, ICE is still an empty server list, `stop()` and `release()`
are still two calls, `VOICE_*` is still absent from the pre-authentication allowlist, and
`VoiceController` is still retained across a control reconnect with capture open.

One new `VoiceSignalDropReason` (`RETIRED_CONTROL_LIFETIME`) is produced by the mailbox rather than by
the table, exactly as `INPUT_MAILBOX_OVERFLOW` already is.

### A note on the harness that hid Finding 2

The iOS `VoiceController` test harness minted **one** `voice_session_id` for every call. Under that
generator Finding 2 is invisible: the stranded `NEGOTIATING` state accepts the *next* negotiation's
engine callback as its own, because the ids happen to be equal, and the wedge looks like health. The
harness now mints a fresh id per negotiation the way `VoiceSessionIdGenerator` does. **A test double
that is more deterministic than production can be deterministic about the wrong thing.**

## Amendment A6 — 13 September 2026 — a failed send is not a lifetime boundary, and the purge that is one is scoped by arrival order rather than by identity

**Status:** Accepted. Extends Amendment A5, and corrects one of its claims.

### Context

A5 fixed a real defect — an offer that could not be sent left the table in `NEGOTIATING`, so
`attachVoice`'s reconnect rebuild was a no-op and voice wedged for the ride segment — and it fixed it
by turning `transport.send(...) == false` into `VoiceInput.ControlLinkLost`. Its reasoning was that
the table's *reaction* to the two is identical, so reusing the input was the smaller change and
mirrored "a real control-link blip rather than inventing a new failure path."

The reaction is identical. The **event** is not, and `ControlLinkLost` had by then acquired two
powers that belong only to a control lifetime ending:

1. **A5's own sibling, Amendment A5's problem-50 fix, gave it ownership of queued remote work.**
   Offering a `ControlLinkLost` discards every queued `VoiceInput.SignalReceived`, on the reasoning
   that the lifetime which admitted them has ended.
2. **It occupies the single `VoiceMailboxLane.TEARDOWN` slot**, latest wins — so offering one
   replaces whatever teardown was already pending there.

A send failure is entitled to neither, because `VoiceSignalTransport.send` **suspends**. On Android
`VoiceSignalRelay.send` goes through `withContext(ioDispatcher)`, then `ControlSocket.writeFrame`'s
write lock, then a socket `flush()`, and reports `false` for a write that threw. On iOS it is three
`await`s deep before a byte moves — `authenticatedWriter()` and `activeSessionId()` each hop to the
`ControlSessionManager` actor, then the writer itself — and every one of those releases the
`VoiceController` actor. So the `Boolean` a `SendOffer`/`SendAnswer` finally produces can arrive long
after PROTOCOL §10's ladder has authenticated a **successor** generation.

### Finding 1 — a retired send discarded a successor lifetime's freshly admitted offer (problem 57)

Reachable with **no race**, because the controller's single consumer is the same thread that parks
inside the send and runs the degrade on resume:

1. lifetime A's consumer parks inside `transport.send(A's answer)`;
2. lifetime A ends; `onControlLinkLost` queues a `ControlLinkLost`, which discards nothing because
   nothing is queued yet;
3. the ladder reconnects, lifetime B authenticates, and B's peer sends a fresh `VOICE_OFFER`.
   `VoiceSignalRelay.deliver` admits it against a live generation — ADR-025 is satisfied, it is
   genuinely the successor's work — and `submit` (non-blocking on Android, `nonisolated` on iOS)
   puts it in the mailbox;
4. A's write reports `false`. The degrade offers a second `ControlLinkLost`, whose discard now
   **eats B's offer**;
5. the table returns to `IDLE`, B's leader never gets an answer, and `VoiceNegotiation.start` is
   idempotent against its own live negotiation on the way back. **Voice is wedged for the ride
   segment** — A5's exact failure mode, resurrected by A5's fix.

### Finding 2 — a retired send erased a pending `StopRequested` (problem 57)

The same input, the same lane, a worse consequence. `TEARDOWN` is one slot, latest wins, so a degrade
offered from the consumer's resume replaces a `StopRequested` that `shutdown()` is waiting on.
Nothing then applies that stop: capture is never released, `pendingStopCompletions` is never
resolved, and `SessionCoordinator.retireSession` — which awaits `shutdown()` with **no timeout of its
own**, by design (ADR-021 Amendment A4) — can never emit `TeardownComplete`. The session can never
reach `IDLE`, which is precisely what ADR-026 / rule 21 exists to prevent. Reachable whenever a send
is still in flight when the ride ends: `stopAndAwaitRelease()`'s 5 s bound elapses, `shutdown()`
registers its own waiter, and the parked write then comes back false.

### Finding 3 — A5's exemption was right about every `VOICE_STATE` but one (problem 59)

A5 exempted `SendVoiceState` because "a lost state update is carried by the next one." True of a
mute, a mode, a connectivity transition and a `closed` — and **false of an answerer's
intent-to-talk**. An answerer never offers (§7.3); its `start()` produces exactly one wire effect, a
`VOICE_STATE { negotiating }` with no `voice_session_id`, and the table advances to `NEGOTIATING`
whether or not that frame reached anything. There is no next one. `start` is then idempotent, so
`attachVoice`'s rebuild does nothing; and if the leader has not itself consented, `attachVoice` does
not call `start()` there either, so **neither side ever asks again**. A5's fix therefore closed the
offerer's half of problem 56 and left the answerer's half open.

### Decision

**`VoiceInput.NegotiationSendFailed(voiceSessionId)` is a distinct input, in a lane of its own.**

- **Reducer.** Identical outcome to `controlLinkLost` — drop the media transport, keep
  `localAudioOpen`, `micMuted` and `mode`, send nothing — but **only** when the table is holding a
  live negotiation whose `voiceSessionId` is the one the failed frame named. Anything else is
  `GENERATION_MISMATCH` / `UNEXPECTED_FOR_STATUS` and changes nothing. That guard is what makes a
  late `Boolean` unable to retire whatever came next; it is the same guard every engine callback in
  this table already carries. `null` is a legitimate name, not "unknown": an answerer's intent has no
  generation because the offerer has not created one.
- **Lane.** A new `SEND_FAILURE` lane, one slot, ranked **below `TEARDOWN` and above
  `TERMINAL_PEER_STATE`**. Above `CRITICAL` because the table must be back at `IDLE` before a queued
  successor `VOICE_OFFER` is reduced — against a still-live retired negotiation that offer is a
  `GENERATION_MISMATCH` and is dropped. Not *in* `TEARDOWN` because that slot's occupant must not be
  displaceable by it (Finding 2).
- **`TEARDOWN` precedence.** A pending `StopRequested` is never displaced by a `ControlLinkLost`. A
  stop is a strict superset — it also releases capture — and it is the only input `shutdown()` and
  `stopAndAwaitRelease()` can complete on. The link loss's *discard* still happens; only its slot is
  yielded. A `StopRequested` offered over a pending `ControlLinkLost` still replaces it.
- **The answerer's intent is degraded** (Finding 3), and nothing else about `SendVoiceState` or
  `SendCandidate` is.

### Correction to A5's problem-50 reasoning (problem 60)

A5's mailbox comment claimed the offer-time discard was "exact rather than a race", because
"`endConnection` clears the authenticated connection *before* it emits `LinkLost`, and
`VoiceSignalRelay.deliver` refuses any frame whose generation is not the live one, so nothing remote
can be offered between the lifetime ending and this call."

Re-audited for this amendment, **that claim is false as written**, in both directions:

- `deliver` reads the live generation and then calls `sink.submit` with nothing spanning the two,
  while `endConnection` runs on another coroutine (Android) or another actor (iOS). A **retired**
  frame can pass the check, be overtaken by the whole teardown, and be offered *after* the discard.
- `ControlEvent.LinkLost` reaches `onControlLinkLost` through `SessionCoordinator`'s event consumer,
  not synchronously from `endConnection` — and an **inbound** promotion reaches
  `activateAuthenticatedSession` without passing through that consumer at all. A **successor's**
  frame can therefore be offered before the discard runs.

Both windows are instruction-wide and neither is reproducible at any seam this layer exposes, so
they are **recorded, not papered over** (STATUS §4 problem 60). What would close them by construction
is carrying the admitting generation to `VoiceSignalSink.submit` — exactly what ADR-025 already does
for `MANIFEST_*`/`TRANSFER_*` — and giving `VoiceInputMailbox` a retired-generation floor, so "whose
work is this" stops being a question about when it arrived. That is a `VoiceSignalSink` signature
change on both platforms and is deliberately **not** done here; it is the recorded follow-up.

What is no longer in doubt is the direction this amendment does close: a send whose `Boolean` came
back late cannot reach the discard at all, because a send failure is no longer a lifetime boundary.

### What did not change

No wire change and no new message type. `VoiceSignalSink`, `VoiceSignalTransport` and
`ControlEvent` all keep their signatures. The leader is still always the offerer, ICE is still an
empty server list, `stop()` and `release()` are still two calls, `VOICE_*` is still absent from the
pre-authentication allowlist, and `VoiceController` is still retained across a control reconnect with
capture open — no part of this touches the capture device.

**Unlike A5, this does change the pure table**, so `protocol/vectors/voice-fsm/` gains four rows for
the new input (its two accepting cases and its two guard cases) and a new file-level invariant.
`tools/generate_voice_fsm_vectors.py` is the thing edited; the JSON is generated.

---

## Amendment A7 — 13 September 2026 — semantic voice work carries the control generation that admitted it, and a lifetime boundary names the one that ended

**Status:** accepted.
**Closes:** `docs/STATUS.md` §4 problem 60, which A6 opened and deliberately left open.
**Opens:** `docs/STATUS.md` §4 problem 61 (see "The residue", below).

### What A6 left

A6 gave `ControlLinkLost` ownership of the remote signals queued below it (problem 50's fix) and took
**offer time** as the instant at which that ownership was least wrong. A6 then re-audited its own
claim and found it false in both directions, recorded the two windows as problem 60, and named the
fix without doing it. This amendment does it.

The defect in one line: **the discard had no lifetime identity.** `VoiceInput.SignalReceived` carried
no control generation and `VoiceInput.ControlLinkLost` carried none either, so the mailbox could only
express "discard every remote signal queued *right now*" — a statement about arrival order. Arrival
order is not ownership, and the two windows are the two ways that shows.

### Window 1 — a retired lifetime's signal admitted after its own boundary

`VoiceSignalRelay.deliver` reads `liveGeneration()`, finds a match, parses, and calls `sink.submit`.
Nothing spans the read and the submit: on Android `endConnection` runs on another coroutine, on iOS on
another actor, and on neither platform is there a lock, a barrier or a suspension the teardown must
wait behind. So a frame can pass the liveness check, be overtaken by the **entire** teardown — link
loss included — and be offered afterwards. The discard runs at offer time and cannot see it, and the
table is by then `IDLE` with `voiceSessionId = null`, which is exactly the state `offerReceived`
accepts any generation in. That is problem 50's failure reached by the one route problem 50's fix left
open: an SDP answered on a dead link.

**Verdict: confirmed, and narrow.** It needs a thread or task to be descheduled between two adjacent
unsynchronised reads of shared state. It is bounded to a single in-flight frame — `endConnection`
closes the socket, so the read loop ends — and it is not reproducible at any seam the relay exposes.
It is a real race all the same: "very unlikely" is not a serialization invariant, and nothing in
production makes the ordering safe.

### Window 2 — a successor lifetime's signal deleted by a delayed boundary

`ControlEvent.LinkLost` does not reach `VoiceController` from `endConnection`. It is emitted into a
flow (Android) or a handler feeding an ordered channel (iOS), consumed by `SessionCoordinator`, and on
iOS deferred once more into `launchInSession`. Meanwhile `ControlSessionManager.promote` requires only
that `activeSocket` be null — which `endConnection` has already done — so an **inbound** promotion
authenticates a successor, starts its read loop and admits its frames **without waiting on that
consumer at all**. `attachVoice` keeps the same `VoiceController` across a reconnect by design, and
`ControlRelays.resetCounters` detaches no sink (problem 54), so the successor's frames reach the
mailbox normally. The predecessor's link loss then arrives and deletes them.

**Verdict: confirmed, wide, and not a race.** `VoiceLifetimeProvenanceTest[s]` holds a
coordinator-shaped consumer on the link loss it is handed and shows generation 2 authenticating and
its own `VOICE_OFFER` reaching the voice sink while that loss is still unconsumed. A peer does not
re-send an offer, so deleting it wedges voice for the ride segment — problem 56's failure mode, down a
different path.

### The decision

**Preserve the generation that authorised the work all the way to the semantic consumer.** This is
ADR-025's provenance model, applied one layer below where ADR-025 stopped:

1. `VoiceSignalSink.submit(signal, controlGeneration)`. The value is the frame's own —
   `ReadFrameBinding.generation`, handed to `VoiceSignalRelay.deliver` and passed on unchanged.
   `deliver` still *compares* it against `liveGeneration()` and still refuses a mismatch; what it must
   never do is substitute the live read, because downstream the question stops being "is a session
   live" and becomes "whose semantic work is this".
2. `VoiceInput.SignalReceived(signal, controlGeneration, freshVoiceSessionId)`. Three identities, none
   interchangeable: `controlGeneration` owns the authenticated control lifetime that admitted the
   frame, `voice_session_id` owns one WebRTC negotiation, `freshVoiceSessionId` is an unused id the
   reducer may consume. `VoiceNegotiation` reads the first **nowhere** — it is a lifetime concern, and
   the table decides negotiations.
3. `VoiceInput.ControlLinkLost(retiredControlGeneration)`, filled from
   `ControlEvent.LinkLost(reason, retiredAuthGeneration)`, which `endConnection` captures from the
   `AuthenticatedConnection` record **before** clearing it and identity-checks against the socket that
   is actually ending. Null means no control lifetime ended — a connection that never authenticated,
   or the mailbox-overflow degrade.
4. `VoiceInputMailbox` decides on identity, at **both** instants, because either alone is
   insufficient: a signal already queued when its lifetime is retired is **discarded**, and one
   arriving afterwards is **refused** (`VoiceMailboxOutcome.RetiredGeneration`, counted in
   `refusedRetiredSignalCount`, deliberately *not* an overflow).

The invariant:

> A semantic `VOICE_*` input may affect `VoiceNegotiation` only while the control generation that
> admitted it has not been retired. Retiring generation A may discard or refuse A's semantic work, and
> may never discard or refuse B's.

### Retired-generation state: a monotonic floor, and why that is exact

`retiredControlGenerationFloor` is one `Long?`/`Int64?` that only ever rises. That rests on facts
about the producer, so they are stated rather than assumed, and
`VoiceLifetimeProvenanceTest[s]` pins the one that is not obvious:

- `activateAuthenticatedSession` is the only allocator, it does `authenticationGeneration += 1`, and
  **nothing resets it** — `shutdown()` un-latches the manager for reuse without touching the counter,
  which is exactly the path a full session restart takes.
- A genuinely new ride session builds a **new** `VoiceController` and therefore a new mailbox with a
  null floor: `SessionCoordinator.retireSession` clears `voice` synchronously and `attachVoice`
  constructs a fresh one. The floor cannot outlive the counter that produced it.
- Arrival order is irrelevant to it. ADR-024 Amendment A7 made generation *arrival* non-monotonic on
  purpose; retirement is a statement about a lifetime, not about when its frames turn up. `max` is
  what makes a link loss for an older generation arriving after a newer one has been retired add
  nothing.

A bounded set was considered and **rejected**: a bound must evict, and an evicted entry is a retired
lifetime silently becoming live again. Monotonicity is what makes one number both exact and free of
unbounded memory.

### The second half of retirement, which a boundary alone cannot give

A floor fed only by `ControlLinkLost` would still rest on that boundary arriving before the retired
lifetime's late frame — the very timing assumption this amendment removes. So the mailbox also tracks
`newestAdmittedControlGeneration` and treats a signal from any strictly older generation as stale.
The inference is sound because `ControlSessionManager` holds exactly one `authenticatedConnection` at
a time and allocates a strictly greater generation for each: **observing a frame admitted by B proves
A ended before B was activated.**

This is what closes Window 1 with no boundary in sight, and one lane makes it load-bearing rather than
tidy. `COALESCED` is one slot per kind, latest wins, and PROTOCOL §7.3's `negotiating` intent-to-talk
lives in it — so a retired lifetime's peer state arriving late would **overwrite** the successor's
intent, losing the one message that starts the successor's negotiation. Coalescing *within* one live
lifetime is untouched: the comparison is strictly `<`.

### The overflow degrade is deliberately narrowed

`VoiceController.offer` still answers a `CriticalOverflow`/`TerminalPeerStateOverflow` by forcing
`ControlLinkLost` through the always-accepting teardown lane — but it now names **no** generation, so
it retires nothing and **discards nothing**. CLAUDE.md rule 22's parenthetical licensed the old
behaviour on the grounds that an overflow "is decided now, about the lifetime that is live now, so it
owns what it discards". Under lifetime identity it owns nothing: no lifetime ended, every queued
signal belongs to one that is still live, and deleting live work because something else went wrong is
the defect this amendment exists to remove. The degrade itself is unchanged — the reducer still
returns to `IDLE` and still stops the media transport — and the residual backlog then drains through
the ordinary reducer path, which is what would have happened had the flood never overflowed.

### The residue — problem 61, recorded rather than half-fixed

A `ControlLinkLost(A)` applied **after** a successor's work has already been *reduced* returns the
successor's live negotiation to `IDLE`. Suppressing such a boundary was implemented, tested, and
**rejected**: admission is not application. A successor's admitted offer can be dropped by
`offerReceived`'s `GENERATION_MISMATCH` against a still-live predecessor negotiation, so "a newer
generation admitted something" does not imply its negotiation is live — and suppressing on that
premise leaves a dead lifetime's negotiation standing, which then refuses every offer the successor
sends. Both orderings wedge; the difference is only which one.

Closing it properly means the pure table knowing which control lifetime owns a negotiation, which
`StartRequested` (a local press, admitted by no frame) has no answer for. That is an ADR-scale change
to `VoiceNegotiation` and its vectors, and it is **not** problem 60's. It is recorded as
`docs/STATUS.md` §4 problem 61, with the regression that keeps the teardown unsuppressed in the
meantime.

### What did not change

**No wire change, and no protocol vector change.** Control generation is receiver-local provenance
derived from the authenticated connection: it is not on the wire, not negotiated, and not
peer-influenceable. `protocol/vectors/voice-fsm/` is untouched *because* the reducer reads neither new
field — the vector readers pass a constant, and that constant is itself the assertion that this is a
lifetime concern and not a negotiation one. `vectors/session-gate/` is untouched for the same reason:
`SessionGate` reads only `LinkLossReason`.

The leader is still always the offerer, ICE is still an empty server list, `stop()` and `release()`
are still two calls, `VOICE_*` is still absent from the pre-authentication allowlist, and
`VoiceController` is still retained across a control reconnect with capture open. An ordinary
reconnect still keeps the microphone open, stops the old media transport, retires the old lifetime's
signalling, admits the successor's, and rebuilds per §7.8. A6's `NegotiationSendFailed` is untouched
and stays negotiation-scoped: a failed send still never speaks as a lifetime boundary, and still
retires no control generation.

---

## Amendment A8 — 13 September 2026 — a live negotiation names the control lifetime that owns it

**Status:** Accepted. Closes `docs/STATUS.md` §4 problem 61 — the residue A7 opened and deliberately
recorded rather than half-fixed. No wire change. **The shared vectors do change**, and that is the
point: this is the first of the eight amendments where the control lifetime stops being something
only `VoiceInputMailbox` reasons about and becomes part of what the pure table decides.

### The problem, reproduced from production before anything was changed

A7 closed the *queue* half of the delayed-boundary window: `VoiceInput.SignalReceived` carries the
generation that admitted it, `ControlEvent.LinkLost` names the generation that ended, and
`VoiceInputMailbox` discards what a retirement finds queued and refuses what arrives after it. What
that reaches is **inputs**. It does not reach an input that has already been *reduced*.

The ordering is production's own and needs no race. `ControlEvent.LinkLost` reaches `VoiceController`
through `SessionCoordinator`'s event consumer — deferred once more into `launchInSession` on iOS —
while `ControlSessionManager.promote` requires only that `activeSocket` be null, which `endConnection`
has already done. A successor therefore authenticates, starts its read loop and admits its frames
without waiting on that consumer at all (`VoiceLifetimeProvenanceTest[s]` pins exactly that over two
real TLS sessions on one real manager). So generation B's `VOICE_OFFER` can be admitted, drained and
**applied** while generation A's boundary is still sitting unconsumed.

Applied then, `VoiceNegotiation.controlLinkLost` returned B's live negotiation to `IDLE` and stopped
its media transport, because the state it was looking at said nothing about whose it was. PROTOCOL
§7.8's rebuild does not recover it: `start` is idempotent against the peer's still-live negotiation,
so the answerer's re-stated intent produces no new offer and voice is wedged for the ride segment.

Reproduced on both platforms against unmodified sources before the fix, and the engine trace is the
whole story — `start(…)`, `applyRemote(OFFER)`, `createAnswer`, then `stop`.

### Why the obvious fix was rejected — twice

Suppressing a boundary that a newer generation appears to have superseded was implemented, mirrored
and tested during A7, and rejected there; it is rejected again here, and the reason is the load-bearing
sentence of this amendment:

> **Admission is not application.**

A successor's admitted offer can be dropped by `offerReceived`'s `GENERATION_MISMATCH` against a
still-live predecessor negotiation. "A newer generation admitted something" therefore does **not**
imply that generation's negotiation is live. Suppressing A's boundary on that premise leaves a *dead*
lifetime's negotiation standing, which then refuses every offer the successor sends — the same wedge,
reached from the other side. Both orderings wedge; only state that records an owner can tell them
apart, which is why this is a change to the table rather than to the mailbox.

`VoiceMailboxLifetimeIdentityTest[s]`' regression that the teardown is never suppressed stays exactly
as A7 left it. The mailbox still delivers every boundary to the reducer, whatever else it has
admitted. What changed is what the reducer does with one.

### The decision

`VoiceNegotiationState` gains **`negotiationControlGeneration`**: the authenticated control lifetime
that owns the negotiation state the value holds — a live `status`, or a `heldRemoteOffer`. Those two
are mutually exclusive by construction (every branch that goes live requires `localAudioOpen`; every
branch that holds an offer requires it to be false), so one field names the owner of whichever exists.

**Ownership is established, never inferred.** It is set only by the transitions that actually create
negotiation state, always to the generation carried by the very input that created it:

| Transition | Owner becomes |
|---|---|
| `start` (offerer) | the press's `controlGeneration` |
| `start` (answerer, held offer) | the press's — the answer goes out on the link that is live **now** |
| `start` (answerer, intent-to-talk) | the press's |
| `offerReceived`, full accept | the frame's admitting generation |
| `offerReceived`, offer held | the frame's — a held offer is negotiation state too |
| `peerWantsVoice` (§7.3 glare) | the frame's |

`answerReceived` and `candidateReceived` *advance* a negotiation that already exists and already has
an owner; moving it because a successor's link happened to carry a later frame would be inferring
ownership rather than establishing it, and would leave the negotiation A created un-retirable by A's
own boundary. `start`'s idempotent early-return does not re-own either: a second press under a newer
lifetime establishes nothing.

Ownership clears wherever the table returns to a value holding no negotiation state — `stop`,
`negotiationSendFailed`, `teardownFromPeer`, and a boundary that does retire. All four already
construct a fresh `VoiceNegotiationState`, so they clear it by construction rather than by
remembering to.

**The link-loss rule is one comparison:**

> Retire unless `owner != null && retired != null && owner > retired`.

The four corners, and why the third is the one that matters most:

- `owner > retired` — a predecessor's delayed boundary. **Preserved**, and recorded as
  `VoiceSignalDropReason.SUPERSEDED_CONTROL_LIFETIME` rather than silently ignored: a preserved
  successor produces no other observable at all, and the one case this amendment exists for must not
  be the only one that leaves no evidence it happened. (It is also what lets the iOS regressions
  sequence on an observable instead of a sleep.)
- `owner == retired` — §7.8 unchanged.
- `owner < retired` — a **newer** lifetime ended while an older one still owns the negotiation.
  **Retired.** `ControlSessionManager` holds one authenticated connection at a time and allocates a
  strictly greater generation for each, so a newer lifetime having existed *proves* the owner's has
  ended — the same fact `VoiceInputMailbox.newestAdmittedControlGeneration` already rests on. This is
  why the rule is "older than" and not "different from": expressing it as inequality is what stops a
  lost or never-emitted predecessor boundary stranding a dead negotiation forever, which is precisely
  the failure mode the rejected suppression had.
- `owner == null`, or `retired == null` — **retired.** The first is unreachable by construction and is
  failed safe rather than trusted. The second is not a lifetime boundary at all: its producers are a
  connection that died before authenticating and the mailbox-overflow degrade, and that degrade is a
  local safety valve that has to work whoever owns what.

### `StartRequested`, which is the hard half

A local press is admitted by no frame, so there is no provenance to carry and the table must not
invent one (CLAUDE.md rule 9). `VoiceInput.StartRequested` therefore takes `controlGeneration` from
its caller: `SessionCoordinator.attachVoice` passes the generation **the `Connected` event named**
(`ControlEvent.Connected` gains `authGeneration`, emitted from the one statement that mints it), and
`startIntercom` passes `ControlSessionManager.liveAuthenticatedGeneration`.

Reading a live generation there is correct and is **not** ADR-025's defect. That defect is re-reading
live state to label a frame that has *already been read*, discarding provenance the frame carried.
A press carries none, happens now, and "which lifetime is authenticated now" is exactly the question
it asks. The distinction rule 20 already draws — comparing a live generation is correct, reading one
to label a frame is the defect — is what makes this sound.

**A null generation is a real case with a deliberate answer.** A user can press Start in the gap
between one link dying and §10's ladder restoring the next; `voice` survives a reconnect by design, so
the press is reachable. Refusing it is wrong — ARCHITECTURE §6.4 requires capture to be opened while
the app is foreground-visible, and this may be the last such moment. Creating a negotiation is worse:
it would be owned by a lifetime that does not exist, and an un-ownable negotiation is the one state no
boundary can retire.

So the press **records consent and starts no negotiation**: `StartLocalAudio`, `localAudioOpen = true`,
status stays `IDLE`, nothing on the wire. `attachVoice` then rebuilds it under the successor the moment
one authenticates, because it already starts voice for any segment whose capture is open. Deterministic,
no wedge, and no negotiation owned by nobody. It is also strictly simpler than what happened before,
which was to create an offer, fail to send it, and degrade back through `NegotiationSendFailed`.

**The three lifetimes stay separate, exactly as ARCHITECTURE §6.3/§6.4 requires.** Capture lifetime is
the ride segment and is untouched by any of this — no boundary here closes a microphone. WebRTC
negotiation lifetime is `voice_session_id`. Control authentication lifetime is this new owner. Nothing
in this amendment moves a boundary between them.

### The vectors move, and why that is deliberate

A3–A7 each added no vector table, on the stated grounds that coroutine lifetime and connection
identity are not distributed decisions. This one **is** a change to the table, so it changes the table's
vectors. `protocol/vectors/voice-fsm/` gains `negotiation_control_generation` on every state and a
control generation on every `StartRequested`, `SignalReceived` and `ControlLinkLost`, plus fourteen new
rows for the ownership rule's corners, establishment, the consent-only press and its rebuild.

Two choices in the extension are worth stating. First, `tools/generate_voice_fsm_vectors.py` supplies
`CTL_A` for every row that does not say otherwise, on the same reasoning `TEST_CONTROL_GENERATION_A`
already documents: every row written before this amendment describes a single control lifetime.
Second, both platforms' readers **require** the keys to be present while allowing them to be null, so
a future row that forgets to say which lifetime it is about fails a build rather than quietly meaning
`CTL_A`. Null is a meaning here, not an omission.

Two property tests carry what rows cannot. One asserts over every row that a resulting state names an
owner **iff** it holds negotiation state — which turns the generator's default from a convenience into
a checked invariant. The other exhausts role × status against an older, equal, newer and null boundary,
and is the one that pins the `owner < retired` direction the rejected suppression got wrong.

**Still no wire change.** A control authentication generation is a number one device allocates for its
own connections. It is not serialised, not negotiated, and not peer-influenceable; `vectors/voice-signal/`
and `vectors/session-gate/` are untouched.

### What did not change

The leader is still always the offerer, ICE is still an empty server list, `stop()` and `release()` are
still two calls, `VOICE_*` is still absent from the pre-authentication allowlist, and `VoiceController`
is still retained across a control reconnect with capture open. A6's `NegotiationSendFailed` is
untouched and stays negotiation-scoped. A7's mailbox is untouched: it still discards what a retirement
finds queued, still refuses what arrives after it, still names `retiredControlGeneration`, still never
suppresses a boundary, and its overflow degrade still retires and discards nothing. Problems 50, 56, 57,
59 and 60 keep their regressions and they all still pass.

---

## Amendment A9 — 14 September 2026 — a negotiation's authority reaches its wire, and does not cross a lifetime to get there

**Status:** Accepted. Closes `docs/STATUS.md` §4 problems 63 and 64, both found by an independent
review **of A8** rather than by A8's own stress run. No wire change. **The shared vectors do change**
again, for A8's reason and one more: an outbound action now names the lifetime it may be written on,
and that is a decision of the pure table.

### What A8 established, and the two things it did not ask

A8 gave a negotiation an owner — `VoiceNegotiationState.negotiationControlGeneration` — so that a
predecessor's delayed `ControlLinkLost` can no longer retire a successor's already-reduced state. That
rule is unchanged and every one of its regressions still passes. What A8 did not ask is:

1. whether *negotiation state created by one lifetime may be adopted by another*, and
2. whether an *action* the table authorised is still written on the connection that authorised it.

The answer to both was no, and in both cases production did it anyway.

### Problem 63 — a held remote offer could cross a control lifetime

PROTOCOL §7.3 holds a `VOICE_OFFER` that arrives before this user has consented: the microphone is
never opened because a *peer* asked (ARCHITECTURE §6.4). A8 correctly recorded the *delivering*
lifetime as that held offer's owner. But `start`'s answerer branch then answered whatever held offer it
found and set the owner to the **press's** lifetime, with a comment saying exactly that.

So: A delivers an offer, A dies, B authenticates, and — before A's boundary is consumed — the user
taps Start. The table applied A's SDP, created an answer naming A's `voice_session_id`, and moved the
owner to B. Three consequences, and the third is the worst:

- the answer names a generation the **offerer has already discarded**, because its own copy of that
  link died and its own `ControlLinkLost` retired it; the peer drops the answer as a generation
  mismatch and this side sits in `CONNECTING` forever;
- §7.8's requirement that a reconnect rebuild voice as a **fresh** negotiation is silently violated,
  on the one path that looked like an optimisation ("answer the offer we already have");
- A's boundary, arriving afterwards, is now *superseded* by A8's own rule and is inert — so nothing
  retires the wedge, and `start`'s idempotence makes `attachVoice`'s rebuild a no-op. This is problem
  56's wedge, re-created by a different route.

**The rule.** *Negotiation state created from remote SDP retains the control lifetime that
authenticated that SDP; local consent under another lifetime cannot transfer it.* Concretely, in
`start`'s answerer branch:

| held offer's owner vs. the press's lifetime | outcome |
|---|---|
| equal (or no owner) | answer it — A8's behaviour, unchanged |
| **older** than the press | the offerer's link died with it: **discard** the held offer (`RETIRED_HELD_OFFER`) and state §7.3's intent-to-talk under the press's lifetime instead |
| **newer** than the press | the *press* is the stale thing: record consent, open capture, start **no** negotiation (`SUPERSEDED_START_LIFETIME`), and leave the held offer for its own lifetime's consent |

Both directions are reachable and neither is a race: the first is a tap after a reconnect, the second
is a tap that sat in the mailbox while a successor's offer was reduced ahead of it. The third row uses
the same reasoning as A8's `owner < retired` case and the same reasoning as `start`'s null-generation
branch — consent is honoured because ARCHITECTURE §6.4 may give no second foreground-visible chance to
open capture, and a negotiation owned by a lifetime that has ended is precisely what A8 exists to
prevent creating.

Note what this does **not** do: it does not suppress anything, and it does not re-own. Discarding a
dead lifetime's state and building fresh is the opposite of adopting it.

### Problem 64 — an action authorised by one lifetime was written on another's socket

`VoiceSignalRelay.send` resolved "the authenticated writer" at the moment of the **write**. That is
never the moment the frame was authorised: the mailbox's single consumer, `createOffer`'s engine
callback, the dispatcher/actor hop, the write lock and the flush all suspend between the two. So a
`VOICE_OFFER` authorised by lifetime A and finally written after B authenticated was written to **B's
connection**, where the peer accepted it as current work — and A's boundary, arriving afterwards,
retired this side's media while the peer was still negotiating.

This is ADR-024 Amendment A7's rule pointing outwards, and rule 20's distinction in the other
direction: *a live generation may be compared against an authorisation, never substituted for one.*

**The design, and why this seam.** Four options were weighed: deriving the owner in the driver from the
pre/post reduction state; a generation-bound writer lease alone; a generation on the action; and a
generation-bearing action context. The driver derivation is correct for every branch that exists
today and would have been wrong for the *first* branch this amendment adds (problem 63's discard,
where the pre-state owner is the dead lifetime and the send belongs to the live one) — which is
precisely the failure mode this codebase keeps finding, so it was rejected. The generation goes on the
action:

- `SendOffer`, `SendAnswer`, `SendVoiceState` and `SendCandidate` implement `OutboundVoiceAction` and
  carry `controlGeneration`, set by the transition that produced them from the owner of the
  negotiation the frame belongs to. `stop`'s `closed` reads it **before** the reset, which is the one
  place a derivation would have differed.
- `VoiceSignalTransport.send(signal, controlGeneration)` takes it. The relay refuses on mismatch and
  on null, counts it as `droppedRetiredGenerationOutbound`, and returns `false`.
- The writer supplier is itself generation-bound and resolves the socket **and** the generation from
  the one immutable `AuthenticatedConnection` record — never from `activeSocket` plus a separate
  generation read, which has an interleaving in which a successor's socket is handed out under a
  predecessor's number. This is `ReadFrameBinding.of`'s own reasoning, outbound.

**What a refusal means, and what it must not mean.** It is a plain `false`, which is the outcome
`VoiceSignalTransport` already defines, and `degradeIfUnsent` already answers it with
`NegotiationSendFailed` — **never** `ControlLinkLost` (A6/problem 57). Because generations strictly
increase and one connection is authenticated at a time, a refusal is permanent rather than transient:
once A is not live it never will be again, so the degrade is deterministic, not a retry. Which sends
degrade is unchanged from A6: `SendOffer`, `SendAnswer`, and the answerer's intent-to-talk
`SendVoiceState { voice_session_id: null, negotiating }` — the one state update no later one carries
(problem 59). A mute, a mode, a connectivity transition and a `closed` are still deliberately left
alone: each either names a generation or is genuinely superseded by the next one.

**Engine callbacks are unchanged, and this amendment is why they can be.** `LocalOfferCreated`,
`LocalAnswerCreated`, `LocalCandidateGathered`, `RemoteTrackChanged` and `MediaConnectivityChanged`
are guarded by `voice_session_id` alone, and that is sufficient: 128 CSPRNG bits per negotiation mean a
callback from a torn-down peer connection can never match a different one. What `voice_session_id`
could not answer is the case where the callback matches a negotiation that *is* still live but whose
lifetime has ended — and that is now refused at the send, because the `SendOffer` it produces carries
the negotiation's owner. Adding control-lifetime provenance to the callbacks themselves was considered
and rejected: it would be a second answer to a question the negotiation's own owner already answers,
and two sources of one fact is how they come to disagree.

### Scope

Only `VOICE_*` is generation-bound outbound, and deliberately. `AUDIO_STATE` is re-derived per session
(PROTOCOL §4.4 sends one on every `CONNECTED` regardless of change) and carries ADR-021 A7's
`revision_epoch` for its own lifetime question; Phase 4's transfers and Phase 5's `PlaybackRelay.send`
already carry and check their own generation (ADR-024 A2). Widening the bound writer to them would
duplicate a guard rather than add one.

Four identities remain separate and must never be conflated: `voice_session_id` (one WebRTC
negotiation), `revision_epoch` (ADR-021 A7's `AUDIO_STATE` sender lifetime), `localAudioOpen` (capture
lifetime, which survives all of this), and the authenticated control generation (which connection).

### What is deliberately *not* claimed

A stale `NegotiationSendFailed` being applied **after** a successor's rebuild has been reduced is not
reachable, and building the regression is what proved it: `VoiceMailboxLane.SEND_FAILURE` outranks
`CRITICAL` by design (A6), and the controller's single consumer is parked inside `perform` for as long
as the write is — so the failure is always reduced before any rebuild queued behind it. The reducer's
guard against that ordering is real and stays pinned where it belongs, in
`protocol/vectors/voice-fsm/`'s `negotiation-send-failed-from-a-retired-generation-is-inert`. Writing
a controller test for it would have asserted a state no production ordering can produce.

### Unchanged

A8's ownership table, in both directions. A7's mailbox — it still discards what a retirement finds
queued, refuses what arrives after it, names `retiredControlGeneration`, never suppresses a boundary,
and its overflow degrade still retires and discards nothing. A6's `NegotiationSendFailed` semantics.
Capture lifetime: no path here opens or closes the capture device, and `localAudioOpen` still survives
every control-lifetime boundary. `StopRequested` is still undisplaceable. Problems 50, 56, 57, 59, 60
and 61 keep their regressions and all still pass.
