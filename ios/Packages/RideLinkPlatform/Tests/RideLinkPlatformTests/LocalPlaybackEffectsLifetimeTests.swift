import RideLinkCore
@testable import RideLinkPlatform
import XCTest

/// ADR-024 Amendment A15 (PR #18 review round 2): **a proof taken before an async boundary does not
/// authorise the effect after it.** Mirrors Android's `LocalPlaybackEffectsLifetimeTest`.
///
/// A local edit is admitted, and its queue mutation applied, under one local-ownership lifetime; its
/// player effects run later, in `Task`s. Each test parks that work at a real suspension point — the
/// entry lookup before `Load`, inside `Load` before `Play`, or before a launched `Stop` has run at
/// all — moves ownership, releases it, and asserts the player (and the audio session) were not
/// touched. `@MainActor` does not make the proof unnecessary: every `await` releases the main actor.
///
/// `LifetimeGate` reproduces the synchronisation owner's lifetime rule exactly; that rule itself is
/// proved against the real `SyncPlaybackCoordinator` and `SyncPlaybackGateAdapter` in
/// `SyncPlaybackTransportOwnershipTests`. Nothing sleeps: barriers and the main actor decide ordering.
@MainActor
final class LocalPlaybackEffectsLifetimeTests: XCTestCase {
    private var gate: LifetimeGate!
    private var player: ParkingPlayer!
    private var resolver: ParkingResolver!
    private var prepared = 0
    private var effects: LocalPlaybackEffects!

    override func setUp() async throws {
        gate = LifetimeGate()
        player = ParkingPlayer()
        resolver = ParkingResolver()
        prepared = 0
        effects = LocalPlaybackEffects(
            player: player,
            prepare: { [unowned self] in self.prepared += 1 },
            resolve: { [unowned self] id in await self.resolver.resolve(id) },
            stillValid: { [unowned self] admission in self.gate.isLocalQueueEditStillValid(admission) }
        )
    }

    private static func id(_ n: Int) -> LocalEntryId { LocalEntryId("ffffffff-0000-4000-8000-00000000000\(n)") }
    private static func load(_ n: Int) -> PlaybackCommand { .load(localEntryId: id(n), location: ParkingResolver.location(id(n))) }

    private static let threeWithFirstCurrent = LocalQueueState(
        items: (1...3).map { LocalQueueItem(id: "q\($0)", localEntryId: id($0), insertedAtMonoUs: Int64($0)) },
        currentId: "q1"
    )

    private func admitted(_ state: LocalQueueState, _ actions: [LocalQueueAction]) throws -> AdmittedLocalQueueEdit {
        try XCTUnwrap(LocalQueueEdits.reduce(state, actions, gate: gate), "premise: admitted under local ownership")
    }

    private func awaitAll(_ tasks: [Task<Void, Never>]) async {
        for task in tasks { await task.value }
    }

    func test1SelectParkedBeforeLoadThenSynchronisedActivationLoadsAndPlaysNothing() async throws {
        let parked = resolver.park(Self.id(2))
        let edit = try admitted(Self.threeWithFirstCurrent, [.select(id: "q2")])
        XCTAssertEqual(edit.state.currentId, "q2", "the queue mutation itself was admitted")
        let tasks = effects.run(edit.effects, admission: edit.admission)
        await parked.waitForArrival()

        gate.activateSynchronised()
        await parked.release()
        await awaitAll(tasks)

        let calls = await player.calls
        XCTAssertEqual(calls, [], "stale local work touched the player after synchronised activation")
    }

    func test2ClearParkedBeforeStopThenSynchronisedActivationStopsNothing() async throws {
        let edit = try admitted(Self.threeWithFirstCurrent, [.clear])
        let tasks = effects.run(edit.effects, admission: edit.admission) // not yet run: the main actor is ours
        gate.activateSynchronised()
        await awaitAll(tasks)

        let calls = await player.calls
        XCTAssertEqual(calls, [])
    }

    func test3RemoveCurrentSuccessorParkedBeforeLoadThenActivationTouchesNothing() async throws {
        let parked = resolver.park(Self.id(3))
        let queue = LocalQueueState(items: Self.threeWithFirstCurrent.items, currentId: "q2")
        let edit = try admitted(queue, [.remove(id: "q2")])
        XCTAssertEqual(edit.state.currentId, "q3", "premise: LocalQueue hands playback to the successor")
        let tasks = effects.run(edit.effects, admission: edit.admission)
        await parked.waitForArrival()

        gate.activateSynchronised()
        await parked.release()
        await awaitAll(tasks)

        let calls = await player.calls
        XCTAssertEqual(calls, [])
    }

