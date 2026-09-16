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

#: Marks "this row does not exercise the pending-intent/resume machinery" (ADR-020 Amendment A11).
#: Both are **required** keys on every state, mirroring `negotiation_control_generation`: a row that
#: omits them fails both platforms' decoders rather than quietly meaning "false/absent".
_PENDING_DEFAULT = object()


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
    pending_start_intent: bool = False,
    authenticated_control_generation: int | None = None,
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

    Pending intent defaults false and recorded availability defaults null: older rows describe a
    table that has not received ControlAuthenticated. Neither field is inferred from consent.
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
        "pending_start_intent": pending_start_intent,
        "authenticated_control_generation": authenticated_control_generation,
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


def send_state(
    vsid: str | None,
    wire: str,
    mic_muted: bool = False,
    mode: str = "CONTINUOUS",
    owner: int | None = CTL_A,
) -> dict:
    return {
        "kind": "SendVoiceState",
        "voice_session_id": vsid,
        "state": wire,
        "mic_muted": mic_muted,
        "mode": mode,
        "control_generation": owner,
    }


# Every action that puts a frame on the wire names **the control lifetime whose connection it may be
# written to** (STATUS §4 problem 64, ADR-020 Amendment A9). It defaults to CTL_A for the same reason
# `state()`'s owner does — a row that does not say otherwise is a row about one control lifetime —
# and the rows that are about two name it. It is receiver-local: nothing here is serialised to a peer.
def send_offer(vsid: str, sdp: str, owner: int | None = CTL_A) -> dict:
    return {"kind": "SendOffer", "voice_session_id": vsid, "sdp": sdp, "control_generation": owner}


def send_answer(vsid: str, sdp: str, owner: int | None = CTL_A) -> dict:
    return {"kind": "SendAnswer", "voice_session_id": vsid, "sdp": sdp, "control_generation": owner}


