package com.ridelink.app.library

import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.SpkiHash
import com.ridelink.core.model.TransferId
import com.ridelink.core.protocol.ManifestMessage
import com.ridelink.core.protocol.TransferMessage
import com.ridelink.data.transfer.ContentResolution
import com.ridelink.data.transfer.LocalContentResolver
import com.ridelink.network.control.ControlEvent
import com.ridelink.network.control.ControlSessionManager
import com.ridelink.network.manifest.ManifestRelay
import com.ridelink.network.manifest.ManifestSink
import com.ridelink.network.transfer.BulkFetchOutcome
import com.ridelink.network.transfer.BulkServeOutcome
import com.ridelink.network.transfer.BulkTransportManager
import com.ridelink.network.transfer.ChunkSink
import com.ridelink.network.transfer.ChunkSource
import com.ridelink.network.transfer.TransferRelay
import com.ridelink.network.transfer.TransferSink
import kotlinx.coroutines.flow.SharedFlow

/**
 * ADR-023 Amendment A3 — the coordinator-level test seam. [SharedLibraryCoordinator] depends on
 * three concrete production classes (`LocalContentResolver`, `BulkTransportManager`,
 * `ControlSessionManager`), each in a module below `app` with no reason to know a test double
 * exists. These three narrow interfaces are exactly [SharedLibraryCoordinator]'s own call surface
 * on each — nothing more — declared here, in `app`, rather than in `core`/`network`/`data`, because
 * that is the direction this module's dependencies already point and doing it here needs no change
 * to any lower module.
 *
 * A deterministic test builds a fake implementing these interfaces directly (a controllable
 * suspension point in [ContentResolverPort.resolve], mutable session state behind
 * [TransferSessionPort]) with no TLS, no coroutine timing tricks, and no change to production
 * wiring: the adapters below are the only thing standing between the real classes and these
 * interfaces, and [AppContainer][com.ridelink.app.di.AppContainer] is their one call site.
 */
interface ContentResolverPort {
    suspend fun resolve(
        contentHash: ContentHash,
        nowMonoUs: Long,
    ): ContentResolution
}

/** [SharedLibraryCoordinator]'s exact call surface on [BulkTransportManager] — nothing more. */
interface BulkTransportPort {
    suspend fun ensureListening(): Int

    fun tryIssueToken(
        transferId: TransferId,
        generation: Long,
    ): String?

    suspend fun serve(
        transferId: TransferId,
        expectedPeerSpki: SpkiHash,
        currentGeneration: () -> Long,
        expectedChunkCount: Long,
        source: ChunkSource,
    ): BulkServeOutcome

    @Suppress("LongParameterList")
    suspend fun fetch(
        transferId: TransferId,
        host: String,
        port: Int,
        token: String,
        expectedPeerSpki: SpkiHash,
        expectedChunkCount: Long,
        sink: ChunkSink,
    ): BulkFetchOutcome

    /** ADR-023 Amendment A5: `transfer_id`-scoped on Android too, mirroring iOS's A3 signature. */
    fun cancelActive(transferId: TransferId)

    fun close()
}

/**
 * [SharedLibraryCoordinator]'s exact call surface on [ManifestRelay] — [ManifestRelay] itself has
 * an `internal constructor`, so a fake implementing this interface directly (rather than wrapping
 * a real, constructed instance) is what makes a coordinator-level test possible from the `app`
 * module without a real [ControlSessionManager].
 */
interface ManifestChannelPort {
    var sink: ManifestSink?

    suspend fun send(message: ManifestMessage): Boolean
}

/** [SharedLibraryCoordinator]'s exact call surface on [TransferRelay] — see [ManifestChannelPort]'s doc comment. */
interface TransferChannelPort {
    var sink: TransferSink?

    suspend fun send(message: TransferMessage): Boolean
}

/**
 * [SharedLibraryCoordinator]'s exact call surface on [ControlSessionManager]: the `MANIFEST_*`/
 * `TRANSFER_*` channels, the session-lifecycle event stream, and the read-only live-session view
 * ([currentAuthGeneration]/[liveAuthenticatedGeneration]/[currentPeerSpki]/[currentPeerHost]) —
 * never the connection-management surface (handshake, pairing, reconnect), which stays
 * [ControlSessionManager]'s alone and has no reason to be fakeable here.
 */
