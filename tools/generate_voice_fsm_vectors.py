#!/usr/bin/env python3
"""Generate protocol/vectors/voice-fsm/voice_fsm_vectors.json.

PROTOCOL §7.3 / §7.8 — the voice negotiation table: `(role, state, input) -> (actions, new state)`.

Like `generate_session_gate_vectors.py`, this is a **third, independent implementation**, written
from `docs/PROTOCOL.md` rather than ported from either platform's reducer. That independence is the
only thing that makes the vectors evidence rather than a restatement: two ports of each other share
their misreadings, and a shared misreading is exactly the class of bug ADR-019 was written about.

Edit this generator, never the JSON.

Run:  python3 tools/generate_voice_fsm_vectors.py
"""

from __future__ import annotations

import json
from pathlib import Path

VSID_A = "5e2a9c40b7f13d86e0a4c95b28f7d613"  # the generation under negotiation
VSID_B = "0123456789abcdef0123456789abcdef"  # a different generation — the guard's test material
VSID_FRESH = "ffeeddccbbaa99887766554433221100"  # what a caller supplies for a new negotiation

SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\n"
CANDIDATE = "candidate:1 1 udp 1 192.0.2.11 51234 typ host"

IDLE = "IDLE"
NEGOTIATING = "NEGOTIATING"
CONNECTING = "CONNECTING"
ACTIVE = "ACTIVE"
FAILED = "FAILED"

OFFERER = "OFFERER"
ANSWERER = "ANSWERER"

# --- control authentication generations (STATUS §4 problem 61, ADR-020 Amendment A8) ------------
#
# A third identity, and not to be confused with the two above: VSID_* name one WebRTC negotiation,
# while these name the authenticated **control lifetime** a negotiation belongs to.
# `ControlSessionManager.activateAuthenticatedSession` allocates them, strictly increasing, one per
# trust-gate pass, and never reuses one.
#
# Every row written before this amendment describes a *single* control lifetime — a signal it
# admitted, a start it authorised, and its own link loss — so CTL_A is the faithful default for all
# of them, and `row()` supplies it rather than 63 rows repeating it. The rows that are *about*
# ownership name two or three explicitly.
CTL_A = 1
CTL_B = 2
CTL_C = 3

#: Marks "let `state()` decide from the state itself" — see its `negotiation_control_generation`.
_OWNER_DEFAULT = object()


def state(
    role: str,
    status: str = IDLE,
    voice_session_id: str | None = None,
    local_audio_open: bool = False,
    remote_description_applied: bool = False,
    peer_voice_enabled: bool = False,
    peer_reported_state: str = "IDLE",
    held_remote_offer: dict | None = None,
    mic_muted: bool = False,
    mode: str = "CONTINUOUS",
    negotiation_control_generation: object = _OWNER_DEFAULT,
) -> dict:
    """One `VoiceNegotiationState`.

    `negotiation_control_generation` is **which control lifetime owns the negotiation state this
    value holds**. Left alone it is CTL_A whenever the state holds any — a live status, a
    `voice_session_id`, or a held offer — and null when it holds none, which is exactly the
    single-lifetime reading every pre-amendment row already had. Pass it explicitly for the rows
    that are about two lifetimes.

    "Holds negotiation state" is spelled out rather than inferred from `status` alone because the
    two come apart in both directions: an answerer's intent-to-talk is live with **no**
    `voice_session_id` (§7.3 — the offerer has not minted one yet), while a peer's `failed` leaves a
    `FAILED` status that owns nothing at all.
    """
    owner = negotiation_control_generation
    if owner is _OWNER_DEFAULT:
        holds = status in (NEGOTIATING, CONNECTING, ACTIVE) or voice_session_id is not None or held_remote_offer is not None
        owner = CTL_A if holds else None
    return {
        "role": role,
        "status": status,
        "voice_session_id": voice_session_id,
        "local_audio_open": local_audio_open,
        "remote_description_applied": remote_description_applied,
        "peer_voice_enabled": peer_voice_enabled,
        "peer_reported_state": peer_reported_state,
        "held_remote_offer": held_remote_offer,
        "mic_muted": mic_muted,
        "mode": mode,
        "negotiation_control_generation": owner,
    }


#: Inputs that carry a control-lifetime identity, and the key each one carries it under.
_LIFETIME_KEY = {
    "StartRequested": "control_generation",
    "SignalReceived": "control_generation",
    "ControlLinkLost": "retired_control_generation",
}


def row(name: str, before: dict, inp: dict, actions: list[dict], after: dict) -> dict:
    # Defaulted here rather than repeated on every row, for the reason CTL_A's own comment gives:
    # a row that does not say otherwise is a row about one control lifetime. Emitted rather than
    # left absent, so both platforms' decoders can *require* the key and a future row that forgets
    # it fails a build instead of silently meaning CTL_A.
    key = _LIFETIME_KEY.get(inp["kind"])
    if key is not None and key not in inp:
        inp = {**inp, key: CTL_A}
    return {"name": name, "state": before, "input": inp, "expect": {"actions": actions, "state": after}}


def send_state(vsid: str | None, wire: str, mic_muted: bool = False, mode: str = "CONTINUOUS") -> dict:
    return {"kind": "SendVoiceState", "voice_session_id": vsid, "state": wire, "mic_muted": mic_muted, "mode": mode}


def drop(reason: str) -> dict:
    return {"kind": "RecordDroppedSignal", "reason": reason}


