import Foundation
import RideLinkCore
import XCTest
@testable import RideLinkPlatform

/// **Phase 8's cross-platform software integration gate: the iOS half of one real session with the
/// Android/Kotlin implementation.**
///
/// This test does not run under an ordinary `swift test`. It is started by
/// `tools/crossplatform/run.sh`, which runs it and its Kotlin counterpart
/// (`com.ridelink.network.interop.CrossPlatformInteropTest`) as two processes on one machine, joined
/// by a **real TCP socket carrying the real RideLink protocol**: a real TLS 1.3 handshake with mutual
/// authentication, real ECDSA P-256 identities encoded by the shared DER encoder, the real
/// `PROTOCOL` §4.5 six-digit pairing exchange, real `PING`/`PONG` clock-sync bursts, and the real
/// Phase 5/Phase 7 relays and codecs.
///
/// **Nothing here is faked except the player and the content store.** The point of the gate is that
/// two *different language implementations* agree on the wire, the trust model and the session
/// lifetime — so every byte between them is produced and consumed by production code.
///
/// ## What this gate is not
///
/// It is not the interactive emulator ↔ simulator UI journey. It drives no UI, launches no app, and
/// says nothing about Bluetooth, audio, background behaviour or a physical iPhone. Those remain
/// separate gates, and `docs/PHASE8_RELEASE_HARDENING.md` records exactly which.
///
/// It also runs the Kotlin half on the JVM against Conscrypt rather than on a device against
/// Android's own TLS stack — the same pre-existing limitation `docs/test-results/`'s Phase 1b
/// security spike records, unchanged by this gate and not hidden by it.
final class CrossPlatformInteropTests: XCTestCase {
    private var reportDir: URL?

    /// The iOS half: listen, pair, and run the transcript against whatever dials in.
    func testIosHalfOfACrossPlatformSession() async throws {
        guard let dir = ProcessInfo.processInfo.environment["RIDELINK_CROSS_DIR"] else {
            throw XCTSkip("cross-platform interop gate: set RIDELINK_CROSS_DIR (tools/crossplatform/run.sh)")
        }
        let reportDir = URL(fileURLWithPath: dir)
        self.reportDir = reportDir
        var report: [String: Any] = ["platform": "ios"]
        defer { write(report, to: reportDir.appendingPathComponent("ios-report.json")) }

        let peer = try TestSessions.unpairedPeer("11112222aaaabbbb", name: "RideLink-iOS")
        let monotonic: @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1_000) }
        let session = FsmSession(peer: peer, manager: peer.manager(monotonicNowUs: monotonic))
        await session.attach()
        session.apply(.startDiscovery)
        session.apply(.peerSelected)

        let port = try await session.manager.startListening(local: peer.local)
        report["port"] = Int(port)
        // Published only once the listener is genuinely bound, and atomically, so the Kotlin half
        // never reads a half-written file or dials a port that does not exist yet.
        try write(text: "\(port)", to: reportDir.appendingPathComponent("ios-port"))

        // PROTOCOL §4.5: the six digits this side computed from *its own* TLS exporter. The
        // orchestrator compares them with the Kotlin half's, which is the actual cross-platform
        // assertion — the protocol cannot make it, because it is two humans who compare the codes.
        let prompt = try await session.awaitPairingPrompt()
        report["sas6"] = prompt.sas6
        report["remotePeerId"] = prompt.remotePeerId.value
        report["peerDisplayName"] = prompt.peerDisplayName
        await session.manager.confirmPairing(accepted: true)

        try await session.awaitEvent { if case .connected = $0 { return true } else { return false } }
        guard case .connected(let remote, let sessionId, let isLocalLeader, let generation)? =
            session.events.last(where: { if case .connected = $0 { return true } else { return false } })
        else { return XCTFail("no connected event") }
        report["sessionId"] = sessionId.value
        report["isLocalLeader"] = isLocalLeader
        report["authGeneration"] = Int(generation)
        report["connectedRemotePeerId"] = remote.value
        // The pin is written by the trust gate, not by the test: this is the persisted trust that
        // makes the reconnect below silent.
        report["trustedAfterPairing"] = peer.trustedPeers.all().count

        // Sinks first, **before** anything can be sent in either direction: a relay with no sink
        // drops the frame, so installing them after a clock wait would make the transcript depend on
        // which side's estimator converged first.
        let inbox = InteropInbox()
        await session.manager.playbackRelay().setPlaybackSink(inbox)
        await session.manager.playbackRelay().setQueueSink(inbox)
        await session.manager.resyncRelay().setSink(inbox)

        // ARCHITECTURE §7.1's real burst, over the real socket. Nothing is injected.
        try await expect("the iOS clock estimator accepted a window", timeout: 60) {
            await session.manager.sessionClockEstimate()?.ready == true
        }
        let estimate = await session.manager.sessionClockEstimate()
        report["clockReady"] = estimate?.ready ?? false
        report["rttP95Us"] = Int(estimate?.rttP95Us ?? -1)

        // A two-file rendezvous, so neither side sends into a peer that is not listening yet. The
        // gate is about the two implementations agreeing on the wire, not about which of two
        // estimators converged first.
        try write(text: "ready", to: reportDir.appendingPathComponent("ios-ready"))
        try await expect("the Kotlin half installed its sinks", timeout: 120) {
            FileManager.default.fileExists(atPath: reportDir.appendingPathComponent("android-ready").path)
        }

        // PROTOCOL §9: an authoritative queue snapshot, encoded by `QueueCodec` here and decoded by
        // Kotlin's `QueueCodec` there.
        let snapshot = QueueMessage.snapshot(
            queueRevision: 7,
            items: [SharedQueueItem(
                queueItemId: SyncTestValues.ulid(9),
                trackHash: SyncTestValues.hash(9),
                addedBy: peer.peerId,
                order: PlaybackBounds.queueOrderStep
            )],
            currentIndex: 0
        )
        report["sentQueueSnapshot"] = await session.manager.playbackRelay()
            .send(snapshot, authorizingGeneration: generation)

        // PROTOCOL §10: this side asks for full state; the Kotlin half answers.
        report["sentStateRequest"] = await session.manager.resyncRelay()
            .send(.stateRequest, generation: generation)

        try await expect("the Kotlin half's PLAY and STATE_SNAPSHOT arrived", timeout: 60) {
            inbox.hasPlay && inbox.hasStateSnapshot
        }
        report["receivedPlay"] = inbox.playDescription
        report["receivedStateSnapshot"] = inbox.stateSnapshotDescription

        // A link loss and a reconnect, driven by the Kotlin half re-dialling. Both sides now hold a
        // pin, so the successor must authenticate **silently** — no second pairing prompt — and must
        // mint a strictly greater generation.
        try write(text: "reconnect", to: reportDir.appendingPathComponent("ios-phase"))
        try await expect("the Kotlin half re-dialled and authenticated silently", timeout: 120) {
            session.count { if case .connected = $0 { return true } else { return false } } >= 2
        }
        let connections = session.events.filter { if case .connected = $0 { return true } else { return false } }
        guard case .connected(_, let sessionId2, _, let generation2)? = connections.last else {
            return XCTFail("no second connected event")
        }
        report["reconnectAuthGeneration"] = Int(generation2)
        report["reconnectSessionId"] = sessionId2.value
        report["pairingRequiredCount"] = session.count { if case .pairingRequired = $0 { return true } else { return false } }
        XCTAssertGreaterThan(generation2, generation, "a reconnect mints a strictly greater generation")

        // The same rendezvous again: the successor's sinks must be installed before it is written to.
        try await expect("the Kotlin half reinstalled its sinks", timeout: 120) {
            FileManager.default.fileExists(atPath: reportDir.appendingPathComponent("android-reconnected").path)
        }
        // One more authoritative frame under the *successor* generation, to prove the relays rebind.
        let state = PlaybackMessage.playbackState(
            commandSeq: 11, queueRevision: 7, trackHash: SyncTestValues.hash(9),
            queueItemId: SyncTestValues.ulid(9), positionMs: 4_321, playing: true,
            atSessionUs: 1_234_567
        )
        report["sentPlaybackStateAfterReconnect"] = await session.manager.playbackRelay()
            .send(state, authorizingGeneration: generation2)
        try write(text: "done", to: reportDir.appendingPathComponent("ios-phase"))
        // Give the Kotlin half a bounded window to observe the post-reconnect frame before this
        // process tears the socket down.
        try await expect("the Kotlin half finished", timeout: 60) {
            FileManager.default.fileExists(atPath: reportDir.appendingPathComponent("android-done").path)
        }
        report["ok"] = true
        await session.manager.shutdown()
    }

    private func expect(_ what: String, timeout: TimeInterval, _ condition: @escaping () async -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTFail("timed out waiting for: \(what)")
    }

    private func write(text: String, to url: URL) throws {
        let temporary = url.appendingPathExtension("tmp")
        try text.write(to: temporary, atomically: true, encoding: .utf8)
        _ = try? FileManager.default.removeItem(at: url)
        try FileManager.default.moveItem(at: temporary, to: url)
    }

    private func write(_ report: [String: Any], to url: URL) {
        guard let data = try? JSONSerialization.data(withJSONObject: report, options: [.sortedKeys, .prettyPrinted])
        else { return }
        try? data.write(to: url)
    }
}

