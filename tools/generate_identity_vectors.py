#!/usr/bin/env python3
"""Regenerate protocol/vectors/identity/identity_vectors.json.

An independent third implementation of the encodings in ADR-017, written here so that Kotlin and
Swift are checked against something neither of them was ported from. No third-party imports, no
network. Run from the repository root:

    python3 tools/generate_identity_vectors.py

Public-key points below are throwaway values generated once for this file; the matching private
keys were never written to disk and do not exist. Nothing here is a device identity, and per
CLAUDE.md `vectors/identity/` never carries real key material.
"""

import hashlib
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "protocol/vectors/identity/identity_vectors.json"

# --- Minimal DER, per ADR-017 -------------------------------------------------------------------

def der_len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    body = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(body)]) + body


def tlv(tag: int, body: bytes) -> bytes:
    return bytes([tag]) + der_len(len(body)) + body


def seq(*items: bytes) -> bytes:
    return tlv(0x30, b"".join(items))


def der_set(*items: bytes) -> bytes:
    return tlv(0x31, b"".join(items))


def oid(raw: bytes) -> bytes:
    return tlv(0x06, raw)


def integer(magnitude: bytes) -> bytes:
    b = bytearray(magnitude)
    while len(b) > 1 and b[0] == 0x00 and not (b[1] & 0x80):
        del b[0]
    if not b:
        b = bytearray(b"\x00")
    if b[0] & 0x80:
        b.insert(0, 0x00)
    return tlv(0x02, bytes(b))


def bit_string(body: bytes) -> bytes:
    return tlv(0x03, b"\x00" + body)


def octet_string(body: bytes) -> bytes:
    return tlv(0x04, body)


def utf8_string(s: str) -> bytes:
    return tlv(0x0C, s.encode("utf-8"))


def utc_time(s: str) -> bytes:
    return tlv(0x17, s.encode("ascii"))


def boolean(v: bool) -> bytes:
    return tlv(0x01, b"\xff" if v else b"\x00")


def explicit(tag_number: int, body: bytes) -> bytes:
    return tlv(0xA0 | tag_number, body)


OID_EC_PUBLIC_KEY = bytes([0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01])
OID_PRIME256V1 = bytes([0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07])
OID_ECDSA_SHA256 = bytes([0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02])
OID_COMMON_NAME = bytes([0x55, 0x04, 0x03])
OID_BASIC_CONSTRAINTS = bytes([0x55, 0x1D, 0x13])
OID_KEY_USAGE = bytes([0x55, 0x1D, 0x0F])

SUBJECT_COMMON_NAME = "RideLink Device"


def spki(point: bytes) -> bytes:
    return seq(seq(oid(OID_EC_PUBLIC_KEY), oid(OID_PRIME256V1)), bit_string(point))


def spki_hash(point: bytes) -> str:
    return "sha256:" + hashlib.sha256(spki(point)).hexdigest()


def tbs_certificate(point: bytes, serial: bytes, not_before: str, not_after: str) -> bytes:
    name = seq(der_set(seq(oid(OID_COMMON_NAME), utf8_string(SUBJECT_COMMON_NAME))))
    signature_algorithm = seq(oid(OID_ECDSA_SHA256))
    extensions = explicit(3, seq(
        seq(oid(OID_BASIC_CONSTRAINTS), boolean(True), octet_string(seq())),
        seq(oid(OID_KEY_USAGE), boolean(True), octet_string(bytes([0x03, 0x02, 0x07, 0x80]))),
    ))
    return seq(
        explicit(0, integer(b"\x02")),
        integer(serial),
        signature_algorithm,
        name,
        seq(utc_time(not_before), utc_time(not_after)),
        name,
        spki(point),
        extensions,
    )


def certificate(tbs: bytes, signature: bytes) -> bytes:
    return seq(tbs, seq(oid(OID_ECDSA_SHA256)), bit_string(signature))