    func test4AbaLocalAgainNeverRevivesTheOldAdmissionButAFreshEditRuns() async throws {
        let parked = resolver.park(Self.id(2))
        let underA = try admitted(Self.threeWithFirstCurrent, [.select(id: "q2")])
        let tasksA = effects.run(underA.effects, admission: underA.admission)
        await parked.waitForArrival()

        gate.activateSynchronised() // B
        gate.returnToLocal() // C: local again — but a different lifetime
        XCTAssertFalse(gate.localQueueLocked(), "premise: ownership is local again")
        await parked.release()
        await awaitAll(tasksA)
        var calls = await player.calls
        XCTAssertEqual(calls, [], "\"local again\" resurrected A's stale effect")

        let underC = try admitted(Self.threeWithFirstCurrent, [.select(id: "q3")])
        await awaitAll(effects.run(underC.effects, admission: underC.admission))
        calls = await player.calls
        XCTAssertEqual(calls, [Self.load(3), .play], "a fresh edit under C works normally")
    }

    func test5PlayIsNotAuthorisedByTheProofTakenBeforeLoad() async throws {
        let parked = await player.park(on: Self.load(4))
        let playNow: [LocalQueueAction] = [.add(LocalQueueItem(id: "q4", localEntryId: Self.id(4), insertedAtMonoUs: 4)), .select(id: "q4")]
        let edit = try admitted(Self.threeWithFirstCurrent, playNow)
        let tasks = effects.run(edit.effects, admission: edit.admission)
        await parked.waitForArrival()

        gate.activateSynchronised()
        await parked.release()
        await awaitAll(tasks)

        let calls = await player.calls
        XCTAssertEqual(calls, [Self.load(4)], "Play ran on a proof taken before the Load")
    }

    func test6RemovingTheLastCurrentEntryParkedBeforeStopThenInvalidatedStopsNothing() async throws {
        let lastCurrent = LocalQueueState(items: Self.threeWithFirstCurrent.items, currentId: "q3")
        let edit = try admitted(lastCurrent, [.remove(id: "q3")])
        XCTAssertNil(edit.state.currentId, "premise: LocalQueue stops when the last current entry goes")
        let tasks = effects.run(edit.effects, admission: edit.admission)
        gate.activateSynchronised()
        await awaitAll(tasks)

        let calls = await player.calls
        XCTAssertEqual(calls, [])
    }

    func test7OneLocalLifetimeSelectRemoveCurrentClearMoveAddAndPlayNowAllWork() async throws {
        func apply(_ state: LocalQueueState, _ actions: [LocalQueueAction]) async throws -> LocalQueueState {
            let edit = try admitted(state, actions)
            await awaitAll(effects.run(edit.effects, admission: edit.admission))
            return edit.state
        }

        var queue = try await apply(Self.threeWithFirstCurrent, [.select(id: "q2")])
        var calls = await player.takeCalls()
        XCTAssertEqual(calls, [Self.load(2), .play])

        queue = try await apply(queue, [.remove(id: "q2")])
        XCTAssertEqual(queue.currentId, "q3")
        calls = await player.takeCalls()
        XCTAssertEqual(calls, [Self.load(3), .play])

        queue = try await apply(queue, [.move(id: "q3", toIndex: 0)])
        XCTAssertEqual(queue.items.map(\.id), ["q3", "q1"])
        queue = try await apply(queue, [.add(LocalQueueItem(id: "q5", localEntryId: Self.id(5), insertedAtMonoUs: 5))])
        XCTAssertEqual(queue.items.count, 3)
        calls = await player.takeCalls()
        XCTAssertEqual(calls, [], "move and add have no player effect")

        queue = try await apply(queue, [.add(LocalQueueItem(id: "q6", localEntryId: Self.id(6), insertedAtMonoUs: 6)), .select(id: "q6")])
        calls = await player.takeCalls()
        XCTAssertEqual(calls, [Self.load(6), .play])

        _ = try await apply(queue, [.clear])
        calls = await player.takeCalls()
        XCTAssertEqual(calls, [.stop])

        let pause = try XCTUnwrap(LocalQueueEdits.admit(gate: gate))
        await effects.command(.pause, admission: pause).value
        calls = await player.takeCalls()
        XCTAssertEqual(calls, [.pause])
        XCTAssertGreaterThan(prepared, 0, "the audio session was activated for local playback")
    }

