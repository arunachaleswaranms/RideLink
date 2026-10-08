package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.ridelink.app.music.CoexistenceDiagnostics
import com.ridelink.app.session.SessionCoordinator
import com.ridelink.app.sync.SyncPlaybackCoordinator
import com.ridelink.core.audiopolicy.IntercomPolicy
import com.ridelink.core.audiopolicy.RideStartDecision
import com.ridelink.core.playback.PlaybackRole
import com.ridelink.core.protocol.AudioStateMessage
import com.ridelink.core.sessionfsm.SessionStatus
import com.ridelink.network.voice.VoiceDiagnostics

/**
 * Phase 5's synchronised playback, as the home screen shows it: whether both phones are playing
 * together, a way back to playing on this phone only, and a short preview of the shared queue. The
 * full shared queue and the catalogue live on the other phone's music screen, which is lazy.
 *
 * **There are no transport buttons here any more** (Phase 9A.5). `MusicCoordinator`'s gate already
 * routes the main Now Playing controls — and the lock screen's — through the leader-ordered path
 * while synchronised mode is active (ADR-024 A14), so a second row of Prev/Pause/Resume/Next was two
 * controls for one action. The coordinator's direct entry points stay reachable under diagnostics,
 * where they always belonged.
 *
 * **The internal leader is never presented as a master.** ADR-010's rule is that both users get
 * identical controls; the role appears only in diagnostics, as a fact about command ordering.
 */
@Composable
fun SyncPlaybackCard(
    sync: SyncPlaybackCoordinator,
    titles: Map<String, String>,
) {
    val diagnostics by sync.diagnostics.collectAsState()
    val queue by sync.queueState.collectAsState()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
        Text(
            rideMusicLabel(SessionStatus.CONNECTED, diagnostics.syncState, sync.isSynchronizedModeActive()),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (diagnostics.role == null) return@Column
        if (sync.isSynchronizedModeActive()) {
            OutlinedButton(onClick = { sync.leaveSynchronizedMode() }) { Text("Play on this phone only") }
        }
        SharedQueueContent(queue, titles, sync::removeFromQueue, previewLimit = SHARED_QUEUE_PREVIEW)
        DiagnosticDisclosure("sync diagnostics") { SyncDiagnosticsBlock(sync) }
    }
}

/**
 * [MainScreen]'s post-trust-gate intercom content (PROTOCOL §7.1: voice, catalogue and
 * synchronisation all require the trust gate the same way, so they share one guard at the call
 * site).
 */
@Suppress("LongParameterList") // one per existing MainScreen value this block already read directly
@Composable
fun AuthenticatedIntercomSection(
    coordinator: SessionCoordinator,
    voice: VoiceDiagnostics,
    coexistence: CoexistenceDiagnostics,
    policy: IntercomPolicy,
    peerAudioState: AudioStateMessage?,
    intercomRefusal: RideStartDecision.Refused?,
    onStartIntercom: () -> Unit,
    onStopIntercom: () -> Unit,
) {
    VoiceCard(
        voice = voice,
        coexistence = coexistence,
        policy = policy,
        peerAudioState = peerAudioState,
        refusal = intercomRefusal,
        onStartIntercom = onStartIntercom,
        onStopIntercom = onStopIntercom,
        // The user's own Mute latch, not the wire's `mic_muted` — under PTT the latter is true
        // whenever the button is not held, and toggling from it would be a coin flip.
        onToggleMute = { coordinator.setMicrophoneMuted(!voice.userMuted) },
        onPushToTalkHeld = coordinator::setPushToTalkHeld,
        onSelectPolicy = coordinator::selectIntercomPolicy,
    )
}

/**
 * FR-023's Phase 5 half. Every number is a measurement or a count of something refused — there is no
 * claim here about audible alignment, which only the real-device gate can produce. The coordinator's
 * direct transport entry points are here for testing, labelled as such.
 */
@Composable
private fun SyncDiagnosticsBlock(sync: SyncPlaybackCoordinator) {
    val d by sync.diagnostics.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(RideSpace.xs)) {
        Text("Direct synchronized commands", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
            val active = sync.isSynchronizedModeActive()
            OutlinedButton(onClick = { sync.previous() }, enabled = active) { Text("Previous") }
            OutlinedButton(onClick = { sync.pause() }, enabled = active) { Text("Pause") }
            OutlinedButton(onClick = { sync.resume() }, enabled = active) { Text("Resume") }
            OutlinedButton(onClick = { sync.next() }, enabled = active) { Text("Next") }
            OutlinedButton(onClick = { sync.seek(0) }, enabled = active) { Text("Seek 0:00") }
        }
        SyncDiagnosticRow("sync state", d.syncState.name)
        SyncDiagnosticRow("transport ownership", if (sync.isSynchronizedModeActive()) "synchronized" else "local")
        SyncDiagnosticRow("role", if (d.role == PlaybackRole.LEADER) "orders commands" else "sends intents")
        SyncDiagnosticRow("clock ready", d.clockReady.toString())
        SyncDiagnosticRow("clock offset", d.clockOffsetUs?.let { "$it us" } ?: "—")
        SyncDiagnosticRow("rtt p95", d.rttP95Us?.let { "$it us" } ?: "—")
        SyncDiagnosticRow("scheduling lead", d.leadUs?.let { "$it us" } ?: "—")
        SyncDiagnosticRow("last command_seq", d.lastAppliedCommandSeq?.toString() ?: "—")
        SyncDiagnosticRow("late commands", d.lateCommandCount.toString())
        SyncDiagnosticRow("duplicate / stale", "${d.duplicateCommandCount} / ${d.staleCommandCount}")
        SyncDiagnosticRow("role violations", d.roleViolationCount.toString())
        SyncDiagnosticRow("stale revisions", d.staleRevisionCount.toString())
        SyncDiagnosticRow("queue revision / size", "${d.queueRevision} / ${d.queueSize}")
        SyncDiagnosticRow("local drift", d.localDriftMs?.let { "$it ms" } ?: "—")
        SyncDiagnosticRow("peer drift", d.peerDriftMs?.let { "$it ms" } ?: "—")
        SyncDiagnosticRow("last correction", d.lastCorrection.name)
        SyncDiagnosticRow("playback rate", d.playbackRate.toString())
        SyncDiagnosticRow("hard seeks", d.hardSeekCount.toString())
        SyncDiagnosticRow("schedule error", d.lastScheduleErrorUs?.let { "$it us (software only)" } ?: "—")
        SyncDiagnosticRow("route transitioning", d.routeTransitioning.toString())
        SyncDiagnosticRow("correction ticks", d.correctionTickCount.toString())
        SyncDiagnosticRow("session generation", d.sessionGeneration.toString())
    }
}

@Composable
private fun SyncDiagnosticRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private const val SHARED_QUEUE_PREVIEW = 3
