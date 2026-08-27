package com.ridelink.core.security

import com.ridelink.core.model.PeerId
import com.ridelink.core.model.SpkiHash

/**
 * The trusted-peer record persisted after a successful pairing
 * ([PROTOCOL §4.5](../../../../../../../docs/PROTOCOL.md#45-pairing--first-meeting-only)).
 * [identitySpkiSha256] is the pin, and it is the only thing here that trust depends on — the rest
 * is for the UI (ADR-012).
 */
data class TrustedPeer(
    val peerId: PeerId,
    val identitySpkiSha256: SpkiHash,
    val displayName: String,
    val pairedAtEpochSeconds: Long,
    val lastSeenAtEpochSeconds: Long,
)

/**
 * What to do with a peer whose TLS certificate has just arrived. Exactly one of these, decided
 * before any session state changes.
 */
sealed interface PinDecision {
    /** Known peer, pin matches. Silent connect — no code, no prompt, even if the certificate was re-issued. */
    data object Trusted : PinDecision

    /** No stored pin for this peer: go to PROTOCOL §4.5 pairing, which requires SAS confirmation. */
    data object PairingRequired : PinDecision

    /** A failure that closes the connection. [code] is the PROTOCOL §4.6 wire code. */
    data class Refused(
        val code: String,
    ) : PinDecision

    companion object {
        /** The identity key changed. An unknown peer wearing a familiar name — never auto re-paired. */
        val PIN_MISMATCH: Refused = Refused("pin_mismatch")

        /** `HELLO.identity_spki_sha256` disagreed with the TLS certificate. Trust never derives from a claimed field. */
        val IDENTITY_MISMATCH: Refused = Refused("identity_mismatch")

        /** Structure, self-signature or validity window failed. Distinct so clock skew is not reported as an attack. */
        val CERTIFICATE_INVALID: Refused = Refused("certificate_invalid")
    }
}

/**
 * The pin check of [PROTOCOL §4.1](../../../../../../../docs/PROTOCOL.md#41-handshake) and
 * [§4.5.3](../../../../../../../docs/PROTOCOL.md#453-certificate-re-issuance-versus-key-rotation),
 * as a pure function.
 *
 * Pure on purpose: this is the single place that decides whether a peer is trusted, and it is
 * worth being able to exhaust its behaviour in a laptop test rather than inferring it from a
 * transport. It reads no clock and touches no socket — certificate structure and validity are
 * checked by the platform's X.509 implementation (`X509Certificate.checkValidity()` +
 * `verify()` on Android, `SecTrustEvaluateWithError` against the certificate as its own anchor on
 * iOS) and the outcome arrives here as [certificateStructurallyValid].
 *
 * `RideLinkCore.Security.PeerTrust` is the mirror; `protocol/vectors/identity/` pins both.
 */
object PeerTrust {
    /**
     * @param storedPin the pinned `identity_spki_sha256` for this peer, or null if it is unknown.
     * @param presentedSpki computed from the peer's TLS certificate. **The only trustworthy input.**
     * @param helloAdvertisedSpki `HELLO.identity_spki_sha256` — advisory, chosen by the peer, cross-checked here.
     * @param certificateStructurallyValid the platform's verdict on DER, self-signature and validity window.
     */
    @Suppress("ReturnCount") // one early return per PROTOCOL §4.1 outcome; the ORDER is the point
    fun decide(
        storedPin: SpkiHash?,
        presentedSpki: SpkiHash,
        helloAdvertisedSpki: SpkiHash?,
        certificateStructurallyValid: Boolean,
    ): PinDecision {
        // Order matters and is not arbitrary: an unparseable, unverifiable or out-of-window
        // certificate is rejected *before* anything reasons about whose key it claims to be, so a
        // device with a wrong clock gets `certificate_invalid` and never a security warning.
        if (!certificateStructurallyValid) return PinDecision.CERTIFICATE_INVALID

        // Checked before pairing is offered, so a peer that lies in HELLO never reaches the SAS
        // screen — otherwise the user would be asked to approve a code for an identity that is
        // not the one about to be pinned.
        if (helloAdvertisedSpki != null && helloAdvertisedSpki != presentedSpki) {
            return PinDecision.IDENTITY_MISMATCH
        }

        if (storedPin == null) return PinDecision.PairingRequired
        // ADR-012: the pin is over the key, so a re-issued certificate around the same key lands
        // here as Trusted with no prompt. A different key can never land here at all.
        return if (storedPin == presentedSpki) PinDecision.Trusted else PinDecision.PIN_MISMATCH
    }

    /**
     * Whether [value] is a well-formed `identity_spki_sha256` (ADR-012): `"sha256:"` followed by
     * exactly 64 **lowercase** hex characters. Lowercase is required rather than normalised, so
     * two peers cannot disagree about case and produce two different-looking pins for one key.
     */
    fun isWellFormedSpkiHash(value: String): Boolean = SPKI_HASH_FORMAT.matches(value)

    private val SPKI_HASH_FORMAT = Regex("^sha256:[0-9a-f]{64}$")
}
