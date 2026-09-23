#!/usr/bin/env python3
"""Compares the two halves of Phase 8's cross-platform interop gate.

Every assertion here is a claim *neither implementation can make on its own*: it is about the two
agreeing. The protocol itself cannot check the six digits — PROTOCOL §4.5 has two humans compare
them out loud — so this is the only place that comparison exists.

Exit status 0 means the two implementations completed one real session together.
"""
import json
import pathlib
import sys

FAILURES = []


def check(condition, message):
    print(("  PASS  " if condition else "  FAIL  ") + message)
    if not condition:
        FAILURES.append(message)


def main(directory):
    root = pathlib.Path(directory)
    reports = {}
    for name in ("ios", "android"):
        path = root / f"{name}-report.json"
        if not path.exists():
            print(f"  FAIL  {name} produced no report ({path})")
            FAILURES.append(f"{name} report missing")
            continue
        reports[name] = json.loads(path.read_text())
    if len(reports) != 2:
        return 1

    ios, android = reports["ios"], reports["android"]
    print("cross-platform session comparison")
    check(ios.get("ok") is True and android.get("ok") is True,
          "both halves completed their transcript")

    # PROTOCOL §4.5 and ADR-018: the exporter-derived six digits, computed independently on two
    # stacks in two languages. This is the assertion the protocol structurally cannot make.
    check(ios.get("sas6") and ios.get("sas6") == android.get("sas6"),
          f"the six-digit pairing codes match ({ios.get('sas6')} == {android.get('sas6')})")
    check(ios.get("trustedAfterPairing") == 1 and android.get("trustedAfterPairing") == 1,
          "each side persisted exactly one pin for the other")
    for name, report in reports.items():
        check(report.get("remotePeerId") and
              report.get("remotePeerId") == report.get("connectedRemotePeerId"),
              f"{name}: the peer it showed the code for is the peer it authenticated "
              f"({report.get('remotePeerId')})")
    check(ios.get("remotePeerId") != android.get("remotePeerId"),
          "and the two halves are genuinely two different peers")

    # ADR-010: exactly one leader, decided by the handshake and agreed across the wire.
    check(isinstance(ios.get("isLocalLeader"), bool)
          and ios.get("isLocalLeader") != android.get("isLocalLeader"),
          f"exactly one peer leads (ios={ios.get('isLocalLeader')}, android={android.get('isLocalLeader')})")
    check(ios.get("sessionId") == android.get("sessionId"),
          f"both agree on one session_id ({ios.get('sessionId')})")

    # ARCHITECTURE §7.1: both estimators accepted a window from the real PING/PONG burst.
    check(ios.get("clockReady") is True and android.get("clockReady") is True,
          f"both clock estimators became ready (rtt_p95: ios={ios.get('rttP95Us')}us, "
          f"android={android.get('rttP95Us')}us)")

    # PROTOCOL §5/§9/§10: each side's codec decoded what the other's encoded.
    check(android.get("sentPlay") is True and "seq=5" in str(ios.get("receivedPlay", "")),
          f"the Android PLAY decoded on iOS: {ios.get('receivedPlay')}")
    check(ios.get("sentQueueSnapshot") is True
          and "rev=7" in str(android.get("receivedQueueSnapshot", "")),
          f"the iOS QUEUE_SNAPSHOT decoded on Android: {android.get('receivedQueueSnapshot')}")
    check(ios.get("sentStateRequest") is True and android.get("receivedStateRequest") is True,
          "the iOS STATE_REQUEST reached the Android half")
    check(android.get("sentStateSnapshot") is True
          and "seq=5" in str(ios.get("receivedStateSnapshot", "")),
          f"the Android STATE_SNAPSHOT decoded on iOS: {ios.get('receivedStateSnapshot')}")

    # Reconnect: silent re-authentication on a stored pin, and a strictly greater generation.
    for name, report in reports.items():
        check(report.get("reconnectAuthGeneration", 0) > report.get("authGeneration", 0),
              f"{name}: the reconnect minted a strictly greater authentication generation "
              f"({report.get('authGeneration')} -> {report.get('reconnectAuthGeneration')})")
    check(ios.get("pairingRequiredCount") == 1 and android.get("pairingPromptCount") == 1,
          "the reconnect authenticated silently — no second six-digit prompt on either side")
    check(ios.get("sentPlaybackStateAfterReconnect") is True
          and "gen=" in str(android.get("receivedPlaybackStateAfterReconnect", "")),
          f"and a PLAYBACK_STATE crossed under the successor generation: "
          f"{android.get('receivedPlaybackStateAfterReconnect')}")

    if FAILURES:
        print(f"\n{len(FAILURES)} comparison(s) failed")
        return 1
    print("\nall cross-platform comparisons passed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
