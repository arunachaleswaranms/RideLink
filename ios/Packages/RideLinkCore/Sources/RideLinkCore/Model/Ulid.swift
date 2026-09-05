import Foundation

/// Generates a fresh identifier in the 26-character Crockford-base32 shape `ManifestId`/
/// `TransferId` already require (both minted at the app layer: a `ManifestId` by whichever peer is
/// about to send `MANIFEST_BEGIN`, a `TransferId` by the requester in `TRANSFER_REQUEST`).
///
/// **Not** a spec-faithful monotonic ULID (a real ULID encodes a sortable 48-bit millisecond
/// timestamp; this is uniform CSPRNG output over the full 26 characters). Nothing here depends on
/// timestamp-sortability for a `ManifestId`/`TransferId` — only on the shape and on
/// unpredictability — mirroring `com.ridelink.core.model.Ulid`'s identical stance exactly.
///
/// `SystemRandomNumberGenerator` rather than `Security.framework`'s `SecRandomCopyBytes` (which
/// `BulkTokenTable` uses, for its own stronger token-secrecy requirement): that keeps this pure per
/// CLAUDE.md rule 9 (RideLinkCore imports only Foundation+CryptoKit), the same way Android's
/// mirror uses `java.security.SecureRandom` — the JVM standard library, not an Android platform
/// API — from inside its own pure `core` module.
public enum Ulid {
    private static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    public static func generate() -> String {
        var rng = SystemRandomNumberGenerator()
        return String((0..<26).map { _ in alphabet[Int.random(in: 0..<alphabet.count, using: &rng)] })
    }
}
