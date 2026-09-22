import Foundation
import RideLinkCore

/// Phase 7 (ADR-028, FR-018): pure derivations from **existing** published state into what the
/// simplified Ride Mode screen renders. No new state of its own — every value already lives in
/// `SessionCoordinator`'s `SessionStatus`, `MusicCoordinator`'s `PlayerState`/`LibraryEntry`,
/// `VoiceController`'s `VoiceDiagnostics`/`IntercomPolicy`, or `AudioRouteSnapshot` — this only
/// decides how a riding surface reads them, deliberately without inventing a second status source
/// (brief §5's "no raw protocol complexity on the Ride Mode screen" cuts the other way too: never a
/// *fabricated* one either).
///
/// Kept in `RideLinkPlatform` rather than the `ios/RideLink` app target, mirroring every other
/// Phase 5+ presentation seam: the app target has no test target (`docs/STATUS.md` §4 problem 20),
/// so pure logic that must be testable on this platform lives here. Mirrors Android's Ride Mode
/// presentation mapping in shape, not in code — Kotlin/Compose and Swift/SwiftUI share no UI layer
/// (CLAUDE.md rule 1).
public enum RideModePresentation {
    /// A late sync publication must never imply synchronized playback while the link is down.
    public static func syncLabel(status: SessionStatus, syncState: SyncState) -> String {
        if status == .reconnecting { return "Synchronizing when connection returns" }
        guard status == .connected || status == .rideActive else { return "Waiting for peer" }
        switch syncState {
        case .inactive: return "Local music"
        case .clockUnready, .waitingForQueue, .scheduled: return "Synchronizing"
        case .waitingForContent: return "Waiting for content"
        case .synced: return "Synchronized"
        case .syncFailed: return "Sync failed — local music continues"
        case .desynchronized, .transportFailed, .localOverload: return "Sync unavailable — local music continues"
        }
    }

    // MARK: - Connection (FR-018's tri-state indicator)

    public enum ConnectionHealth: Sendable, Equatable {
        case healthy
        case reconnecting
        case disconnected
    }

    /// `.connected` and `.rideActive` both read as healthy — the connection row does not
    /// distinguish "riding" from "could start riding"; `canStartRide`/`isRideActive` below do.
    public static func connectionHealth(_ status: SessionStatus) -> ConnectionHealth {
        switch status {
        case .connected, .rideActive: return .healthy
        case .reconnecting: return .reconnecting
        case .idle, .discovering, .pairing, .connecting, .disconnected, .ending, .error: return .disconnected
        }
    }

    /// `SessionFsm`'s own legality (`.startRide` is legal only from `CONNECTED`) mirrored here so
    /// the entry point can gate itself without asking the FSM to reject a button press it could
    /// simply never offer.
    public static func canStartRide(_ status: SessionStatus) -> Bool { status == .connected }

    public static func isRideActive(_ status: SessionStatus) -> Bool { status == .rideActive }

    /// PROTOCOL §10's 120 s reconnect budget is spent — `DISCONNECTED` is the one state in which
    /// Ride Mode may surface an explicit retry action instead of the passive reconnecting indicator
    /// (brief §15). Never true merely because the peer is momentarily unreachable.
    public static func reconnectBudgetExhausted(_ status: SessionStatus) -> Bool { status == .disconnected }

