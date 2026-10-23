# Problem 137: a stalled control-plane write blocks link-loss detection

**Status (23 October 2026, second pass): FIXED IN SOFTWARE (Android), pending independent review.**
§§1–4 are the investigation as first recorded, unchanged, including its failure evidence. §§5–9 are
the fix pass: what a blocked write actually does (§5), the fix (§6), the regressions and mutation
results (§7), the runs under CPU load (§8), and the iOS review and remaining risks (§9). See also
ADR-007 Amendment A3.

**Status (investigation, as first recorded):** root cause established for the CI failure;
**production defect confirmed in the JVM transport**; not fixed. Reproduced deterministically by
`android/network/src/test/kotlin/com/ridelink/network/control/WriteStallLivenessTest.kt`, which is
**expected to fail** on current code.
**Date:** 23 October 2026. Base: `main` at `15b49ac`. Android only. iOS's control plane is a
different implementation (an actor, with `PingRequestRegistry`) and was not examined here.

## 1. What was observed

- `PongWriteFailureTest > a stale PONG write failure never retires the successor connection` timed
  out after 15 s waiting for `RECONNECTING` (`PongWriteFailureTest.kt:97`).
  - **CI:** it failed 2 of 2 runs on PR #32 at `b22ce40`, while other heads passed, including `main`
    `413cb51` and PR #27 `f6ce2d4`. PR #32 changes nothing in `network`.
- **Locally, under 12 busy processes:** it failed 7 of 8 runs on unmodified `main` `413cb51` and 4 of
  8 on PR #32's head, with the identical signature. Unloaded, PR #32's head passed 10 of 10.
- **With timestamped instrumentation added** (not committed), it passed 8 of 8 under load. The
  printing changes the timing, which is itself a sign the outcome depends on an interleaving.

## 2. The interleaving (instrumented trace, passing run)

The test parks this side's `PONG` write on connection 1 and then closes the peer's end.

In passing runs the peer is dropped about 1 ms after the park. This side's read loop reads
`ConnectionClosed` at once, `endConnection` runs, `LinkLost` is emitted, and `RECONNECTING` follows
14 ms later.

Right after authentication, though, the peer is running its ARCHITECTURE §7.1 clock burst: 11 `PING`s,
50 ms apart. If one of them reaches this side **between the park and the drop**:
1. The read loop calls `handleFrame`, inline, then `handlePing`, then `socket.writeFrame`.
2. That write waits on the connection's `writeLock`, which the parked write holds.
3. So the read loop never returns to `readFrame()` and never sees the close.

CPU load widens the park-to-drop window, which is why the failure tracks load.

## 3. Why this is a production defect, not only test timing

The session should still have noticed. PROTOCOL §1's keepalive declares the link lost after 6 s with
no `PONG`, independently of the read loop. In every failing run it waited 15 s and did not.

`keepaliveLoop` calls `sendPingAndAwait`. That method registers the waiter and then calls
`socket.writeFrame`, which waits on the same `writeLock`, **outside** its `withTimeout`, which bounds
only the wait for the `PONG`. So the keepalive is stuck behind the stalled write too.

**Deterministic reproduction** (`WriteStallLivenessTest`): the peer sends one `PING` after the park
and then goes away. It asserts the session reaches `RECONNECTING` within 9 s (6 s threshold plus
slack).
- **Unmodified `main`:** 3 of 3 failures, unloaded. The session is still `CONNECTED` 9 s after the
  peer left.
- **Causality experiment** (scratch, reverted): with the keepalive's write moved inside its timeout,
  and nothing else changed, the same test **passes**, with the loss detected after 6.9 s. The
  keepalive being blocked is therefore a cause, and bounding its write is enough to restore
  detection in this scenario.

**Exposure on a phone.** It needs a control-plane write that blocks, rather than fails, while the peer
is gone. Small frames go straight into the kernel's send buffer, so the realistic trigger is a large
frame to a peer that has stopped reading — for example a `MANIFEST_PAGE` of up to 256 KiB (rule 11's
cap) during a silent Wi-Fi loss, which can fill the send buffer. Detection then waits for TCP itself
to give up, which takes minutes on Linux/Android defaults, instead of 6 s. This is inferred from the
code path and **not measured on a device**.

## 4. What is not decided here

- **The fix.** Candidates, one variable at a time:
  - (a) bound the keepalive's write by its timeout, which is the experiment above;
  - (b) answer `PING` off the read loop, so a stalled write cannot stop it reading;
  - (c) on a keepalive loss, close the socket so the stalled write fails at once.

  (a) restores the PROTOCOL §1 bound in the reproduction. (b) also lets the read loop see a clean
  close at once. Each needs its own review against ADR-025's per-connection binding of `PING`/`PONG`
  answers.