def send_candidate(
    vsid: str,
    candidate: str,
    sdp_mid: str | None,
    sdp_mline_index: int,
    owner: int | None = CTL_A,
) -> dict:
    return {
        "kind": "SendCandidate",
        "voice_session_id": vsid,
        "candidate": candidate,
        "sdp_mid": sdp_mid,
        "sdp_mline_index": sdp_mline_index,
        "control_generation": owner,
    }


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
            [send_offer(VSID_A, SDP)],
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
                send_answer(VSID_A, SDP),
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
                send_candidate(VSID_A, CANDIDATE, "0", 0)
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
            # The intent that began it arrived on B's link, so B is both the owner and the only
            # connection this offer's `VOICE_STATE` may be written to.
            [send_state(VSID_FRESH, "negotiating", owner=CTL_B), {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}],
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
    # --- StartRequested with no authenticated lifetime (P61-F, ADR-020 Amendment A11) ----------
    #
    # A user can press Start in the gap between one control link dying and PROTOCOL §10's ladder
    # restoring the next. The press must not be refused — ARCHITECTURE §6.4 requires capture to be
    # opened while the app is foreground-visible, and this may be the last such moment — but it also
    # must not create a negotiation, because there is no link to negotiate over and, worse, no
    # lifetime to own it. A negotiation owned by nobody is the one state no boundary can retire.
    #
    # So: consent, and only consent — plus a **one-shot pending intent** the successor's
    # authentication event consumes (ADR-020 Amendment A11, STATUS §4 problem 69). `attachVoice`'s
    # §7.8 rebuild was A8's answer, but it is gated on the *published* capture-open projection and a
    # press deferred past `.connected` is invisible to it, which STATUS §2aq.6 measured. The intent
    # is the designed answer: the press keeps its two halves (consent now, authority never), and
    # the resume is an explicit reducer input rather than a coordinator re-read.
    rows.append(
        row(
            "start-with-no-authenticated-lifetime-opens-capture-and-records-a-pending-intent",
            state(OFFERER, IDLE, authenticated_control_generation=None),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": None},
            [{"kind": "StartLocalAudio"}],
            state(
                OFFERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                pending_start_intent=True,
                authenticated_control_generation=None,
            ),
        )
    )
    rows.append(
        row(
            "an-answerers-start-with-no-authenticated-lifetime-states-no-intent",
            state(ANSWERER, IDLE, authenticated_control_generation=None),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": None},
            # Not even a SendVoiceState: there is no link to send an intent-to-talk on, and §7.3's
            # intent is an answerer's *only* wire effect, so sending one that cannot leave is the
            # unrecoverable loss STATUS §4 problem 59 is about.
            [{"kind": "StartLocalAudio"}],
            state(
                ANSWERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                pending_start_intent=True,
                authenticated_control_generation=None,
            ),
        )
    )
    rows.append(
        row(
            # And the rebuild: the same user, once a successor has authenticated. Capture is already
            # open, so no StartLocalAudio — but the negotiation now begins, owned by the successor.
            "the-reconnect-rebuild-starts-the-negotiation-the-gap-press-could-not",
            state(OFFERER, IDLE, None, local_audio_open=True),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": CTL_B},
            [send_state(VSID_FRESH, "negotiating", owner=CTL_B), {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}],
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
    # --- the pending intent is a one-shot resume, not a retry (ADR-020 Amendment A11) -----------
    #
    # STATUS §4 problem 69. The successor's authentication is an explicit input, and it is what
    # consumes the intent — exactly one fresh negotiation, owned by the generation the **event**
    # named. The two halves of a gap press finally have a consumer: consent was recorded by the
    # press, authority arrives with the event, and neither is reconstructed from the other.
    rows.append(
        row(
            "the-successors-authentication-resumes-the-pending-gap-press-as-one-fresh-negotiation",
            state(
                OFFERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                pending_start_intent=True,
                authenticated_control_generation=None,
            ),
            {"kind": "ControlAuthenticated", "control_generation": CTL_B, "fresh_voice_session_id": VSID_FRESH},
            [send_state(VSID_FRESH, "negotiating", owner=CTL_B), {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}],
            state(
                OFFERER,
                NEGOTIATING,
                VSID_FRESH,
                local_audio_open=True,
                negotiation_control_generation=CTL_B,
                pending_start_intent=False,
                authenticated_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # An answerer's resume states §7.3's intent-to-talk under the successor, on the successor's
            # link — the wire effect the gap press could not produce because there was no link.
            "an-answerers-pending-intent-resumes-as-the-successors-intent-to-talk",
            state(
                ANSWERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                pending_start_intent=True,
                authenticated_control_generation=None,
            ),
            {"kind": "ControlAuthenticated", "control_generation": CTL_B, "fresh_voice_session_id": VSID_FRESH},
            [send_state(None, "negotiating", owner=CTL_B)],
            state(
                ANSWERER,
                NEGOTIATING,
                None,
                local_audio_open=True,
                negotiation_control_generation=CTL_B,
                pending_start_intent=False,
                authenticated_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # P69-F — duplicate availability. A second authentication of the same lifetime (or any
            # authentication arriving after the intent was consumed) finds a live negotiation and is
            # a recorded no-op: never a second offer, never a second voice_session_id, never a re-own.
            "a-duplicate-authentication-is-inert",
            state(
                OFFERER,
                NEGOTIATING,
                VSID_FRESH,
                local_audio_open=True,
                negotiation_control_generation=CTL_B,
                authenticated_control_generation=CTL_B,
            ),
            {"kind": "ControlAuthenticated", "control_generation": CTL_B, "fresh_voice_session_id": VSID_B},
            [],
            state(
                OFFERER,
                NEGOTIATING,
                VSID_FRESH,
                local_audio_open=True,
                negotiation_control_generation=CTL_B,
                authenticated_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # An authentication with nothing pending records the lifetime and does nothing else — the
            # ordinary `attachVoice` first-connect case.
            "an-authentication-with-no-pending-intent-records-the-lifetime-and-starts-nothing",
            state(OFFERER, IDLE, authenticated_control_generation=None),
            {"kind": "ControlAuthenticated", "control_generation": CTL_A, "fresh_voice_session_id": VSID_FRESH},
            [],
            state(
                OFFERER,
                IDLE,
                None,
                negotiation_control_generation=None,
                pending_start_intent=False,
                authenticated_control_generation=CTL_A,
            ),
        )
    )
    rows.append(
        row(
            # P69-B — the other order. The successor authenticated first (the event reduced), and the
            # press arrives late, still carrying the honest nil it read at tap time. The press
            # resolves against the lifetime the table has **seen** — the same decision the
            # coordinator would have made had the press not deferred past `.connected`. One
            # negotiation, owned by the event's lifetime, no intent left behind.
            "a-late-gap-press-resolves-against-the-lifetime-the-table-has-seen",
            state(
                OFFERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                authenticated_control_generation=CTL_B,
            ),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": None},
            [send_state(VSID_FRESH, "negotiating", owner=CTL_B), {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}],
            state(
                OFFERER,
                NEGOTIATING,
                VSID_FRESH,
                local_audio_open=True,
                negotiation_control_generation=CTL_B,
                pending_start_intent=False,
                authenticated_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # P69-H — the same late press meeting a **held** offer the successor delivered. The held
            # offer supplies the authority (Amendment A10's rule, reached from the nil-press side):
            # answered under the offer's own lifetime, exactly once, and never a second negotiation.
            "a-late-gap-press-answers-the-lifetime-the-table-has-seens-held-offer",
            state(
                ANSWERER,
                IDLE,
                None,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                held_remote_offer={"voice_session_id": VSID_B, "sdp": SDP},
                negotiation_control_generation=CTL_B,
                authenticated_control_generation=CTL_B,
            ),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": None},
            [
                {"kind": "StartLocalAudio"},
                {"kind": "ApplyRemoteOffer", "voice_session_id": VSID_B, "sdp": SDP},
                {"kind": "DrainQueuedCandidates"},
                {"kind": "CreateAnswer", "voice_session_id": VSID_B},
            ],
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_B,
                local_audio_open=True,
                remote_description_applied=True,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                negotiation_control_generation=CTL_B,
                pending_start_intent=False,
                authenticated_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # P69-C — the retry-loop hazard, refused by construction. A send failure degrades to IDLE
            # with consent still recorded and **manufactures no intent**, so the next authentication
            # has nothing to consume. Only a press in a real gap sets the intent.
            "a-send-failure-leaves-no-pending-intent-for-the-next-authentication-to-consume",
            state(
                OFFERER,
                NEGOTIATING,
                VSID_B,
                local_audio_open=True,
                negotiation_control_generation=CTL_B,
                authenticated_control_generation=CTL_B,
            ),
            {"kind": "NegotiationSendFailed", "voice_session_id": VSID_B},
            [{"kind": "StopMediaTransport"}],
            state(
                OFFERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                pending_start_intent=False,
                authenticated_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # P69-D — an explicit stop clears the intent: the same action that withdrew consent
            # withdrew the request.
            "an-explicit-stop-clears-a-pending-intent",
            state(
                OFFERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                pending_start_intent=True,
                authenticated_control_generation=None,
            ),
            {"kind": "StopRequested"},
            # No SendVoiceState: there is no negotiation to name, and no link to send one on.
            [{"kind": "StopMediaTransport"}, {"kind": "ReleaseLocalAudio"}],
            state(
                OFFERER,
                IDLE,
                None,
                negotiation_control_generation=None,
                pending_start_intent=False,
                authenticated_control_generation=None,
            ),
        )
    )
    rows.append(
        row(
            # P69-E — the intent survives the boundary between two successors: that gap is exactly
            # what it is for. The successor's authentication is what consumes it, and the
            # negotiation is C's from creation.
            "the-pending-intent-survives-a-boundary-and-is-consumed-by-the-next-successor",
            state(
                OFFERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                pending_start_intent=True,
                authenticated_control_generation=None,
            ),
            {"kind": "ControlAuthenticated", "control_generation": CTL_C, "fresh_voice_session_id": VSID_FRESH},
            [send_state(VSID_FRESH, "negotiating", owner=CTL_C), {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}],
            state(
                OFFERER,
                NEGOTIATING,
                VSID_FRESH,
                local_audio_open=True,
                negotiation_control_generation=CTL_C,
                pending_start_intent=False,
                authenticated_control_generation=CTL_C,
            ),
        )
    )
    rows.append(
        row(
            # A boundary clears the recorded lifetime — a press after it cannot resolve against a
            # lifetime whose death the boundary itself just named.
            "a-boundary-clears-the-recorded-lifetime-while-the-intent-survives",
            state(
                OFFERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                pending_start_intent=True,
                authenticated_control_generation=CTL_B,
            ),
            {"kind": "ControlLinkLost", "retired_control_generation": CTL_B},
            [],
            state(
                OFFERER,
                IDLE,
                None,
                local_audio_open=True,
                negotiation_control_generation=None,
                pending_start_intent=True,
                authenticated_control_generation=None,
            ),
        )
    )
    # --- a held offer may not cross a control lifetime (STATUS §4 problem 63) ------------------
    #
    # ADR-020 Amendment A9. A `VOICE_OFFER` held for want of local consent (§7.3) is negotiation
    # state, and A8 already gave it an owner. What A8 did not do is ask whether the *consent* that
    # answers it belongs to the same lifetime. It did not, and the answer branch then set the owner
    # to the press's lifetime — so a successor's Start adopted a dead lifetime's SDP, reused a
    # `voice_session_id` the offerer had already discarded with its own copy of that link, and left
    # the predecessor's boundary inert. PROTOCOL §7.8 wants a reconnect to rebuild voice as a
    # **fresh** negotiation; this was the one path that quietly did the opposite.
    #
    # Both directions are here, because the press and the offer can go stale relative to each other
    # either way round and only one of the two is about the offer.
    rows.append(
        row(
            "a-held-offer-from-a-retired-lifetime-is-discarded-rather-than-answered",
            state(
                ANSWERER,
                IDLE,
                None,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                held_remote_offer={"voice_session_id": VSID_A, "sdp": SDP},
                negotiation_control_generation=CTL_A,
            ),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": CTL_B},
            [
                {"kind": "StartLocalAudio"},
                drop("RETIRED_HELD_OFFER"),
                # §7.3's intent-to-talk, on B's link — never an answer naming A's generation.
                send_state(None, "negotiating", owner=CTL_B),
            ],
            state(
                ANSWERER,
                NEGOTIATING,
                None,
                local_audio_open=True,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                negotiation_control_generation=CTL_B,
            ),
        )
    )
    rows.append(
        row(
            # The same lifetime that delivered the offer is the one consenting: answered, unchanged.
            "a-held-offer-is-still-answered-by-the-lifetime-that-delivered-it",
            state(
                ANSWERER,
                IDLE,
                None,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                held_remote_offer={"voice_session_id": VSID_A, "sdp": SDP},
                negotiation_control_generation=CTL_B,
            ),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": CTL_B},
            [
                {"kind": "StartLocalAudio"},
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
    # --- consent outlives a control lifetime; control authority does not (STATUS §4 problem 66) ---
    #
    # ADR-020 Amendment A10. The opposite ordering to the two rows above: the *press* is the stale
    # thing, because a newer lifetime's offer was reduced while the tap sat in the mailbox. A9
    # refused it outright, which was safe and **not live**: the offerer sends one VOICE_OFFER per
    # voice_session_id (§7.4), attachVoice's §7.8 rebuild has already run and found no open capture,
    # and the user has already consented — so the held offer stayed held for the rest of the ride
    # segment.
    #
    # The two halves of a press separate. Its control authority is stale and contributes nothing; its
    # consent is ride-segment state and is exactly as valid as when the user tapped. The held offer
    # supplies the authenticated lifetime and the voice_session_id, so the negotiation is B's from
    # creation — note negotiation_control_generation stays CTL_B, not the press's CTL_A.
    rows.append(
        row(
            "a-stale-start-answers-a-newer-lifetimes-held-offer-under-that-offers-own-lifetime",
            state(
                ANSWERER,
                IDLE,
                None,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                held_remote_offer={"voice_session_id": VSID_B, "sdp": SDP},
                negotiation_control_generation=CTL_B,
            ),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": CTL_A},
            [
                {"kind": "StartLocalAudio"},
                {"kind": "ApplyRemoteOffer", "voice_session_id": VSID_B, "sdp": SDP},
                {"kind": "DrainQueuedCandidates"},
                {"kind": "CreateAnswer", "voice_session_id": VSID_B},
            ],
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_B,
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
            # The same press with capture already open. The only difference is that no StartLocalAudio
            # is emitted — consent that is already recorded is not re-recorded — which is what says the
            # progress comes from the held offer rather than from opening anything.
            "a-stale-start-with-capture-already-open-still-answers-the-newer-held-offer",
            state(
                ANSWERER,
                IDLE,
                None,
                local_audio_open=True,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                held_remote_offer={"voice_session_id": VSID_B, "sdp": SDP},
                negotiation_control_generation=CTL_B,
            ),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": CTL_A},
            [
                {"kind": "ApplyRemoteOffer", "voice_session_id": VSID_B, "sdp": SDP},
                {"kind": "DrainQueuedCandidates"},
                {"kind": "CreateAnswer", "voice_session_id": VSID_B},
            ],
            state(
                ANSWERER,
                NEGOTIATING,
                VSID_B,
                local_audio_open=True,
                remote_description_applied=True,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                negotiation_control_generation=CTL_B,
            ),
        )
    )
    # SUPERSEDED_START_LIFETIME now has **no row**, and that is the finding rather than an omission.
    # What it still covers is newer-owned negotiation state that is not a held offer, and no legal
    # state has that shape: an owner is set only by a transition that also sets a live status or a
    # held offer, a live status returns through start's idempotence, and only an answerer can hold an
    # offer. `testNegotiationStateAndItsOwningControlLifetimeArePresentTogetherOrNotAtAll` is the
    # assertion that says so — a row for the residue would fail it, which is how this was confirmed
    # rather than assumed. The branch is kept as a fail-closed refusal, for the reason controlLinkLost
    # keeps its null-owner branch: the alternative is a negotiation owned by a lifetime that has ended.
    rows.append(
        row(
            # And an offerer's press from a retired lifetime, which has no held offer to protect but
            # is refused for the same reason: there is no link for the offer it would author.
            "an-offerers-start-from-a-retired-lifetime-consents-and-starts-no-negotiation",
            state(
                OFFERER,
                IDLE,
                None,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                negotiation_control_generation=None,
            ),
            {"kind": "StartRequested", "fresh_voice_session_id": VSID_FRESH, "control_generation": CTL_A},
            # Nothing owned, so nothing proves this press's lifetime is over: it proceeds normally.
            # The refusal below is the *owned* case, and the two rows together are what say the
            # table refuses on evidence rather than on suspicion.
            [{"kind": "StartLocalAudio"}, send_state(VSID_FRESH, "negotiating"), {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}],
            state(
                OFFERER,
                NEGOTIATING,
                VSID_FRESH,
                local_audio_open=True,
                peer_voice_enabled=True,
                peer_reported_state=NEGOTIATING,
                negotiation_control_generation=CTL_A,
            ),
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

    # A11: availability is separate from negotiation ownership. Expected states are written from
    # the event rules, independently of either reducer implementation.
    for role in (OFFERER, ANSWERER):
        available = state(role, authenticated_control_generation=CTL_B)
        rows.append(row(
            f"{role}-a-delayed-A-boundary-preserves-idle-B-availability", available,
            {"kind": "ControlLinkLost", "retired_control_generation": CTL_A}, [], available,
        ))
        failed = state(role, local_audio_open=True, authenticated_control_generation=CTL_B)
        rows.append(row(
            f"{role}-duplicate-B-availability-cannot-retry-a-failed-send", failed,
            {"kind": "ControlAuthenticated", "control_generation": CTL_B, "fresh_voice_session_id": VSID_FRESH},
            [], failed,
        ))
        rows.append(row(
            f"{role}-only-a-new-successor-can-rebuild-existing-consent", failed,
            {"kind": "ControlAuthenticated", "control_generation": CTL_C, "fresh_voice_session_id": VSID_FRESH},
            ([send_state(VSID_FRESH, "negotiating", owner=CTL_C),
              {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}] if role == OFFERER else
             [send_state(None, "negotiating", owner=CTL_C)]),
            state(role, NEGOTIATING, VSID_FRESH if role == OFFERER else None, local_audio_open=True,
                  negotiation_control_generation=CTL_C, authenticated_control_generation=CTL_C),
        ))
        rows.append(row(
            f"{role}-late-nil-press-opens-capture-under-recorded-B", available,
            {"kind": "StartRequested", "control_generation": None, "fresh_voice_session_id": VSID_FRESH},
            [{"kind": "StartLocalAudio"}] +
            ([send_state(VSID_FRESH, "negotiating", owner=CTL_B),
              {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}] if role == OFFERER else
             [send_state(None, "negotiating", owner=CTL_B)]),
            state(role, NEGOTIATING, VSID_FRESH if role == OFFERER else None, local_audio_open=True,
                  negotiation_control_generation=CTL_B, authenticated_control_generation=CTL_B),
        ))
    rows.append(row(
        "held-B-alone-supplies-authority-to-nil-consent",
        state(ANSWERER, held_remote_offer={"voice_session_id": VSID_B, "sdp": SDP},
              negotiation_control_generation=CTL_B),
        {"kind": "StartRequested", "control_generation": None, "fresh_voice_session_id": VSID_FRESH},
        [{"kind": "StartLocalAudio"}, {"kind": "ApplyRemoteOffer", "voice_session_id": VSID_B, "sdp": SDP},
         {"kind": "DrainQueuedCandidates"}, {"kind": "CreateAnswer", "voice_session_id": VSID_B}],
        state(ANSWERER, NEGOTIATING, VSID_B, local_audio_open=True, remote_description_applied=True,
              negotiation_control_generation=CTL_B),
    ))
    rows.append(row(
        "retiring-A-negotiation-keeps-independent-B-availability",
        state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True,
              negotiation_control_generation=CTL_A, authenticated_control_generation=CTL_B),
        {"kind": "ControlLinkLost", "retired_control_generation": CTL_A},
        [{"kind": "StopMediaTransport"}],
        state(OFFERER, local_audio_open=True, authenticated_control_generation=CTL_B),
    ))
    rows.append(row(
        "Connected-B-before-delayed-loss-A-replaces-media-with-fresh-B-negotiation",
        state(OFFERER, NEGOTIATING, VSID_A, local_audio_open=True,
              negotiation_control_generation=CTL_A, authenticated_control_generation=CTL_A),
        {"kind": "ControlAuthenticated", "control_generation": CTL_B, "fresh_voice_session_id": VSID_FRESH},
        [{"kind": "StopMediaTransport"}, send_state(VSID_FRESH, "negotiating", owner=CTL_B),
         {"kind": "CreateOffer", "voice_session_id": VSID_FRESH}],
        state(OFFERER, NEGOTIATING, VSID_FRESH, local_audio_open=True,
              negotiation_control_generation=CTL_B, authenticated_control_generation=CTL_B),
    ))
    # A12 / Problem 71: the stale press contributes consent; recorded successor authority wins.
    for role in (OFFERER, ANSWERER):
        for press, available, owner in ((CTL_A, CTL_B, CTL_B), (CTL_B, CTL_A, CTL_B)):
            vsid = VSID_FRESH if role == OFFERER else None
            actions = [{"kind": "StartLocalAudio"}, send_state(vsid, "negotiating", owner=owner)]
            if role == OFFERER:
                actions.append({"kind": "CreateOffer", "voice_session_id": vsid})
            rows.append(row(
                f"{role}-Start-{press}-with-recorded-{available}-establishes-under-{owner}",
                state(role, authenticated_control_generation=available),
                {"kind": "StartRequested", "control_generation": press, "fresh_voice_session_id": VSID_FRESH},
                actions,
                state(role, NEGOTIATING, vsid, local_audio_open=True,
                      negotiation_control_generation=owner, authenticated_control_generation=available),
            ))
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
            "A Start carrying an older control generation than recorded ControlAuthenticated availability supplies consent only; the explicit successor event supplies authority, with no current-live lookup (ADR-020 A12, Problem 71).",
            "No row may have an ANSWERER emit CreateOffer or SendOffer. §7.3: only the leader offers.",
            "No row may have an OFFERER accept a VOICE_OFFER or an ANSWERER accept a VOICE_ANSWER.",
            "No row where the input's voice_session_id differs from the state's may produce any action other than RecordDroppedSignal. That is the §7.2 generation guard.",
            "ControlLinkLost must never emit ReleaseLocalAudio: the capture device survives a link blip (ARCHITECTURE §6.3/§6.4).",
            "ControlLinkLost must never emit SendVoiceState: there is no link to send it on.",
            "NegotiationSendFailed must never emit ReleaseLocalAudio or SendVoiceState, for ControlLinkLost's two reasons, and must never act on a generation other than the one the state holds — that guard is why it is a separate input rather than a second use of ControlLinkLost (STATUS §4 problem 57).",
            "No ModeSelected row may emit any action other than SendVoiceState, and none may change the status: choosing a gate is a local policy change, not a state transition of the voice session (PROTOCOL §7.4, ADR-021).",
            "No ModeSelected row may emit StartLocalAudio or ReleaseLocalAudio. PTT and VOX gate transmission, never the capture device (ARCHITECTURE §6.3).",
            "No row whose input is a received signal may start a negotiation (CreateOffer/CreateAnswer) when local_audio_open is false. The microphone is never opened because a *peer* asked — only a local StartRequested, which is how consent arrives, may open it (ARCHITECTURE §6.4).",
            "The pending gap-press intent is a one-shot, and only an explicit event consumes it: a ControlAuthenticated may start at most one negotiation from it, a send failure never manufactures it, an explicit stop clears it, and no row may create a second live negotiation over one that already exists (ADR-020 Amendment A11, STATUS §4 problem 69).",
            "A StartRequested carrying control_generation=null may establish a negotiation only under a lifetime an input delivered — the state's authenticated_control_generation (set by ControlAuthenticated) or a held offer's own owner. Never may a null press invent a generation (ADR-020 Amendment A11).",
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
