import XCTest

@testable import RideLinkCore
@testable import RideLinkPlatform

/// **`docs/STATUS.md` §4 problems 53 and 54**, at the two seams on this platform that a test can
/// actually reach.
///
/// `SessionFsm` has always had `ENDING -> IDLE` on `.teardownComplete` and
/// `DISCONNECTED -> DISCOVERING` on `.retryRequested`, and **nothing in the app emitted either**. The
/// hard half of emitting them is that `.teardownComplete` is an ownership claim:
///
/// > A session may enter `IDLE` only after every effect owned by the ending session has completed.
/// > No continuation owned by the retired session may mutate state after `.teardownComplete`.
///
/// **What this file can and cannot prove, stated plainly.** `ios/RideLink/SessionCoordinator.swift`
/// is in the app target, and `ios/RideLink.xcodeproj` has no XCTest target over the app's own Swift
/// sources (`docs/STATUS.md` §4 problem 22), so the *wiring* — which effects `retireSession` awaits,
/// in which order, and that `.teardownComplete` is its last statement — has **no direct iOS test**.
/// Android's `SessionLifecycleRestartTest` is the mirrored proof of that wiring, and this file covers
/// the two pieces that do live in a tested package:
///
/// 1. `SessionTeardownOwner`, the ownership primitive the coordinator is built on. It was extracted
///    into `RideLinkPlatform` for precisely this reason and for no other — an untestable ownership
///    primitive is the wrong thing to have.
/// 2. `ControlSessionManager.shutdown()`'s sink ownership (problem 54), against a real manager.
final class SessionTeardownOwnershipTests: XCTestCase {
    // MARK: - the ownership primitive

    /// The property a successor depends on: awaiting the returned task is awaiting the teardown body
    /// having **returned**, not merely having been started.
    @MainActor
    func testAwaitingARetirementAwaitsItsBody() async {
        let owner = SessionTeardownOwner()
        let gate = Gate()
        var finished = false

        let task = owner.retire {
            await gate.wait()
            finished = true
        }
        XCTAssertFalse(finished, "the body has not run yet")
        await gate.open()
        await task.value
        XCTAssertTrue(finished, "awaiting the task is awaiting the body")
    }

    /// Two teardowns never interleave their steps. They share one `ControlSessionManager`, so an
    /// overlapping pair could shut down the one a successor had just started.
    @MainActor
    func testASecondRetirementWaitsForTheFirst() async {
        let owner = SessionTeardownOwner()
        let firstGate = Gate()
        let order = Order()

        owner.retire {
            await firstGate.wait()
            await order.append("first")
        }
        let second = owner.retire {
            await order.append("second")
        }

        let beforeOpening = await order.entries
        XCTAssertTrue(beforeOpening.isEmpty, "neither teardown may have finished while the first is parked")
        await firstGate.open()
        await second.value
        let recorded = await order.entries
        XCTAssertEqual(recorded, ["first", "second"], "teardowns run in order, never concurrently")
    }

    /// `pending` is what a successor awaits. It must always be the **latest** retirement, because
    /// that is the one whose completion implies every earlier one has completed too.
    @MainActor
    func testPendingIsTheLatestRetirement() async {
        let owner = SessionTeardownOwner()
        XCTAssertNil(owner.pending, "nothing has been retired yet")
        let first = owner.retire {}
        XCTAssertEqual(owner.pending, first)
        let second = owner.retire {}
        XCTAssertEqual(owner.pending, second)
        await second.value
        await first.value
    }

    // MARK: - problem 54: whose sink is it?

