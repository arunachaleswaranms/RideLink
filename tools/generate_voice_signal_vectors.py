#!/usr/bin/env python3
"""Generate protocol/vectors/voice-signal/voice_signal_vectors.json.

PROTOCOL §7.4 / §7.5 — the `VOICE_*` message layer: every required field, every wrong type, every
bound, and the two nullable fields.

This is deliberately a **third, independent implementation** of the spec, in a third language,
written from `docs/PROTOCOL.md` rather than from either platform's parser. That is the whole point:
if Kotlin's `VoiceSignalCodec` and Swift's `VoiceSignalCodec` were both ported from each other, a
shared misreading of the spec would pass on both platforms and be discovered on a ride. Edit this
generator, never the JSON.

Run:  python3 tools/generate_voice_signal_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

# PROTOCOL §7.5. Transcribed from the spec table, not imported from either platform.
MAX_SDP_BYTES = 16_384
MAX_CANDIDATE_BYTES = 512
MAX_SDP_MID_BYTES = 64
MAX_MLINE_INDEX = 31
MAX_QUEUED_CANDIDATES = 64

# A fabricated 32-hex generation id. Never a real one: `protocol/vectors/` carries test values only.
VSID = "5e2a9c40b7f13d86e0a4c95b28f7d613"
VSID_OTHER = "0123456789abcdef0123456789abcdef"

# A minimal but structurally realistic offer. Fabricated: no real fingerprint, no real address.
SDP = (
    "v=0\r\n"
    "o=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n"
    "s=-\r\n"
    "t=0 0\r\n"
    "a=group:BUNDLE 0\r\n"
    "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    "c=IN IP4 0.0.0.0\r\n"
    "a=rtcp-mux\r\n"
    "a=mid:0\r\n"
    "a=sendrecv\r\n"
    "a=rtpmap:111 opus/48000/2\r\n"
    "a=fmtp:111 minptime=10;useinbandfec=1\r\n"
    "a=fingerprint:sha-256 "
    "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:"
    "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF\r\n"
    "a=setup:actpass\r\n"
)

CANDIDATE = "candidate:842163049 1 udp 1677729535 192.0.2.11 51234 typ host generation 0"


def utf8_len(s: str) -> int:
    return len(s.encode("utf-8"))


def padded(base: str, target_bytes: int) -> str:
    """ASCII-pad `base` to exactly `target_bytes` UTF-8 bytes.

    Bounds in §7.5 are specified in UTF-8 bytes, so the fixtures are built to an exact byte length
    rather than a character count. Everything here is ASCII, so the two coincide — which is why one
    multi-byte case below exists as well.
    """
    assert utf8_len(base) <= target_bytes, "base already exceeds the target"
    return base + "x" * (target_bytes - utf8_len(base))


def parsed(name: str, msg_type: str, payload: dict, expected: dict) -> dict:
    return {"name": name, "type": msg_type, "payload": payload, "expect": {"parsed": expected}}


def rejected(name: str, msg_type: str, payload: dict, reason: str) -> dict:
    return {"name": name, "type": msg_type, "payload": payload, "expect": {"rejected": reason}}


def build() -> dict:
    rows: list[dict] = []

    # --- VOICE_OFFER / VOICE_ANSWER: identical shape, so every case is generated for both -------
    for msg_type, kind in (("VOICE_OFFER", "Offer"), ("VOICE_ANSWER", "Answer")):
        slug = kind.lower()
        rows.append(
            parsed(
                f"{slug}-minimal-valid",
                msg_type,
                {"voice_session_id": VSID, "sdp": SDP},
                {"kind": kind, "voice_session_id": VSID, "sdp": SDP},
            )
        )
        rows.append(
            parsed(
                f"{slug}-unknown-field-ignored",
                msg_type,
                {"voice_session_id": VSID, "sdp": SDP, "future_field": 7},
                {"kind": kind, "voice_session_id": VSID, "sdp": SDP},
                # §2 rule 1: additive changes are non-breaking, including here.
            )
        )
        rows.append(
            rejected(f"{slug}-missing-voice-session-id", msg_type, {"sdp": SDP}, "MISSING_FIELD")
        )
        rows.append(
            rejected(f"{slug}-missing-sdp", msg_type, {"voice_session_id": VSID}, "MISSING_FIELD")
        )
        rows.append(
            rejected(
                f"{slug}-voice-session-id-wrong-type",
                msg_type,
                {"voice_session_id": 12345, "sdp": SDP},
                "WRONG_FIELD_TYPE",
                # Present but not a JSON string. Deliberately distinguished from absence: "you did
                # not send it" and "you sent a number" are different bugs, and the diagnostics
                # screen is where someone will be looking for the difference. Pinned so both
                # platforms agree on *which* rejection it is, not merely that there was one.
            )
        )
        rows.append(
            rejected(
                f"{slug}-sdp-wrong-type",
                msg_type,
                {"voice_session_id": VSID, "sdp": 42},
                "WRONG_FIELD_TYPE",
            )
        )
        rows.append(
            rejected(
                f"{slug}-voice-session-id-uppercase-hex",
                msg_type,
                {"voice_session_id": VSID.upper(), "sdp": SDP},
                "MALFORMED_VOICE_SESSION_ID",
                # §7.4: uppercase is rejected, not normalised. One canonical form, as for
                # `identity_spki_sha256`.
            )
        )
        rows.append(
            rejected(
                f"{slug}-voice-session-id-too-short",
                msg_type,
                {"voice_session_id": VSID[:-1], "sdp": SDP},
                "MALFORMED_VOICE_SESSION_ID",
            )
        )
        rows.append(
            rejected(
                f"{slug}-voice-session-id-too-long",
                msg_type,
                {"voice_session_id": VSID + "a", "sdp": SDP},
                "MALFORMED_VOICE_SESSION_ID",
            )
        )
        rows.append(
            rejected(
                f"{slug}-voice-session-id-non-hex",
                msg_type,
                {"voice_session_id": "z" * 32, "sdp": SDP},
                "MALFORMED_VOICE_SESSION_ID",
            )
        )
        rows.append(
            rejected(
                f"{slug}-voice-session-id-null",
                msg_type,
                {"voice_session_id": None, "sdp": SDP},
                "WRONG_FIELD_TYPE",
                # Nullable only for VOICE_STATE (§7.4). An offer or answer always names its
                # generation, because it *is* the generation.
            )
        )
        rows.append(
            rejected(f"{slug}-sdp-empty", msg_type, {"voice_session_id": VSID, "sdp": ""}, "SDP_EMPTY")
        )
        rows.append(
            parsed(
                f"{slug}-sdp-at-exact-bound",
                msg_type,
                {"voice_session_id": VSID, "sdp": padded(SDP, MAX_SDP_BYTES)},
                {"kind": kind, "voice_session_id": VSID, "sdp": padded(SDP, MAX_SDP_BYTES)},
            )
        )
        rows.append(
            rejected(
                f"{slug}-sdp-one-byte-over-bound",
                msg_type,
                {"voice_session_id": VSID, "sdp": padded(SDP, MAX_SDP_BYTES + 1)},
                "SDP_TOO_LARGE",
            )
        )

    # A multi-byte character makes byte length and character count disagree, which is the case a
    # UTF-16-length check would get wrong. `é` is 2 UTF-8 bytes.
    multibyte_over = padded(SDP, MAX_SDP_BYTES - 1) + "é"
    assert utf8_len(multibyte_over) == MAX_SDP_BYTES + 1
    assert len(multibyte_over) == MAX_SDP_BYTES
    rows.append(
        rejected(
            "offer-sdp-bound-is-utf8-bytes-not-characters",
            "VOICE_OFFER",
            {"voice_session_id": VSID, "sdp": multibyte_over},
            "SDP_TOO_LARGE",
        )
    )

    # --- VOICE_ICE ------------------------------------------------------------------------------
    rows.append(
        parsed(
            "ice-minimal-valid",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": 0,
            },
            {
                "kind": "IceCandidate",
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": 0,
            },
        )
    )
    rows.append(
        parsed(
            "ice-null-sdp-mid-is-legal",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": None,
                "sdp_mline_index": 0,
            },
            {
                "kind": "IceCandidate",
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": None,
                "sdp_mline_index": 0,
            },
            # §7.4: WebRTC permits a candidate identified by index alone.
        )
    )
    rows.append(
        parsed(
            "ice-absent-sdp-mid-treated-as-null",
            "VOICE_ICE",
            {"voice_session_id": VSID, "candidate": CANDIDATE, "sdp_mline_index": 0},
            {
                "kind": "IceCandidate",
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": None,
                "sdp_mline_index": 0,
            },
        )
    )
    rows.append(
        rejected(
            "ice-sdp-mid-wrong-type",
            "VOICE_ICE",
            {"voice_session_id": VSID, "candidate": CANDIDATE, "sdp_mid": 0, "sdp_mline_index": 0},
            "WRONG_FIELD_TYPE",
        )
    )
    rows.append(
        parsed(
            "ice-sdp-mid-at-exact-bound",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "m" * MAX_SDP_MID_BYTES,
                "sdp_mline_index": 0,
            },
            {
                "kind": "IceCandidate",
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "m" * MAX_SDP_MID_BYTES,
                "sdp_mline_index": 0,
            },
        )
    )
    rows.append(
        rejected(
            "ice-sdp-mid-one-byte-over-bound",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "m" * (MAX_SDP_MID_BYTES + 1),
                "sdp_mline_index": 0,
            },
            "SDP_MID_TOO_LARGE",
        )
    )
    rows.append(
        rejected(
            "ice-candidate-empty",
            "VOICE_ICE",
            {"voice_session_id": VSID, "candidate": "", "sdp_mid": "0", "sdp_mline_index": 0},
            "CANDIDATE_EMPTY",
        )
    )
    rows.append(
        parsed(
            "ice-candidate-at-exact-bound",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": padded(CANDIDATE, MAX_CANDIDATE_BYTES),
                "sdp_mid": "0",
                "sdp_mline_index": 0,
            },
            {
                "kind": "IceCandidate",
                "voice_session_id": VSID,
                "candidate": padded(CANDIDATE, MAX_CANDIDATE_BYTES),
                "sdp_mid": "0",
                "sdp_mline_index": 0,
            },
        )
    )
    rows.append(
        rejected(
            "ice-candidate-one-byte-over-bound",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": padded(CANDIDATE, MAX_CANDIDATE_BYTES + 1),
                "sdp_mid": "0",
                "sdp_mline_index": 0,
            },
            "CANDIDATE_TOO_LARGE",
        )
    )
    rows.append(
        rejected(
            "ice-missing-candidate",
            "VOICE_ICE",
            {"voice_session_id": VSID, "sdp_mid": "0", "sdp_mline_index": 0},
            "MISSING_FIELD",
        )
    )
    rows.append(
        rejected(
            "ice-missing-mline-index",
            "VOICE_ICE",
            {"voice_session_id": VSID, "candidate": CANDIDATE, "sdp_mid": "0"},
            "MISSING_FIELD",
        )
    )
    rows.append(
        rejected(
            "ice-mline-index-quoted-number-is-wrong-type",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": "0",
            },
            "WRONG_FIELD_TYPE",
            # PROTOCOL fields are typed, not stringly-typed. The same rule §2e established for
            # PING/PONG's numeric fields.
        )
    )
    rows.append(
        rejected(
            "ice-mline-index-negative",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": -1,
            },
            "MLINE_INDEX_OUT_OF_RANGE",
        )
    )
    rows.append(
        parsed(
            "ice-mline-index-at-exact-bound",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": MAX_MLINE_INDEX,
            },
            {
                "kind": "IceCandidate",
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": MAX_MLINE_INDEX,
            },
        )
    )
    rows.append(
        rejected(
            "ice-mline-index-one-over-bound",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": MAX_MLINE_INDEX + 1,
            },
            "MLINE_INDEX_OUT_OF_RANGE",
        )
    )
    rows.append(
        rejected(
            "ice-mline-index-absurdly-large",
            "VOICE_ICE",
            {
                "voice_session_id": VSID,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": 2_147_483_647,
            },
            "MLINE_INDEX_OUT_OF_RANGE",
        )
    )
    rows.append(
        rejected(
            "ice-voice-session-id-malformed",
            "VOICE_ICE",
            {"voice_session_id": "nothex", "candidate": CANDIDATE, "sdp_mid": "0", "sdp_mline_index": 0},
            "MALFORMED_VOICE_SESSION_ID",
        )
    )

    # --- VOICE_STATE ----------------------------------------------------------------------------
    for wire_state in ("idle", "negotiating", "connecting", "active", "failed", "closed"):
        rows.append(
            parsed(
                f"state-{wire_state}-with-id",
                "VOICE_STATE",
                {
                    "voice_session_id": VSID,
                    "state": wire_state,
                    "mic_muted": False,
                    "mode": "continuous",
                },
                {
                    "kind": "State",
                    "voice_session_id": VSID,
                    "state": wire_state.upper(),
                    "mic_muted": False,
                    "mode": "CONTINUOUS",
                },
            )
        )
    rows.append(
        parsed(
            "state-negotiating-with-null-id-is-the-answerer-intent",
            "VOICE_STATE",
            {"voice_session_id": None, "state": "negotiating", "mic_muted": False, "mode": "continuous"},
            {
                "kind": "State",
                "voice_session_id": None,
                "state": "NEGOTIATING",
                "mic_muted": False,
                "mode": "CONTINUOUS",
            },
            # §7.3/§7.4: the answerer has no generation to name yet. Whether a null id is
            # *meaningful* for a given state is the negotiation table's job, not the parser's.
        )
    )
    rows.append(
        parsed(
            "state-absent-id-treated-as-null",
            "VOICE_STATE",
            {"state": "idle", "mic_muted": False, "mode": "continuous"},
            {
                "kind": "State",
                "voice_session_id": None,
                "state": "IDLE",
                "mic_muted": False,
                "mode": "CONTINUOUS",
            },
        )
    )
    rows.append(
        parsed(
            "state-mic-muted-true",
            "VOICE_STATE",
            {"voice_session_id": VSID, "state": "active", "mic_muted": True, "mode": "ptt"},
            {
                "kind": "State",
                "voice_session_id": VSID,
                "state": "ACTIVE",
                "mic_muted": True,
                "mode": "PTT",
            },
        )
    )
    for wire_mode in ("continuous", "vox", "ptt"):
        rows.append(
            parsed(
                f"state-mode-{wire_mode}",
                "VOICE_STATE",
                {"voice_session_id": VSID, "state": "active", "mic_muted": False, "mode": wire_mode},
                {
                    "kind": "State",
                    "voice_session_id": VSID,
                    "state": "ACTIVE",
                    "mic_muted": False,
                    "mode": wire_mode.upper(),
                },
            )
        )
    rows.append(
        parsed(
            "state-unknown-state-value-tolerated-as-unknown",
            "VOICE_STATE",
            {
                "voice_session_id": VSID,
                "state": "renegotiating_v2",
                "mic_muted": False,
                "mode": "continuous",
            },
            {
                "kind": "State",
                "voice_session_id": VSID,
                "state": "UNKNOWN",
                "mic_muted": False,
                "mode": "CONTINUOUS",
            },
            # §7.4: an unrecognised enum value is `unknown`, not a malformed frame — the same
            # forward-compatibility rule §4.3.1 gives the audio vocabulary.
        )
    )
    rows.append(
        parsed(
            "state-unknown-mode-value-tolerated-as-unknown",
            "VOICE_STATE",
            {
                "voice_session_id": VSID,
                "state": "active",
                "mic_muted": False,
                "mode": "ptt_remote",
            },
            {
                "kind": "State",
                "voice_session_id": VSID,
                "state": "ACTIVE",
                "mic_muted": False,
                "mode": "UNKNOWN",
            },
            # `ptt_remote` is named in §12 as reserved, so this is the exact forward-compatibility
            # case the reservation anticipates.
        )
    )
    rows.append(
        rejected(
            "state-missing-state",
            "VOICE_STATE",
            {"voice_session_id": VSID, "mic_muted": False, "mode": "continuous"},
            "MISSING_FIELD",
        )
    )
    rows.append(
        rejected(
            "state-missing-mic-muted",
            "VOICE_STATE",
            {"voice_session_id": VSID, "state": "active", "mode": "continuous"},
            "MISSING_FIELD",
        )
    )
    rows.append(
        rejected(
            "state-missing-mode",
            "VOICE_STATE",
            {"voice_session_id": VSID, "state": "active", "mic_muted": False},
            "MISSING_FIELD",
        )
    )
    rows.append(
        rejected(
            "state-mic-muted-quoted-boolean-is-wrong-type",
            "VOICE_STATE",
            {"voice_session_id": VSID, "state": "active", "mic_muted": "true", "mode": "continuous"},
            "WRONG_FIELD_TYPE",
        )
    )
    rows.append(
        rejected(
            "state-mic-muted-numeric-is-wrong-type",
            "VOICE_STATE",
            {"voice_session_id": VSID, "state": "active", "mic_muted": 1, "mode": "continuous"},
            "WRONG_FIELD_TYPE",
        )
    )
    rows.append(
        rejected(
            "state-state-wrong-type",
            "VOICE_STATE",
            {"voice_session_id": VSID, "state": 3, "mic_muted": False, "mode": "continuous"},
            "WRONG_FIELD_TYPE",
        )
    )
    rows.append(
        rejected(
            "state-malformed-id",
            "VOICE_STATE",
            {
                "voice_session_id": VSID_OTHER[:31],
                "state": "active",
                "mic_muted": False,
                "mode": "continuous",
            },
            "MALFORMED_VOICE_SESSION_ID",
        )
    )
    rows.append(
        rejected(
            "state-id-wrong-type",
            "VOICE_STATE",
            {"voice_session_id": 7, "state": "active", "mic_muted": False, "mode": "continuous"},
            "WRONG_FIELD_TYPE",
        )
    )

    # --- not a voice message at all -------------------------------------------------------------
    rows.append(rejected("unknown-voice-type", "VOICE_RENEGOTIATE", {"voice_session_id": VSID}, "UNKNOWN_TYPE"))
    rows.append(rejected("non-voice-type", "PING", {"t1_mono_us": 1}, "UNKNOWN_TYPE"))

    return {
        "_comment": (
            "PROTOCOL §7.4/§7.5 — the VOICE_* message layer. Every row is (type, payload) -> parsed "
            "or rejected, and both platforms' VoiceSignalCodec runs this same file, so a bound "
            "enforced on one platform and not the other is a laptop unit-test failure rather than "
            "something two phones discover on a ride. Generated by "
            "tools/generate_voice_signal_vectors.py — an independent third implementation of the "
            "spec. Edit the generator, never this file."
        ),
        "_invariant": (
            "No row may expect a malformed VOICE_* frame to end the control connection. §7.4: the "
            "framing was intact, so the frame is dropped and the connection survives. Every "
            "'rejected' row is a drop, never a disconnect."
        ),
        "_test_values_only": (
            "Every SDP, candidate, fingerprint and voice_session_id here is fabricated. No real "
            "key, address, exporter output or pairing code appears in protocol/vectors/."
        ),
        "bounds": {
            "MAX_VOICE_SDP_BYTES": MAX_SDP_BYTES,
            "MAX_VOICE_CANDIDATE_BYTES": MAX_CANDIDATE_BYTES,
            "MAX_VOICE_SDP_MID_BYTES": MAX_SDP_MID_BYTES,
            "MAX_VOICE_MLINE_INDEX": MAX_MLINE_INDEX,
            "MAX_QUEUED_VOICE_CANDIDATES": MAX_QUEUED_CANDIDATES,
        },
        "rows": rows,
    }


def main() -> None:
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "voice-signal"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "voice_signal_vectors.json"
    payload = build()
    target.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(payload['rows'])} rows)")


if __name__ == "__main__":
    main()
