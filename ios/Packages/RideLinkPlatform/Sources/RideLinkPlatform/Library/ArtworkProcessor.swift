import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Bounds embedded artwork before it ever reaches disk or a database row — this phase's brief §18/
/// §20's "bound dimensions, decoded memory, stored size" and "malformed artwork must not crash
/// indexing." Mirrors `com.ridelink.data.library.ArtworkProcessor`'s bounds exactly (1024 px, 512
/// KiB, quality 85) so a track imported on either phone produces comparably-sized cached artwork.
///
/// Untrusted input: an embedded picture is attacker-shaped the moment it comes from a file the user
/// merely *possesses* (brief §20's "even local files are untrusted input"). `ImageIO`'s
/// `kCGImageSourceThumbnailMaxPixelSize` option decodes a bounded thumbnail directly — the same
/// "measure before a full decode" discipline Android's `BitmapFactory.Options.inJustDecodeBounds`
/// pass gives, achieved here in one call rather than two.
public enum ArtworkProcessor {
    /// No music-player artwork needs to be larger than this to fill a phone screen at arm's length.
    private static let maxDimensionPx = 1024

    /// A cap on the *encoded* output, independent of dimensions — a highly compressible image at
    /// the maximum dimensions could otherwise still balloon on a pathological re-encode.
    private static let maxOutputBytes = 512 * 1024

    private static let jpegQuality: CGFloat = 0.85

    /// @return bounded JPEG bytes, or `nil` if `rawData` is empty, not decodable as an image at all,
    ///   or a decoded image could not be produced or re-encoded within `maxOutputBytes` — never a
    ///   thrown error.
    public static func processToBoundedJpeg(_ rawData: Data?) -> Data? {
        guard let rawData, !rawData.isEmpty else { return nil }
        guard let cgImage = decodeBounded(rawData) else { return nil }
        return compressBounded(cgImage)
    }

    /// `nil` if `rawData` is not a decodable image at all, or if its thumbnail could not be produced
    /// — a hostile, enormous image is never fully decoded at native size just to bound it.
    private static func decodeBounded(_ rawData: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(rawData as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDimensionPx,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// A pathological source image that still exceeds the byte cap after JPEG compression at
    /// `maxDimensionPx` is treated as "no usable artwork" rather than truncated — a truncated JPEG
    /// is a corrupt image, not a smaller one.
    private static func compressBounded(_ image: CGImage) -> Data? {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output, UTType.jpeg.identifier as CFString, 1, nil
        ) else { return nil }
        let properties: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: jpegQuality]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        let bytes = output as Data
        return bytes.count <= maxOutputBytes ? bytes : nil
    }
}
