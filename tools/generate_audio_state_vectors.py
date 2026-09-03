#!/usr/bin/env python3
"""Generate protocol/vectors/audio-state/audio_state_vectors.json.

PROTOCOL §4.4 / ADR-016 — the `AUDIO_STATE` message: its exact field set, its bounds, its
`media_quality` derivation, and the monotonic `revision` rule on both the sending and the
receiving side.

Like every other generator in this directory, this is a **third, independent implementation**,
written from `docs/PROTOCOL.md` §4.4 and §4.3.1 rather than ported from either platform's codec.
That independence is the only thing that makes the vectors evidence rather than a restatement.

The privacy invariant this file defends is ADR-016's: **no platform vocabulary reaches the wire.**
Every value below is drawn from §4.3.1's closed vocabulary, and `_invariants` states outright that
no `a2dp`, `hfp`, `sco`, `AVAudioSession` or `AudioManager` string may appear anywhere in the file —
which both platforms' tests then assert against the file itself.

Edit this generator, never the JSON.

Run:  python3 tools/generate_audio_state_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

MAX_SAMPLE_RATE_HZ = 768_000
MAX_REVISION = 9_007_199_254_740_991  # 2^53 - 1; see AudioStateCodec.MAX_REVISION for why

FIELD_ORDER = [
    "revision",
    "endpoint_class",
    "microphone_open",
    "effective_output_profile",
    "effective_input_profile",
    "effective_output_sample_rate_hz",
    "effective_input_sample_rate_hz",
    "media_quality",
    "route_state",
    "intercom_mode",
    "confidence",
]

# §4.3.1's closed vocabularies, transcribed.
ENDPOINT_CLASSES = ["bluetooth", "wired", "builtin_speaker", "builtin_earpiece", "other", "unknown"]
PROFILES = [
    "media_stereo",
    "duplex_narrowband",
    "duplex_wideband",
    "duplex_wide_stereo",
    "builtin",
    "none",
    "unknown",
]
ROUTE_STATES = ["stable", "transitioning"]
INTERCOM_MODES = ["continuous", "vox", "ptt", "disabled"]
CONFIDENCES = ["measured", "assumed", "unknown"]

# The keys the privacy scan covers: the *data*, not the prose. `_comment` and `_invariants` name the
# forbidden vocabulary in order to forbid it, so scanning them would trip on the explanation itself.
SCANNED_KEYS = ["bounds", "field_order", "vocabulary", "encode", "parse", "media_quality", "publisher", "inbox"]

# Forbidden anywhere in the scanned data. ADR-016's whole point, expressed as data so that both
# platforms' tests can enforce it against this file rather than trusting a reviewer to notice.
FORBIDDEN_SUBSTRINGS = [
    "a2dp",
    "hfp",
    "sco",
    "avaudiosession",
    "audiomanager",
    "airpods",
    "helmet",
    "bluetoothhfp",
    "setcommunicationdevice",
]


def media_quality_for(effective_output_profile: str) -> str:
    """ADR-016 Amendment A1, written from the amendment rather than read off a reducer.

    `reduced` exactly when the effective output profile is a **narrowed** duplex profile.
    `duplex_wide_stereo` and `builtin` are duplex but not narrowed, so they are `full` — that is the
    correction Amendment A1 made to the original "duplex and not wide stereo" wording, which
    contradicted §4.4's own representable-states table for `builtin`.
    """
    if effective_output_profile == "none":
        return "unavailable"
    if effective_output_profile == "unknown":
        return "unknown"
    if effective_output_profile in ("duplex_narrowband", "duplex_wideband"):
        return "reduced"
    return "full"


def snapshot(
    endpoint_class: str = "bluetooth",
    microphone_open: bool = False,
    effective_output_profile: str = "media_stereo",
    effective_input_profile: str = "none",
    effective_output_sample_rate_hz: int | None = 48_000,
    effective_input_sample_rate_hz: int | None = None,
    route_state: str = "stable",
    profile_coupling: str = "input_forces_output",
    confidence: str = "assumed",
    interrupted: bool = False,
    last_change_reason: str = "UNKNOWN",
    last_transition_duration_us: int | None = None,
) -> dict:
    """An `AudioRouteSnapshot`, which is a **superset** of the §4.4 message.

    The last three fields are diagnostics §4.4's field table does not carry. Rows below use that
    on purpose: changing only a diagnostic field must not produce a new `revision`, because nothing
    the peer can see has changed.
    """
    return {
        "endpoint_class": endpoint_class,
        "microphone_open": microphone_open,
        "effective_output_profile": effective_output_profile,
        "effective_input_profile": effective_input_profile,
        "effective_output_sample_rate_hz": effective_output_sample_rate_hz,
        "effective_input_sample_rate_hz": effective_input_sample_rate_hz,
        "route_state": route_state,
        "profile_coupling": profile_coupling,
        "confidence": confidence,
        "interrupted": interrupted,
        "last_change_reason": last_change_reason,
        "last_transition_duration_us": last_transition_duration_us,
    }


def message_from(revision: int, snap: dict, intercom_mode: str) -> dict:
    return {
        "revision": revision,
        "endpoint_class": snap["endpoint_class"],
        "microphone_open": snap["microphone_open"],
        "effective_output_profile": snap["effective_output_profile"],
        "effective_input_profile": snap["effective_input_profile"],
        "effective_output_sample_rate_hz": snap["effective_output_sample_rate_hz"],
        "effective_input_sample_rate_hz": snap["effective_input_sample_rate_hz"],
        "media_quality": media_quality_for(snap["effective_output_profile"]),
        "route_state": snap["route_state"],
        "intercom_mode": intercom_mode,
        "confidence": snap["confidence"],
    }


# --- §4.4's own representable-states table, row for row ----------------------------------------

REPRESENTABLE = [
    (
        "music-only-bluetooth",
        snapshot(
            endpoint_class="bluetooth",
            microphone_open=False,
            effective_output_profile="media_stereo",
            effective_input_profile="none",
            effective_output_sample_rate_hz=48_000,
        ),
        "disabled",
    ),
    (
        "intercom-active-bluetooth",
        snapshot(
            endpoint_class="bluetooth",
            microphone_open=True,
            effective_output_profile="duplex_wideband",
            effective_input_profile="duplex_wideband",
            effective_output_sample_rate_hz=16_000,
            effective_input_sample_rate_hz=16_000,
        ),
        "ptt",
    ),
    (
        "wired-headset",
        snapshot(
            endpoint_class="wired",
            microphone_open=True,
            effective_output_profile="duplex_wide_stereo",
            effective_input_profile="duplex_wide_stereo",
            effective_output_sample_rate_hz=48_000,
            effective_input_sample_rate_hz=48_000,
            profile_coupling="independent",
        ),
        "continuous",
    ),
    (
        "nothing-attached",
        snapshot(
            endpoint_class="builtin_speaker",
            microphone_open=True,
            effective_output_profile="builtin",
            effective_input_profile="builtin",
            effective_output_sample_rate_hz=48_000,
            effective_input_sample_rate_hz=48_000,
            profile_coupling="independent",
        ),
        "continuous",
    ),
    (
        "mid-route-change",
        snapshot(
            endpoint_class="bluetooth",
            microphone_open=True,
            effective_output_profile="media_stereo",
            effective_input_profile="none",
            route_state="transitioning",
        ),
        "ptt",
    ),
    (
        "platform-gave-us-nothing",
        snapshot(
            endpoint_class="unknown",
            microphone_open=False,
            effective_output_profile="unknown",
            effective_input_profile="unknown",
            effective_output_sample_rate_hz=None,
            confidence="unknown",
            profile_coupling="unknown",
        ),
        "disabled",
    ),
]


def build_encode() -> list[dict]:
    rows = []
    for index, (name, snap, mode) in enumerate(REPRESENTABLE, start=1):
        message = message_from(index, snap, mode)
        rows.append(
            {
                "name": f"encode-{name}",
                "revision": index,
                "snapshot": snap,
                "intercom_mode": mode,
                "expect": {"message": message, "payload": dict(message)},
            }
        )
    # A null sample rate is an explicit JSON null, not an absent key (§4.4). Pinned separately
    # because "absent" and "null" are the same to a lenient parser and different on the wire.
    rows.append(
        {
            "name": "encode-null-sample-rates-are-explicit-json-null",
            "revision": 7,
            "snapshot": snapshot(
                effective_output_sample_rate_hz=None,
                effective_input_sample_rate_hz=None,
            ),
            "intercom_mode": "ptt",
            "expect": {
                "message": message_from(
                    7,
                    snapshot(effective_output_sample_rate_hz=None, effective_input_sample_rate_hz=None),
                    "ptt",
                ),
                "payload": message_from(
                    7,
                    snapshot(effective_output_sample_rate_hz=None, effective_input_sample_rate_hz=None),
                    "ptt",
                ),
                "explicit_nulls": [
                    "effective_output_sample_rate_hz",
                    "effective_input_sample_rate_hz",
                ],
            },
        }
    )
    return rows


def valid_payload(**overrides) -> dict:
    base = message_from(
        5,
        snapshot(
            microphone_open=True,
            effective_output_profile="duplex_wideband",
            effective_input_profile="duplex_wideband",
            effective_output_sample_rate_hz=16_000,
            effective_input_sample_rate_hz=16_000,
        ),
        "ptt",
    )
    base.update(overrides)
    return base


def build_parse() -> list[dict]:
    rows: list[dict] = []

    rows.append(
        {
            "name": "parse-well-formed",
            "payload": valid_payload(),
            "expect": {"parsed": valid_payload()},
        }
    )

    # Every enum field, unrecognised. §4.3.1's forward-compatibility rule: tolerated as `unknown`,
    # never malformed. `route_state` is the exception the spec itself names — it falls back to
    # `stable`, because "the route is fine" is the only safe default for a field that suspends the
    # drift ladder.
    unknown_cases = [
        ("endpoint_class", "unknown"),
        ("effective_output_profile", "unknown"),
        ("effective_input_profile", "unknown"),
        ("media_quality", "unknown"),
        ("intercom_mode", "unknown"),
        ("confidence", "unknown"),
    ]
    for field, fallback in unknown_cases:
        payload = valid_payload(**{field: "something-a-future-build-invented"})
        expected = valid_payload(**{field: fallback})
        rows.append(
            {
                "name": f"parse-unrecognised-{field}-is-tolerated-as-{fallback}",
                "payload": payload,
                "expect": {"parsed": expected},
            }
        )
    rows.append(
        {
            "name": "parse-unrecognised-route_state-falls-back-to-stable",
            "payload": valid_payload(route_state="something-a-future-build-invented"),
            "expect": {"parsed": valid_payload(route_state="stable")},
        }
    )

    # A missing key of any required field is MISSING_FIELD, and a wrong JSON type is
    # WRONG_FIELD_TYPE. Both are drops: §4.4 carries no "end the connection" outcome, exactly as
    # §7.4 does not for VOICE_*.
    for field in FIELD_ORDER:
        payload = valid_payload()
        del payload[field]
        if field in ("effective_output_sample_rate_hz", "effective_input_sample_rate_hz"):
            # Nullable: a missing key means "unknown", not malformed (§4.4).
            expected = valid_payload(**{field: None})
            rows.append(
                {
                    "name": f"parse-missing-{field}-means-unknown",
                    "payload": payload,
                    "expect": {"parsed": expected},
                }
            )
        else:
            rows.append(
                {
                    "name": f"parse-missing-{field}-is-rejected",
                    "payload": payload,
                    "expect": {"rejected": "MISSING_FIELD"},
                }
            )

    wrong_types = {
        "revision": "5",
        "endpoint_class": 3,
        "microphone_open": "true",
        "effective_output_profile": 1,
        "effective_input_profile": True,
        "effective_output_sample_rate_hz": "16000",
        "effective_input_sample_rate_hz": "16000",
        "media_quality": 0,
        "route_state": False,
        "intercom_mode": 7,
        "confidence": 1,
    }
    for field, bad in wrong_types.items():
        rows.append(
            {
                "name": f"parse-wrong-type-{field}-is-rejected",
                "payload": valid_payload(**{field: bad}),
                "expect": {"rejected": "WRONG_FIELD_TYPE"},
            }
        )

    # An explicit JSON null is legal for exactly the two nullable fields and for nothing else.
    for field in ("effective_output_sample_rate_hz", "effective_input_sample_rate_hz"):
        rows.append(
            {
                "name": f"parse-explicit-null-{field}-means-unknown",
                "payload": valid_payload(**{field: None}),
                "expect": {"parsed": valid_payload(**{field: None})},
            }
        )
    for field in ("endpoint_class", "microphone_open", "revision", "media_quality"):
        rows.append(
            {
                "name": f"parse-explicit-null-{field}-is-rejected",
                "payload": valid_payload(**{field: None}),
                "expect": {"rejected": "WRONG_FIELD_TYPE"},
            }
        )

    # Bounds.
    rows.append(
        {
            "name": "parse-revision-zero-is-legal",
            "payload": valid_payload(revision=0),
            "expect": {"parsed": valid_payload(revision=0)},
        }
    )
    rows.append(
        {
            "name": "parse-negative-revision-is-rejected",
            "payload": valid_payload(revision=-1),
            "expect": {"rejected": "REVISION_OUT_OF_RANGE"},
        }
    )
    rows.append(
        {
            "name": "parse-revision-at-the-cap-is-legal",
            "payload": valid_payload(revision=MAX_REVISION),
            "expect": {"parsed": valid_payload(revision=MAX_REVISION)},
        }
    )
    rows.append(
        {
            "name": "parse-revision-above-the-cap-is-rejected",
            "payload": valid_payload(revision=MAX_REVISION + 1),
            "expect": {"rejected": "REVISION_OUT_OF_RANGE"},
        }
    )
    rows.append(
        {
            "name": "parse-sample-rate-zero-is-legal",
            "payload": valid_payload(effective_output_sample_rate_hz=0),
            "expect": {"parsed": valid_payload(effective_output_sample_rate_hz=0)},
        }
    )
    rows.append(
        {
            "name": "parse-negative-sample-rate-is-rejected",
            "payload": valid_payload(effective_output_sample_rate_hz=-1),
            "expect": {"rejected": "SAMPLE_RATE_OUT_OF_RANGE"},
        }
    )
    rows.append(
        {
            "name": "parse-sample-rate-at-the-cap-is-legal",
            "payload": valid_payload(effective_input_sample_rate_hz=MAX_SAMPLE_RATE_HZ),
            "expect": {"parsed": valid_payload(effective_input_sample_rate_hz=MAX_SAMPLE_RATE_HZ)},
        }
    )
    rows.append(
        {
            "name": "parse-sample-rate-above-the-cap-is-rejected",
            "payload": valid_payload(effective_input_sample_rate_hz=MAX_SAMPLE_RATE_HZ + 1),
            "expect": {"rejected": "SAMPLE_RATE_OUT_OF_RANGE"},
        }
    )
    # §2 rule 1: unknown fields are ignored, never fatal.
    payload = valid_payload()
    payload["a_field_a_later_version_added"] = "whatever"
    rows.append(
        {
            "name": "parse-unknown-extra-field-is-ignored",
            "payload": payload,
            "expect": {"parsed": valid_payload()},
        }
    )
    return rows


def build_media_quality() -> list[dict]:
    """Every profile value against ADR-016 Amendment A1's derivation. The whole vocabulary, no gaps."""
    return [
        {
            "name": f"media-quality-{profile}",
            "effective_output_profile": profile,
            "expect": media_quality_for(profile),
        }
        for profile in PROFILES
    ]


