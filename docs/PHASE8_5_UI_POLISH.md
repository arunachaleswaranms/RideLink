# Phase 8.5 — UI polish and UX clarity

Baseline: `2aa728fd45bfc37c59ba8a5d75014fc80d77e540` (merged Phase 8).
Review branch: `phase8-5/ui-polish-review`; [PR #12](https://github.com/arunachaleswaranms/RideLink/pull/12).
An isolated worktree preserves unrelated concurrent edits in the original checkout.
No merge is authorized. Physical qualification is deferred.

## Initial audit (before implementation)

Reviewed every Compose/SwiftUI surface and its existing production action wiring.
This inventory is a source audit; rendered evidence is recorded separately below.

| Surface | Classification | Finding / treatment |
|---|---|---|
| Startup / identity failure | Polish / Separate | Preserve fail-closed behavior; put raw error in diagnostics. |
| Main / connection / discovery | Simplify | Repeated device/connection headings, phase labels and transport banner obscure the setup journey. Explain same-network discovery and automatic connection. |
| Connecting / peer found | Polish | Discovery automatically selects a peer; do not invent a chooser or a new cancel transition. Explain waiting states. |
| Pairing / trust | Polish | Keep two explicit SAS decisions and all trust gates. Group the six digits, retain spoken digit labels, avoid peer IDs as name fallback. |
| Security warning | Keep / Separate | Keep the identity-change warning visible; put raw failure code in disclosure. |
| Ride Mode | Polish / Fix | Android has a non-scrolling column and unweighted audio cards; iOS truncates titles and has unlabeled icon controls. Status and transport need clear hierarchy. |
| Reconnect / disconnected | Fix | Preserve ride visibility and legal recovery. Android suggests foregrounding even while already foreground; expose existing retry. Do not promise music is playing merely because connection recovery exists. |
| Intercom / mute / PTT | Simplify / Fix | Raw voice fields dominate; PTT held is not proof of transmission when muted. Show production voice/transmission truth with a clear pressed state. Keep policy and capture ownership unchanged. |
| Modes A–E | Polish | Single letters need descriptions and selection semantics. Detailed mode selection belongs in setup; Ride Mode displays the selected policy. |
| Permissions / audio route failures | Fix | Raw failure enum names give no useful next action. Map failures without changing permission requests or foreground ownership. |
| Local music / Now Playing | Polish | Intentional empty state, readable metadata, labeled transport; local playback stays independent of peer state. |
| Local library / import / search | Polish / Fix | Four sort buttons overflow narrow widths and selected looks disabled. Use native selection and wrapping. Keep platform file pickers. |
| Shared library / transfer | Polish / Separate | Keep measured byte progress; explain download/verification/failure. Preserve technical errors in disclosure. |
| Synchronized music | Simplify | Long uppercase protocol explanations; duplicate precision transport controls. Preserve existing command entry points and clearly describe sync state. |
| Shared queue | Fix | Hash prefixes are user-facing titles. Resolve metadata for display only; keep queue-item IDs as row/action identity, including duplicates. |
| Empty / waiting / error states | Polish | Replace ambiguous dashes and “Empty” with short explanations and existing next actions. No fabricated progress or retry promises. |
| Diagnostics | Separate | Keep control, resync, voice, route, coexistence and playback details behind collapsed, named disclosures. |

## Design direction (before implementation)

Restrained native surfaces with a teal primary action, neutral backgrounds and high-contrast text.
Setup follows system appearance; Ride Mode uses a dark surface. Status always includes words.
Use a small spacing scale: 4 / 8 / 12 / 16 / 24 / 32. Surface radius 16, small controls 8,
large controls 16. Native scalable typography: screen title, section title, primary body,
secondary body, status and caption. Ride title and transport have stronger visual emphasis.
Ride transport targets are at least 72 points/dp; setup targets follow native minimums.
Success/connected, warning/reconnecting, error/disconnected, information, active intercom,
muted, PTT, synchronized and local playback use semantic roles, paired with labels/icons.
Use SF Symbols and Compose-native vector icons; no bitmap assets or remote UI dependencies.

Ride hierarchy: connection → now playing and sync → large transport → intercom/microphone →
separated End Ride. End Ride remains immediate: its existing stop behavior should not gain a
modal delay. Distinct destructive styling and distance from transport prevent casual confusion.

## Boundaries

No protocol, security, synchronization, lifetime, authority, capture or coexistence redesign.
`TransportOwnership` remains authoritative; `SyncState` is diagnostic. Presentation projections
and fixtures are not production state owners. No new cloud/network/analytics dependency.
No `PROJECT_STATE.md` exists in this baseline; this record and the dated status entry are the handoff.

## Implementation and evidence

Implemented on both native platforms: Ride Mode hierarchy, connection summary, grouped pairing
code, intercom status/policy wording, Now Playing, library search/sort, duplicate-safe shared queue,
transfer labels and secondary diagnostic disclosures. The platform file pickers, permission prompts,
trust decisions, discovery FSM, action entry points and lifecycle hooks remain in place.

### Accessibility and implementation

- Setup uses Material 3 / native SwiftUI text and controls. Ride transport, mute, PTT and End Ride
  have a minimum 72 dp/pt target; setup controls use native minimums (48 dp Android, at least 44 pt
  for native large iOS controls). Labels accompany icons; critical state always includes text.
- Teal is the primary identity, amber denotes PTT-held/warning, and destructive/error treatment is
  separate. Text foregrounds are paired with background roles in both appearances. The render pass
  corrected iOS white-on-bright-teal contrast and Android light status-bar contrast.
- System text scaling is retained. Scroll containers keep lower controls reachable. Long track
  names wrap; setup sorting wraps on Android and uses an iOS menu. Android transport captions
  were adjusted after the 150% narrow-viewport pass showed an awkward single-letter wrap.
  Setup pairing, transport and intercom controls wrap as groups when the available width is insufficient.
- PTT still calls the existing gate: touch hold/release, cancellation/disposal release, and named
  accessible Start talking / Stop talking actions. A held indicator does not claim transmission
  while muted. No new microphone/session owner exists.
- Queue rows and removal callbacks use queue-item IDs, never track hashes. Hashes only look up
  display metadata; missing metadata shows Shared track. Verified cache-only playback also keeps
  usable controls without pretending a local library entry exists.
- SF Symbols and small native Android vector transport resources require no new library or
  network access. No decorative animation, polling timer, image service or telemetry was added.
- Physical TalkBack/VoiceOver, glove ergonomics and sunlight contrast are not established by this
  audit. Android emulator accessibility-action tests and native visual inspection are software evidence only.

### Fresh-fix architecture audit

`TransportOwnership` remains authoritative and `SyncState` remains diagnostic. Both setup and
Ride Mode use ownership-aware presentation labels, so a late SCHEDULED/SYNCED diagnostic after
End Ride cannot claim that local controls became synchronized again. Regression mappings cover
this case. Existing Phase 8 debt/lifetime suites continue to verify the actual distributed behavior.
The UI does not mint authority, copy protocol state into a mutable owner, or change reservation,
delivery, accepted-command, generation, ride, epoch, reconciliation or bounded-work logic.

Ride Mode transport still enters MusicCoordinator; setup synchronized actions retain their existing
coordinator admission gates. Recomposition/body evaluation does not issue commands. Intercom and
pairing actions retain the same production callbacks. The iOS library row now has separate play
and queue buttons instead of a tap handler enclosing another button. The fixture hosts contain
no-op callbacks and are explicitly separated from production state and authentication.

### Validation ledger

Local validation on 24–25 September 2026:

| Gate | Result |
|---|---|
| Android JDK 21 core and all unit tests | PASS — 1,096 distinct tests across core/app/network/audio/data (459/289/284/33/31); debug/release variants are not double-counted |
| Android ktlint, detekt, lint | PASS |
| Android assembleDebug / assembleRelease | PASS |
| Android instrumentation, API 36 ARM64 | PASS — 54 tests (app 9, audio 11, data 34), including PTT accessibility/disposal and duplicate queue identity |
| Swift RideLinkCore | PASS — 353 tests |
| Swift RideLinkPlatform | PASS — 673 tests, one expected standalone interop skip, zero failures |
| Signed iOS simulator Debug / Release, ARM64 | PASS |
| `tools/crossplatform/run.sh` | PASS — actual TLS/SAS, clock readiness, protocol/state exchange, generation-2 silent pinned reconnect; this supplies the separately orchestrated interop case |
| Local-only source audit | PASS — 318 production sources, zero findings |

The final run uses the isolated worktree. Android invocation: JDK 21, `:core:test test
ktlintCheck detekt lint assembleDebug assembleRelease`; instrumentation uses serial
`connectedAndroidTest`, with final app rendering/action reruns through AndroidJUnitRunner.
Swift package commands are `swift test --package-path ios/Packages/RideLinkCore` and
`swift test --package-path ios/Packages/RideLinkPlatform`. Simulator builds use the production
`ios/RideLink.xcodeproj`, scheme RideLink, both configurations, and `ARCHS=arm64`.

The first pushed UI head `0e781bf` passed CI run `35985322854` and security run `35985323111`
(Android, iOS, Gitleaks, local-only policy, Dependency Review and both CodeQL languages).
Those runs do not certify later commits. The final exact-head run IDs and verdicts are recorded in
[PR #12](https://github.com/arunachaleswaranms/RideLink/pull/12), whose head must match the reviewed
commit. No workflow, dependency manifest or lockfile is changed by this phase.

Transient harness evidence is not counted as a pass: one Android screenshot-service null result,
Android launcher/System UI ANRs, early accessibility-tree reads, and simulator launch-transition
frames. Android capture checks wait for the intended fixture identity in the native accessibility tree,
then native frames/accessibility idle. Invalid simulator transition images are discarded and recaptured.
The PTT removal assertion waits for disposal's release callback rather than assuming main-loop idle
means recomposition completed. No production behavior was relaxed to make a fixture pass.
All physical observations remain **DEFERRED — PHYSICAL QUALIFICATION** (Phase 9): sunlight,
gloves, mounting, helmet/TWS, real background behavior, audible synchronization, distraction,
battery, thermal and a real two-hour ride. Emulator/simulator images cannot close these gates.

### Native visual configurations

| Platform | Configuration actually rendered and inspected | Coverage |
|---|---|---|
| Android API 36 ARM64, RideLink_API36 | 1280 × 2856 px, density 480 (about 427 × 952 dp), font scale 1.0; light setup / dark Ride Mode | Large phone reference, not a physical OnePlus Nord 5 |
| Android API 36 ARM64, same AVD | 720 × 1280 px, density 320 (360 × 640 dp), font scale 1.5; dark setup / dark Ride Mode | Narrow controls, long titles, disabled states and scaled text |
| iOS 27, iPhone 17 Pro Max | 1320 × 2868 px (440 × 956 pt), default text; light setup / dark Ride Mode | Large phone hierarchy and safe areas |
| iOS 27, iPhone 17e | 1170 × 2532 px (390 × 844 pt), XXXL text; dark setup / dark Ride Mode | Small phone, long metadata, PTT and pairing |
| iOS 27, iPhone 17e | Same viewport, normal text and light setup | Library, pairing, queue, Now Playing and transfer; software-keyboard presentation |

The fixtures render production components with sample values and no-op callbacks. Their screenshots
are **AUTOMATED NATIVE RENDERS, VISUALLY INSPECTED**, not a two-peer UI journey. Native keyboard and
scroll observations are recorded separately. Ride coverage includes idle/nothing playing, intercom,
music only, combined, PTT-held, muted, reconnecting, disconnected, sync problem, long title and
waiting for content. Setup covers connection phases, SAS, security warning, microphone permission
failure, empty/playing/paused music, library, measured transfer progress and duplicate queue items.

The old-obligation-after-End-Ride case is covered by deterministic ownership-label tests and the
existing real-coordinator Phase 8 suites, not by a fabricated interactive ride. The fresh-fix audit
above records why its diagnostics cannot authorize transport. Native screenshots do not prove
real audio or an authenticated emulator-to-simulator flow.

Representative reviewed renders are in [the screenshot index](UI_EVIDENCE/phase8_5/README.md).

Native interaction observations: the iPhone 17e library retains the search field above the software
keyboard. At XXXL text, scrolling exposes the complete End Ride control with separation from music
transport. Android fixtures also exercise library keyboard presentation and require the End Ride
label to be inside the visible window after scrolling. These are simulator/emulator observations,
not physical VoiceOver/TalkBack or moving-rider qualification. Landscape/tablet expansion was not
added; native permission dialogs and a paired two-phone UI journey were not newly qualified here.
