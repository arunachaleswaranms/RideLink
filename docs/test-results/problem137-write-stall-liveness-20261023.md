# Problem 137: a stalled control-plane write blocks link-loss detection (investigation)

**Status:** root cause established for the CI failure; **production defect confirmed in the JVM
transport**; not fixed. Reproduced deterministically by
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
