import Foundation
@testable import RideLinkCore
@testable import RideLinkPlatform
import XCTest

/// STATUS §4 problem 126 / ADR-012 Amendment A1, over real managers and real loopback TLS 1.3:
/// **forgetting a phone can never be bypassed** — not by the next connection, and not by a handshake
/// that read the pin a moment before the forget landed. The mirror of Android's
/// `ForgetPeerTrustGateTest`.
///
/// The handshake reads the trust store during `HELLO` and the connection is promoted only after
/// duplicate resolution's grace period, so before this amendment a forget that landed in between was
/// silently overruled: the stale `.trusted` verdict passed the trust gate. `ForgetOnLookup` reproduces
/// that interleaving deterministically: the store behind the manager is changed the instant the
/// handshake's own lookup has returned the pin, which is exactly what the user's forget does to it.
final class ForgetPeerTrustGateTests: XCTestCase {
    private let clock: @Sendable () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }

    func testAfterBothPhonesForgetTheNextConnectionAsksForTheCodeAndConnectsOnlyAfterBothConfirm() async throws {
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")
        try await withSessions(a, store: a.trustedPeers, b) { sessionA, port, sessionB in
            try await sessionA.manager.forgetTrustedPeer(b.peerId)
            try await sessionB.manager.forgetTrustedPeer(a.peerId)
            XCTAssertNil(a.trustedPeers.byPeerId(b.peerId))
            XCTAssertNil(b.trustedPeers.byPeerId(a.peerId))

            await sessionB.manager.connectTo(host: "127.0.0.1", port: port, local: b.local)

            let promptA = try await sessionA.awaitPairingPrompt()
            let promptB = try await sessionB.awaitPairingPrompt()
            XCTAssertEqual(promptA.sas6, promptB.sas6, "a fresh first meeting, bound to this TLS session")
            try await Task.sleep(nanoseconds: FsmSession.settleNs)
            await assertNeverAuthenticated(sessionA)
            await assertNeverAuthenticated(sessionB)

            await sessionA.manager.confirmPairing(accepted: true)
            await sessionB.manager.confirmPairing(accepted: true)
            try await sessionA.awaitEvent { if case .connected = $0 { return true }; return false }
            try await sessionB.awaitEvent { if case .connected = $0 { return true }; return false }

            XCTAssertEqual(sessionA.gateEventNames, ["PairingRequired", "PairingSucceeded", "Connected"])
            XCTAssertEqual(a.trustedPeers.byPeerId(b.peerId)?.identitySpkiSha256, b.identity.identitySpkiSha256)
            XCTAssertEqual(b.trustedPeers.byPeerId(a.peerId)?.identitySpkiSha256, a.identity.identitySpkiSha256)
        }
    }

    func testAForgetThatLandsAfterTheHandshakeReadThePinSendsTheConnectionToTheCode() async throws {
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaab", "bbbbbbbbbbbbbbba")
        // B has forgotten A already; A's user forgets B while B's dial is mid-handshake.
        try b.trustedPeers.forget(a.peerId)
        let racing = ForgetOnLookup(a.trustedPeers) { peerId, store in try? store.forget(peerId) }
        try await withSessions(a, store: racing, b) { sessionA, port, sessionB in
            await sessionB.manager.connectTo(host: "127.0.0.1", port: port, local: b.local)

            try await sessionA.awaitPairingPrompt()
            try await sessionB.awaitPairingPrompt()
            XCTAssertTrue(racing.fired, "the forget really landed after the handshake had read the pin")
            try await Task.sleep(nanoseconds: FsmSession.settleNs)
            await assertNeverAuthenticated(sessionA)
            XCTAssertNil(a.trustedPeers.byPeerId(b.peerId))

            // The code is a real first meeting: confirmed on both screens, it pairs afresh.
            await sessionA.manager.confirmPairing(accepted: true)
            await sessionB.manager.confirmPairing(accepted: true)
            try await sessionA.awaitEvent { if case .connected = $0 { return true }; return false }
            XCTAssertEqual(sessionA.gateEventNames, ["PairingRequired", "PairingSucceeded", "Connected"])
        }
    }

    /// Forgetting is local. The phone that still holds a pin passes its *own* trust gate silently; this
    /// one shows the code and, because the other side never confirms one, never authenticates. That is
    /// why the documented procedure forgets on both phones (TEST_PLAN I-02/I-16).
    func testAPhoneThatForgotNeverAuthenticatesOnTheOldPinEvenWhenTheOtherPhoneStillTrustsIt() async throws {
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaac", "bbbbbbbbbbbbbbbc")
        let racing = ForgetOnLookup(a.trustedPeers) { peerId, store in try? store.forget(peerId) }
        try await withSessions(a, store: racing, b) { sessionA, port, sessionB in
            await sessionB.manager.connectTo(host: "127.0.0.1", port: port, local: b.local)

            try await sessionA.awaitPairingPrompt()
            try await sessionB.awaitEvent { if case .connected = $0 { return true }; return false }
            try await Task.sleep(nanoseconds: FsmSession.settleNs)
            XCTAssertTrue(racing.fired)
            await assertNeverAuthenticated(sessionA)
            XCTAssertNil(a.trustedPeers.byPeerId(b.peerId), "the forget is not undone by the peer's trust")
        }
    }

    /// The re-check's third verdict: a pin *replaced* in the window is refused, never re-paired.
    func testAPinReplacedWhileTheHandshakeWasInFlightIsRefusedAsPinMismatch() async throws {
        let (a, b) = try TestSessions.pairedPeers("aaaaaaaaaaaaaaad", "bbbbbbbbbbbbbbbd")
        let replacement = SpkiHash("sha256:" + String(repeating: "ee", count: 32))
        let racing = ForgetOnLookup(a.trustedPeers) { peerId, store in
            try? store.forget(peerId)
            try? store.remember(TrustedPeer(
                peerId: peerId, identitySpkiSha256: replacement, displayName: "other",
                pairedAtEpochSeconds: 1, lastSeenAtEpochSeconds: 1))
        }
        try await withSessions(a, store: racing, b) { sessionA, port, sessionB in
            await sessionB.manager.connectTo(host: "127.0.0.1", port: port, local: b.local)

            try await sessionA.awaitEvent { if case .pairingFailed = $0 { return true }; return false }
            var code: String?
            for event in sessionA.events {
                if case .pairingFailed(let failure) = event { code = failure }
            }
            XCTAssertEqual(code, errorCodePinMismatch)
            let prompt = await sessionA.manager.pairingPrompt
            XCTAssertNil(prompt, "a pin mismatch is never resolved by pairing")
            await assertNeverAuthenticated(sessionA)
            XCTAssertEqual(a.trustedPeers.byPeerId(b.peerId)?.identitySpkiSha256, replacement)
            XCTAssertFalse(sessionB.events.contains { if case .pairingSucceeded = $0 { return true }; return false })
        }
    }

    // MARK: - helpers

    private func assertNeverAuthenticated(_ session: FsmSession, file: StaticString = #filePath, line: UInt = #line) async {
        XCTAssertFalse(
            session.events.contains {
                switch $0 {
                case .peerTrusted, .connected: return true
                default: return false
                }
            }, "\(session.gateEventNames)", file: file, line: line)
        XCTAssertFalse(session.hasReached(.connecting), file: file, line: line)
        XCTAssertFalse(session.hasReached(.connected), file: file, line: line)
        XCTAssertNil(session.manager.liveAuthenticatedGeneration(), file: file, line: line)
    }

    /// A listens with `store` behind its manager and only `startDiscovery` applied — the state a phone
    /// is in when the other one dials it. B is a second real manager, also listening, that the test
    /// makes dial A.
    private func withSessions(
        _ a: TestPeer,
        store: any TrustedPeerStore,
        _ b: TestPeer,
        _ body: (FsmSession, UInt16, FsmSession) async throws -> Void
    ) async throws {
        let managerA = ControlSessionManager(
            localPeerId: a.peerId,
            channel: a.channel(),
            trustedPeers: store,
            monotonicNowUs: clock,
            nowEpochSeconds: { TestTlsSupport.nowEpochSeconds }
        )
        let sessionA = FsmSession(peer: a, manager: managerA)
        let sessionB = FsmSession(peer: b, manager: b.manager(monotonicNowUs: clock))
        await sessionA.attach()
        await sessionB.attach()
        XCTAssertTrue(sessionA.apply(.startDiscovery))
        XCTAssertTrue(sessionB.apply(.startDiscovery))
        XCTAssertTrue(sessionB.apply(.peerSelected))
        let port = try await managerA.startListening(local: a.local)
        _ = try await sessionB.manager.startListening(local: b.local)
        do {
            try await body(sessionA, port, sessionB)
        } catch {
            await managerA.shutdown()
            await sessionB.manager.shutdown()
            throw error
        }
        await managerA.shutdown()
        await sessionB.manager.shutdown()
    }
}

