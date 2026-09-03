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
) -> dict:
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
    }


def row(name: str, before: dict, inp: dict, actions: list[dict], after: dict) -> dict:
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
