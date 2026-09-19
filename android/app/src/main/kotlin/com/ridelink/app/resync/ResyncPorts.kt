package com.ridelink.app.resync

import com.ridelink.core.resync.ResyncMessage
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.resync.ResyncRelay
import com.ridelink.network.resync.ResyncSink
import kotlinx.coroutines.flow.SharedFlow

/**
 * ADR-023 Amendment A3's coordinator-level test seam, applied to [ResyncCoordinator] exactly as
 * `library.TransferPorts` applies it to [com.ridelink.app.library.SharedLibraryCoordinator]:
 * [ResyncCoordinator] depends on [ControlSessionManager], a concrete production class in `network`
 * with no reason to know a test double exists, so its **exact** call surface is declared here as a
 * narrow interface with a zero-behaviour-change adapter. [com.ridelink.app.sync.SyncPlaybackCoordinator]
 * and [com.ridelink.app.library.SharedLibraryCoordinator] need no equivalent wrapping: both already
 * live in `app`, so a test constructs real instances of them directly (as
 * `SyncPlaybackTwoPeerTest`/`SharedLibraryCoordinatorProviderAuthorizationTest` already do).
 */
interface ResyncChannelPort {
    var sink: ResyncSink?

    suspend fun send(message: ResyncMessage): Boolean
}

/** [ResyncCoordinator]'s exact call surface on [ControlSessionManager]. */
interface ResyncSessionPort {
    val resync: ResyncChannelPort
    val events: SharedFlow<ControlEvent>
    val currentAuthGeneration: Long

    /** ADR-025 §1: see [com.ridelink.app.library.TransferSessionPort.liveAuthenticatedGeneration] — same contract. */
    val liveAuthenticatedGeneration: Long?
}

/** Zero-behaviour-change wrapper around an already-constructed [ResyncRelay] (its constructor is `internal` to `network`). */
internal class ResyncRelayAdapter(
    private val delegate: ResyncRelay,
) : ResyncChannelPort {
    override var sink: ResyncSink?
        get() = delegate.sink
        set(value) {
            delegate.sink = value
        }

    override suspend fun send(message: ResyncMessage): Boolean = delegate.send(message)
}

/** Zero-behaviour-change wrapper — `AppContainer`'s production call site. */
internal class ResyncSessionManagerAdapter(
    private val delegate: ControlSessionManager,
) : ResyncSessionPort {
    override val resync: ResyncChannelPort = ResyncRelayAdapter(delegate.resync)
    override val events: SharedFlow<ControlEvent> get() = delegate.events
    override val currentAuthGeneration: Long get() = delegate.currentAuthGeneration
    override val liveAuthenticatedGeneration: Long? get() = delegate.liveAuthenticatedGeneration
}