/// Delegates to `delegate`, and the first time a lookup returns a pin — which is the handshake's own
/// `PeerTrust.decide` read — runs `onLookup` against the store before handing the pin back. That is a
/// user's forget landing after the verdict was read and before the connection is promoted, without a
/// sleep. (The manager's own `forgetTrustedPeer` cannot be awaited from inside its actor; what it does
/// to the store is exactly this.)
private final class ForgetOnLookup: TrustedPeerStore, @unchecked Sendable {
    private let delegate: any TrustedPeerStore
    private let onLookup: (PeerId, any TrustedPeerStore) -> Void
    private let lock = NSLock()
    private var armed = true

    init(_ delegate: any TrustedPeerStore, onLookup: @escaping (PeerId, any TrustedPeerStore) -> Void) {
        self.delegate = delegate
        self.onLookup = onLookup
    }

    var fired: Bool { lock.withLock { !armed } }

    func byPeerId(_ peerId: PeerId) -> TrustedPeer? {
        let found = delegate.byPeerId(peerId)
        let fire = found != nil && lock.withLock { () -> Bool in
            guard armed else { return false }
            armed = false
            return true
        }
        if fire { onLookup(peerId, delegate) }
        return found
    }

    func bySpki(_ identitySpkiSha256: SpkiHash) -> TrustedPeer? { delegate.bySpki(identitySpkiSha256) }
    func all() -> [TrustedPeer] { delegate.all() }
    func remember(_ peer: TrustedPeer) throws { try delegate.remember(peer) }
    func forget(_ peerId: PeerId) throws { try delegate.forget(peerId) }
}