def build_publisher() -> list[dict]:
    """The sending side of §4.4's revision rule."""
    music = snapshot()
    intercom = snapshot(
        microphone_open=True,
        effective_output_profile="duplex_wideband",
        effective_input_profile="duplex_wideband",
        effective_output_sample_rate_hz=16_000,
        effective_input_sample_rate_hz=16_000,
    )
    transitioning = dict(intercom)
    transitioning["route_state"] = "transitioning"
    interrupted_only = dict(intercom)
    interrupted_only["interrupted"] = True
    timed_only = dict(intercom)
    timed_only["last_transition_duration_us"] = 1_234_567

    rows: list[dict] = []
    rows.append(
        {
            "name": "publisher-first-change-is-revision-1",
            "steps": [
                {"snapshot": music, "intercom_mode": "disabled", "force": False, "expect_revision": 1},
            ],
        }
    )
    rows.append(
        {
            "name": "publisher-identical-state-publishes-nothing-and-does-not-move-the-revision",
            "steps": [
                {"snapshot": music, "intercom_mode": "disabled", "force": False, "expect_revision": 1},
                {"snapshot": music, "intercom_mode": "disabled", "force": False, "expect_revision": None},
                {"snapshot": music, "intercom_mode": "disabled", "force": False, "expect_revision": None},
            ],
        }
    )
    rows.append(
        {
            "name": "publisher-stable-transitioning-stable-is-three-strictly-increasing-revisions",
            "steps": [
                {"snapshot": music, "intercom_mode": "disabled", "force": False, "expect_revision": 1},
                {"snapshot": transitioning, "intercom_mode": "ptt", "force": False, "expect_revision": 2},
                {"snapshot": intercom, "intercom_mode": "ptt", "force": False, "expect_revision": 3},
            ],
        }
    )
    rows.append(
        {
            "name": "publisher-mode-change-alone-is-a-new-revision",
            "steps": [
                {"snapshot": intercom, "intercom_mode": "ptt", "force": False, "expect_revision": 1},
                {"snapshot": intercom, "intercom_mode": "continuous", "force": False, "expect_revision": 2},
            ],
        }
    )
    rows.append(
        {
            "name": "publisher-interruption-alone-is-not-a-new-revision-because-4.4-does-not-carry-it",
            "steps": [
                {"snapshot": intercom, "intercom_mode": "ptt", "force": False, "expect_revision": 1},
                {"snapshot": interrupted_only, "intercom_mode": "ptt", "force": False, "expect_revision": None},
            ],
        }
    )
    rows.append(
        {
            "name": "publisher-transition-duration-alone-is-not-a-new-revision",
            "steps": [
                {"snapshot": intercom, "intercom_mode": "ptt", "force": False, "expect_revision": 1},
                {"snapshot": timed_only, "intercom_mode": "ptt", "force": False, "expect_revision": None},
            ],
        }
    )
    rows.append(
        {
            "name": "publisher-force-publishes-an-unchanged-state-because-a-new-peer-has-seen-nothing",
            "steps": [
                {"snapshot": music, "intercom_mode": "disabled", "force": False, "expect_revision": 1},
                {"snapshot": music, "intercom_mode": "disabled", "force": True, "expect_revision": 2},
                {"snapshot": music, "intercom_mode": "disabled", "force": False, "expect_revision": None},
            ],
        }
    )
    rows.append(
        {
            "name": "publisher-revision-never-goes-backwards-across-a-mic-open-and-close",
            "steps": [
                {"snapshot": music, "intercom_mode": "disabled", "force": False, "expect_revision": 1},
                {"snapshot": intercom, "intercom_mode": "ptt", "force": False, "expect_revision": 2},
                {"snapshot": music, "intercom_mode": "disabled", "force": False, "expect_revision": 3},
            ],
        }
    )
    return rows