def build() -> list[dict]:
    rows: list[dict] = []

    # =========================================================================================
    # §7.3 — StartRequested. The offerer offers; the answerer states intent and waits.
    # =========================================================================================
    rows.append(
        row(
            "start-as-offerer-from-idle-offers",
            state(OFFERER),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH},
            [
                {"kind": "StartLocalAudio"},
                send_state(VSID_FRESH, "negotiating"),
                {"kind": "CreateOffer", "voice_session_id": VSID_FRESH},
            ],
            state(OFFERER, NEGOTIATING, VSID_FRESH, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "start-as-answerer-from-idle-states-intent-only",
            state(ANSWERER),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH},
            [
                {"kind": "StartLocalAudio"},
                # Null id: the offerer, not this side, creates the generation (§7.3).
                send_state(None, "negotiating"),
            ],
            state(ANSWERER, NEGOTIATING, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "start-when-already-negotiating-is-idempotent",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH},
            [],
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "start-when-already-active-is-idempotent",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH},
            [],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "start-from-failed-retries-without-reopening-audio",
            state(OFFERER, FAILED, None, local_audio_open=True),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH},
            [
                # No StartLocalAudio: the capture device is already open for this ride segment
                # (ARCHITECTURE §6.3), and reopening it is an audible Bluetooth route change.
                send_state(VSID_FRESH, "negotiating"),
                {"kind": "CreateOffer", "voice_session_id": VSID_FRESH},
            ],
            state(OFFERER, NEGOTIATING, VSID_FRESH, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "start-as-answerer-with-held-offer-answers-it",
            state(
                ANSWERER,
                IDLE,
                None,
                peer_voice_enabled=True,
                peer_reported_state="NEGOTIATING",
                held_remote_offer={"voice_session_id": VSID_A, "sdp": SDP},
            ),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH},
            [
                {"kind": "StartLocalAudio"},
                # The offer we already hold is answered — the offerer is not asked to resend it.
                {"kind": "ApplyRemoteOffer", "voice_session_id": VSID_A, "sdp": SDP},
                {"kind": "DrainQueuedCandidates"},
                {"kind": "CreateAnswer", "voice_session_id": VSID_A},
            ],
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                peer_voice_enabled=True,
                peer_reported_state="NEGOTIATING",
            ),
        )
    )

    # =========================================================================================
    # §7.3 glare — the answerer's `negotiating` is an intent, and the offerer's response to it.
    # =========================================================================================
    rows.append(
        row(
            "glare-offerer-idle-and-consented-begins-on-peer-intent",
            state(OFFERER, IDLE, None, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": None, "state": "NEGOTIATING", "mic_muted": False, "mode": "CONTINUOUS"},
            },
            [
                send_state(VSID_FRESH, "negotiating"),
                {"kind": "CreateOffer", "voice_session_id": VSID_FRESH},
            ],
            state(
                OFFERER,
                NEGOTIATING,
                VSID_FRESH,
                local_audio_open=True,
                peer_voice_enabled=True,
                peer_reported_state="NEGOTIATING",
            ),
        )
    )
    rows.append(
        row(
            "glare-offerer-already-negotiating-ignores-peer-intent",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": None, "state": "NEGOTIATING", "mic_muted": False, "mode": "CONTINUOUS"},
            },
            [],
            # This row *is* the glare property: two simultaneous presses produce one generation.
            state(
                OFFERER,
                NEGOTIATING,
                VSID_A,
                local_audio_open=True,
                peer_voice_enabled=True,
                peer_reported_state="NEGOTIATING",
            ),
        )
    )
    rows.append(
        row(
            "glare-offerer-without-local-consent-surfaces-request-only",
            state(OFFERER, IDLE, None, local_audio_open=False),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": None, "state": "NEGOTIATING", "mic_muted": False, "mode": "CONTINUOUS"},
            },
            [{"kind": "SurfacePeerVoiceRequest"}],
            # The microphone is never opened because a peer asked — illegal from the background on
            # Android (ARCHITECTURE §6.4) and wrong on iOS too.
            state(
                OFFERER,
                IDLE,
                None,
                local_audio_open=False,
                peer_voice_enabled=True,
                peer_reported_state="NEGOTIATING",
            ),
        )
    )
    rows.append(
        row(
            "glare-answerer-never-offers-on-peer-intent",
            state(ANSWERER, IDLE, None, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": VSID_A, "state": "NEGOTIATING", "mic_muted": False, "mode": "CONTINUOUS"},
            },
            [],
            state(
                ANSWERER,
                IDLE,
                None,
                local_audio_open=True,
                peer_voice_enabled=True,
                peer_reported_state="NEGOTIATING",
            ),
        )
    )

    # =========================================================================================
    # §7.4 — VOICE_OFFER receiver rules.
    # =========================================================================================
    rows.append(
        row(
            "offer-to-answerer-with-consent-applies-and-answers",
            state(ANSWERER, NEGOTIATING, None, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Offer", "voice_session_id": VSID_A, "sdp": SDP},
            },
            [
                {"kind": "ApplyRemoteOffer", "voice_session_id": VSID_A, "sdp": SDP},
                {"kind": "DrainQueuedCandidates"},
                {"kind": "CreateAnswer", "voice_session_id": VSID_A},
            ],
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                peer_voice_enabled=True,
                peer_reported_state="NEGOTIATING",
            ),
        )
    )
    rows.append(
        row(
            "offer-to-answerer-without-consent-is-held",
            state(ANSWERER, IDLE, None, local_audio_open=False),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Offer", "voice_session_id": VSID_A, "sdp": SDP},
            },
            [{"kind": "SurfacePeerVoiceRequest"}],
            state(
                ANSWERER,
                IDLE,
                None,
                local_audio_open=False,
                peer_voice_enabled=True,
                peer_reported_state="NEGOTIATING",
                held_remote_offer={"voice_session_id": VSID_A, "sdp": SDP},
            ),
        )
    )
    rows.append(
        row(
            "offer-to-offerer-is-a-role-violation",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Offer", "voice_session_id": VSID_A, "sdp": SDP},
            },
            [drop("ROLE_VIOLATION")],
            # §7.3: a peer that offers to the offerer disagrees about leadership — the same
            # condition §4.1 calls leader_mismatch.
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "duplicate-offer-same-generation-is-ignored",
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
            ),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Offer", "voice_session_id": VSID_A, "sdp": SDP},
            },
            [drop("DUPLICATE")],
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
            ),
        )
    )
    rows.append(
        row(
            "offer-for-a-different-generation-while-live-is-dropped",
            state(ANSWERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Offer", "voice_session_id": VSID_B, "sdp": SDP},
            },
            [drop("GENERATION_MISMATCH")],
            state(ANSWERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )

    # =========================================================================================
    # §7.4 — VOICE_ANSWER receiver rules.
    # =========================================================================================
    rows.append(
        row(
            "answer-to-offerer-applies-and-moves-to-connecting",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Answer", "voice_session_id": VSID_A, "sdp": SDP},
            },
            [
                {"kind": "ApplyRemoteAnswer", "voice_session_id": VSID_A, "sdp": SDP},
                {"kind": "DrainQueuedCandidates"},
                send_state(VSID_A, "connecting"),
            ],
            state(
                OFFERER,
                CONNECTING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                peer_voice_enabled=True,
            ),
        )
    )
    rows.append(
        row(
            "answer-to-answerer-is-a-role-violation",
            state(ANSWERER, NEGOTIATING, VSID_A, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Answer", "voice_session_id": VSID_A, "sdp": SDP},
            },
            [drop("ROLE_VIOLATION")],
            state(ANSWERER, NEGOTIATING, VSID_A, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "duplicate-answer-is-ignored",
            state(
                OFFERER,
                CONNECTING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
            ),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Answer", "voice_session_id": VSID_A, "sdp": SDP},
            },
            [drop("DUPLICATE")],
            state(
                OFFERER,
                CONNECTING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
            ),
        )
    )
    rows.append(
        row(
            "answer-for-a-different-generation-is-dropped",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Answer", "voice_session_id": VSID_B, "sdp": SDP},
            },
            [drop("GENERATION_MISMATCH")],
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "answer-with-no-generation-held-is-dropped",
            state(OFFERER, IDLE, None, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "Answer", "voice_session_id": VSID_A, "sdp": SDP},
            },
            [drop("GENERATION_MISMATCH")],
            state(OFFERER, IDLE, None, local_audio_open=True),
        )
    )

    # =========================================================================================
    # §7.4 — trickle ICE: queued before the remote description, applied after, inert when stale.
    # =========================================================================================
    rows.append(
        row(
            "candidate-before-remote-description-is-queued",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True, remote_description_applied=False),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {
                    "kind": "IceCandidate",
                    "voice_session_id": VSID_A,
                    "candidate": CANDIDATE,
                    "sdp_mid": "0",
                    "sdp_mline_index": 0,
                },
            },
            [
                {
                    "kind": "QueueRemoteCandidate",
                    "voice_session_id": VSID_A,
                    "candidate": CANDIDATE,
                    "sdp_mid": "0",
                    "sdp_mline_index": 0,
                }
            ],
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True, remote_description_applied=False),
        )
    )
    rows.append(
        row(
            "candidate-after-remote-description-is-applied",
            state(OFFERER, CONNECTING, VSID_A, local_audio_open=True, remote_description_applied=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {
                    "kind": "IceCandidate",
                    "voice_session_id": VSID_A,
                    "candidate": CANDIDATE,
                    "sdp_mid": None,
                    "sdp_mline_index": 0,
                },
            },
            [
                {
                    "kind": "ApplyRemoteCandidate",
                    "voice_session_id": VSID_A,
                    "candidate": CANDIDATE,
                    "sdp_mid": None,
                    "sdp_mline_index": 0,
                }
            ],
            state(OFFERER, CONNECTING, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "candidate-after-teardown-cannot-resurrect-anything",
            state(OFFERER, IDLE, None, local_audio_open=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {
                    "kind": "IceCandidate",
                    "voice_session_id": VSID_A,
                    "candidate": CANDIDATE,
                    "sdp_mid": "0",
                    "sdp_mline_index": 0,
                },
            },
            [drop("GENERATION_MISMATCH")],
            # Teardown cleared voice_session_id, so this is inert by comparison rather than by luck.
            state(OFFERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "candidate-for-a-different-generation-is-dropped",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {
                    "kind": "IceCandidate",
                    "voice_session_id": VSID_B,
                    "candidate": CANDIDATE,
                    "sdp_mid": "0",
                    "sdp_mline_index": 0,
                },
            },
            [drop("GENERATION_MISMATCH")],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )

    # =========================================================================================
    # Engine callbacks — the generation guard applied to the media stack (§7.8).
    # =========================================================================================
    rows.append(
        row(
            "local-offer-created-is-sent",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
            {"kind": "LocalOfferCreated", "voice_session_id": VSID_A, "sdp": SDP},
            [{"kind": "SendOffer", "voice_session_id": VSID_A, "sdp": SDP}],
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "stale-local-offer-cannot-activate-a-later-generation",
            state(OFFERER, NEGOTIATING, VSID_B, local_audio_open=True),
            {"kind": "LocalOfferCreated", "voice_session_id": VSID_A, "sdp": SDP},
            [drop("STALE_ENGINE_CALLBACK")],
            state(OFFERER, NEGOTIATING, VSID_B, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "local-answer-created-is-sent-and-moves-to-connecting",
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
            ),
            {"kind": "LocalAnswerCreated", "voice_session_id": VSID_A, "sdp": SDP},
            [
                {"kind": "SendAnswer", "voice_session_id": VSID_A, "sdp": SDP},
                send_state(VSID_A, "connecting"),
            ],
            state(
                ANSWERER,
                CONNECTING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
            ),
        )
    )
    rows.append(
        row(
            "stale-local-answer-is-dropped",
            state(ANSWERER, IDLE, None, local_audio_open=True),
            {"kind": "LocalAnswerCreated", "voice_session_id": VSID_A, "sdp": SDP},
            [drop("STALE_ENGINE_CALLBACK")],
            state(ANSWERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "local-candidate-gathered-is-trickled",
            state(OFFERER, CONNECTING, VSID_A, local_audio_open=True, remote_description_applied=True),
            {
                "kind": "LocalCandidateGathered",
                "voice_session_id": VSID_A,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": 0,
            },
            [
                {
                    "kind": "SendCandidate",
                    "voice_session_id": VSID_A,
                    "candidate": CANDIDATE,
                    "sdp_mid": "0",
                    "sdp_mline_index": 0,
                }
            ],
            state(OFFERER, CONNECTING, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "stale-local-candidate-is-not-sent",
            state(OFFERER, IDLE, None, local_audio_open=True),
            {
                "kind": "LocalCandidateGathered",
                "voice_session_id": VSID_A,
                "candidate": CANDIDATE,
                "sdp_mid": "0",
                "sdp_mline_index": 0,
            },
            [drop("STALE_ENGINE_CALLBACK")],
            state(OFFERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "media-connected-goes-active",
            state(OFFERER, CONNECTING, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "MediaConnectivityChanged", "voice_session_id": VSID_A, "connected": True, "failed": False},
            [send_state(VSID_A, "active")],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "media-connected-again-while-active-is-idempotent",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "MediaConnectivityChanged", "voice_session_id": VSID_A, "connected": True, "failed": False},
            [],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "media-disconnected-while-active-returns-to-connecting",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "MediaConnectivityChanged", "voice_session_id": VSID_A, "connected": False, "failed": False},
            [send_state(VSID_A, "connecting")],
            state(OFFERER, CONNECTING, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "media-failed-tears-down-transport-and-reports",
            state(OFFERER, CONNECTING, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "MediaConnectivityChanged", "voice_session_id": VSID_A, "connected": False, "failed": True},
            [{"kind": "StopMediaTransport"}, send_state(VSID_A, "failed")],
            state(OFFERER, FAILED, VSID_A, local_audio_open=True, remote_description_applied=False),
        )
    )
    rows.append(
        row(
            "stale-media-state-change-is-dropped",
            state(OFFERER, ACTIVE, VSID_B, local_audio_open=True, remote_description_applied=True),
            {"kind": "MediaConnectivityChanged", "voice_session_id": VSID_A, "connected": True, "failed": False},
            [drop("STALE_ENGINE_CALLBACK")],
            state(OFFERER, ACTIVE, VSID_B, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "remote-track-present-is-generation-guarded-and-otherwise-silent",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "RemoteTrackChanged", "voice_session_id": VSID_A, "present": True},
            [],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "stale-remote-track-change-is-dropped",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "RemoteTrackChanged", "voice_session_id": VSID_B, "present": True},
            [drop("STALE_ENGINE_CALLBACK")],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )

    # =========================================================================================
    # Mute — gates transmission, never the hardware (ARCHITECTURE §6.3).
    # =========================================================================================
    rows.append(
        row(
            "mute-while-active-disables-sender-and-tells-the-peer",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "MuteRequested", "muted": True},
            [
                {"kind": "SetMicrophoneMuted", "muted": True},
                send_state(VSID_A, "active", mic_muted=True),
            ],
            state(
                OFFERER,
                ACTIVE,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                mic_muted=True,
            ),
        )
    )
    rows.append(
        row(
            "unmute-restores-the-sender",
            state(
                OFFERER,
                ACTIVE,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                mic_muted=True,
            ),
            {"kind": "MuteRequested", "muted": False},
            [
                {"kind": "SetMicrophoneMuted", "muted": False},
                send_state(VSID_A, "active", mic_muted=False),
            ],
            state(
                OFFERER,
                ACTIVE,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                mic_muted=False,
            ),
        )
    )
    rows.append(
        row(
            "mute-to-the-same-value-is-a-no-op",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "MuteRequested", "muted": False},
            [],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "mute-before-any-audio-records-the-preference-only",
            state(OFFERER, IDLE),
            {"kind": "MuteRequested", "muted": True},
            [],
            state(OFFERER, IDLE, mic_muted=True),
        )
    )

    # =========================================================================================
    # §7.4 `mode` — Phase 2b. The intercom policy chooses the gate; the peer is told which one.
    #
    # A mode change is not a state transition of the voice session, so the status is re-sent
    # unchanged. And it is announced only when there is a generation to name: with no live
    # negotiation there is nothing to report the mode *of*, and the next VOICE_STATE this side
    # sends will carry the new value anyway.
    # =========================================================================================
    rows.append(
        row(
            "mode-selected-while-active-tells-the-peer-without-changing-status",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "ModeSelected", "mode": "PTT"},
            [send_state(VSID_A, "active", mode="PTT")],
            state(
                OFFERER,
                ACTIVE,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                mode="PTT",
            ),
        )
    )
    rows.append(
        row(
            "mode-selected-while-negotiating-tells-the-peer-with-the-negotiating-status",
            state(ANSWERER, NEGOTIATING, VSID_A, local_audio_open=True),
            {"kind": "ModeSelected", "mode": "VOX"},
            [send_state(VSID_A, "negotiating", mode="VOX")],
            state(ANSWERER, NEGOTIATING, VSID_A, local_audio_open=True, mode="VOX"),
        )
    )
    rows.append(
        row(
            "mode-selected-carries-the-current-mute-value-unchanged",
            state(
                OFFERER,
                ACTIVE,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                mic_muted=True,
            ),
            {"kind": "ModeSelected", "mode": "PTT"},
            [send_state(VSID_A, "active", mic_muted=True, mode="PTT")],
            state(
                OFFERER,
                ACTIVE,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                mic_muted=True,
                mode="PTT",
            ),
        )
    )
    rows.append(
        row(
            "mode-selected-to-the-same-value-is-a-no-op",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "ModeSelected", "mode": "CONTINUOUS"},
            [],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "mode-selected-with-no-generation-records-the-preference-only",
            state(OFFERER, IDLE),
            {"kind": "ModeSelected", "mode": "PTT"},
            [],
            state(OFFERER, IDLE, mode="PTT"),
        )
    )
    rows.append(
        row(
            "mode-selected-with-capture-open-but-no-generation-still-sends-nothing",
            state(ANSWERER, NEGOTIATING, None, local_audio_open=True),
            {"kind": "ModeSelected", "mode": "PTT"},
            [],
            state(ANSWERER, NEGOTIATING, None, local_audio_open=True, mode="PTT"),
        )
    )
    rows.append(
        row(
            "mode-selected-never-releases-or-opens-local-audio",
            state(OFFERER, CONNECTING, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "ModeSelected", "mode": "PTT"},
            [send_state(VSID_A, "connecting", mode="PTT")],
            state(
                OFFERER,
                CONNECTING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                mode="PTT",
            ),
        )
    )

    # =========================================================================================
    # §7.8 — teardown. Deliberate releases capture; involuntary does not.
    # =========================================================================================
    rows.append(
        row(
            "stop-tells-the-peer-then-releases-everything",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "StopRequested"},
            [
                send_state(VSID_A, "closed"),
                {"kind": "StopMediaTransport"},
                {"kind": "ReleaseLocalAudio"},
            ],
            state(OFFERER, IDLE),
        )
    )
    rows.append(
        row(
            "stop-preserves-the-mute-preference",
            state(
                OFFERER,
                ACTIVE,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                mic_muted=True,
            ),
            {"kind": "StopRequested"},
            [
                send_state(VSID_A, "closed", mic_muted=True),
                {"kind": "StopMediaTransport"},
                {"kind": "ReleaseLocalAudio"},
            ],
            state(OFFERER, IDLE, mic_muted=True),
        )
    )
    rows.append(
        row(
            "stop-from-idle-with-nothing-open-is-a-no-op",
            state(OFFERER, IDLE),
            {"kind": "StopRequested"},
            [],
            state(OFFERER, IDLE),
        )
    )
    rows.append(
        row(
            "control-link-lost-drops-media-but-keeps-capture",
            state(ANSWERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {"kind": "ControlLinkLost"},
            # No SendVoiceState: there is no link to send it on. No ReleaseLocalAudio: the capture
            # device stays open for the ride segment (ARCHITECTURE §6.3/§6.4) — on Android there is
            # no second legal opportunity to open a microphone once the screen is locked.
            [{"kind": "StopMediaTransport"}],
            state(ANSWERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "control-link-lost-while-idle-and-closed-is-a-no-op",
            state(ANSWERER, IDLE),
            {"kind": "ControlLinkLost"},
            [],
            state(ANSWERER, IDLE),
        )
    )

    # --- control-lifetime ownership (STATUS §4 problem 61, ADR-020 Amendment A8) --------------
    #
    # A control-lifetime boundary may retire only negotiation state **owned by that lifetime**, and
    # never state that has already transferred to a successor. The whole rule is one comparison of
    # the owner against the lifetime that ended, and these rows are its four corners.
    #
    # The comparison is deliberately "older than", not "different from". A boundary naming a *newer*
    # lifetime than the owner must still tear down: one authenticated connection exists at a time
    # and generations strictly increase, so a newer lifetime having existed **proves** the owner's
    # ended. That is what stops a lost predecessor boundary stranding a dead negotiation forever —
    # the wedge that made "suppress a superseded boundary" strictly worse than the defect it fixed.
    rows.append(
        row(
            # P61-A. The defect itself: B's offer was admitted *and reduced*, and only then did A's
            # boundary arrive out of `SessionCoordinator`'s event consumer.
            "control-link-lost-for-a-predecessor-cannot-retire-the-successors-negotiation",
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                negotiation_control_generation=CTL_B,
            ),
            {"kind": "ControlLinkLost", "retired_control_generation": CTL_A},
            # Recorded, never silent: a preserved successor is the one outcome this amendment exists
            # for, and it would otherwise be the only one with no evidence it happened.
            [drop("SUPERSEDED_CONTROL_LIFETIME")],
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                negotiation_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # P61-C. Two reconnects later, A's boundary is older still, and no less inert.
            "control-link-lost-for-a-long-dead-lifetime-cannot-retire-a-third-generations-negotiation",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, negotiation_control_generation=CTL_C),
            {"kind": "ControlLinkLost", "retired_control_generation": CTL_A},
            [drop("SUPERSEDED_CONTROL_LIFETIME")],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, negotiation_control_generation=CTL_C),
        )
    )
    rows.append(
        row(
            # P61-D. The ordinary case, unchanged: §7.8 in full.
            "control-link-lost-for-the-owning-lifetime-still-drops-media-and-keeps-capture",
            state(ANSWERER, ACTIVE, VSID_A, local_audio_open=True, negotiation_control_generation=CTL_B),
            {"kind": "ControlLinkLost", "retired_control_generation": CTL_B},
            [{"kind": "StopMediaTransport"}],
            state(ANSWERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            # P61-B, as a table row. B merely *admitted* work; `offerReceived` refused it against A's
            # still-live negotiation, so A is still the owner and A's boundary must still retire it.
            # This is the row that makes the naïve suppression fail: it cannot tell this state from
            # the one above it without an owner to read.
            "control-link-lost-still-retires-a-predecessor-a-successor-never-took-over-from",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, negotiation_control_generation=CTL_A),
            {"kind": "ControlLinkLost", "retired_control_generation": CTL_A},
            [{"kind": "StopMediaTransport"}],
            state(OFFERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            # A *newer* lifetime's boundary against an older owner. Its own boundary may have been
            # lost or never emitted; either way the negotiation is dead and must not survive.
            "control-link-lost-for-a-newer-lifetime-still-retires-an-older-owners-negotiation",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, negotiation_control_generation=CTL_A),
            {"kind": "ControlLinkLost", "retired_control_generation": CTL_B},
            [{"kind": "StopMediaTransport"}],
            state(OFFERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            # The mailbox-overflow degrade and a connection that died before authenticating both
            # name no lifetime. The degrade is a local safety valve, so it must work whoever owns
            # what — an unconditional teardown, never a comparison.
            "control-link-lost-naming-no-lifetime-retires-whatever-is-live",
            state(ANSWERER, ACTIVE, VSID_A, local_audio_open=True, negotiation_control_generation=CTL_C),
            {"kind": "ControlLinkLost", "retired_control_generation": None},
            [{"kind": "StopMediaTransport"}],
            state(ANSWERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            # A held offer is negotiation state too — it is what a later consent answers — so it is
            # owned and retired exactly like a live one.
            "control-link-lost-for-a-predecessor-cannot-discard-the-successors-held-offer",
            state(
                ANSWERER,
                IDLE,
                None,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                held_remote_offer={"voice_session_id": VSID_A, "sdp": SDP},
                negotiation_control_generation=CTL_B,
            ),
            {"kind": "ControlLinkLost", "retired_control_generation": CTL_A},
            [drop("SUPERSEDED_CONTROL_LIFETIME")],
            state(
                ANSWERER,
                IDLE,
                None,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                held_remote_offer={"voice_session_id": VSID_A, "sdp": SDP},
                negotiation_control_generation=CTL_B,
            ),
        )
    )
    # --- ownership is *established*, never inferred --------------------------------------------
    #
    # The transitions that create negotiation state are the only ones that set an owner, and they
    # set it to the generation carried by the very input that created it. Nothing transfers
    # ownership merely by being observed.
    rows.append(
        row(
            "a-successors-offer-taken-up-from-idle-is-owned-by-the-successor",
            state(ANSWERER, IDLE, None, local_audio_open=True, negotiation_control_generation=None),
            {
                "kind": "SignalReceived",
                "signal": {"kind": "Offer", "voice_session_id": VSID_A, "sdp": SDP},
                "fresh_voice_session_id": VSID_FRESH,
                "control_generation": CTL_B,
            },
            [
                {"kind": "ApplyRemoteOffer", "voice_session_id": VSID_A, "sdp": SDP},
                {"kind": "DrainQueuedCandidates"},
                {"kind": "CreateAnswer", "voice_session_id": VSID_A},
            ],
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                negotiation_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # An ANSWER advances a negotiation that already exists and already has an owner. Moving
            # ownership because a successor's link happened to carry the frame would be *inferring*
            # it, and would leave the negotiation A established un-retirable by A's own boundary.
            "an-answer-arriving-over-a-successors-link-does-not-move-ownership",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True, negotiation_control_generation=CTL_A),
            {
                "kind": "SignalReceived",
                "signal": {"kind": "Answer", "voice_session_id": VSID_A, "sdp": SDP},
                "fresh_voice_session_id": VSID_FRESH,
                "control_generation": CTL_B,
            },
            [
                {"kind": "ApplyRemoteAnswer", "voice_session_id": VSID_A, "sdp": SDP},
                {"kind": "DrainQueuedCandidates"},
                send_state(VSID_A, "connecting"),
            ],
            state(
                OFFERER,
                CONNECTING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                peer_voice_enabled=True,
                negotiation_control_generation=CTL_A,
            ),
        )
    )
    rows.append(
        row(
            # §7.3 glare, owned: the peer's intent-to-talk begins the offerer's negotiation, so the
            # lifetime that delivered that intent is the lifetime that owns it.
            "a-peer-intent-that-begins-a-negotiation-is-owned-by-the-lifetime-that-delivered-it",
            state(OFFERER, IDLE, None, local_audio_open=True, negotiation_control_generation=None),
            {
                "kind": "SignalReceived",
                "signal": {"kind": "State", "voice_session_id": None, "state": "NEGOTIATING", "mic_muted": False, "mode": "CONTINUOUS"},
                "fresh_voice_session_id": VSID_FRESH,
                "control_generation": CTL_B,
            },
            [send_state(VSID_FRESH, "negotiating"), {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}],
            state(
                OFFERER,
                NEGOTIATING,
                VSID_FRESH,
                local_audio_open=True,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                negotiation_control_generation=CTL_B,
            ),
        )
    )
    # --- StartRequested with no authenticated lifetime (P61-F) --------------------------------
    #
    # A user can press Start in the gap between one control link dying and PROTOCOL §10's ladder
    # restoring the next. The press must not be refused — ARCHITECTURE §6.4 requires capture to be
    # opened while the app is foreground-visible, and this may be the last such moment — but it also
    # must not create a negotiation, because there is no link to negotiate over and, worse, no
    # lifetime to own it. A negotiation owned by nobody is the one state no boundary can retire.
    #
    # So: consent, and only consent. `SessionCoordinator.attachVoice` rebuilds under the successor
    # the moment one authenticates, because it starts voice for any segment whose capture is open.
    rows.append(
        row(
            "start-with-no-authenticated-lifetime-opens-capture-and-starts-no-negotiation",
            state(OFFERER, IDLE),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": None},
            [{"kind": "StartLocalAudio"}],
            state(OFFERER, IDLE, None, local_audio_open=True, negotiation_control_generation=None),
        )
    )
    rows.append(
        row(
            "an-answerers-start-with-no-authenticated-lifetime-states-no-intent",
            state(ANSWERER, IDLE),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": None},
            # Not even a SendVoiceState: there is no link to send an intent-to-talk on, and §7.3's
            # intent is an answerer's *only* wire effect, so sending one that cannot leave is the
            # unrecoverable loss STATUS §4 problem 59 is about.
            [{"kind": "StartLocalAudio"}],
            state(ANSWERER, IDLE, None, local_audio_open=True, negotiation_control_generation=None),
        )
    )
    rows.append(
        row(
            # And the rebuild: the same user, once a successor has authenticated. Capture is already
            # open, so no StartLocalAudio — but the negotiation now begins, owned by the successor.
            "the-reconnect-rebuild-starts-the-negotiation-the-gap-press-could-not",
            state(OFFERER, IDLE, None, local_audio_open=True),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": CTL_B},
            [send_state(VSID_FRESH, "negotiating"), {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}],
            state(
                OFFERER,
                NEGOTIATING,
                VSID_FRESH,
                local_audio_open=True,
                negotiation_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # Idempotence does **not** re-own. A second press under a newer lifetime observes a live
            # negotiation and changes nothing — including whose it is. Ownership moves only when a
            # transition actually establishes successor state, and this one establishes nothing.
            "a-start-under-a-newer-lifetime-does-not-re-own-a-live-negotiation",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True, negotiation_control_generation=CTL_A),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": CTL_B},
            [],
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True, negotiation_control_generation=CTL_A),
        )
    )
    # --- NegotiationSendFailed (STATUS §4 problems 56/57/59) ---------------------------------
    #
    # An outbound frame the negotiation depended on could not be put on the wire. The table's
    # *reaction* is ControlLinkLost's, but the *event* is not: this one is a local, in-lifetime fact
    # about one frame, it carries the generation that frame belonged to, and it is deliberately inert
    # against any other. That guard is the whole reason it is a separate input rather than a reuse of
    # ControlLinkLost, which owns a control lifetime's queued remote work and may not be forged by a
    # send whose Boolean arrived after that lifetime ended.
    rows.append(
        row(
            "negotiation-send-failed-drops-media-but-keeps-capture",
            state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True),
            {"kind": "NegotiationSendFailed", "voice_session_id": VSID_A},
            # Exactly ControlLinkLost's actions: no SendVoiceState (nothing could be sent — that is
            # what just failed) and no ReleaseLocalAudio (ARCHITECTURE §6.3/§6.4).
            [{"kind": "StopMediaTransport"}],
            state(OFFERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "negotiation-send-failed-for-an-answerers-intent-names-no-generation",
            # §7.3: an answerer's intent-to-talk has no voice_session_id because the offerer has not
            # created one yet, so null is the generation it legitimately names — not "unknown".
            state(ANSWERER, NEGOTIATING, None, local_audio_open=True),
            {"kind": "NegotiationSendFailed", "voice_session_id": None},
            [{"kind": "StopMediaTransport"}],
            state(ANSWERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "negotiation-send-failed-from-a-retired-generation-is-inert",
            # The send that failed belonged to a negotiation the table has already moved past. This is
            # the case that stops a late Boolean retiring whatever came next.
            state(OFFERER, NEGOTIATING, VSID_B, local_audio_open=True),
            {"kind": "NegotiationSendFailed", "voice_session_id": VSID_A},
            [drop("GENERATION_MISMATCH")],
            state(OFFERER, NEGOTIATING, VSID_B, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "negotiation-send-failed-against-an-idle-table-is-inert",
            # A teardown already reset the table. A null-named intent failure must not match this.
            state(ANSWERER, IDLE, None, local_audio_open=True),
            {"kind": "NegotiationSendFailed", "voice_session_id": None},
            [drop("UNEXPECTED_FOR_STATUS")],
            state(ANSWERER, IDLE, None, local_audio_open=True),
        )
    )
    rows.append(
        row(
            "peer-closed-drops-media-and-keeps-consent",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": VSID_A, "state": "CLOSED", "mic_muted": False, "mode": "CONTINUOUS"},
            },
            [{"kind": "StopMediaTransport"}],
            state(OFFERER, IDLE, None, local_audio_open=True, peer_reported_state="CLOSED"),
        )
    )
    rows.append(
        row(
            "peer-failed-drops-media-and-shows-failed",
            state(ANSWERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": VSID_A, "state": "FAILED", "mic_muted": False, "mode": "CONTINUOUS"},
            },
            [{"kind": "StopMediaTransport"}],
            state(ANSWERER, FAILED, None, local_audio_open=True, peer_reported_state="FAILED"),
        )
    )
    rows.append(
        row(
            "peer-closed-for-a-different-generation-is-dropped",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": VSID_B, "state": "CLOSED", "mic_muted": False, "mode": "CONTINUOUS"},
            },
            [drop("GENERATION_MISMATCH")],
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
        )
    )
    rows.append(
        row(
            "peer-idle-clears-peer-enabled-without-teardown",
            state(OFFERER, IDLE, None, local_audio_open=True, peer_voice_enabled=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": None, "state": "IDLE", "mic_muted": False, "mode": "CONTINUOUS"},
            },
            [],
            state(OFFERER, IDLE, None, local_audio_open=True, peer_voice_enabled=False, peer_reported_state="IDLE"),
        )
    )
    rows.append(
        row(
            "peer-active-is-informational",
            state(OFFERER, CONNECTING, VSID_A, local_audio_open=True, remote_description_applied=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": VSID_A, "state": "ACTIVE", "mic_muted": False, "mode": "CONTINUOUS"},
            },
            [],
            state(
                OFFERER,
                CONNECTING,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                peer_voice_enabled=True,
                peer_reported_state="ACTIVE",
            ),
        )
    )
    rows.append(
        row(
            "peer-unknown-state-is-tolerated-not-fatal",
            state(OFFERER, ACTIVE, VSID_A, local_audio_open=True, remote_description_applied=True),
            {
                "kind": "SignalReceived",
                "fresh_voice_session_id": VSID_FRESH,
                "signal": {"kind": "State", "voice_session_id": VSID_A, "state": "UNKNOWN", "mic_muted": False, "mode": "UNKNOWN"},
            },
            [],
            state(
                OFFERER,
                ACTIVE,
                VSID_A,
                local_audio_open=True,
                remote_description_applied=True,
                peer_voice_enabled=True,
                peer_reported_state="UNKNOWN",
            ),
        )
    )

    return rows