/// Records what the Kotlin half sent, decoded by the **production** codecs on this side.
private final class InteropInbox: PlaybackSink, QueueSink, ResyncSink, @unchecked Sendable {
    private let lock = NSLock()
    private var play: String?
    private var snapshot: String?
    private var queue: String?

    var playDescription: String? { lock.withLock { play } }
    var stateSnapshotDescription: String? { lock.withLock { snapshot } }
    var queueDescription: String? { lock.withLock { queue } }
    var hasPlay: Bool { playDescription != nil }
    var hasStateSnapshot: Bool { stateSnapshotDescription != nil }

    func submit(_ message: PlaybackMessage, generation: Int64) {
        guard case .play(let header, let trackHash, let positionMs, let queueItemId) = message else { return }
        lock.withLock {
            play = "seq=\(header.commandSeq) rev=\(header.queueRevision) at=\(header.effectiveAtSessionUs) " +
                "hash=\(trackHash.value) item=\(queueItemId) pos=\(positionMs) gen=\(generation)"
        }
    }

    func submit(_ message: QueueMessage, generation: Int64) {
        guard case .snapshot(let revision, let items, let index) = message else { return }
        lock.withLock { queue = "rev=\(revision) items=\(items.count) index=\(index ?? -1)" }
    }

    func submit(_ message: ResyncMessage, generation: Int64) {
        guard case .stateSnapshot(let leader, let commandSeq, let revision, let playback, let items, _,
                                  let manifestRevision, _) = message else { return }
        lock.withLock {
            snapshot = "leader=\(leader.value) seq=\(commandSeq) rev=\(revision) " +
                "track=\(playback?.trackHash?.value ?? "none") items=\(items.count) manifest=\(manifestRevision)"
        }
    }
}
