import Foundation
import MediaPlayer

/// Shapes the dictionary `MPNowPlayingInfoCenter.default().nowPlayingInfo` expects, factored out
/// from the actual assignment so this is unit-testable off a device — there is no lock screen under
/// `swift test`, but the dictionary this builds is a plain value the test target can assert on
/// directly.
///
/// Lives in `RideLinkPlatform`, not `RideLinkCore` (CLAUDE.md rule 9): `MediaPlayer` is a platform
/// framework and `RideLinkCore`'s import allowlist is Foundation + CryptoKit only (its own
/// `Package.swift` comment), so the real `MPMediaItemProperty*`/`MPNowPlayingInfoProperty*` key
/// constants cannot be referenced there. This is `RideLinkPlatform`'s existing role — the one place
/// allowed to know Apple platform APIs exist — applied to the music plane the same way
/// `AVAudioEnginePlayer` already is.
///
/// Fills in ARCHITECTURE §6.2's "Background / lock screen" row (`MPNowPlayingInfoCenter` +
/// `MPRemoteCommandCenter`), specified since the doc's first draft but with no implementation
/// anywhere until this pass (closure-audit Finding D).
public enum NowPlayingInfoBuilder {
    /// `title`/`artist` are `nil` when there is no library entry to resolve them from (the caller's
    /// job — see `MusicCoordinator.currentEntry`) and are simply omitted from the result, matching
    /// how `MPNowPlayingInfoCenter` itself treats a missing key: no key at all, never an empty
    /// string standing in for "unknown".
    ///
    /// `durationMs` is omitted the same way when it is `0` (a track loaded but not yet reporting a
    /// real duration, or no track at all) — `MPMediaItemPropertyPlaybackDuration: 0` would read to
    /// the system as a real zero-length track rather than as "unknown yet".
    public static func build(
        title: String?,
        artist: String?,
        durationMs: Int64,
        positionMs: Int64,
        playing: Bool
    ) -> [String: Any] {
        var info: [String: Any] = [:]
        if let title { info[MPMediaItemPropertyTitle] = title }
        if let artist { info[MPMediaItemPropertyArtist] = artist }
        if durationMs > 0 { info[MPMediaItemPropertyPlaybackDuration] = seconds(fromMs: durationMs) }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = seconds(fromMs: positionMs)
        info[MPNowPlayingInfoPropertyPlaybackRate] = playing ? 1.0 : 0.0
        return info
    }

    private static func seconds(fromMs ms: Int64) -> Double {
        Double(ms) / millisecondsPerSecond
    }

    private static let millisecondsPerSecond: Double = 1000
}
