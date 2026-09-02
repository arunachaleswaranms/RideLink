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