def build_inbox() -> list[dict]:
    """The receiving side: drops anything not strictly greater than what it holds."""
    return [
        {"name": "inbox-first-message-is-accepted", "offer": [1], "expect_accepted": [True], "expect_current": 1},
        {
            "name": "inbox-increasing-revisions-are-accepted",
            "offer": [1, 2, 3],
            "expect_accepted": [True, True, True],
            "expect_current": 3,
        },
        {
            "name": "inbox-a-lower-revision-is-dropped-and-cannot-resurrect-a-stale-route",
            "offer": [5, 4],
            "expect_accepted": [True, False],
            "expect_current": 5,
        },
        {
            "name": "inbox-an-equal-revision-is-dropped-as-a-retransmit",
            "offer": [5, 5],
            "expect_accepted": [True, False],
            "expect_current": 5,
        },
        {
            "name": "inbox-reordered-delivery-settles-on-the-highest",
            "offer": [1, 3, 2, 4],
            "expect_accepted": [True, True, False, True],
            "expect_current": 4,
        },
        {
            "name": "inbox-revision-zero-is-a-legal-first-value",
            "offer": [0, 0, 1],
            "expect_accepted": [True, False, True],
            "expect_current": 1,
        },
    ]


def main() -> None:
    payload = {
        "_comment": (
            "PROTOCOL §4.4 / ADR-016 — the AUDIO_STATE message: its exact field set, its bounds, its "
            "media_quality derivation, and the monotonic revision rule on both sides. Both platforms' "
            "AudioStateCodec, AudioStatePublisher and AudioStateInbox run this same file, so a bound or "
            "a revision rule implemented differently on the two phones is a laptop unit-test failure "
            "rather than something two phones discover on a ride. Generated by "
            "tools/generate_audio_state_vectors.py — an independent third transcription of the spec. "
            "Edit the generator, never this file."
        ),
        "_invariants": [
            "No value anywhere in this file is a platform audio string. ADR-016 forbids a2dp, hfp, sco, "
            "AVAudioSession, AudioManager, a device name or a headset model from ever reaching the wire, "
            "and _forbidden_substrings is that rule as data for both platforms' tests to check.",
            "No row expects a malformed AUDIO_STATE frame to end the control connection. The framing was "
            "intact, so the frame is dropped and the connection survives — the same rule §7.4 gives for "
            "VOICE_* and §6 for PING.",
            "An unrecognised enum value is tolerated as `unknown` (or `stable`, for route_state) rather "
            "than treated as malformed: §4.3.1's forward-compatibility rule.",
            "Every publisher row's revisions are strictly increasing, and a suppressed publish "
            "(expect_revision null) must not move the revision at all.",
            "No inbox row accepts a revision that is not strictly greater than the one it holds.",
            "media_quality is derived from effective_output_profile alone and is never measured from "
            "audio (ADR-016 Amendment A1).",
        ],
        "_test_values_only": (
            "Every value here is fabricated. No real device name, address, key or measurement appears in "
            "protocol/vectors/. In particular `confidence: assumed` is the truth for both platforms "
            "until docs/PHASE0_RESULTS.md is filled in, and the one `measured` value below appears only "
            "in a parse row to prove the vocabulary round-trips."
        ),
        "_forbidden_substrings": FORBIDDEN_SUBSTRINGS,
        "_scanned_keys": SCANNED_KEYS,
        "bounds": {"MAX_SAMPLE_RATE_HZ": MAX_SAMPLE_RATE_HZ, "MAX_REVISION": MAX_REVISION},
        "field_order": FIELD_ORDER,
        "vocabulary": {
            "endpoint_class": ENDPOINT_CLASSES,
            "profile": PROFILES,
            "route_state": ROUTE_STATES,
            "intercom_mode": INTERCOM_MODES,
            "confidence": CONFIDENCES,
        },
        "encode": build_encode(),
        "parse": build_parse(),
        "media_quality": build_media_quality(),
        "publisher": build_publisher(),
        "inbox": build_inbox(),
    }

    # One `measured` row, so the vocabulary is exercised without implying anything was measured.
    payload["parse"].append(
        {
            "name": "parse-confidence-measured-round-trips-but-nothing-here-was-measured",
            "payload": valid_payload(confidence="measured"),
            "expect": {"parsed": valid_payload(confidence="measured")},
        }
    )

    text = json.dumps(payload, indent=2) + "\n"

    # Self-check the privacy invariant against exactly the keys both platforms' tests scan: the data,
    # not the prose. The `_invariants` and `_comment` strings necessarily *name* the forbidden
    # vocabulary in order to forbid it, and a check that tripped on its own explanation would be
    # useless. A generator that can violate its own stated invariant is not evidence, so this runs
    # here as well as in both test suites.
    scanned = json.dumps({key: payload[key] for key in SCANNED_KEYS}, indent=2).lower()
    for forbidden in FORBIDDEN_SUBSTRINGS:
        assert forbidden not in scanned, f"forbidden platform vocabulary {forbidden!r} reached the vectors"

    names = [r["name"] for group in ("encode", "parse", "media_quality", "publisher", "inbox") for r in payload[group]]
    assert len(names) == len(set(names)), "duplicate row names"

    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "audio-state"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "audio_state_vectors.json"
    target.write_text(text, encoding="utf-8")
    print(
        f"wrote {target} ("
        f"{len(payload['encode'])} encode, {len(payload['parse'])} parse, "
        f"{len(payload['media_quality'])} media_quality, {len(payload['publisher'])} publisher, "
        f"{len(payload['inbox'])} inbox)"
    )


if __name__ == "__main__":
    main()