/// The persistent store's half of *Forget peer*: exactly one record goes, durably, and a forget that
/// could not reach the disk says so instead of reporting success.
final class FileTrustedPeerStoreForgetTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: directory.path)
        try? FileManager.default.removeItem(at: directory)
    }

    private let alice = PeerId("aaaaaaaaaaaaaaaa"), bob = PeerId("bbbbbbbbbbbbbbbb")
    private let aliceSpki = SpkiHash("sha256:" + String(repeating: "11", count: 32))
    private let bobSpki = SpkiHash("sha256:" + String(repeating: "22", count: 32))

    private func record(_ id: PeerId, _ spki: SpkiHash) -> TrustedPeer {
        TrustedPeer(peerId: id, identitySpkiSha256: spki, displayName: "Rider", pairedAtEpochSeconds: 1, lastSeenAtEpochSeconds: 2)
    }

    private var storeURL: URL { directory.appendingPathComponent("trusted_peers.json") }

    func testForgettingOnePeerRemovesOnlyThatRecordAndLeavesThePeerIdFileAlone() throws {
        let peerIdURL = directory.appendingPathComponent("peer_id")
        let localPeerId = LocalPeerIdStore(url: peerIdURL).loadOrCreate()
        let peerIdBytes = try Data(contentsOf: peerIdURL)
        let store = FileTrustedPeerStore(url: storeURL)
        try store.remember(record(alice, aliceSpki))
        try store.remember(record(bob, bobSpki))

        try store.forget(alice)

        let reloaded = FileTrustedPeerStore(url: storeURL)
        XCTAssertNil(reloaded.byPeerId(alice), "the forgotten pin must not come back after a restart")
        XCTAssertEqual(reloaded.byPeerId(bob)?.identitySpkiSha256, bobSpki, "the other phone stays trusted")
        XCTAssertEqual(try Data(contentsOf: peerIdURL), peerIdBytes, "this phone's peer_id is not touched")
        XCTAssertEqual(LocalPeerIdStore(url: peerIdURL).loadOrCreate(), localPeerId)
        XCTAssertEqual(
            Set(try FileManager.default.contentsOfDirectory(atPath: directory.path)), ["peer_id", "trusted_peers.json"])

        try store.forget(PeerId("cccccccccccccccc")) // unknown: a no-op, never an error
        XCTAssertEqual(FileTrustedPeerStore(url: storeURL).all().count, 1)
    }

    func testAForgetThatCannotBePersistedThrowsAndKeepsThePeerTrusted() throws {
        let store = FileTrustedPeerStore(url: storeURL)
        try store.remember(record(alice, aliceSpki))
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: directory.path)

        XCTAssertThrowsError(try store.forget(alice))

        XCTAssertEqual(store.byPeerId(alice)?.identitySpkiSha256, aliceSpki, "not forgotten in memory either")
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: directory.path)
        XCTAssertEqual(FileTrustedPeerStore(url: storeURL).byPeerId(alice)?.identitySpkiSha256, aliceSpki)
    }
}
