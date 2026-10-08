import RideLinkCore

/// Pure user-facing vocabulary; never used as command authority.
public enum UiPresentation {
    /// Mirrors Android's `connectionTitle` (Phase 9A.5 §4: one phrase per state on both platforms).
    public static func connectionTitle(_ value: SessionStatus) -> String {
        switch value {
        case .idle: return "Not connected"
        case .discovering: return "Searching…"
        case .connecting: return "Connecting…"
        case .pairing: return "Check the code"
        case .connected: return "Connected"
        case .rideActive: return "Riding"
        case .reconnecting: return "Reconnecting…"
        case .disconnected: return "Disconnected"
        case .ending: return "Ending session…"
        case .error: return "Connection problem"
        }
    }

    public static func connectionHint(_ value: SessionStatus) -> String {
        switch value {
        case .idle: return "Put both phones on the same Wi-Fi or hotspot, then tap Find other phone on each."
        case .discovering: return "Looking on this network. Open RideLink on the other phone and tap Find other phone."
        case .pairing: return "Make sure both phones show the same six digits."
        case .connecting: return "Setting up an encrypted connection."
        case .connected: return "Ready. Start the intercom or music, then start your ride."
        case .rideActive: return "Ride in progress."
        case .reconnecting: return "Trying to reach the other phone. Music on this phone keeps playing."
        case .disconnected: return "The other phone is out of reach. Check both are on the same network, then search again."
        case .ending: return "Closing the connection."
        case .error: return "The session stopped. Connection diagnostics below have the details."
        }
    }

    public static func voiceLabel(_ value: VoiceStatus) -> String {
        switch value {
        case .idle: return "Intercom off"
        case .negotiating, .connecting: return "Connecting the intercom…"
        case .active: return "Intercom on"
        case .failed: return "Intercom unavailable"
        }
    }

    /// The capture device's state in words — whether speech is being *sent* is a separate fact.
    public static func microphoneLabel(localAudioOpen: Bool, userMuted: Bool, transmitting: Bool) -> String {
        if !localAudioOpen { return "Microphone off" }
        if userMuted { return "Microphone muted" }
        return transmitting ? "Talking" : "Microphone on"
    }

    public static func voiceFailureLabel(_ value: VoiceFailure) -> String {
        switch value {
        case .micPermissionDenied: return "Allow microphone access in Settings, then start the intercom again."
        case .noAudioEndpoint: return "Connect your helmet unit or earphones, then try again."
        case .audioSessionActivationFailed: return "Audio is busy. Finish other audio activity, then try again."
        case .routeSelectionFailed: return "Check your audio output, then try again."
        case .captureStartFailed: return "The microphone did not start. Try the intercom again."
        case .webRtcFailed: return "The intercom could not connect. Try again."
        case .controlLinkLost: return "The connection to the other phone was lost."
        case .interrupted: return "Audio was interrupted by another app or a call."
        case .mediaServicesReset: return "The system reset audio. Try the intercom again."
        case .backgroundStartRefused, .foregroundServiceStartFailed: return "Bring RideLink to the front, then try again."
        case .sessionNotAuthenticated: return "Connect to the other phone and check the code first."
        }
    }

    public static func syncLabel(_ value: SyncState) -> String {
        switch value {
        case .inactive: return "Playing on this phone"
        case .clockUnready, .waitingForQueue, .scheduled: return "Getting both phones in step…"
        case .waitingForContent: return "Waiting for the track to download…"
        case .synced: return "Playing on both phones"
        case .desynchronized: return "Getting back in step…"
        case .syncFailed: return "Music sync paused"
        case .transportFailed: return "Music command did not reach the other phone"
        case .localOverload: return "Music sync is busy. Try again shortly."
        }
    }

    public static func rideMusicLabel(status: SessionStatus, state: SyncState, ownsTransport: Bool) -> String {
        if status == .reconnecting { return "Music sync resumes when reconnected" }
        guard status == .connected || status == .rideActive else { return "Other phone unavailable · Playing on this phone" }
        if [.syncFailed, .transportFailed, .localOverload, .desynchronized].contains(state) { return syncLabel(state) }
        return ownsTransport ? syncLabel(state) : "Playing on this phone"
    }

    public static func policyLabel(_ policy: IntercomPolicy) -> String {
        switch policy.id {
        case .modeA: "Always on · music lowered"
        case .modeB: "Voice-activated · music lowered"
        case .modeC: "Push to talk · music lowered"
        case .modeD: "Always on · music paused"
        case .modeE: "Music only · intercom off"
        case .custom: "Custom intercom mode"
        }
    }
}