interface TransferSessionPort {
    val manifest: ManifestChannelPort
    val transfer: TransferChannelPort
    val events: SharedFlow<ControlEvent>
    val currentAuthGeneration: Long

    /**
     * ADR-025 §1: the generation owning the connection that is an authenticated session **right
     * now**, or null when none is. What an inbound `MANIFEST_*`/`TRANSFER_*` message's own
     * authorising generation is compared against before it is applied.
     *
     * Deliberately not [currentAuthGeneration]: that one keeps reporting the last number it
     * assigned after the link drops, so a frame authorised by a session that has ended would still
     * match it.
     */
    val liveAuthenticatedGeneration: Long?
    val currentPeerSpki: SpkiHash?
    val currentPeerHost: String?
}

/** Zero-behavior-change wrapper — [AppContainer][com.ridelink.app.di.AppContainer]'s production call site. */
internal class LocalContentResolverAdapter(
    private val delegate: LocalContentResolver,
) : ContentResolverPort {
    override suspend fun resolve(
        contentHash: ContentHash,
        nowMonoUs: Long,
    ): ContentResolution = delegate.resolve(contentHash, nowMonoUs)
}

/** Zero-behavior-change wrapper — [AppContainer][com.ridelink.app.di.AppContainer]'s production call site. */
internal class BulkTransportManagerAdapter(
    private val delegate: BulkTransportManager,
) : BulkTransportPort {
    override suspend fun ensureListening(): Int = delegate.ensureListening()

    override fun tryIssueToken(
        transferId: TransferId,
        generation: Long,
    ): String? = delegate.tryIssueToken(transferId, generation)

    override suspend fun serve(
        transferId: TransferId,
        expectedPeerSpki: SpkiHash,
        currentGeneration: () -> Long,
        expectedChunkCount: Long,
        source: ChunkSource,
    ): BulkServeOutcome = delegate.serve(transferId, expectedPeerSpki, currentGeneration, expectedChunkCount, source)

    @Suppress("LongParameterList")
    override suspend fun fetch(
        transferId: TransferId,
        host: String,
        port: Int,
        token: String,
        expectedPeerSpki: SpkiHash,
        expectedChunkCount: Long,
        sink: ChunkSink,
    ): BulkFetchOutcome = delegate.fetch(transferId, host, port, token, expectedPeerSpki, expectedChunkCount, sink)

    override fun cancelActive(transferId: TransferId) = delegate.cancelActive(transferId)

    override fun close() = delegate.close()
}

/** Zero-behavior-change wrapper around an already-constructed [ManifestRelay] (its own constructor is `internal` to `network`). */
internal class ManifestRelayAdapter(
    private val delegate: ManifestRelay,
) : ManifestChannelPort {
    override var sink: ManifestSink?
        get() = delegate.sink
        set(value) {
            delegate.sink = value
        }

    override suspend fun send(message: ManifestMessage): Boolean = delegate.send(message)
}

/** Zero-behavior-change wrapper around an already-constructed [TransferRelay] (its own constructor is `internal` to `network`). */
internal class TransferRelayAdapter(
    private val delegate: TransferRelay,
) : TransferChannelPort {
    override var sink: TransferSink?
        get() = delegate.sink
        set(value) {
            delegate.sink = value
        }

    override suspend fun send(message: TransferMessage): Boolean = delegate.send(message)
}

/** Zero-behavior-change wrapper — [AppContainer][com.ridelink.app.di.AppContainer]'s production call site. */
internal class ControlSessionManagerAdapter(
    private val delegate: ControlSessionManager,
) : TransferSessionPort {
    override val manifest: ManifestChannelPort = ManifestRelayAdapter(delegate.manifest)
    override val transfer: TransferChannelPort = TransferRelayAdapter(delegate.transfer)
    override val events: SharedFlow<ControlEvent> get() = delegate.events
    override val currentAuthGeneration: Long get() = delegate.currentAuthGeneration
    override val liveAuthenticatedGeneration: Long? get() = delegate.liveAuthenticatedGeneration
    override val currentPeerSpki: SpkiHash? get() = delegate.currentPeerSpki
    override val currentPeerHost: String? get() = delegate.currentPeerHost
}
