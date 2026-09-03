import Foundation

/// The stages of bringing voice up, timed with a **monotonic** clock the caller supplies (CLAUDE.md
/// rule 5 — never a wall clock, and never for an interval).
///
/// ### What this can and cannot measure
///
/// It measures **software setup time**: how long it took between the user asking for voice and the media
/// transport carrying a remote track. That is a genuinely useful number — it is what a "why is it slow to
/// connect?" complaint is about, and TEST_PLAN V-01/V-02 will read it.
///
/// It is **not** latency. Mouth-to-ear latency (REQUIREMENTS' <200 ms target, TEST_PLAN A-09/V-11)
/// includes two Bluetooth hops, an encoder, a jitter buffer and a decoder, and can only be obtained by
/// playing a click into a real microphone and cross-correlating a recording of the real earbud output.
/// **Network RTT is not mouth-to-ear latency and neither is anything in this file**, so no value here may
/// be presented as approaching, meeting or bearing on that target.
public struct VoiceSetupTimeline: Sendable, Equatable {
    /// Start Intercom pressed, or a reconnect rebuild beginning (PROTOCOL §7.8).
    public var startRequestedAtMonoUs: Int64?
    /// The platform audio session and capture path came up.
    public var captureOpenAtMonoUs: Int64?
    /// The local description this side authored was created — the first signalling milestone.
    public var localDescriptionAtMonoUs: Int64?
    /// The peer's description was applied: both sides now agree on the media parameters.
    public var remoteDescriptionAtMonoUs: Int64?
    /// `RTCPeerConnection` reached `connected`: DTLS-SRTP is up.
    public var mediaConnectedAtMonoUs: Int64?
    /// The remote audio track appeared. The last software milestone before audio could flow.
    public var remoteTrackAtMonoUs: Int64?

    public init(
        startRequestedAtMonoUs: Int64? = nil,
        captureOpenAtMonoUs: Int64? = nil,
        localDescriptionAtMonoUs: Int64? = nil,
        remoteDescriptionAtMonoUs: Int64? = nil,
        mediaConnectedAtMonoUs: Int64? = nil,
        remoteTrackAtMonoUs: Int64? = nil
    ) {
        self.startRequestedAtMonoUs = startRequestedAtMonoUs
        self.captureOpenAtMonoUs = captureOpenAtMonoUs
        self.localDescriptionAtMonoUs = localDescriptionAtMonoUs
        self.remoteDescriptionAtMonoUs = remoteDescriptionAtMonoUs
        self.mediaConnectedAtMonoUs = mediaConnectedAtMonoUs
        self.remoteTrackAtMonoUs = remoteTrackAtMonoUs
    }

    /// Start Intercom -> capture path open. The route/profile switch dominates this on Bluetooth.
    public var captureOpenMs: Double? { span(startRequestedAtMonoUs, captureOpenAtMonoUs) }

    /// Start Intercom -> our own SDP created.
    public var localDescriptionMs: Double? { span(startRequestedAtMonoUs, localDescriptionAtMonoUs) }

    /// Start Intercom -> the peer's SDP applied. One control round trip lives in here.
    public var signallingMs: Double? { span(startRequestedAtMonoUs, remoteDescriptionAtMonoUs) }

    /// Start Intercom -> DTLS-SRTP connected.
    public var mediaConnectedMs: Double? { span(startRequestedAtMonoUs, mediaConnectedAtMonoUs) }

    /// Start Intercom -> remote track present. **Voice setup time**, and the only end-to-end figure this
    /// type produces. Explicitly not a latency figure — see the type doc.
    public var setupMs: Double? { span(startRequestedAtMonoUs, remoteTrackAtMonoUs) }

    private func span(_ from: Int64?, _ to: Int64?) -> Double? {
        guard let from, let to else { return nil }
        let delta = to - from
        // A negative span would mean the marks were recorded out of order, which is a bug in the caller
        // rather than a fast connection. Reported as absent rather than as a nonsense number.
        guard delta >= 0 else { return nil }
        return Double(delta) / Self.microsPerMs
    }

    private static let microsPerMs = 1_000.0
}

/// One milestone in `VoiceSetupTimeline`.
public enum VoiceSetupMark: String, Sendable, Equatable, CaseIterable {
    case startRequested = "START_REQUESTED"
    case captureOpen = "CAPTURE_OPEN"
    case localDescription = "LOCAL_DESCRIPTION"
    case remoteDescription = "REMOTE_DESCRIPTION"
    case mediaConnected = "MEDIA_CONNECTED"
    case remoteTrack = "REMOTE_TRACK"
}

/// Records `VoiceSetupMark`s, first-write-wins within one negotiation.
///
/// First-write-wins matters: WebRTC reports `connected` more than once across a session's
/// disconnect/reconnect cycles, and the setup figure is about the *first* time each milestone was reached.
/// A new negotiation calls `restart`, which is what makes the number per-generation rather than a lifetime
/// average.
public enum VoiceSetupTimer {
    public static func restart(atMonoUs: Int64) -> VoiceSetupTimeline {
        VoiceSetupTimeline(startRequestedAtMonoUs: atMonoUs)
    }

    public static func mark(
        _ timeline: VoiceSetupTimeline,
        _ mark: VoiceSetupMark,
        atMonoUs: Int64
    ) -> VoiceSetupTimeline {
        var next = timeline
        switch mark {
        case .startRequested:
            if next.startRequestedAtMonoUs == nil { next.startRequestedAtMonoUs = atMonoUs }
        case .captureOpen:
            if next.captureOpenAtMonoUs == nil { next.captureOpenAtMonoUs = atMonoUs }
        case .localDescription:
            if next.localDescriptionAtMonoUs == nil { next.localDescriptionAtMonoUs = atMonoUs }
        case .remoteDescription:
            if next.remoteDescriptionAtMonoUs == nil { next.remoteDescriptionAtMonoUs = atMonoUs }
        case .mediaConnected:
            if next.mediaConnectedAtMonoUs == nil { next.mediaConnectedAtMonoUs = atMonoUs }
        case .remoteTrack:
            if next.remoteTrackAtMonoUs == nil { next.remoteTrackAtMonoUs = atMonoUs }
        }
        return next
    }
}
