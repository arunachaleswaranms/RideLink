import RideLinkCore
import RideLinkPlatform
import SwiftUI

// This file is compiled only in an isolated QA copy, never into RideLink's app target.
@main
struct VisualQAApp: App {
    private let fixture = ProcessInfo.processInfo.arguments.dropFirst().first ?? "idle"
    var body: some Scene {
        WindowGroup {
            if fixture.hasPrefix("setup-") {
                SetupFixture(name: String(fixture.dropFirst(6)))
            } else {
                RideModeContent(
                    health: fixture == "reconnecting" ? .reconnecting : fixture == "disconnected" ? .disconnected : .healthy,
                    title: ["idle", "intercom"].contains(fixture) ? nil
                        : fixture == "long-title" ? "The long way home through the mountains and beyond the horizon" : "The long way home",
                    artist: ["idle", "intercom"].contains(fixture) ? nil : "Evening Roads",
                    playing: !["idle", "intercom", "waiting"].contains(fixture),
                    hasTrack: !["idle", "intercom"].contains(fixture),
                    syncText: fixture == "reconnecting" ? "Music sync waits for connection"
                        : fixture == "sync-problem" ? "Music sync paused"
                        : fixture == "waiting" ? "Waiting for the track to download…"
                        : ["idle", "intercom", "disconnected"].contains(fixture) ? "Local playback" : "Synchronized",
                    voiceText: ["idle", "music"].contains(fixture) ? "Intercom not started" : "Intercom active",
                    microphoneText: ["idle", "music"].contains(fixture) ? "Microphone unavailable"
                        : fixture == "muted" ? "Muted" : fixture == "ptt" ? "Transmitting" : "Microphone ready",
                    micAvailable: !["idle", "music"].contains(fixture), muted: fixture == "muted", ptt: true, held: fixture == "ptt",
                    policyText: "C · Push to Talk / duck music", audioDegraded: false,
                    onPrevious: {}, onPlayPause: {}, onNext: {}, onToggleMute: {}, onPushToTalkHeld: { _ in },
                    onReconnect: {}, onEndRide: {}
                )
            }
        }
    }
}

struct SetupFixture: View {
    let name: String
    private let peer = PeerId("0123456789abcdef")
    private let hash = ContentHash("sha256:" + String(repeating: "a", count: 64))
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: RideDesign.lg) {
                Text("RideLink").font(.largeTitle)
                switch name {
                case "PAIR_CODE":
                    PairingCard(prompt: PairingPrompt(sas6: "123456", remotePeerId: peer, peerDisplayName: "Your peer’s phone with a long name"), onDecision: { _ in })
                case "VOICE":
                    VoiceCard(voice: VoiceDiagnostics(), coexistence: CoexistenceDiagnostics(), policy: IntercomPolicy.default,
                        peerAudioState: nil, refusal: .micPermissionDenied, onStartIntercom: {}, onStopIntercom: {},
                        onToggleMute: {}, onPushToTalkHeld: { _ in }, onSelectPolicy: { _ in })
                case "QUEUE":
                    SharedQueueContent(queue: SharedQueueState(items: [
                        SharedQueueItem(queueItemId: "first", trackHash: hash, addedBy: peer, order: 0),
                        SharedQueueItem(queueItemId: "second", trackHash: hash, addedBy: peer, order: 1)
                    ], currentItemId: "first"), titles: [hash.value: "The long way home through the mountains"], onRemove: { _ in })
                case "MUSIC_EMPTY":
                    NowPlayingCard(playerState: PlayerState(), currentEntry: nil, queueSize: 0,
                        onPlay: {}, onPause: {}, onSeek: { _ in }, onNext: {}, onPrevious: {})
                case "LIBRARY":
                    LibraryView(query: LibraryQuery(), entries: [], onSearchTextChange: { _ in }, onSortChange: { _ in }, onImportFolder: {}, onImportFiles: {}, onAddToQueue: { _ in }, onPlayNow: { _ in })
                case "TRANSFER":
                    SharedTrackRow(entry: ManifestEntry(contentHash: hash, quickId: QuickId(hash.value), workKey: "fixture", title: "The long way home", artist: "Evening Roads", album: "Mountain journey", durationMs: 180000, codec: "mp3", bitrateKbps: 320, sizeBytes: 10000, filename: "fixture.mp3", hasArtwork: false), availability: Availability(hasLocal: false, hasCached: false, hasRemote: true), download: DownloadState(status: .transferring, bytesReceived: 4000, totalBytes: 10000), onDownload: {}, onCancel: {}, onPlayLocally: {})
                case "MUSIC_PLAYING", "MUSIC_PAUSED":
                    NowPlayingCard(playerState: PlayerState(localEntryId: LocalEntryId("00000000-0000-0000-0000-000000000001"), positionMs: 60000, durationMs: 180000, playing: name == "MUSIC_PLAYING"), currentEntry: nil, queueSize: 2, title: "The long way home", artist: "Evening Roads", onPlay: {}, onPause: {}, onSeek: { _ in }, onNext: {}, onPrevious: {})
                case "SECURITY": SecurityAlertCard(code: "pin_mismatch", onDismiss: {})
                default:
                    ConnectionSummary(status: SessionStatus(rawValue: name) ?? .idle, peerCount: name == "DISCOVERING" ? 1 : 0)
                }
            }.padding(RideDesign.xl)
        }.background(RideDesign.background).tint(RideDesign.primary).controlSize(.large)
    }
}