def main() -> None:
    rows = build()
    names = [r["name"] for r in rows]
    duplicates = sorted({n for n in names if names.count(n) > 1})
    assert not duplicates, f"duplicate vector names: {duplicates}"

    payload = {
        "_comment": (
            "PROTOCOL §7.3/§7.8 — the voice negotiation table: (role, status, input) -> (actions, "
            "new status). Both platforms' VoiceNegotiation reducer runs this same file, so an "
            "offerer rule or a generation guard implemented differently on the two phones is a "
            "laptop unit-test failure rather than something a ride discovers. Generated by "
            "tools/generate_voice_fsm_vectors.py — an independent third transcription of the spec. "
            "Edit the generator, never this file."
        ),
        "_invariants": [
            "No row may have an ANSWERER emit CreateOffer or SendOffer. §7.3: only the leader offers.",
            "No row may have an OFFERER accept a VOICE_OFFER or an ANSWERER accept a VOICE_ANSWER.",
            "No row where the input's voice_session_id differs from the state's may produce any action other than RecordDroppedSignal. That is the §7.2 generation guard.",
            "ControlLinkLost must never emit ReleaseLocalAudio: the capture device survives a link blip (ARCHITECTURE §6.3/§6.4).",
            "ControlLinkLost must never emit SendVoiceState: there is no link to send it on.",
            "NegotiationSendFailed must never emit ReleaseLocalAudio or SendVoiceState, for ControlLinkLost's two reasons, and must never act on a generation other than the one the state holds — that guard is why it is a separate input rather than a second use of ControlLinkLost (STATUS §4 problem 57).",
            "No ModeSelected row may emit any action other than SendVoiceState, and none may change the status: choosing a gate is a local policy change, not a state transition of the voice session (PROTOCOL §7.4, ADR-021).",
            "No ModeSelected row may emit StartLocalAudio or ReleaseLocalAudio. PTT and VOX gate transmission, never the capture device (ARCHITECTURE §6.3).",
            "No row whose input is a received signal may start a negotiation (CreateOffer/CreateAnswer) when local_audio_open is false. The microphone is never opened because a *peer* asked — only a local StartRequested, which is how consent arrives, may open it (ARCHITECTURE §6.4).",
        ],
        "_test_values_only": "Every SDP, candidate and voice_session_id here is fabricated.",
        "rows": rows,
    }
    out = Path(__file__).resolve().parent.parent / "protocol" / "vectors" / "voice-fsm"
    out.mkdir(parents=True, exist_ok=True)
    target = out / "voice_fsm_vectors.json"
    target.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {target} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
