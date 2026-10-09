import Foundation
import RideLinkCore

/// The player half of an admitted local queue edit or local transport press (ADR-024 Amendment A15).
/// Mirrors Android's `com.ridelink.app.music.LocalPlaybackEffects`. Owned by the one `MusicCoordinator`;
/// there is no other local effect path.
///
/// Every effect is issued synchronously, in the same main-actor step as the queue mutation or press
/// that caused it, and runs later in a `Task` that suspends — for audio-session activation, for the
/// library lookup, and inside `Load` itself. Each `await` releases the main actor, so `@MainActor` does
/// not make a proof taken earlier still true. Two independent authorities are carried into the effect
/// and re-proved immediately before **every** externally visible step, and after every suspension that
/// precedes one:
///
/// 1. **The ownership admission** (round 2): the local transport-ownership lifetime that admitted the
///    edit. Synchronised mode taking transport — or a later return to local, which is a new lifetime —
///    ends it. Never re-read "is ownership local now?" in its place: local A → synchronised B → local C
///    is local again, and A's work must still be dead.
/// 2. **The playback ticket** (round 3): this effect's place in the local operation order, so an older
///    local operation cannot overtake a newer one inside the same lifetime. Two sequences, because they
///    answer two questions:
///    - **selection** — *which track should be loaded?* Advanced by a load-and-play and by a stop. A
///      newer selection or stop ends an older `Load` and its `Play`.
///    - **transport intent** — *should the player be running?* Advanced by a load-and-play, a stop, a
///      resume and a pause. A newer intent ends an older `Play` (Select A, then Pause: A may still load,
///      so the player holds the track the queue names, but A's `Play` cannot defeat the pause).
///
///    Add, move and removing a non-current entry have no player effect and advance neither. A seek is a
///    position on the current selection: it advances nothing and is dropped only if the selection moved.
///
/// A failed proof drops the rest of that effect. Nothing is reconstructed from current queue state, and
/// no replacement ticket or admission is ever minted after a suspension.
///
/// Phase 5's own entry points (`syncSelect`/`syncLoad`/`syncStart`/…) never pass through here: their
/// authority is the synchronised coordinator's, re-proved by `runOwnedSteps`, and neither sequence
/// moves for them.
@MainActor
public final class LocalPlaybackEffects {
    private let player: any Player
    private let prepare: @MainActor () async -> Void
    private let resolve: @MainActor (LocalEntryId) async -> LocalTrackLocation?
    private let stillValid: @MainActor (LocalQueueEditAdmission) -> Bool
    private var selection: Int64 = 0
    private var transport: Int64 = 0

    /// A local effect's place in the operation order, captured when it is issued.
    private struct Ticket {
        let selection: Int64
        let transport: Int64
    }

    /// - Parameters:
    ///   - prepare: activates the music audio session — an externally visible effect, so it is proved too.
    ///   - resolve: where an entry's file is, or `nil` if it has gone.
    ///   - stillValid: compares a carried admission with the synchronisation owner's live lifetime.
    public init(
        player: any Player,
        prepare: @escaping @MainActor () async -> Void,
        resolve: @escaping @MainActor (LocalEntryId) async -> LocalTrackLocation?,
        stillValid: @escaping @MainActor (LocalQueueEditAdmission) -> Bool
    ) {
        self.player = player
        self.prepare = prepare
        self.resolve = resolve
        self.stillValid = stillValid
    }

    /// Runs an admitted edit's effects, each in its own task, each carrying `admission` and a ticket.
    @discardableResult
    public func run(_ effects: [LocalQueueEffect], admission: LocalQueueEditAdmission) -> [Task<Void, Never>] {
        effects.map { effect in
            switch effect {
            case .loadAndPlay(let localEntryId):
                let ticket = issue(newSelection: true, newTransport: true)
                return Task { await self.loadAndPlay(localEntryId, admission, ticket) }
            case .stopPlayback:
                let ticket = issue(newSelection: true, newTransport: true)
                return Task {
                    guard self.stillValid(admission), self.selectionCurrent(ticket), self.transportCurrent(ticket) else { return }
                    await self.player.execute(.stop)
                }
            case .resumePlayback:
                let ticket = issue(newSelection: false, newTransport: true)
                return Task { await self.resume(admission, ticket) }
            }
        }
    }

    /// A local Pause press: a newer transport intent than anything issued before it.
    @discardableResult
    public func pause(admission: LocalQueueEditAdmission) -> Task<Void, Never> {
        let ticket = issue(newSelection: false, newTransport: true)
        return Task {
            guard self.stillValid(admission), self.transportCurrent(ticket) else { return }
            await self.player.execute(.pause)
        }
    }

    /// A local seek: a position on the current selection, dropped if the selection has moved on.
    @discardableResult
    public func seek(positionMs: Int64, admission: LocalQueueEditAdmission) -> Task<Void, Never> {
        let ticket = issue(newSelection: false, newTransport: false)
        return Task {
            guard self.stillValid(admission), self.selectionCurrent(ticket) else { return }
            await self.player.execute(.seek(positionMs: positionMs))
        }
    }

    private func issue(newSelection: Bool, newTransport: Bool) -> Ticket {
        if newSelection { selection += 1 }
        if newTransport { transport += 1 }
        return Ticket(selection: selection, transport: transport)
    }

    private func selectionCurrent(_ ticket: Ticket) -> Bool { selection == ticket.selection }

    private func transportCurrent(_ ticket: Ticket) -> Bool { transport == ticket.transport }

    private func loadAndPlay(_ localEntryId: LocalEntryId, _ admission: LocalQueueEditAdmission, _ ticket: Ticket) async {
        guard stillValid(admission), selectionCurrent(ticket) else { return }
        await prepare()
        guard let location = await resolve(localEntryId) else { return }
        guard stillValid(admission), selectionCurrent(ticket) else { return }
        await player.execute(.load(localEntryId: localEntryId, location: location))
        // Neither proof taken before `Load` authorises `Play`: `Load` suspends, and a newer pause,
        // resume, stop or selection may have been issued meanwhile.
        guard stillValid(admission), selectionCurrent(ticket), transportCurrent(ticket) else { return }
        await player.execute(.play)
    }

    private func resume(_ admission: LocalQueueEditAdmission, _ ticket: Ticket) async {
        guard stillValid(admission), transportCurrent(ticket) else { return }
        await prepare()
        guard stillValid(admission), transportCurrent(ticket) else { return }
        await player.execute(.play)
    }
}
