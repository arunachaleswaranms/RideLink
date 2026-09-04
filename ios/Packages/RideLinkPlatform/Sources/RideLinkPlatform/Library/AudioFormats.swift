import Foundation

/// REQUIREMENTS §9.1: MP3/AAC/M4A required (P0); FLAC allowed where the platform decodes it
/// natively, which `AVFoundation` does on every iOS version at or above this project's deployment
/// target — so FLAC needs no extra code here, only inclusion in this allowlist. Mirrors
/// `com.ridelink.data.library.AudioFormats` exactly.
///
/// An **extension gate**, checked before any attempt to open a file — the cheap, first-line
/// classification this phase's brief §9 requires ("indexing and playback capability are distinct: a
/// file that cannot be decoded must not crash the indexer"). A file passing this gate can still turn
/// out to be `DecodeStatus.corrupt`; a file failing it is `DecodeStatus.unsupported` without ever
/// being opened.
public enum AudioFormats {
    private static let supportedExtensions: Set<String> = ["mp3", "m4a", "aac", "flac"]

    public static func isSupportedExtension(_ filename: String) -> Bool {
        let ext = (filename as NSString).pathExtension.lowercased()
        return !ext.isEmpty && supportedExtensions.contains(ext)
    }
}
