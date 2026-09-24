import RideLinkCore

/// Pure user-facing vocabulary; never used as command authority.
public enum UiPresentation {
    public static func connectionHint(_ value: SessionStatus) -> String {
        switch value {
        case .idle: return "Connect both phones to the same Wi-Fi or hotspot, then find your peer."
        case .discovering: return "Looking for the other phone. Open RideLink and find peers there too."
        case .pairing: return "Compare the pairing digits on both phones."
        case .connecting: return "Connecting securely to your peer…"
        case .connected: return "Paired and connected. Set up audio and music, then start your ride."
        case .rideActive: return "Your ride is active."
        case .reconnecting: return "Trying to reconnect automatically. Local controls remain available."
        case .disconnected: return "Peer features are unavailable. Check the shared network and retry."
        case .ending: return "Ending the session…"
        case .error: return "The session could not continue. See diagnostics for details."
        }
    }

    public static func voiceLabel(_ value: VoiceStatus) -> String {
        switch value {
        case .idle: return "Intercom not started"
        case .negotiating, .connecting: return "Connecting intercom…"
        case .active: return "Intercom active"
        case .failed: return "Intercom unavailable"
        }
    }

    public static func voiceFailureLabel(_ value: VoiceFailure) -> String {
        switch value {
        case .micPermissionDenied: return "Allow microphone access in Settings, then start Intercom again."
        case .noAudioEndpoint: return "Connect an audio device, then try again."
        case .audioSessionActivationFailed: return "Audio is unavailable. Finish other audio activity, then try again."
        case .routeSelectionFailed: return "Check your audio output, then try again."
        case .captureStartFailed: return "The microphone could not start. Try Intercom again."
        case .webRtcFailed: return "Intercom could not connect. Try Intercom again."
        case .controlLinkLost: return "The peer connection was lost. Intercom needs a connected peer."
        case .interrupted: return "Audio was interrupted by another app or call."
        case .mediaServicesReset: return "Audio was reset by the system. Try Intercom again."
        case .backgroundStartRefused, .foregroundServiceStartFailed: return "Bring RideLink to the front, then try again."
        case .sessionNotAuthenticated: return "Connect and verify your peer before starting Intercom."
        }
    }

    public static func syncLabel(_ value: SyncState) -> String {
        switch value {
        case .inactive: return "Local playback"
        case .clockUnready, .waitingForQueue, .scheduled: return "Preparing synchronized playback…"
        case .waitingForContent: return "Waiting for the track to download…"
        case .synced: return "Synchronized"
        case .desynchronized: return "Restoring music sync…"
        case .syncFailed: return "Music sync paused"
        case .transportFailed: return "Music command could not reach your peer"
        case .localOverload: return "Music sync is busy. Try again shortly."
        }
    }

    public static func rideMusicLabel(status: SessionStatus, state: SyncState, ownsTransport: Bool) -> String {
        if status == .reconnecting { return "Music sync waits for connection" }
        guard status == .connected || status == .rideActive else { return "Peer unavailable · Local controls" }
        if [.syncFailed, .transportFailed, .localOverload, .desynchronized].contains(state) { return syncLabel(state) }
        return ownsTransport ? syncLabel(state) : "Local playback"
    }

    public static func policyLabel(_ policy: IntercomPolicy) -> String {
        switch policy.id {
        case .modeA: "A · Continuous / duck music"
        case .modeB: "B · Voice-activated / duck music"
        case .modeC: "C · Push to Talk / duck music"
        case .modeD: "D · Continuous / pause music"
        case .modeE: "E · Music only"
        case .custom: "Custom intercom mode"
        }
    }
}
