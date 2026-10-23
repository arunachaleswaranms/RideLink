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

    /// What the one session button does from a given state — the UI half of `docs/STATUS.md` §4 problem
    /// 53. Every case maps to an event `SessionFsm` accepts from that state, so the button is never
    /// offered for a transition the FSM would reject. Here rather than in `MainScreen` so it is testable:
    /// the app target has no test bundle, and recovery from `pin_mismatch` runs through this button
    /// (ADR-012 Amendment A2). Mirrors Android's `sessionAction`.
    ///
    /// `nil` for `PAIRING`, `CONNECTING` and `ENDING`: the first two are waiting on two humans or a
    /// handshake and have no FSM event to abandon them, and `ENDING` is a teardown in progress — offering
    /// a button there would invite exactly the successor race this pass closes.
    ///
    /// `nil` for `ERROR` too, and for a different reason: its only exit is `.errorAcknowledged`, and
    /// nothing in the app emits `.fatalError`, so the state cannot be entered. Wiring a button to a state
    /// no production path reaches would be dead code; the remaining gap is recorded in `docs/STATUS.md`
    /// §4 rather than papered over here.
    public static func sessionAction(_ status: SessionStatus) -> SessionAction? {
        switch status {
        case .idle: return .start
        case .discovering: return .stopDiscovery
        case .connected, .rideActive, .reconnecting: return .end
        case .disconnected: return .retry
        case .pairing, .connecting, .ending, .error: return nil
        }
    }

    // MARK: - Paired phones (STATUS §4 problem 126, ADR-012 Amendment A1)

    /// Why *Forget* is disabled in `status`, or nil when it is offered. Reads the same `ForgetPeerPolicy`
    /// the coordinator enforces, so the button and the refusal cannot disagree. Mirrors Android's
    /// `forgetPeerUnavailableHint`.
    public static func forgetPeerUnavailableHint(_ status: SessionStatus) -> String? {
        if ForgetPeerPolicy.permits(status) { return nil }
        switch status {
        case .discovering: return "Tap Stop searching first, then forget the phone."
        case .pairing, .connecting: return "Finish or cancel the code check first, then end the session to forget the phone."
        case .ending: return "Closing the connection…"
        default: return "End the session first, then forget the phone."
        }
    }

    /// The confirmation dialog's body: what forgetting does, and what it deliberately leaves alone.
    public static func forgetPeerConfirmation(_ name: String) -> String {
        "This phone will stop trusting \(name). The next time the two phones connect, both screens will "
            + "show a six-digit code that you must compare and confirm again. Forget this phone on the "
            + "other phone too. This phone's own identity, your music and your settings are not changed."
    }

    public static func forgetPeerResultLabel(_ result: ForgetPeerResult) -> String? {
        switch result {
        case .forgotten: return nil
        case .refusedSessionActive: return "End the session first, then forget the phone."
        case .failed: return "Could not forget the phone. It is still trusted. Try again."
        }
    }

    /// The `pin_mismatch` security card's explanation, here so it names the control that resolves it.
    public static let pinMismatchExplanation =
        "The other phone's identity key has changed. That happens after a reinstall — but it is "
            + "also what an impersonation attempt looks like. RideLink will not reconnect until you "
            + "forget this phone under Paired phones, on both phones, and pair again with a new code."
}

/// The one session button's four meanings (`UiPresentation.sessionAction`). Mirrors Android's
/// `SessionAction`.
public enum SessionAction: Sendable, Equatable, CaseIterable {
    case start
    case stopDiscovery
    case end
    case retry

    public var label: String {
        switch self {
        case .start: return "Find other phone"
        case .stopDiscovery: return "Stop searching"
        case .end: return "End session"
        case .retry: return "Search again"
        }
    }
}