    /// Whether the Ride Mode screen should be showing, given the previous frame's answer and the
    /// FSM's current `status`/`returnTo` — mirrors Android's `nextRideModeVisibility` in shape
    /// (`RideModeUiState.kt`), not in code. A naive `status == .rideActive` check (this file's
    /// original `isRideActive`, still kept above for call sites that only care about the ride-active
    /// case specifically) sends the rider back to the developer/diagnostics screen the instant an
    /// ordinary reconnect begins, because `SessionFsm` moves `status` to `.reconnecting` — exactly
    /// the setup-screen bounce brief §15 forbids for a transient loss that can legitimately last up
    /// to PROTOCOL §10's 120 s budget. `.reconnecting` keeps the screen up only when `returnTo` is
    /// `.rideActive` (never `.connected`, which was never riding). `.disconnected` deliberately
    /// preserves `previous` rather than resetting it: `FsmState.returnTo` does not survive budget
    /// exhaustion (it is only set for `.reconnecting`), so this is the one bit of memory needed to
    /// keep the budget-exhausted banner (brief §15) inside Ride Mode rather than dropping the rider
    /// back to the main screen at the exact moment they need the retry action. This is derived from
    /// the FSM's own output every call, never independently mutated by user action — the only way
    /// out of Ride Mode remains `endRide()` through `SessionFsm`, so it is not a second authority
    /// source (brief §19).
    public static func nextRideModeVisibility(
        previous: Bool,
        status: SessionStatus,
        returnTo: SessionStatus?
    ) -> Bool {
        switch status {
        case .rideActive: return true
        case .reconnecting: return returnTo == .rideActive
        case .disconnected: return previous
        default: return false
        }
    }

    // MARK: - Now playing

    public struct NowPlaying: Sendable, Equatable {
        public let title: String?
        public let artist: String?
        public let playing: Bool

        public init(title: String?, artist: String?, playing: Bool) {
            self.title = title
            self.artist = artist
            self.playing = playing
        }
    }

    /// `entry` is whatever `MusicCoordinator.currentEntry` already resolves — a Phase 3 library row
    /// or, via `SharedLibraryContentPort`, a Phase 4 verified-cache-only track. No second lookup.
    public static func nowPlaying(entry: LibraryEntry?, playerState: PlayerState) -> NowPlaying {
        NowPlaying(title: entry?.track.title, artist: entry?.track.artist, playing: playerState.playing)
    }

    // MARK: - Microphone (FR-018's mute/unmute, PTT-aware)

    public enum MicrophoneState: Sendable, Equatable {
        /// The capture device is not open — nothing to mute (PROTOCOL §4.4's `mic (device)`).
        case unavailable
        /// Full-duplex/VOX gate, user's own Mute latch engaged.
        case mutedIdle
        /// PTT gate, not currently held.
        case pttIdle
        /// PTT gate, currently transmitting.
        case pttTalking
        /// Full-duplex/VOX gate, transmitting (unmuted).
        case unmutedIdle
    }

    /// Folds PTT's separate "held" signal and the ordinary Mute latch into **one** state, because
    /// the view needs one label, not two booleans to reconcile against each other — the same
    /// PTT/mute distinction `VoiceCard` already draws (`onToggleMute` targets the user's latch, not
    /// the wire's `mic_muted`, which under PTT is true whenever the button is not held).
    public static func microphoneState(voice: VoiceDiagnostics, policy: IntercomPolicy) -> MicrophoneState {
        guard voice.localAudioOpen else { return .unavailable }
        if policy.gate == .ptt {
            return voice.pttHeld ? .pttTalking : .pttIdle
        }
        return voice.userMuted ? .mutedIdle : .unmutedIdle
    }

    // MARK: - Intercom mode

    public static func intercomModeLabel(_ policy: IntercomPolicy) -> String {
        policy.id.rawValue.replacingOccurrences(of: "MODE_", with: "Mode ")
    }

    // Whether the intercom is enabled at all is already `IntercomPolicy.intercomEnabled` (Mode E's
    // gate is `.disabled`) — reused directly by the view rather than re-derived here.

    // MARK: - Audio/connection health (never fabricated — brief §5)

    public enum AudioHealth: Sendable, Equatable {
        case normal
        case degraded
        case unknown
    }

    /// Read straight from the existing route diagnostics' own `routeState`/`mediaQuality` — never a
    /// second, invented notion of "degraded". `nil` (no route reported yet) is `.unknown`, not
    /// `.normal`: silence is not evidence of health.
    public static func audioHealth(route: AudioRouteSnapshot?) -> AudioHealth {
        guard let route else { return .unknown }
        if route.routeState == .transitioning { return .degraded }
        switch route.mediaQuality {
        case .full: return .normal
        case .reduced, .unavailable: return .degraded
        case .unknown: return .unknown
        }
    }
}
