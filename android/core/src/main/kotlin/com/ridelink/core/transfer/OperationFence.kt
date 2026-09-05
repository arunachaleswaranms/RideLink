package com.ridelink.core.transfer

/**
 * A monotonic fence guarding one coordinator-owned asynchronous operation slot against a stale
 * completion mutating state after the operation has been superseded — by user cancellation,
 * session/link loss, or a new operation starting in its place.
 *
 * This is the "small authoritative operation-generation guard" the Phase 4 closure audit calls
 * for (brief §5/§16/§17) in place of routing every transfer/manifest-sync step through
 * [TransferReducer]: it does not model transfer status, voice/session state or any domain
 * transition — it only answers "is the caller still the operation currently in force." Pure and
 * mirrored on both platforms (`RideLinkCore.Transfer.OperationFence`) so the invariant it enforces
 * — a superseded operation's own late writes become inert — is proven identically on both, exactly
 * like every other pure/mirrored type in `core.transfer`.
 *
 * **Not thread-safe by design.** Every call site in this codebase confines a given [OperationFence]
 * instance to one coroutine dispatcher (`Dispatchers.Main`, matching `AppContainer.appScope`), the
 * same confinement `SharedLibraryCoordinator`'s own plain `var activeDownload`/`pendingOffer`
 * fields already rely on. A fence shared across real threads would need external synchronisation.
 */
class OperationFence {
    // -1, not 0: begin() always returns a value >= 1 (pre-incremented), so this sentinel can never
    // collide with a real token — "nothing is current yet" holds for every possible token, not just
    // the ones a real caller happens to pass.
    private var current: Long = -1L

    /**
     * Starts a new operation, immediately invalidating whatever token an earlier [begin] handed
     * out. @return the token this new operation must present to every later [isCurrent] check.
     */
    fun begin(): Long {
        current += 1
        return current
    }

    /**
     * Invalidates the currently active operation without starting a replacement — user
     * cancellation or a session/link-loss teardown, where nothing should be considered "current"
     * until a fresh [begin] happens (if ever).
     */
    fun supersede() {
        current += 1
    }

    /** True only if [token] is still the operation most recently returned by [begin]. */
    fun isCurrent(token: Long): Boolean = token == current
}
