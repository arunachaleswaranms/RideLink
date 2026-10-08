package com.ridelink.app.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ridelink.app.R
import com.ridelink.core.sessionfsm.SessionStatus
import kotlinx.coroutines.delay

/**
 * The connection state, first thing on the home screen, as a status line rather than a card.
 *
 * Motion here only reports state (Phase 9A.5 §17): a small spinner while the app is actually
 * working (searching, connecting, reconnecting, ending) and a still dot otherwise; the title
 * cross-fades when the state changes; "Other phone found" slides in when discovery finds one; and a
 * check shows for two seconds when pairing has just succeeded. With the system's "Remove animations"
 * setting on, the spinner becomes a still dot and nothing animates.
 */
@Composable
internal fun ConnectionSummary(
    status: SessionStatus,
    peerCount: Int,
) {
    val reducedMotion = rememberReducedMotion()
    var justPaired by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf(status) }
    LaunchedEffect(status) {
        val paired = previous == SessionStatus.PAIRING && (status == SessionStatus.CONNECTING || status == SessionStatus.CONNECTED)
        previous = status
        if (paired) {
            justPaired = true
            delay(PAIRED_CHECK_MS)
            justPaired = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RideSpace.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RideSpace.md)) {
            StatusIndicator(status, reducedMotion)
            AnimatedContent(
                targetState = connectionTitle(status),
                transitionSpec = {
                    val duration = if (reducedMotion) 0 else TRANSITION_MS
                    fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
                },
                label = "connection-title",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) { title -> Text(title, style = MaterialTheme.typography.titleLarge) }
        }
        AnimatedVisibility(
            visible = justPaired,
            enter = if (reducedMotion) fadeIn(tween(0)) else fadeIn() + expandVertically(),
            exit = if (reducedMotion) fadeOut(tween(0)) else fadeOut() + shrinkVertically(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RideSpace.xs)) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Paired", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(connectionHint(status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(
            visible = status == SessionStatus.DISCOVERING && peerCount > 0,
            enter = if (reducedMotion) fadeIn(tween(0)) else fadeIn() + expandVertically(),
            exit = if (reducedMotion) fadeOut(tween(0)) else fadeOut() + shrinkVertically(),
        ) {
            Text(
                "Other phone found. Connecting…",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A spinner only while work is in progress; otherwise a dot whose fill says connected or not. */
@Composable
private fun StatusIndicator(
    status: SessionStatus,
    reducedMotion: Boolean,
) {
    val working = status in WORKING_STATES
    Box(Modifier.size(INDICATOR_SIZE), contentAlignment = Alignment.Center) {
        if (working && !reducedMotion) {
            CircularProgressIndicator(Modifier.size(INDICATOR_SIZE), strokeWidth = 2.dp)
        } else {
            val color = indicatorColor(status)
            val filled =
                status == SessionStatus.CONNECTED || status == SessionStatus.RIDE_ACTIVE || status == SessionStatus.ERROR || working
            Box(
                Modifier
                    .size(DOT_SIZE)
                    .then(if (filled) Modifier.background(color, CircleShape) else Modifier.border(2.dp, color, CircleShape)),
            )
        }
    }
}

@Composable
private fun indicatorColor(status: SessionStatus): Color =
    when (status) {
        SessionStatus.CONNECTED, SessionStatus.RIDE_ACTIVE -> MaterialTheme.colorScheme.primary
        SessionStatus.PAIRING, SessionStatus.RECONNECTING -> MaterialTheme.colorScheme.tertiary
        SessionStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** The system "Remove animations" switch (animator duration scale 0). */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

internal fun connectionTitle(status: SessionStatus): String =
    when (status) {
        SessionStatus.IDLE -> "Not connected"
        SessionStatus.DISCOVERING -> "Searching…"
        SessionStatus.CONNECTING -> "Connecting…"
        SessionStatus.PAIRING -> "Check the code"
        SessionStatus.CONNECTED -> "Connected"
        SessionStatus.RIDE_ACTIVE -> "Riding"
        SessionStatus.RECONNECTING -> "Reconnecting…"
        SessionStatus.DISCONNECTED -> "Disconnected"
        SessionStatus.ENDING -> "Ending session…"
        SessionStatus.ERROR -> "Connection problem"
    }

private val WORKING_STATES =
    setOf(SessionStatus.DISCOVERING, SessionStatus.CONNECTING, SessionStatus.RECONNECTING, SessionStatus.ENDING)
private val INDICATOR_SIZE = 18.dp
private val DOT_SIZE = 10.dp
private const val TRANSITION_MS = 180
private const val PAIRED_CHECK_MS = 2_000L
