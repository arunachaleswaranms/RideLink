import CoreTransferable
import RideLinkCore
import RideLinkPlatform
import SwiftUI
import UniformTypeIdentifiers

/// Deliberately developer-oriented (CLAUDE.md Phase 2b scope): device identity, connection status, the
/// six-digit pairing prompt, security warnings, the intercom card, and diagnostics (peer, RTT, clock
/// offset/jitter, reconnect count, discovery count, transport). No Ride Mode UI belongs here yet.
///
/// The scene phase is reported into the coordinator rather than looked up there: it is the only honest
/// source for "the app is foreground-visible", which `RideStartPolicy` needs, and leaving the foreground
/// releases the PTT gate (this phase's brief §25).
struct MainScreen: View {
    let coordinator: SessionCoordinator
    /// Phase 3's local music stack, deliberately independent of `coordinator` — see
    /// `MusicCoordinator`'s own doc comment. A `.failure` is shown inline rather than hidden, the
    /// same honesty `SecureTransportUnavailableView` gives a failed `SessionCoordinator`.
    let music: Result<MusicCoordinator, Error>
    /// Phase 5's synchronisation plane. `nil` when either the session or the music stack failed to
    /// construct — the card is then simply absent, exactly as `SharedLibraryView` is.
    let syncPlayback: SyncPlaybackPresenter?
    let deviceDescription: String

