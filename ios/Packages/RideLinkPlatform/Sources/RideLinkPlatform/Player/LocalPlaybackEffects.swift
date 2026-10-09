import Foundation
import RideLinkCore

/// The player half of an admitted local queue edit or local transport press (ADR-024 Amendment A15,
/// PR #18 review round 2). Mirrors Android's `com.ridelink.app.music.LocalPlaybackEffects`.
///
/// `LocalQueueEdits` admits an edit and applies its queue mutation synchronously; the effects run
/// here, later, in `Task`s that suspend — for audio-session activation, for the library lookup, and
/// inside `Load` itself. Every one of those suspensions releases the main actor, and synchronised mode
/// can take ownership of transport during any of them. So the admission minted with the edit travels
/// into every effect and is **re-proved immediately before each externally visible step**:
/// audio-session activation, `Load`, `Play`, `Stop`, `Pause`, `Seek`. A step whose admission has died
/// does nothing, and nothing after it runs.
///
/// Never mint a replacement admission after a suspension, and never read "is ownership local now?"
/// in its place: local A → synchronised B → local C is local again, and A's work must still be dead.
/// That is why the comparison is against the lifetime the admission carries.
///
/// Phase 5's own entry points (`syncSelect`/`syncLoad`/`syncStart`/…) never pass through here: their
/// authority is the synchronised coordinator's, re-proved by `runOwnedSteps`.
@MainActor
public final class LocalPlaybackEffects {
    private let player: any Player
    private let prepare: @MainActor () async -> Void
    private let resolve: @MainActor (LocalEntryId) async -> LocalTrackLocation?
    private let stillValid: @MainActor (LocalQueueEditAdmission) -> Bool

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

    /// Runs an admitted edit's effects, each in its own task, each carrying `admission`.
    @discardableResult
    public func run(_ effects: [LocalQueueEffect], admission: LocalQueueEditAdmission) -> [Task<Void, Never>] {
        effects.map { effect in
            switch effect {
            case .loadAndPlay(let localEntryId):
                Task { await self.loadAndPlay(localEntryId, admission) }
            case .stopPlayback:
                command(.stop, admission: admission)
            case .resumePlayback:
                Task { await self.resume(admission) }
            }
        }
    }

    /// One admitted local transport command (`Pause`, `Seek`, `Stop`), proved where it runs.
    @discardableResult
    public func command(_ command: PlaybackCommand, admission: LocalQueueEditAdmission) -> Task<Void, Never> {
        Task {
            guard self.stillValid(admission) else { return }
            await self.player.execute(command)
        }
    }

    private func loadAndPlay(_ localEntryId: LocalEntryId, _ admission: LocalQueueEditAdmission) async {
        guard stillValid(admission) else { return }
        await prepare()
        guard let location = await resolve(localEntryId) else { return }
        guard stillValid(admission) else { return }
        await player.execute(.load(localEntryId: localEntryId, location: location))
        // A proof taken before `Load` does not authorise `Play`: `Load` suspends.
        guard stillValid(admission) else { return }
        await player.execute(.play)
    }

    private func resume(_ admission: LocalQueueEditAdmission) async {
        guard stillValid(admission) else { return }
        await prepare()
        guard stillValid(admission) else { return }
        await player.execute(.play)
    }
}
