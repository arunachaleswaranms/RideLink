#!/usr/bin/env python3
"""Generate protocol/vectors/transfer-fsm/transfer_fsm_vectors.json.

The Phase 4 transfer state machine (ADR-023 / `docs/PROTOCOL.md` §8.2): one transfer's lifecycle,
`(status, event) -> (status, actions)`, pure and mirrored like `VoiceNegotiation`
(`voice-fsm/`) and `IntercomTransmission` (`intercom/`). Models the requester side, since that is
what drives the UI (§27 of the brief) — the provider side is the mirror image (serve instead of
request) and does not need its own table: it has no state of its own beyond "am I currently
serving one transfer," which the brief's single-active-transfer rule already bounds.

An independent third transcription, not a port of either platform's `TransferReducer`. Edit this
generator, never the JSON.

Run:  python3 tools/generate_transfer_fsm_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

STATUSES = ["IDLE", "QUEUED", "NEGOTIATING", "TRANSFERRING", "VERIFYING", "COMPLETE", "FAILED", "CANCELLED"]
TERMINAL = {"COMPLETE", "FAILED", "CANCELLED"}
ACTIVE = {"NEGOTIATING", "TRANSFERRING", "VERIFYING"}  # has an open bulk connection / outstanding request


def apply(status: str, event: dict) -> tuple[str, list[dict], str | None]:
    """Returns (new_status, actions, error). error is set only when new_status == FAILED."""
    kind = event["kind"]

    if status in TERMINAL:
        # §43: terminal states are terminal for that transfer generation. A late event is a no-op.
        return status, [], None

    if kind == "Enqueued":
        if status != "IDLE":
            return status, [], None
        return "QUEUED", [{"kind": "NotifyUi", "status": "QUEUED"}], None
    if kind == "Dequeued":
        if status != "QUEUED":
            return status, [], None
        return "NEGOTIATING", [{"kind": "SendTransferRequest"}, {"kind": "NotifyUi", "status": "NEGOTIATING"}], None
    if kind == "OfferReceived":
        if status != "NEGOTIATING":
            return status, [], None
        return "TRANSFERRING", [{"kind": "OpenBulkConnection"}, {"kind": "NotifyUi", "status": "TRANSFERRING"}], None
    if kind == "OfferRejected":
        if status != "NEGOTIATING":
            return status, [], None
        return "FAILED", [{"kind": "NotifyUi", "status": "FAILED", "error": event["error"]}], event["error"]
    if kind == "BytesReceived":
        if status != "TRANSFERRING":
            return status, [], None
        return "TRANSFERRING", [{"kind": "WriteChunkToPart"}, {"kind": "ReportProgress", "bytes": event["bytes"]}], None
    if kind == "SizeMismatchDetected":
        if status != "TRANSFERRING":
            return status, [], None
        return "FAILED", [{"kind": "DeletePartFile"}, {"kind": "CloseBulkConnection"}, {"kind": "NotifyUi", "status": "FAILED", "error": "SIZE_MISMATCH"}], "SIZE_MISMATCH"
    if kind == "AllBytesReceived":
        if status != "TRANSFERRING":
            return status, [], None
        return "VERIFYING", [{"kind": "ComputeHashFromDisk"}, {"kind": "NotifyUi", "status": "VERIFYING"}], None
    if kind == "HashVerified":
        if status != "VERIFYING":
            return status, [], None
        if event["matches"]:
            return "COMPLETE", [{"kind": "PromoteCacheEntry"}, {"kind": "CloseBulkConnection"}, {"kind": "NotifyUi", "status": "COMPLETE"}], None
        return "FAILED", [{"kind": "DeletePartFile"}, {"kind": "CloseBulkConnection"}, {"kind": "NotifyUi", "status": "FAILED", "error": "HASH_MISMATCH"}], "HASH_MISMATCH"
    if kind == "IoErrorDetected":
        if status not in ("TRANSFERRING", "VERIFYING"):
            return status, [], None
        return "FAILED", [{"kind": "DeletePartFile"}, {"kind": "CloseBulkConnection"}, {"kind": "NotifyUi", "status": "FAILED", "error": "IO_ERROR"}], "IO_ERROR"
    if kind == "DiskFullDetected":
        if status != "TRANSFERRING":
            return status, [], None
        return "FAILED", [{"kind": "DeletePartFile"}, {"kind": "CloseBulkConnection"}, {"kind": "NotifyUi", "status": "FAILED", "error": "DISK_FULL"}], "DISK_FULL"
    if kind == "ConnectionLost":
        if status not in ACTIVE:
            return status, [], None
        actions = [{"kind": "NotifyUi", "status": "FAILED", "error": "CONNECTION_LOST"}]
        if status in ("TRANSFERRING", "VERIFYING"):
            actions = [{"kind": "DeletePartFile"}, {"kind": "CloseBulkConnection"}] + actions
        return "FAILED", actions, "CONNECTION_LOST"
    if kind == "SessionInvalidated":
        # ADR-023 §3: a control-session/generation mismatch. Never resurrected in a new session.
        if status not in ACTIVE and status != "QUEUED":
            return status, [], None
        actions = [{"kind": "NotifyUi", "status": "FAILED", "error": "NOT_AUTHORIZED"}]
        if status in ("TRANSFERRING", "VERIFYING"):
            actions = [{"kind": "DeletePartFile"}, {"kind": "CloseBulkConnection"}] + actions
        return "FAILED", actions, "NOT_AUTHORIZED"
    if kind == "Cancelled":
        if status in ("IDLE",):
            return status, [], None
        actions = [{"kind": "NotifyUi", "status": "CANCELLED"}]
        if status in ("TRANSFERRING", "VERIFYING"):
            actions = [{"kind": "DeletePartFile"}, {"kind": "CloseBulkConnection"}] + actions
        return "CANCELLED", actions, None
    raise AssertionError(f"unknown event kind {kind}")


def row(name: str, status: str, event: dict) -> dict:
    new_status, actions, error = apply(status, event)
    return {
        "name": name,
        "status": status,
        "event": event,
        "expect": {"status": new_status, "actions": actions, "error": error},
    }


def build() -> list[dict]:
    rows: list[dict] = []

    # --- happy path, one event at a time ---------------------------------------------------------
    rows.append(row("idle-enqueued-becomes-queued", "IDLE", {"kind": "Enqueued"}))
    rows.append(row("queued-dequeued-sends-request", "QUEUED", {"kind": "Dequeued"}))
    rows.append(row("negotiating-offer-received-opens-bulk-connection", "NEGOTIATING", {"kind": "OfferReceived", "size_bytes": 10_000_000, "chunk_count": 153}))
    rows.append(row("transferring-bytes-received-reports-progress-and-stays-transferring", "TRANSFERRING", {"kind": "BytesReceived", "bytes": 65536}))
    rows.append(row("transferring-all-bytes-received-moves-to-verifying", "TRANSFERRING", {"kind": "AllBytesReceived"}))
    rows.append(row("verifying-hash-matches-completes-and-promotes", "VERIFYING", {"kind": "HashVerified", "matches": True}))

    # --- failures at each stage they can occur ---------------------------------------------------
    rows.append(row("negotiating-offer-rejected-not-found", "NEGOTIATING", {"kind": "OfferRejected", "error": "NOT_FOUND"}))
    rows.append(row("negotiating-offer-rejected-not-authorized", "NEGOTIATING", {"kind": "OfferRejected", "error": "NOT_AUTHORIZED"}))
    rows.append(row("negotiating-offer-rejected-file-changed", "NEGOTIATING", {"kind": "OfferRejected", "error": "FILE_CHANGED"}))
    rows.append(row("transferring-size-mismatch-fails-and-deletes-part", "TRANSFERRING", {"kind": "SizeMismatchDetected"}))
    rows.append(row("verifying-hash-mismatch-fails-and-deletes-part-never-promotes", "VERIFYING", {"kind": "HashVerified", "matches": False}))
    rows.append(row("transferring-io-error-fails-and-deletes-part", "TRANSFERRING", {"kind": "IoErrorDetected"}))
    rows.append(row("verifying-io-error-fails-and-deletes-part", "VERIFYING", {"kind": "IoErrorDetected"}))
    rows.append(row("transferring-disk-full-fails-and-deletes-part", "TRANSFERRING", {"kind": "DiskFullDetected"}))

    # --- connection loss / session invalidation, across every active state -----------------------
    for status in ("NEGOTIATING", "TRANSFERRING", "VERIFYING"):
        rows.append(row(f"{status.lower()}-connection-lost-fails-cleanly", status, {"kind": "ConnectionLost"}))
        rows.append(row(f"{status.lower()}-session-invalidated-fails-not-authorized", status, {"kind": "SessionInvalidated"}))
    rows.append(row("queued-session-invalidated-fails-not-authorized", "QUEUED", {"kind": "SessionInvalidated"}))
    rows.append(row("idle-connection-lost-is-a-no-op", "IDLE", {"kind": "ConnectionLost"}))

    # --- cancellation, from every non-terminal, non-idle state -------------------------------------
    for status in ("QUEUED", "NEGOTIATING", "TRANSFERRING", "VERIFYING"):
        rows.append(row(f"{status.lower()}-cancelled-moves-to-cancelled", status, {"kind": "Cancelled"}))
    rows.append(row("idle-cancelled-is-a-no-op", "IDLE", {"kind": "Cancelled"}))

    # --- terminal states never resurrect (§43) -----------------------------------------------------
    for status in ("COMPLETE", "FAILED", "CANCELLED"):
        for event in (
            {"kind": "BytesReceived", "bytes": 100},
            {"kind": "HashVerified", "matches": True},
            {"kind": "Cancelled"},
            {"kind": "ConnectionLost"},
            {"kind": "Enqueued"},
        ):
            rows.append(row(f"{status.lower()}-{event['kind'].lower()}-is-a-no-op-terminal-state", status, event))

    # --- events out of their expected state are no-ops, not crashes --------------------------------
    rows.append(row("idle-offer-received-is-a-no-op", "IDLE", {"kind": "OfferReceived", "size_bytes": 1, "chunk_count": 1}))
    rows.append(row("negotiating-bytes-received-is-a-no-op", "NEGOTIATING", {"kind": "BytesReceived", "bytes": 1}))
    rows.append(row("transferring-hash-verified-is-a-no-op", "TRANSFERRING", {"kind": "HashVerified", "matches": True}))
    rows.append(row("verifying-all-bytes-received-is-a-no-op", "VERIFYING", {"kind": "AllBytesReceived"}))
    rows.append(row("queued-offer-received-is-a-no-op", "QUEUED", {"kind": "OfferReceived", "size_bytes": 1, "chunk_count": 1}))

    return rows


def main() -> None:
    rows = build()
    names = [r["name"] for r in rows]
    assert len(names) == len(set(names)), "duplicate row names"
    payload = {
        "_comment": (
            "The Phase 4 transfer state machine (ADR-023, PROTOCOL §8.2): (status, event) -> "
            "(status, actions). Both platforms' TransferReducer runs this same file, so a stray "
            "resume-after-terminal or a missed cleanup action is a laptop unit-test failure rather "
            "than an orphaned .part file discovered on a ride. Generated by "
            "tools/generate_transfer_fsm_vectors.py — an independent third transcription of the "
            "spec. Edit the generator, never this file."
        ),
        "_invariants": [
            "Terminal states (COMPLETE, FAILED, CANCELLED) never transition again: every event "
            "applied to one of them yields the same status and an empty action list.",
            "PromoteCacheEntry appears only on a HashVerified{matches:true} row from VERIFYING — "
            "there is no other path to COMPLETE.",
            "DeletePartFile appears on every row that fails or cancels out of TRANSFERRING or "
            "VERIFYING, and never on a row that reaches COMPLETE.",
            "error is non-null exactly on rows whose resulting status is FAILED, and null "
            "everywhere else.",
            "SessionInvalidated and ConnectionLost are no-ops from IDLE (nothing to lose yet) but "
            "fail QUEUED/NEGOTIATING/TRANSFERRING/VERIFYING.",
        ],
        "statuses": STATUSES,
        "rows": rows,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "transfer-fsm"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "transfer_fsm_vectors.json"
    target.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