    func testAResumeAdmittedBeforeActivationNeitherActivatesTheAudioSessionNorPlays() async throws {
        let resume = try XCTUnwrap(LocalQueueEdits.admit(gate: gate))
        let tasks = effects.run([.resumePlayback], admission: resume)
        gate.activateSynchronised()
        await awaitAll(tasks)

        let calls = await player.calls
        XCTAssertEqual(calls, [])
        XCTAssertEqual(prepared, 0, "the audio session was activated for a dead admission")
    }

    func testALocalPressAdmittedBeforeActivationDoesNotPauseSynchronisedPlayback() async throws {
        let admission = try XCTUnwrap(LocalQueueEdits.admit(gate: gate))
        let task = effects.command(.pause, admission: admission)
        gate.activateSynchronised()
        await task.value

        let calls = await player.calls
        XCTAssertEqual(calls, [])
        XCTAssertNil(LocalQueueEdits.admit(gate: gate), "and no fresh local admission while synchronised")
    }
}

// MARK: - Fixtures

/// The synchronisation owner's lifetime rule: every ownership flip advances the lifetime.
@MainActor
private final class LifetimeGate: SyncPlaybackGate {
    private var synchronised = false
    private var lifetime: Int64 = 0

    func activateSynchronised() {
        precondition(!synchronised)
        synchronised = true
        lifetime += 1
    }

    func returnToLocal() {
        precondition(synchronised)
        synchronised = false
        lifetime += 1
    }

    func interceptPlay() -> Bool { false }
    func interceptPause() -> Bool { false }
    func interceptSeek(_ positionMs: Int64) -> Bool { false }
    func interceptNext() -> Bool { false }
    func interceptPrevious() -> Bool { false }
    func interceptTrackEnded() -> Bool { false }
    func localQueueLocked() -> Bool { synchronised }

    func admitLocalQueueEdit() -> LocalQueueEditAdmission? {
        synchronised ? nil : LocalQueueEditAdmission(lifetime: lifetime)
    }

    func isLocalQueueEditStillValid(_ admission: LocalQueueEditAdmission) -> Bool {
        !synchronised && admission.lifetime == lifetime
    }
}

/// One parking point: the parked task reports its arrival and then waits to be released.
private actor Barrier {
    private var arrived = false
    private var released = false
    private var arrivalWaiters: [CheckedContinuation<Void, Never>] = []
    private var releaseWaiters: [CheckedContinuation<Void, Never>] = []

    func arriveAndWait() async {
        arrived = true
        arrivalWaiters.forEach { $0.resume() }
        arrivalWaiters = []
        if released { return }
        await withCheckedContinuation { releaseWaiters.append($0) }
    }

    func waitForArrival() async {
        if arrived { return }
        await withCheckedContinuation { arrivalWaiters.append($0) }
    }

    func release() {
        released = true
        releaseWaiters.forEach { $0.resume() }
        releaseWaiters = []
    }
}

private actor ParkingPlayer: Player {
    private(set) var calls: [PlaybackCommand] = []
    private var parkOn: PlaybackCommand?
    private var barrier: Barrier?

    func park(on command: PlaybackCommand) -> Barrier {
        let barrier = Barrier()
        parkOn = command
        self.barrier = barrier
        return barrier
    }

    func takeCalls() -> [PlaybackCommand] {
        defer { calls = [] }
        return calls
    }

    func execute(_ command: PlaybackCommand) async {
        calls.append(command)
        if command == parkOn, let barrier { await barrier.arriveAndWait() }
    }

    var state: PlayerState { PlayerState() }
    func setStateSink(_ sink: @escaping @Sendable (PlayerState) -> Void) {}
    func beginCoexistenceLifetime(_ generation: Int64) {}
    func setCoexistenceGain(_ gain: Double, generation: Int64) -> Bool { true }
    func pauseForVoice(generation: Int64, trackToken: String) -> Bool { true }
    func resumeAfterVoice(generation: Int64, trackToken: String) -> Bool { true }
    func release() {}
}

/// Resolves an entry's location; a parked entry suspends here — the lookup before the `Load` proof.
@MainActor
private final class ParkingResolver {
    private var barriers: [LocalEntryId: Barrier] = [:]

    static func location(_ id: LocalEntryId) -> LocalTrackLocation { LocalTrackLocation(uri: "file:///lifetime/\(id.value)") }

    func park(_ id: LocalEntryId) -> Barrier {
        let barrier = Barrier()
        barriers[id] = barrier
        return barrier
    }

    func resolve(_ id: LocalEntryId) async -> LocalTrackLocation? {
        if let barrier = barriers[id] { await barrier.arriveAndWait() }
        return Self.location(id)
    }
}
