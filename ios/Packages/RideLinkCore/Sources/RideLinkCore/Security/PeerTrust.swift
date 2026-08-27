import Foundation

/// The trusted-peer record persisted after a successful pairing (PROTOCOL §4.5).
/// `identitySpkiSha256` is the pin, and it is the only thing here that trust depends on — the rest
/// is for the UI (ADR-012).
public struct TrustedPeer: Hashable, Sendable {
    public let peerId: PeerId
    public let identitySpkiSha256: SpkiHash
    public let displayName: String
    public let pairedAtEpochSeconds: Int64
    public let lastSeenAtEpochSeconds: Int64

    public init(peerId: PeerId,
                identitySpkiSha256: SpkiHash,
                displayName: String,
                pairedAtEpochSeconds: Int64,
                lastSeenAtEpochSeconds: Int64) {
        self.peerId = peerId
        self.identitySpkiSha256 = identitySpkiSha256
        self.displayName = displayName
        self.pairedAtEpochSeconds = pairedAtEpochSeconds
        self.lastSeenAtEpochSeconds = lastSeenAtEpochSeconds
    }
}

/// What to do with a peer whose TLS certificate has just arrived. Exactly one of these, decided
/// before any session state changes.
public enum PinDecision: Hashable, Sendable {
    /// Known peer, pin matches. Silent connect — no code, no prompt, even if the certificate was re-issued.
    case trusted
    /// No stored pin for this peer: go to PROTOCOL §4.5 pairing, which requires SAS confirmation.
    case pairingRequired
    /// A failure that closes the connection. The associated value is the PROTOCOL §4.6 wire code.
    case refused(code: String)

    /// The identity key changed. An unknown peer wearing a familiar name — never auto re-paired.
    public static let pinMismatch = PinDecision.refused(code: "pin_mismatch")
    /// `HELLO.identity_spki_sha256` disagreed with the TLS certificate. Trust never derives from a claimed field.
    public static let identityMismatch = PinDecision.refused(code: "identity_mismatch")
    /// Structure, self-signature or validity window failed. Distinct so clock skew is not reported as an attack.
    public static let certificateInvalid = PinDecision.refused(code: "certificate_invalid")
}

/// The pin check of PROTOCOL §4.1 and §4.5.3, as a pure function.
///
/// Pure on purpose: this is the single place that decides whether a peer is trusted, and it is
/// worth being able to exhaust its behaviour in a laptop test rather than inferring it from a
/// transport. It reads no clock and touches no socket — certificate structure and validity are
/// checked by the platform's X.509 implementation (`SecTrustEvaluateWithError` against the
/// certificate as its own anchor on iOS, `X509Certificate.checkValidity()` + `verify()` on
/// Android) and the outcome arrives here as `certificateStructurallyValid`.
///
/// `com.ridelink.core.security.PeerTrust` is the mirror; `protocol/vectors/identity/` pins both.
public enum PeerTrust {
    /// - Parameters:
    ///   - storedPin: the pinned `identity_spki_sha256` for this peer, or nil if it is unknown.
    ///   - presentedSpki: computed from the peer's TLS certificate. **The only trustworthy input.**
    ///   - helloAdvertisedSpki: `HELLO.identity_spki_sha256` — advisory, chosen by the peer, cross-checked here.
    ///   - certificateStructurallyValid: the platform's verdict on DER, self-signature and validity window.
    public static func decide(storedPin: SpkiHash?,
                              presentedSpki: SpkiHash,
                              helloAdvertisedSpki: SpkiHash?,
                              certificateStructurallyValid: Bool) -> PinDecision {
        // Order matters and is not arbitrary: an unparseable, unverifiable or out-of-window
        // certificate is rejected *before* anything reasons about whose key it claims to be, so a
        // device with a wrong clock gets `certificate_invalid` and never a security warning.
        guard certificateStructurallyValid else { return .certificateInvalid }

        // Checked before pairing is offered, so a peer that lies in HELLO never reaches the SAS
        // screen — otherwise the user would be asked to approve a code for an identity that is not
        // the one about to be pinned.
        if let advertised = helloAdvertisedSpki, advertised != presentedSpki { return .identityMismatch }

        guard let storedPin else { return .pairingRequired }
        // ADR-012: the pin is over the key, so a re-issued certificate around the same key lands
        // here as `.trusted` with no prompt. A different key can never land here at all.
        return storedPin == presentedSpki ? .trusted : .pinMismatch
    }

    /// Whether `value` is a well-formed `identity_spki_sha256` (ADR-012): `"sha256:"` followed by
    /// exactly 64 **lowercase** hex characters. Lowercase is required rather than normalised, so
    /// two peers cannot disagree about case and produce two different-looking pins for one key.
    public static func isWellFormedSpkiHash(_ value: String) -> Bool {
        SpkiHash.parse(value) != nil
    }
}
