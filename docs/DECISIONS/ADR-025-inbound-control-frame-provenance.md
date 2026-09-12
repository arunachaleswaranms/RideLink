# ADR-025 — An inbound control frame keeps the authority of the connection it was read from

**Status:** Accepted · 12 Sep 2026
**Relates to:** [ADR-024 Amendment A7](ADR-024-synchronized-playback-integration.md) (the same rule, for Phase 5, one layer above), [ADR-023](ADR-023-bulk-transfer-session-binding.md) (the session generation), [ADR-019](ADR-019-connected-means-authenticated.md) (what authentication means), [ADR-020](ADR-020-webrtc-voice-foundation.md) (`VOICE_*`), [ADR-021](ADR-021-intercom-transmission-and-capture-ownership.md) (`AUDIO_STATE`)
**Governs:** [PROTOCOL §4.1](../PROTOCOL.md#41-handshake), [§4.4](../PROTOCOL.md#44-audio_state), [§4.5](../PROTOCOL.md#45-pairing--first-meeting-only), [§6](../PROTOCOL.md#6-clock-sync), [§7](../PROTOCOL.md#7-voice-signalling), [§8.1](../PROTOCOL.md#81-manifest-sync), [§8.2](../PROTOCOL.md#82-transfer-negotiation)
**Vectors:** none. This is coroutine/`Task` lifetime and connection identity, not a distributed decision — the same reasoning ADR-024 Amendments A3–A7 give for adding no table.
**Wire format:** **unchanged.** No new field, no new type, no changed bound, no changed encoding.

## Context

ADR-024 Amendment A7 established, for Phase 5, that

> a frame is authorised by **the connection it was read from**, and by that connection's
> authentication epoch. No later session transition may give it a newer one.

and built `ReadFrameBinding` to carry exactly that. `readLoop` now constructs the binding the instant
`readFrame()` returns; `handleFrame` takes it; the pre-authentication gate asks "was **this** frame's
connection an authenticated session when it was read"; and `PlaybackRelay`/`PlaybackSink`/
`Phase5FrameQueue` are handed `binding.generation` rather than looking one up.

A7 threaded it to Phase 5 and **nowhere else**, and said so: its own Finding C recorded that Phase 4's
`MANIFEST_*`/`TRANSFER_*` dispatch still derived its generation from live state, and `docs/STATUS.md`
§7 listed `VoiceController`/`VoiceSignalRelay` and `AudioStateRelay` as never having been asked the
question at all. This ADR is that sweep, finished.

**Why any of it is reachable.** `endConnection` cancels neither read loop — deliberately, because a
read loop is the transport's and a link that dies mid-pairing still has to be noticed — and both
platforms' read loops resume across a scheduling point: a dispatcher hop on Android, actor
re-entrancy on iOS. So `handleFrame` can legitimately be entered with a binding whose connection has
already been replaced, and every step *downstream* of it can run after a whole reconnect has
completed. Anything in that path that answers "which session is this?" by reading live state answers
it about the **successor**.

## Decision

### The rule

> Once `ControlSessionManager` has created a `ReadFrameBinding` for an inbound frame, no downstream
> subsystem may discard that provenance and reconstruct authority from mutable live session state. A
> frame must either **retain** the session/connection provenance that authorised its read, or be
> **rejected** as stale. It must never acquire the authority, epoch, generation, state or identity of
> a successor session merely because downstream work runs later.

Two different questions follow from it, and they are deliberately given two different names:

| Question | Answered by | Changes when |
|---|---|---|
| Which session authorised **this frame**? | `ReadFrameBinding.generation` | never — it is fixed at the read |
| Which session is authenticated **right now**? | `ControlSessionManager.liveAuthenticatedGeneration` | every session boundary |

*Comparing* the two is correct and is what every guard below does. *Reading the second to label a
frame* is the defect — A7's, and this ADR's.

`liveAuthenticatedGeneration` is derived from the one `AuthenticatedConnection` record, so there is no
second source that could disagree (A7's reason for deriving `authenticated` from the record rather
than keeping a boolean beside it). It is deliberately **not** `currentAuthGeneration`: that one keeps
reporting the last number it assigned after the link drops, so a frame authorised by a session that
has ended still matched it. `liveAuthenticatedGeneration` goes `null`, and saying so is what stops a
retired session's frame being applied to whatever comes next.

On iOS the record lives in a lock-backed `AuthenticatedConnectionBox` rather than in plain actor
storage — not as a mirror, as its **only** storage, with `authenticatedConnection` a computed property
over it. That is what lets a relay's synchronous `deliver` and `SharedLibraryCoordinator`'s
`@MainActor` guard read it with no `await`. Introducing an `await` into a synchronous relay callback
purely to query live state is the very class of timing bug this ADR removes.

### §1 — `MANIFEST_*` and `TRANSFER_*` carry the generation to their sink

`ManifestRelay.deliver` / `TransferRelay.deliver` take the frame's generation, refuse (and count) a
frame whose generation is no longer live, and pass it on: `ManifestSink.submit(message, generation)`,
`TransferSink.submit(message, generation)` — exactly the contract `PlaybackSink.submit` already had.

`SharedLibraryCoordinator` no longer reads anything at dispatch time. Its two sink closures take the
supplied generation, and `handleManifestMessage`/`handleTransferMessage` compare it against
`liveAuthenticatedGeneration` before touching `syncState`/`remoteEntries`/`bulkGate`/
`pendingOfferTransferId` or any provider state.

**What did not change.** `OperationFence`, `BulkOperationGate`, `ProviderSessionContext`,
`stillAuthorised`, the bulk token table, SPKI fencing, caching semantics and the `TRANSFER_*` wire
shapes are untouched. On iOS `sessionEpoch` survives as the **provider-side** operation fence ADR-023
Amendments A3/A5 built on it — `serveTransferRequest` still captures it once and `stillAuthorised`
still re-proves it at every suspension point. It is simply no longer the inbound-dispatch fence: it is
read on `@MainActor`, *after* the provenance guard has proved the request belongs to the live session,
instead of in the sink closure. "Is the operation I already started still authorised" and "which
session authorised this frame" are different questions and now have different answers.

### §2 — `VOICE_*` and `AUDIO_STATE` are refused at the relay

Both take the generation and refuse a frame whose session has been replaced, counting it as
`droppedRetiredGeneration` — the sibling of `droppedPreAuthentication`, and distinguishable from it:
that one counts a peer that was never authenticated, this one counts a peer that *was*, on a
connection that is gone.

Their sinks act immediately and have no deferred re-check to hand a generation to, so the earliest
correct point *is* the relay. This is a refusal rather than a relabelling because there is no ledger
here a retired generation's frame has to reach.

**Why `voice_session_id` is not enough, and why the two must not be conflated.** `VoiceController` is
deliberately retained across a control reconnect: the capture device stays open for the whole ride
segment (ARCHITECTURE §6.3/§6.4, ADR-021), which is the behaviour this ADR must not break and does
not. `VoiceNegotiation`'s generation and glare guards prove *voice-session* ownership; they say
nothing about *control-session* ownership, and two of their correct behaviours are what make a stale
frame harmful:

- `VOICE_STATE { state: "closed" }` may legally omit `voice_session_id`, and `peerStateReceived`
  treats an absent id as "carries no generation claim" — **not** a mismatch. It is `teardownFromPeer`.
  A Session A frame therefore stops **Session B's** live media.
- after `ControlLinkLost` the reducer resets to `IDLE` with `voiceSessionId == nil`, and that is
  exactly the state in which `offerReceived` **accepts** an offer naming any generation. A Session A
  offer would then start a negotiation whose answer goes out on Session B's connection.

`AUDIO_STATE` was checked rather than assumed, and is reachable: `AudioStateInboxHolder` is reset per
**discovery** session (PROTOCOL §4.4's `revision` is "per sender per session"), so it deliberately
survives a control-session boundary, and a stale message whose `revision` happens to exceed the held
one is accepted by the revision rule and published as the successor session's peer audio state.

### §3 — Phase 5 is deliberately **not** gated the same way

`PLAY`/`PAUSE`/…/`QUEUE_*` still reach `PlaybackRelay` with a stale generation rather than being
refused at the relay, and that is on purpose: ADR-024 Amendment A6's per-generation loss ledger exists
to attribute a retired session's refusal to the session that caused it and surface it as
`inboundRetiredLossCount`. Refusing at this seam would silently delete exactly the accounting A6
exists to produce. The generation the frame carries is what keeps it harmless. A6 and A7 are untouched
by this ADR.

### §4 — The pre-authentication family is bound to its connection

`PING`, `PONG`, `PAIR_REQUEST`, `PAIR_CONFIRM`, `PAIR_RESULT`, `BYE` and `ERROR` are allowed *past* the
generation gate by design (PROTOCOL §4.1's closed list), so they carry no generation and nothing else
bound them to a connection. They are now answered **only for the connection they were read from** —
`binding.socket === activeSocket`, else counted as `retiredConnectionFrames` and dropped. That is A7's
question in the only form available to a family with no generation, and before the trust gate and
throughout pairing a frame's own connection *is* the active one, so `PING`/`PONG` and the pairing
exchange keep working exactly as PROTOCOL §1/§4.5 requires.

Three reachable consequences this closes:

1. **`PONG` contaminated the successor's clock.** `handlePong` took a payload and no connection, and
   every one of its effects is manager-level: `lastPongAtMonoUs` (what `keepaliveLoop` measures the
   link's liveness against), `clock.recordRtt` — **unconditional**, it does not depend on a matching
   pending ping — and the `rttMs` diagnostic. `promote` calls `clock.reset()`, so a retired
   connection's round trip landed in the **successor's fresh** RTT window, which is what
   ARCHITECTURE §7.2's `LEAD = max(120 ms, 4 × rtt_p95)` is computed from. (The pending-ping
   completion itself was already inert: `endConnection` fails every outstanding waiter, and the key is
   a monotonic timestamp.)
2. **`PAIR_CONFIRM` could supply the remote half of PROTOCOL §4.5's two-human gate for a different
   peer.** `PairingExchange` splits that gate into `localConfirmed` and `remoteConfirmed`;
   `PAIR_REQUEST` and `PAIR_RESULT` each cross-check the advertised `identity_spki_sha256` against the
   one their exchange was built for, and `onPairConfirm` is a bare boolean with nothing to check. A
   frame read from a retired connection reached whatever exchange was live, and with this device's
   user then confirming, a pin was written for a peer whose user never confirmed anything. **Measured,
   not argued** — see the regression below.
3. **A stale `PAIR_RESULT` or fatal `ERROR` destroyed the successor's pairing.** Both take
   `failPairing`, which clears the exchange and the six digits and raises a security alert; its
   `endConnection` call then returns immediately because the retired socket is not the active one, so
   the live connection was left open with `pairing` already null — a pairing that can never complete.

`BYE` and `PING` were **structurally safe** already (`endConnection` re-checks `activeSocket !==
socket`; `PING` replies only on its own connection) and are not changed in behaviour by passing
through the same gate. They go through it because one gate in one place is the invariant, and a second
copy of the reasoning per branch is how a future branch gets added without it.

## Consequences

- One model, two names, four families, one gate for the pre-authentication set. A new message family
  gets its provenance by taking `generation` at `deliver`, and there is one obvious place to do it.
- Two new diagnostics counters (`droppedRetiredGeneration` per relay, `retiredConnectionFrames` on the
  manager). They are `@Volatile`/actor-isolated `Int`s incremented in place, matching
  `droppedPreAuthentication`'s existing convention — a concurrent increment can be lost, which is
  acceptable for a diagnostics count and is not relied on for any correctness claim.
- A frame dispatched **late within its own still-live session** is still delivered, tagged that
  session's generation. Being late is not being stale; a fix that dropped every scheduling delay would
  be worse than the defect, and both platforms assert it.
- A frame authorised by a session that has **ended with no successor yet** is now refused, where
  `currentAuthGeneration` would still have matched it. That is a deliberate strengthening on Android,
  and preserves what iOS's `sessionEpoch` already did there.
- `SharedLibraryCoordinator`'s inbound guard is the only Phase 4 behaviour that moved. Every provider
  fence, token, gate and cache rule is byte-for-byte what ADR-023 Amendments A1–A5 left.

## Considered and judged not to be findings

Recorded rather than silently passed over, because "we looked and it was fine" and "we did not look"
are different facts:

- **`ManifestRelay.send` is not generation-fenced on the way out.** `serveManifestRequest` suspends
  (`manifestGenerator.generate()`, then one `send` per page), and `authenticatedWriter()` yields a
  writer for whatever connection is authenticated *now* — so a serve begun under Session A can finish
  by writing pages to Session B. Judged harmless and left alone: the content is **our own** library
  manifest, identical for any peer, and the receiving peer is authenticated, so the only effect is an
  unrequested `MANIFEST_BEGIN`/`PAGE`/`END` that its own `ManifestSyncStateMachine` applies or
  discards. `TransferRelay.send`'s equivalent path *is* fenced, by ADR-023 Amendments A3/A5's
  `ProviderSessionContext`/`stillAuthorised`, because a transfer offer is peer-specific and carries a
  token.
- **`BYE` and `PING` were already structurally safe.** `endConnection` re-checks `activeSocket !==
  socket`, and `handlePing` replies only on the connection its frame came from. They pass through §4's
  gate because one gate in one place is the invariant, not because their behaviour changed.
- **The `PONG` pending-ping completion was already inert.** `endConnection` fails every outstanding
  waiter, and the map key is a monotonic timestamp, so a retired `PONG` could not have completed a
  successor's waiter. It is the *unconditional* `recordRtt` beside it that was the defect — checked,
  not assumed.
- **Phase 5's locally-originated work still reads the live generation, and correctly.** `issue`,
  `playSynchronized`, `mutateQueue`, `applyLeaderMutation`, `rebroadcastAuthoritativeState` and
  `emitCurrentPlaybackState` all authorise *themselves* against what is live, which is what "now"
  means for a user action. ADR-024 Amendment A2 §E already drew that line; this sweep re-walked every
  one of them and found no new instance of the inbound defect.

## Alternatives considered

| Option | Why not |
|---|---|
| Gate **every** family on liveness at the relay, Phase 5 included | Deletes ADR-024 Amendment A6's retired-loss accounting, which exists precisely to observe those frames. §3 |
| Give `VoiceController` and the `AUDIO_STATE` inbox the control generation and let them decide | Drags a control-plane concept into the voice reducer and the §4.4 inbox, where `voice_session_id` and `revision` already answer *their* questions. The relay is the control plane's edge for that family and is the earliest correct point. §2 |
| Have `SharedLibraryCoordinator` keep a cached "generation that owns my state", set at `onSessionBoundary` | The boundary observer runs asynchronously, so a legitimate Session B `MANIFEST_REQUEST` arriving before it had run would be **dropped** — and nothing retries a manifest request. Comparing against live state has no such window. |
| Make iOS's guards `async` and `await` the manager for the live generation | Puts a suspension immediately before a mutation, which is the shape ADR-024 Amendments A4/A5 spent two passes removing. The lock-backed box keeps the read synchronous. |
| Add a `session_id`/generation field to the wire so a frame carries its own | A wire change to solve a local lifetime problem, and a peer-supplied value could not be trusted as authority anyway. The authority is *ours*, recorded at our own read. |

## Verification

Deterministic and mirrored, no sleeps in any assertion:

| Suite | Cases | Seam |
|---|---|---|
| `RetiredSessionProvenanceTest` / `RetiredSessionProvenanceTests` | 10 each | two real TLS 1.3 sessions on one real `ControlSessionManager`; `MANIFEST_*`, `TRANSFER_*`, `VOICE_*`, `AUDIO_STATE`, `PONG` |
| `RetiredConnectionPairingTest` / `RetiredConnectionPairingTests` | 4 each | a real silent connect, a real link loss, then a real first-meeting with an **unknown** peer; `PAIR_CONFIRM`, `PAIR_RESULT`, fatal `ERROR` |
| `SharedLibraryReadProvenanceTest` (Android only — see below) | 5 | the Phase 4 coordinator itself: catalogue and provider state |

**Measured against the pre-fix behaviour**, by reverting only this ADR's guards on unmodified
production sources:

- 8 of 10 in `RetiredSessionProvenanceTest` fail (the two that pass are the positive controls, which
  must pass both ways); iOS 8 of 10, identically, with `generations` recording `[1, 1, 1, 2]` where it
  must record `[2]`;
- all 3 stale cases in `RetiredConnectionPairingTest[s]` fail. The `PAIR_CONFIRM` one fails with the
  trust store containing **peer C** — the pin written without C's user ever confirming;
- 3 of 5 in `SharedLibraryReadProvenanceTest` fail: a Session A `MANIFEST_PAGE` becomes Session B's
  catalogue, and a Session A `TRANSFER_REQUEST` is resolved and served under Session B.

**The park is produced the way A7's is**, and for A7's reason: nothing a test controls can suspend a
coroutine or task between `readFrame()` returning and the dispatch that follows it — that is the point
of the fix. So the two halves of that one step are called as two statements with a **real** session
boundary between them. `currentReadBinding()` is the capture `readLoop` performs and `handleFrame` is
the very function it calls; both are `internal`, reachable only from each platform's own tests.

**iOS has no app-target test bundle**, so `ios/RideLink/SharedLibraryCoordinator.swift` has no
coordinator-level regression — the table's third row is Android-only. On iOS the same defect is pinned
one layer down, at the relay, where the refusal now happens; the coordinator's own guard is
defence-in-depth behind it. This asymmetry is pre-existing (`RideLinkPlatformTests` covers the package,
not the app target) and is recorded as a watch item rather than closed by adding an Xcode test target
in a change that is about provenance.