- **`PongWriteFailureTest` is not changed, skipped or masked.** It fails under load because of this
  defect, and should pass reliably once (a) or (b) lands.

## 5. What a blocked write actually does (measured, fix pass)

The fix had to rest on measured socket behaviour, not on the assumption that a coroutine timeout ends
blocking I/O. `BlockingWriteSemanticsTest` (JVM, `conscrypt-openjdk-uber` 2.6.3) and
`BlockingWriteSemanticsDeviceTest` (instrumented, the API 36 emulator's platform Conscrypt; not a
phone) open a real TLS 1.3 pair through the production `TlsControlChannel`, stop the peer reading, and
write 200 KiB frames until one blocks in the kernel. Both the accepted and the dialled socket were
measured, because one is layered over a plain TCP socket and the other is not.

Aggregated over the exploratory runs and the final suite (the JVM suite 5 times, the device suite
twice). In the final suite, `abort()` is measured on its own, and a separate close-first case records
`close()` and then cleans up with `abort()`:

| Observation | JVM (`org.conscrypt.Java8EngineSocket`) | API 36 emulator (`com.android.org.conscrypt.Java8EngineSocket`) |
|---|---|---|
| Frames before the block | 2–9 | 57–70 |
| Lock waiter, 500 ms timeout | ended at 505–518 ms, every run | 506–509 ms, every run |
| Kernel-blocked writer, 2 s after `cancel()` | still running, every run | still running, every run |
| `close()` alone, from another thread, 2 s later | **blocked in 14 of 19** observations; returned in 5. The writer was still stuck at the 2 s mark in 19 of 19; in one returned case it ended shortly after | **blocked in 4 of 7**; returned in 3. The writer was still stuck **7 of 7** |
| `abort()` (`shutdownOutput()`, then close) | returned in 0–5 ms; the writer ended 0–15 ms later, `Broken pipe` (or `Socket closed` after a prior close); any waiting close returned | returned in 0–10 ms; the writer ended 0–12 ms later; any waiting close returned |

The thread dump of the blocked `close()` (JVM) shows why: `ConscryptEngineSocket.close` →
`drainOutgoingQueue` → the socket's output → `NioSocketImpl.write` → waiting for the NIO write lock
the stalled writer holds. `shutdownOutput()` on a Conscrypt socket goes straight to
`java.net.Socket.shutdownOutput()` (on the layered socket, to the underlying one), which takes no
write lock.

Two things follow. A timeout around the keepalive's `PING` is necessary but not sufficient: if that
`PING` is itself the blocked write, it never returns. And the existing retirement, `close()` before
`LinkLost`, could itself never return. Android runs it on the main thread (`AppContainer`'s
`appScope` is `Dispatchers.Main`), so there a blocking `close()` would also stall the UI. That last
point is inferred from the code, not observed on a device.

## 6. The fix (ADR-007 Amendment A3)

1. **`PingWaiters.awaitRoundTrip`:** the timeout now bounds the write as well as the `PONG`. The
   waiter is still registered before the write, and every exit, cancellation included, removes it.
2. **`keepaliveLoop`:** every 2 s it reads the time of the last `PONG` and decides. Each `PING` runs
   as a child, at most one in flight, and the loop never waits for it. The 2 s cadence and 6 s
   threshold are PROTOCOL §1's, unchanged.
3. **`endConnection`:** for `LinkLossReason.NETWORK` only, the socket is retired with
   `ControlSocket.abort()`, which shuts down the send direction, then closes. Duplicate-connection,
   `BYE` and user endings still close gracefully, because PROTOCOL §4.2 requires `BYE`, then
   `close_notify`, then FIN of a duplicate loser.

Unchanged, on purpose:
- **Read-frame binding (ADR-024 A7 / ADR-025).** `readLoop` and `handleFrame` are not touched. A
  `PING` is still answered only on the connection it was read from.
- **PING/PONG guards.** `handlePong`'s plausibility check and the pre-authentication allowlist are
  not touched.
- **Which socket is retired.** `endConnection` still acts only while `activeSocket === socket`, and
  it is handed the socket the keepalive was started for. A stale failure released after a successor
  authenticates is a no-op. `PongWriteFailureTest`'s successor case pins this and passed every run
  under load.