    var body: some View {
        NavigationStack {
            ScrollView {
                content
                    .padding(RideDesign.lg)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("RideLink")
            .navigationBarTitleDisplayMode(.inline)
            .background(RideDesign.background)
        }
        .tint(RideDesign.primary)
        .controlSize(.large)
        .onChange(of: scenePhase) { _, phase in
            coordinator.setAppForegroundVisible(phase == .active)
        }
        // Visibility is *derived* from `SessionFsm`'s own `status`/`returnTo` every time either
        // changes (`RideModePresentation.nextRideModeVisibility`, mirroring Android's
        // `nextRideModeVisibility`) — `rideModeVisible` is one bit of memory of the FSM's own past
        // output, not an independent decision (brief §19: UI state must never become an authority
        // source). A naive `status == .rideActive` check would drop the rider back to this screen
        // the instant an ordinary reconnect starts, since `status` becomes `.reconnecting` for up to
        // PROTOCOL §10's 120 s budget — exactly the setup-screen bounce brief §15 forbids.
        // `fullScreenCover`'s binding is read-only by construction: the only way out is
        // `RideModeView`'s End Ride button, which goes through `coordinator.endRide()` and therefore
        // through `SessionFsm` — never a swipe-to-dismiss short-circuiting the FSM.
        .onChange(of: coordinator.state) { _, state in
            rideModeVisible = RideModePresentation.nextRideModeVisibility(
                previous: rideModeVisible, status: state.status, returnTo: state.returnTo
            )
        }
        .onAppear {
            rideModeVisible = RideModePresentation.nextRideModeVisibility(
                previous: rideModeVisible, status: coordinator.state.status, returnTo: coordinator.state.returnTo
            )
        }
        .fullScreenCover(isPresented: .constant(rideModeVisible)) {
            if case .success(let musicCoordinator) = music {
                RideModeView(coordinator: coordinator, music: musicCoordinator, syncPlayback: syncPlayback)
            }
        }
    }

    private var authenticated: Bool { coordinator.state.status == .connected || coordinator.state.status == .rideActive }

    /// Connection first, then the intercom and music, then links to the long lists — every list
    /// that grows with a library is its own lazy destination (Phase 9A.5 §5).
    @ViewBuilder
    private var content: some View {
        VStack(alignment: .leading, spacing: RideDesign.xl) {
            VStack(alignment: .leading, spacing: RideDesign.md) {
                ConnectionSummary(status: coordinator.state.status, peerCount: coordinator.discoveredPeers.count)
                HStack(spacing: RideDesign.sm) {
                    // FR-018 (Phase 7, ADR-028): the entry point into the simplified riding surface,
                    // gated on `SessionFsm`'s own legality and on local music existing.
                    if RideModePresentation.canStartRide(coordinator.state.status), case .success = music {
                        Button("Start ride") { coordinator.startRide() }
                            .buttonStyle(.borderedProminent).foregroundStyle(RideDesign.onPrimary)
                    }
                    // One button, four meanings, decided by `SessionFsm`'s own legal transitions rather
                    // than by this screen (`docs/STATUS.md` §4 problem 53).
                    if let action = sessionAction(coordinator.state.status) {
                        let secondary = coordinator.state.status == .connected || action == .stopDiscovery
                        if secondary {
                            Button(action.label) { perform(action) }.buttonStyle(.bordered)
                        } else {
                            Button(action.label) { perform(action) }
                                .buttonStyle(.borderedProminent).foregroundStyle(RideDesign.onPrimary)
                        }
                    }
                }
            }

            if let alert = coordinator.securityAlert {
                SecurityAlertCard(code: alert) { coordinator.dismissSecurityAlert() }
            }
            if let prompt = coordinator.pairingPrompt {
                PairingCard(prompt: prompt) { coordinator.confirmPairing(accepted: $0) }
            }

            // PROTOCOL §7.1 in the UI: voice controls exist only once the trust gate has passed.
            // A disabled button would still be a button; an absent section cannot be pressed.
            if authenticated {
                VStack(alignment: .leading, spacing: RideDesign.md) {
                    Text("Intercom").font(.title3.weight(.semibold)).foregroundStyle(.secondary)
                        .accessibilityAddTraits(.isHeader)
                    VoiceCard(
                        voice: coordinator.voiceDiagnostics,
                        coexistence: coordinator.coexistenceDiagnostics,
                        policy: coordinator.intercomPolicy,
                        peerAudioState: coordinator.peerAudioState,
                        refusal: coordinator.lastIntercomRefusal,
                        // Routed through the coordinator, which owns the controller's lifetime — the
                        // view never touches `VoiceController` directly (CLAUDE.md rule 8).
                        onStartIntercom: { coordinator.startIntercom() },
                        onStopIntercom: { coordinator.endIntercom() },
                        // The user's own Mute latch, not the wire's `mic_muted` — under PTT the latter is
                        // true whenever the button is not held, and toggling from it would be a coin flip.
                        onToggleMute: { coordinator.setMicrophoneMuted(!coordinator.voiceDiagnostics.userMuted) },
                        onPushToTalkHeld: { coordinator.setPushToTalkHeld($0) },
                        onSelectPolicy: { coordinator.selectIntercomPolicy($0) }
                    )
                }
            }

            // Deliberately independent of `coordinator.state.status` — local music must be fully
            // usable in airplane mode, with no peer, regardless of session state.
            switch music {
            case .success(let musicCoordinator):
                MusicSection(
                    musicCoordinator: musicCoordinator,
                    sharedEntries: coordinator.sharedLibrary?.remoteEntries ?? [],
                    synchronized: syncPlayback?.isSynchronizedModeActive == true
                )
            case .failure(let error):
                Text("Local music unavailable. Restart RideLink to try again.")
                DisclosureGroup("Music diagnostics") { Text(String(describing: error)) }
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            // Same gate as the intercom (PROTOCOL §7.1 / brief §22): the shared library and the
            // synchronisation plane exist only once the trust gate has passed.
            if authenticated, let sharedLibrary = coordinator.sharedLibrary {
                VStack(alignment: .leading, spacing: RideDesign.md) {
                    NavigationLink {
                        SharedMusicScreen(coordinator: sharedLibrary, syncPlayback: syncPlayback, onPlayLocally: playSharedTrackLocally)
                    } label: {
                        HStack {
                            Label("Other phone's music", systemImage: "music.note.house")
                            Spacer()
                            Text(sharedLibrary.remoteEntries.count == 1 ? "1 track" : "\(sharedLibrary.remoteEntries.count.formatted()) tracks")
                                .foregroundStyle(.secondary)
                            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                        }
                        .padding(.horizontal, RideDesign.lg)
                        .frame(minHeight: RideDesign.touch + RideDesign.sm)
                        .background(RideDesign.surface, in: RoundedRectangle(cornerRadius: RideDesign.radius))
                    }
                    .buttonStyle(.plain)
                    if let syncPlayback {
                        SyncPlaybackView(presenter: syncPlayback, sharedLibrary: sharedLibrary)
                    }
                }
            }

            DisclosureGroup("Connection diagnostics") {
                Text(deviceDescription)
                TransportBanner(transportLabel: coordinator.controlDiagnostics.transportLabel)
                DiagnosticsCard(
                    diagnostics: coordinator.controlDiagnostics,
                    discoveredPeerCount: coordinator.discoveredPeers.count,
                    discoveryCount: coordinator.discoveryCount,
                    localIdentityPrefix: coordinator.localIdentityPrefix
                )

                ResyncDiagnosticsCard(diagnostics: coordinator.resyncDiagnostics)
                DiagnosticsExportCard(source: coordinator.diagnosticsExport)
            }.font(.subheadline)
        }
    }

    private func perform(_ action: SessionAction) {
        switch action {
        case .start: coordinator.startDiscovery()
        case .stopDiscovery: coordinator.cancelDiscovery()
        case .end: coordinator.endSession()
        case .retry: coordinator.retryDiscovery()
        }
    }

    @Environment(\.scenePhase) private var scenePhase
    @State private var rideModeVisible = false

    /// Mirrors Android's `MainActivity.attemptPlaySharedTrackLocally`. Two cases, per closure-audit
    /// Finding G:
    ///
    /// 1. A Phase 3 imported `LibraryEntry` already shares this entry's `content_hash` — play it
    ///    exactly like a local library row, unchanged from before this pass.
    /// 2. Otherwise, the track exists only as a verified Phase-4 cache entry (never imported) —
    ///    `SharedLibraryCoordinator.cachedFile` hands back its on-disk location, and
    ///    `MusicCoordinator.playExternalVerifiedCachedTrack` plays it through the *same* one
    ///    player/queue. There is still no second, cache-file-only player.
    private func playSharedTrackLocally(_ entry: ManifestEntry) {
        guard let hash = entry.contentHash, case .success(let musicCoordinator) = music else { return }
        // Looked up in the repository, not in the library's search-filtered list (Phase 9A.5).
        if let localEntry = musicCoordinator.localEntry(contentHash: hash) {
            musicCoordinator.playNow(localEntry)
            return
        }
        guard let sharedLibrary = coordinator.sharedLibrary, let fileURL = sharedLibrary.cachedFile(hash) else { return }
        musicCoordinator.playExternalVerifiedCachedTrack(hash, fileURL: fileURL)
    }
}

/// Green once the link is TLS 1.3, because the banner's job is to be *accurate*: the Phase 1a
/// version was amber and said `PLAIN / PHASE 1A / NOT SECURE`, and a banner that keeps crying wolf
/// after the transport is secure trains the user to ignore it.
private struct TransportBanner: View {
    let transportLabel: String

    private var isSecure: Bool { transportLabel.hasPrefix("TLS") }

    var body: some View {
        Text("TRANSPORT: \(transportLabel)")
            .font(.caption.bold())
            .padding(RideDesign.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background((isSecure ? Color.green : Color.yellow).opacity(0.25))
            .cornerRadius(RideDesign.radius)
    }
}

/// PROTOCOL §4.5: the two users compare six digits on two screens and both confirm.
///
/// The code is shown large and monospaced because it is read aloud across a car park, and the
/// wording says *compare*, not *enter* — there is nowhere to type it, deliberately: a code that
/// travelled between the devices would prove nothing (PROTOCOL §4.5.1).
struct PairingCard: View {
    let prompt: PairingPrompt
    let onDecision: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.md) {
            Text("Check the code")
                .font(.headline)
            Text(prompt.peerDisplayName.isEmpty ? "Nearby phone" : prompt.peerDisplayName)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("\(prompt.sas6.prefix(3)) \(prompt.sas6.suffix(3))")
                .font(.system(size: 44, weight: .bold, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .center)
                .accessibilityLabel(prompt.sas6.map(String.init).joined(separator: " "))
            Text("Both phones must show the same six digits. If they differ, tap They differ.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            HStack(spacing: RideDesign.md) {
                Button("They match") { onDecision(true) }
                    .buttonStyle(.borderedProminent).foregroundStyle(RideDesign.onPrimary)
                Button("They differ") { onDecision(false) }
                    .buttonStyle(.bordered)
            }
        }
        .padding(RideDesign.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.blue.opacity(0.1))
        .cornerRadius(RideDesign.radius)
    }
}

/// A refused handshake the user has to see. `pin_mismatch` is the one that matters: ADR-012
/// requires it to surface as a warning and never to be resolved by silently re-pairing.
struct SecurityAlertCard: View {
    let code: String
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            Text(code == "pin_mismatch" ? "Security warning" : "Connection refused")
                .font(.headline)
            Text(explanation)
                .font(.footnote)
            DisclosureGroup("Security details") { Text(code).font(.caption.monospaced()) }
            Button("Dismiss", action: onDismiss)
                .buttonStyle(.bordered)
        }
        .padding(RideDesign.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.12))
        .cornerRadius(RideDesign.radius)
    }

    private var explanation: String {
        switch code {
        case "pin_mismatch":
            "The other phone's identity key has changed. That happens after a reinstall — but it is "
                + "also what an impersonation attempt looks like. RideLink will not reconnect until you "
                + "forget this phone and pair again."
        case "certificate_invalid":
            "The other phone's certificate is outside its validity window. Check the date and time on both phones."
        case "identity_mismatch":
            "The other phone's stated identity did not match its certificate. The connection was refused."
        default:
            "The connection was refused."
        }
    }
}