# --- Test material ------------------------------------------------------------------------------

def h(x: bytes) -> str:
    return x.hex()


# Throwaway P-256 public points. Uncompressed X9.63 form: 0x04 ‖ X(32) ‖ Y(32).
POINT_A = bytes.fromhex(
    "0425c034bba2b1790eb95671cf18f8852d1755c8ee170cb7b13abf2819aab8dbf1"
    "20515d7196f26c2b73c5c073b3ed26e6511c3a7f72f320c3c7f5e7b787efeb8b")
POINT_B = bytes.fromhex(
    "0418ec5199e0f5ede771c4896d95f1ec86d0a21072aee764593fcda596bba52016"
    "b53d41463b3483e27310c0f717529a09153ffa027b944475d6b81caed6d953d8")

SERIAL_HIGH_BIT = bytes.fromhex("7f0a1b2c3d4e5f60718293a4b5c6d7e8")
NOT_BEFORE = "260826120000Z"
NOT_AFTER = "360823120000Z"
# A fabricated ECDSA signature value: structurally an X9.62 SEQUENCE { r, s }, cryptographically
# meaningless. It exists only to pin how the outer Certificate wraps a signature.
FAKE_SIGNATURE = seq(integer(bytes.fromhex("0102030405060708090a0b0c0d0e0f10")),
                     integer(bytes.fromhex("8f1e2d3c4b5a69788796a5b4c3d2e1f0")))