    /// `shutdown()` used to null every relay sink, including the three installed **once per process**
    /// by `SharedLibraryCoordinator` and `SyncPlaybackCoordinator`. Nothing ever re-installs those, so
    /// a single Stop Discovery silently disabled Phase 4 and Phase 5 for the rest of the process.
    ///
    /// The rule kept now is the narrow one that was always true: a sink belongs to whoever installed
    /// it. The mirror is `TeardownTest.shutdown detaches only the sinks the session owned`.
    func testShutdownDetachesOnlyTheSinksTheSessionOwned() async throws {
        let peer = try TestSessions.unpairedPeer("7070707070707070", name: "SUT")
        let sut = peer.manager(monotonicNowUs: { 0 })

        await sut.manifestRelay().setSink(RecordingManifestSink(id: 11))
        await sut.transferRelay().setSink(RecordingTransferSink(id: 22))
        await sut.playbackRelay().setPlaybackSink(RecordingPlaybackSink(id: 33))
        await sut.playbackRelay().setQueueSink(RecordingQueueSink(id: 44))

        _ = try await sut.startListening(local: peer.local)
        await sut.shutdown()

        await assertSinkIds(sut, manifest: 11, transfer: 22, playback: 33, queue: 44,
                            "a sink belongs to whoever installed it; shutdown owns none of these four")

        // …and a second session on the same manager still has all four.
        _ = try await sut.startListening(local: peer.local)
        await assertSinkIds(sut, manifest: 11, transfer: 22, playback: 33, queue: 44,
                            "…including into the successor session, where Phase 4 and Phase 5 need them")
        await sut.shutdown()
    }

    /// A fresh session must not report the previous one's ending. `shutdown()` leaves `.ended`
    /// behind and `startListening` used to copy it forward, so a restart showed the dead session's
    /// state on the transport banner until a connection happened to promote it — invisible until
    /// problem 53 made a second session reachable at all.
    func testANewSessionDoesNotInheritThePreviousOnesControlState() async throws {
        let peer = try TestSessions.unpairedPeer("7171717171717171", name: "SUT")
        let sut = peer.manager(monotonicNowUs: { 0 })

        _ = try await sut.startListening(local: peer.local)
        await sut.shutdown()
        var state = await sut.diagnostics.controlState
        XCTAssertEqual(state, .ended)

        _ = try await sut.startListening(local: peer.local)
        state = await sut.diagnostics.controlState
        XCTAssertEqual(state, .idle, "a new session starts from `idle`, not from the last one's `ended`")
        await sut.shutdown()
    }

    // MARK: - harness

    /// The four process-lifetime sinks, by the identity each was installed with. Identity rather than
    /// reference equality because none of the sink protocols is class-constrained.
    private func assertSinkIds(
        _ sut: ControlSessionManager,
        manifest: Int,
        transfer: Int,
        playback: Int,
        queue: Int,
        _ message: String
    ) async {
        let manifestId = await (sut.manifestRelay().sinkForTest as? RecordingManifestSink)?.id
        XCTAssertEqual(manifestId, manifest, "manifest: \(message)")
        let transferId = await (sut.transferRelay().sinkForTest as? RecordingTransferSink)?.id
        XCTAssertEqual(transferId, transfer, "transfer: \(message)")
        let playbackId = await (sut.playbackRelay().playbackSinkForTest as? RecordingPlaybackSink)?.id
        XCTAssertEqual(playbackId, playback, "playback: \(message)")
        let queueId = await (sut.playbackRelay().queueSinkForTest as? RecordingQueueSink)?.id
        XCTAssertEqual(queueId, queue, "queue: \(message)")
    }

    private actor Gate {
        private var opened = false
        private var waiters: [CheckedContinuation<Void, Never>] = []

        func wait() async {
            if opened { return }
            await withCheckedContinuation { waiters.append($0) }
        }

        func open() {
            opened = true
            let pending = waiters
            waiters.removeAll()
            for waiter in pending { waiter.resume() }
        }
    }

    private actor Order {
        private(set) var entries: [String] = []

        func append(_ entry: String) { entries.append(entry) }
    }

    private struct RecordingManifestSink: ManifestSink {
        let id: Int
        func submit(_ message: ManifestMessage, generation: Int64) {}
    }

    private struct RecordingTransferSink: TransferSink {
        let id: Int
        func submit(_ message: TransferMessage, generation: Int64) {}
    }

    private struct RecordingPlaybackSink: PlaybackSink {
        let id: Int
        func submit(_ message: PlaybackMessage, generation: Int64) {}
    }

    private struct RecordingQueueSink: QueueSink {
        let id: Int
        func submit(_ message: QueueMessage, generation: Int64) {}
    }
}