/// FR-023's Phase 7 half (PROTOCOL §10, ADR-028) — the developer diagnostics view's resync status,
/// kept separate from `RideModeView`'s own passive indicator (brief §5's "detailed diagnostics
/// remain in the developer diagnostics view").
private struct ResyncDiagnosticsCard: View {
    let diagnostics: ResyncDiagnostics

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            Text("Resync (Phase 7)").font(.headline)
            diagnosticRow("request pending", "\(diagnostics.requestPending)")
            diagnosticRow("reconnect requests", "\(diagnostics.reconnectRequestCount)")
            diagnosticRow("desync requests", "\(diagnostics.desyncRequestCount)")
            diagnosticRow("role violations", "\(diagnostics.roleViolationCount)")
            diagnosticRow("last outcome", outcomeLabel(diagnostics.lastOutcome))
            diagnosticRow("last snapshot manifest_revision", diagnostics.lastSnapshotManifestRevision.map(String.init) ?? "—")
            diagnosticRow("last snapshot command_seq", diagnostics.lastSnapshotCommandSeq.map(String.init) ?? "—")
        }
        .padding(RideDesign.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.gray.opacity(0.1))
        .cornerRadius(RideDesign.radius)
    }

    private func outcomeLabel(_ outcome: ResyncOutcome) -> String {
        switch outcome {
        case .none: "none"
        case .requested: "requested"
        case .snapshotPending: "snapshot pending (clock/content not ready)"
        case .reconciled: "reconciled"
        case .cancelled: "cancelled (the ride or session it belonged to ended)"
        case .sendFailed: "send failed"
        }
    }

    private func diagnosticRow(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: RideDesign.xs) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.body)
        }
    }
}