- **Reconnect.** No new mechanism. `LinkLost(NETWORK)` drives the existing ladder.
- **Orphaned waiters.** Every exit from `awaitRoundTrip` removes its entry, and `failAll` still
  runs in `endConnection`. Cancelling the keepalive cancels its child `PING`. New
  `PingWaitersTest` cases cover a timeout and a cancellation while waiting for the write lock.

## 7. Regressions and mutation results

`WriteStallLivenessTest` now has three cases, one per part of the fix. Each asserts `RECONNECTING`
within 9 s of the peer going silent:
1. this side's `PONG` is parked and the read loop waits behind it (the original reproduction);
2. the keepalive's **own** `PING` is the parked write;
3. a real TLS write is blocked in the kernel by a peer that has stopped reading and has not closed.

Each part was reverted alone, and then all three together (scratch, restored after every run):

| Reverted | Bounded-write unit case | Case 1 | Case 2 | Case 3 |
|---|---|---|---|---|
| `PING` write back outside its timeout | **fail** | pass | pass | pass |
| Keepalive awaits each `PING` inline | pass | pass | **fail** (still `CONNECTED` at 11.9 s) | pass |
| `close()` instead of `abort()` | pass | pass | pass | **fail** |
| All three | **fail** | **fail** | **fail** | **fail** |
| None (the fix) | pass | pass | pass | pass |

Case 1 passes with only part 1 reverted, because part 2 alone keeps the decision independent of the
`PING`. Part 1 is pinned by `PingWaitersTest` instead. It still matters: it bounds each clock-burst
`PING` and stops a `PING` queued behind a stall from staying in flight.

## 8. Under CPU load

Twelve busy processes on a 10-CPU machine, `--rerun` each time, running `WriteStallLivenessTest`
(3 cases), `PongWriteFailureTest` (2) and `PingRaceAndReconnectTest` (4):

| Code | Runs | Result |
|---|---|---|
| The fix | 8 | **72 of 72 pass.** `PongWriteFailureTest` stale case: mean 8.3 s, max 11.5 s, against its 15 s wait. Liveness cases 1 and 2: max 11.2 s *test time*, including connection setup |
| The fix reverted (branch head before this pass) | 4 | **All 12 liveness runs fail, and `PongWriteFailureTest`'s stale case fails 4 of 4** (each ~93 s, because teardown then waits for the parked write; see §9). The other cases pass |

The stale case is the previously intermittent regression. Unloaded, on the tree merged with `main`
`db6858b`, every Android unit suite passed (core 479, app 374, network 332, audio 33, data 48; 0
failures), and ktlint, detekt, lint and `assembleDebug` were clean.

## 9. iOS review, and remaining risks

**iOS (reviewed, not changed).** `ControlConnection.writeFrame` is `NWConnection.send` with a
`contentProcessed` completion. It blocks no thread and holds no lock. `sendPingAndAwait` registers
the waiter, arms a timeout `Task` independently of the write, and runs the write in its own `Task`.
So a `PING` returns within its 3 s timeout whatever the write does, and the keepalive loop reaches
its threshold check. `endConnection` cancels the `NWConnection`, which completes pending sends with an
error, and acts only on the active connection. The read loop does answer `PING` inline, and its
`await` on a stalled send can stop it reading, as on Android, but the keepalive is not stuck behind
it. No reproduction was written for iOS. The finding rests on reading the code.

**Remaining risks:**
- **No phone has run any of this.** The emulator shares the platform Conscrypt class with the JVM
  result, but the OnePlus's TLS stack and kernel buffers are unmeasured.
- **`shutdown()`'s `BYE` write can wait behind a stalled write**, on both platforms (Android: the
  write lock; iOS: the send completion). That is teardown, not loss detection, and is unchanged. It
  showed up as ~93 s test teardowns on the unfixed code, and was not reproduced in isolation. It
  bears on ADR-026's `TeardownComplete`, which awaits `shutdown()`. It should be a separate problem
  row if confirmed.
- **The read loop still answers `PING` inline** (candidate (b) in §4), so during a stall it cannot
  see a clean close until the keepalive retires the connection. Detection is bounded by the keepalive
  (≤ 8 s after the last `PONG`), not immediate.
- **iOS's loss check runs every 2 s plus up to its 3 s `PING` timeout**, so its bound is looser than
  Android's. No stall is involved and nothing here changes it.
- **A network-loss retirement no longer sends `close_notify`.** A peer that is still there sees a TLS
  stream end without one. Both read loops already treat that as a closed connection. Not exercised
  against the iOS implementation over a real socket.