def main() -> None:
    tbs_a = tbs_certificate(POINT_A, SERIAL_HIGH_BIT, NOT_BEFORE, NOT_AFTER)
    doc = {
        "_comment": (
            "Identity vectors for ADR-012 (identity_spki_sha256 is the only pinned value) and "
            "ADR-017 (P-256 key, shared DER certificate encoder). Every public point and every "
            "signature value here is FABRICATED/throwaway test material — no private key for any "
            "of it exists, and per CLAUDE.md vectors/identity/ never carries real key material. "
            "Regenerate with tools/generate_identity_vectors.py."
        ),

        "der_length_vectors": {
            "_comment": "DER definite-length, minimal form. The single most likely place for two "
                        "hand-written encoders to diverge, so it is pinned on its own.",
            "cases": [
                {"name": "zero", "length": 0, "expected_hex": h(der_len(0))},
                {"name": "one", "length": 1, "expected_hex": h(der_len(1))},
                {"name": "max-short-form", "length": 127, "expected_hex": h(der_len(127))},
                {"name": "first-long-form", "length": 128, "expected_hex": h(der_len(128))},
                {"name": "one-byte-long-form-max", "length": 255, "expected_hex": h(der_len(255))},
                {"name": "two-byte-long-form", "length": 256, "expected_hex": h(der_len(256))},
                {"name": "two-byte-long-form-max", "length": 65535, "expected_hex": h(der_len(65535))},
                {"name": "three-byte-long-form", "length": 65536, "expected_hex": h(der_len(65536))},
                {"name": "control-frame-cap", "length": 262144, "expected_hex": h(der_len(262144))},
            ],
        },

        "der_integer_vectors": {
            "_comment": "DER INTEGER, non-negative, minimal two's-complement. Leading zeroes are "
                        "stripped unless one is needed to keep the value positive.",
            "cases": [
                {"name": "zero", "magnitude_hex": "00", "expected_hex": h(integer(b"\x00"))},
                {"name": "one", "magnitude_hex": "01", "expected_hex": h(integer(b"\x01"))},
                {"name": "max-without-pad", "magnitude_hex": "7f", "expected_hex": h(integer(b"\x7f"))},
                {"name": "high-bit-set-needs-pad", "magnitude_hex": "80", "expected_hex": h(integer(b"\x80"))},
                {"name": "redundant-leading-zero-stripped", "magnitude_hex": "0001",
                 "expected_hex": h(integer(bytes.fromhex("0001")))},
                {"name": "necessary-leading-zero-kept", "magnitude_hex": "0080",
                 "expected_hex": h(integer(bytes.fromhex("0080")))},
                {"name": "many-redundant-leading-zeroes", "magnitude_hex": "00000001",
                 "expected_hex": h(integer(bytes.fromhex("00000001")))},
                {"name": "version-v3", "magnitude_hex": "02", "expected_hex": h(integer(b"\x02"))},
                {"name": "serial-16-bytes-high-bit-clear", "magnitude_hex": h(SERIAL_HIGH_BIT),
                 "expected_hex": h(integer(SERIAL_HIGH_BIT))},
                {"name": "serial-16-bytes-high-bit-set", "magnitude_hex": "ff" + h(SERIAL_HIGH_BIT)[2:],
                 "expected_hex": h(integer(bytes.fromhex("ff" + h(SERIAL_HIGH_BIT)[2:])))},
            ],
        },

        "spki_vectors": {
            "_comment": ("SubjectPublicKeyInfo for a P-256 key, built from the raw uncompressed "
                         "X9.63 point. 91 bytes, canonical. Android reads these bytes straight "
                         "out of PublicKey.getEncoded(); iOS rebuilds them from the point, "
                         "because SecKeyCopyExternalRepresentation returns the bare point "
                         "(ADR-017 §4). Both must land here."),
            "cases": [
                {"name": "point-a", "point_hex": h(POINT_A),
                 "expected": {"spki_der_hex": h(spki(POINT_A)), "spki_der_bytes": len(spki(POINT_A)),
                              "identity_spki_sha256": spki_hash(POINT_A)}},
                {"name": "point-b", "point_hex": h(POINT_B),
                 "expected": {"spki_der_hex": h(spki(POINT_B)), "spki_der_bytes": len(spki(POINT_B)),
                              "identity_spki_sha256": spki_hash(POINT_B)}},
            ],
            "rejected_points": [
                {"name": "empty", "point_hex": "", "reason": "not 65 bytes"},
                {"name": "too-short", "point_hex": h(POINT_A[:64]), "reason": "not 65 bytes"},
                {"name": "too-long", "point_hex": h(POINT_A + b"\x00"), "reason": "not 65 bytes"},
                {"name": "compressed-form-even", "point_hex": "02" + h(POINT_A[1:33]),
                 "reason": "leading byte must be 0x04 (uncompressed); compressed points are not accepted"},
                {"name": "compressed-form-odd", "point_hex": "03" + h(POINT_A[1:33]),
                 "reason": "leading byte must be 0x04 (uncompressed)"},
                {"name": "wrong-leading-byte", "point_hex": "00" + h(POINT_A[1:]),
                 "reason": "leading byte must be 0x04"},
            ],
        },

        "identity_spki_sha256_format_vectors": {
            "_comment": "ADR-012: \"sha256:\" followed by exactly 64 LOWERCASE hex characters.",
            "accepted": [
                {"name": "canonical", "value": spki_hash(POINT_A)},
                {"name": "all-zero-digest", "value": "sha256:" + "0" * 64},
                {"name": "all-f-digest", "value": "sha256:" + "f" * 64},
            ],
            "rejected": [
                {"name": "missing-prefix", "value": hashlib.sha256(spki(POINT_A)).hexdigest(),
                 "reason": "no \"sha256:\" prefix"},
                {"name": "uppercase-hex", "value": "sha256:" + hashlib.sha256(spki(POINT_A)).hexdigest().upper(),
                 "reason": "hex must be lowercase, so two peers cannot disagree on case"},
                {"name": "too-short", "value": "sha256:" + "a" * 63, "reason": "63 hex characters"},
                {"name": "too-long", "value": "sha256:" + "a" * 65, "reason": "65 hex characters"},
                {"name": "non-hex", "value": "sha256:" + "g" * 64, "reason": "not hex"},
                {"name": "wrong-algorithm-prefix", "value": "sha512:" + "a" * 64, "reason": "only sha256: exists in v1"},
                {"name": "empty", "value": "", "reason": "empty"},
            ],
        },

        "tbs_certificate_vectors": {
            "_comment": ("The exact TBSCertificate both platforms must produce for the same "
                         "inputs (ADR-017 §2). Fixed serial and fixed validity, so the encoding "
                         "is fully determined. Only the signature differs between platforms, "
                         "because ECDSA is randomised — which is why the signature is not pinned "
                         "here and the certificate case below uses a fabricated one."),
            "cases": [
                {
                    "name": "canonical-identity-certificate",
                    "input": {
                        "point_hex": h(POINT_A),
                        "serial_hex": h(SERIAL_HIGH_BIT),
                        "not_before_utc": NOT_BEFORE,
                        "not_after_utc": NOT_AFTER,
                        "subject_common_name": SUBJECT_COMMON_NAME,
                    },
                    "expected": {
                        "tbs_der_hex": h(tbs_a),
                        "tbs_der_bytes": len(tbs_a),
                        "certificate_der_hex_with_fabricated_signature":
                            h(certificate(tbs_a, FAKE_SIGNATURE)),
                        "fabricated_signature_der_hex": h(FAKE_SIGNATURE),
                    },
                },
                {
                    "name": "different-key-same-everything-else",
                    "input": {
                        "point_hex": h(POINT_B),
                        "serial_hex": h(SERIAL_HIGH_BIT),
                        "not_before_utc": NOT_BEFORE,
                        "not_after_utc": NOT_AFTER,
                        "subject_common_name": SUBJECT_COMMON_NAME,
                    },
                    "expected": {
                        "tbs_der_hex": h(tbs_certificate(POINT_B, SERIAL_HIGH_BIT, NOT_BEFORE, NOT_AFTER)),
                        "tbs_der_bytes": len(tbs_certificate(POINT_B, SERIAL_HIGH_BIT, NOT_BEFORE, NOT_AFTER)),
                    },
                },
            ],
            "properties": [
                {
                    "name": "subject-carries-no-device-information",
                    "expected": {
                        "subject_common_name": SUBJECT_COMMON_NAME,
                        "identical_on_every_device": True,
                        "note": ("ADR-017 §2 / CLAUDE.md privacy rules: the certificate is sent in "
                                 "the clear to anyone who can open the port, so it must contain no "
                                 "device name, user name, hardware serial or library information. "
                                 "Identity is the SPKI hash, never the subject text."),
                    },
                },
                {
                    "name": "no-subject-alternative-name-extension",
                    "expected": {
                        "extension_oids": ["2.5.29.19", "2.5.29.15"],
                        "note": "basicConstraints and keyUsage only. A SAN would be a durable identifier on the wire.",
                    },
                },
            ],
        },

        "pin_decision_vectors": {
            "_comment": ("PROTOCOL §4.1 and §4.5.3 as a pure function of (stored pin, SPKI "
                         "computed from the presented certificate, the advisory HELLO field, and "
                         "certificate validity). Never a network call, never a clock read — "
                         "`now_utc` is an input."),
            "cases": [
                {"name": "unknown-peer-no-stored-pin",
                 "input": {"stored_pin": None, "presented_spki": spki_hash(POINT_A),
                           "hello_advisory": spki_hash(POINT_A), "certificate_valid": True},
                 "expected": {"decision": "pairing_required"}},
                {"name": "known-peer-pin-matches",
                 "input": {"stored_pin": spki_hash(POINT_A), "presented_spki": spki_hash(POINT_A),
                           "hello_advisory": spki_hash(POINT_A), "certificate_valid": True},
                 "expected": {"decision": "trusted"}},
                {"name": "certificate-reissued-same-key-still-trusted",
                 "input": {"stored_pin": spki_hash(POINT_A), "presented_spki": spki_hash(POINT_A),
                           "hello_advisory": spki_hash(POINT_A), "certificate_valid": True,
                           "certificate_serial_changed": True, "certificate_validity_changed": True},
                 "expected": {"decision": "trusted",
                              "note": "ADR-012: the pin is the key, not the certificate bytes. Silent connect, no SAS."}},
                {"name": "identity-key-changed",
                 "input": {"stored_pin": spki_hash(POINT_A), "presented_spki": spki_hash(POINT_B),
                           "hello_advisory": spki_hash(POINT_B), "certificate_valid": True},
                 "expected": {"decision": "pin_mismatch", "error_code": "pin_mismatch",
                              "note": "never auto re-paired; requires an explicit forget-peer"}},
                {"name": "hello-advisory-field-lies",
                 "input": {"stored_pin": spki_hash(POINT_A), "presented_spki": spki_hash(POINT_A),
                           "hello_advisory": spki_hash(POINT_B), "certificate_valid": True},
                 "expected": {"decision": "identity_mismatch", "error_code": "identity_mismatch",
                              "note": ("PROTOCOL §4.1: HELLO.identity_spki_sha256 is advisory and is "
                                       "cross-checked against the TLS certificate. Trust never derives "
                                       "from a field a peer can choose.")}},
                {"name": "hello-advisory-lies-on-an-unknown-peer",
                 "input": {"stored_pin": None, "presented_spki": spki_hash(POINT_A),
                           "hello_advisory": spki_hash(POINT_B), "certificate_valid": True},
                 "expected": {"decision": "identity_mismatch", "error_code": "identity_mismatch",
                              "note": "checked before pairing is offered, so a liar never reaches the SAS screen"}},
                {"name": "certificate-expired-known-peer",
                 "input": {"stored_pin": spki_hash(POINT_A), "presented_spki": spki_hash(POINT_A),
                           "hello_advisory": spki_hash(POINT_A), "certificate_valid": False},
                 "expected": {"decision": "certificate_invalid", "error_code": "certificate_invalid",
                              "note": "a distinct code so a device clock problem is not reported to the user as an attack"}},
                {"name": "certificate-expired-outranks-pin-mismatch",
                 "input": {"stored_pin": spki_hash(POINT_A), "presented_spki": spki_hash(POINT_B),
                           "hello_advisory": spki_hash(POINT_B), "certificate_valid": False},
                 "expected": {"decision": "certificate_invalid",
                              "note": ("structure is checked before identity: an unparseable or "
                                       "out-of-window certificate is rejected without reasoning about "
                                       "whose key it claims to be")}},
            ],
        },

        "certificate_validity_vectors": {
            "_comment": "Pure window check. `now` is an input, never read from a clock inside the domain layer.",
            "cases": [
                {"name": "inside-window", "not_before_utc": NOT_BEFORE, "not_after_utc": NOT_AFTER,
                 "now_utc": "300101000000Z", "expected": {"valid": True}},
                {"name": "exactly-not-before", "not_before_utc": NOT_BEFORE, "not_after_utc": NOT_AFTER,
                 "now_utc": NOT_BEFORE, "expected": {"valid": True, "note": "boundaries are inclusive"}},
                {"name": "exactly-not-after", "not_before_utc": NOT_BEFORE, "not_after_utc": NOT_AFTER,
                 "now_utc": NOT_AFTER, "expected": {"valid": True, "note": "boundaries are inclusive"}},
                {"name": "one-second-before-window", "not_before_utc": NOT_BEFORE, "not_after_utc": NOT_AFTER,
                 "now_utc": "260826115959Z", "expected": {"valid": False, "reason": "not_yet_valid"}},
                {"name": "one-second-after-window", "not_before_utc": NOT_BEFORE, "not_after_utc": NOT_AFTER,
                 "now_utc": "360823120001Z", "expected": {"valid": False, "reason": "expired"}},
            ],
        },
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(doc, indent=2) + "\n")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