private struct DiagnosticsCard: View {
    let diagnostics: ControlDiagnostics
    let discoveredPeerCount: Int
    let discoveryCount: Int
    let localIdentityPrefix: String

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            Text("Connection diagnostics")
                .font(.headline)
            diagnosticRow("Control state", controlStateLabel(diagnostics.controlState))
            // Both identities are shown redacted to 6 hex, matching the ARCHITECTURE §11 logging
            // rule — enough to compare two screens, far too little to identify a device.
            diagnosticRow("This device", localIdentityPrefix)
            diagnosticRow("Peer identity", diagnostics.peerIdentityPrefix ?? "—")
            diagnosticRow("TLS", diagnostics.negotiatedProtocol ?? "—")
            diagnosticRow("Peer", diagnostics.remotePeerId ?? "—")
            diagnosticRow("Local leader", diagnostics.isLocalLeader.map { $0 ? "true" : "false" } ?? "—")
            diagnosticRow("RTT", diagnostics.rttMs.map { String(format: "%.1f ms", $0) } ?? "—")
            diagnosticRow("Clock offset", diagnostics.clockOffsetUs.map { "\($0) µs" } ?? "—")
            diagnosticRow("Clock jitter", diagnostics.clockJitterUs.map { "\($0) µs" } ?? "—")
            diagnosticRow("Reconnect count", "\(diagnostics.reconnectCount)")
            diagnosticRow("Discovered peers (current)", "\(discoveredPeerCount)")
            diagnosticRow("Discovery count (cumulative)", "\(discoveryCount)")
        }
        .padding(RideDesign.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.gray.opacity(0.1))
        .cornerRadius(RideDesign.radius)
    }

    private func diagnosticRow(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: RideDesign.xs) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.body)
        }
    }
}

/// What the one session button does from a given state — the UI half of `docs/STATUS.md` §4 problem
/// 53. Every case maps to an event `SessionFsm` accepts from that state, so the button is never
/// offered for a transition the FSM would reject.
///
/// `nil` for `PAIRING`, `CONNECTING` and `ENDING`: the first two are waiting on two humans or a
/// handshake and have no FSM event to abandon them, and `ENDING` is a teardown in progress — offering
/// a button there would invite exactly the successor race this pass closes.
///
/// `nil` for `ERROR` too, and for a different reason: its only exit is `.errorAcknowledged`, and
/// nothing in the app emits `.fatalError`, so the state cannot be entered. Wiring a button to a state
/// no production path reaches would be dead code; the remaining gap is recorded in `docs/STATUS.md`
/// §4 rather than papered over here.
private enum SessionAction {
    case start
    case stopDiscovery
    case end
    case retry

    var label: String {
        switch self {
        case .start: return "Find other phone"
        case .stopDiscovery: return "Stop searching"
        case .end: return "End session"
        case .retry: return "Search again"
        }
    }
}

private func sessionAction(_ status: SessionStatus) -> SessionAction? {
    switch status {
    case .idle: return .start
    case .discovering: return .stopDiscovery
    case .connected, .rideActive, .reconnecting: return .end
    case .disconnected: return .retry
    case .pairing, .connecting, .ending, .error: return nil
    }
}

private func controlStateLabel(_ state: ControlState) -> String {
    switch state {
    case .idle: "Idle"
    case .connecting: "Connecting…"
    case .connected: "Connected"
    case .reconnecting: "Reconnecting…"
    case .disconnected: "Disconnected"
    case .ended: "Ended"
    }
}

/// The connection state as a status line, mirroring Android's `ConnectionSummary`: a small spinner
/// only while work is in progress, a still dot otherwise, the title cross-fading on change, and
/// "Other phone found" appearing when discovery finds one. Reduce Motion turns the spinner into a dot.
struct ConnectionSummary: View {
    let status: SessionStatus
    let peerCount: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var working: Bool { [.discovering, .connecting, .reconnecting, .ending].contains(status) }
    private var connected: Bool { status == .connected || status == .rideActive }

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.xs) {
            HStack(spacing: RideDesign.md) {
                if working && !reduceMotion {
                    ProgressView().controlSize(.small)
                } else {
                    Circle()
                        .fill(connected ? RideDesign.primary : status == .error ? RideDesign.error : Color.secondary.opacity(0.5))
                        .frame(width: 10, height: 10)
                }
                Text(UiPresentation.connectionTitle(status))
                    .font(.title2.bold())
                    .contentTransition(.opacity)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: status)
            }
            .accessibilityElement(children: .combine)
            Text(UiPresentation.connectionHint(status)).font(.body).foregroundStyle(.secondary)
            if status == .discovering && peerCount > 0 {
                Text("Other phone found. Connecting…")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(RideDesign.primary)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// NFR-08 (ADR-029 Amendment A2): the redacted event log, offered to the system share sheet as a
/// text file. RideLink sends nothing itself — the user picks a target or cancels — and there is no
/// network path here. Mirrors Android's `DiagnosticsExportCard`.
private struct DiagnosticsExportCard: View {
    let source: DiagnosticsExportSource

    var body: some View {
        VStack(alignment: .leading, spacing: RideDesign.sm) {
            Text("Diagnostics log").font(.headline)
            Text("Shares this session's redacted event log as a text file, through the share sheet. Nothing is sent unless you choose where it goes.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            ShareLink(
                item: DiagnosticsExportDocument(source: source),
                preview: SharePreview("RideLink diagnostics")
            ) {
                Label("Export diagnostics log", systemImage: "square.and.arrow.up")
                    .frame(maxWidth: .infinity, minHeight: RideDesign.touch)
            }
            .buttonStyle(.bordered)
        }
    }
}

/// Rendered when the share target asks for the data, not when the view is built, so the export is
/// the log as it stands at the moment of sharing. Nothing is written to disk by RideLink.
private struct DiagnosticsExportDocument: Transferable {
    let source: DiagnosticsExportSource

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .plainText) { document in
            Data(document.source.render().utf8)
        }
        .suggestedFileName(DiagnosticsExport.fileName)
    }
}
