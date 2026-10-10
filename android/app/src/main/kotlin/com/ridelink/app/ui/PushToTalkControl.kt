package com.ridelink.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ridelink.app.R

/**
 * Push to talk. Every cancellation releases the existing transmission gate; it owns no capture and no
 * session state, and [held] is only ever the voice controller's own report.
 *
 * Feedback (Phase 9A.5 §18) is a direct mapping of three states — idle, talking, unavailable — onto
 * colour, label and icon, plus a slight scale while held. The press reaches [onHeld] from the pointer
 * handler on the first down, before any animation runs, so nothing here can delay transmission; and
 * the scale follows the *reported* state, never the finger, so it cannot claim talking the gate has
 * not granted. With "Remove animations" on, the scale change is instant.
 */
@Composable
internal fun PushToTalkControl(
    available: Boolean,
    muted: Boolean,
    held: Boolean,
    onHeld: (Boolean) -> Unit,
) {
    DisposableEffect(Unit) { onDispose { onHeld(false) } }
    val usable = available && !muted
    val reducedMotion = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (held) HELD_SCALE else 1f,
        animationSpec = tween(if (reducedMotion) 0 else PRESS_MS),
        label = "ptt-scale",
    )
    val label =
        when {
            !available -> "Push to talk unavailable"
            muted -> "Unmute to talk"
            held -> "Talking — release to stop"
            else -> "Hold to talk"
        }
    Button(
        onClick = {},
        enabled = usable,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = RideSpace.rideTouch)
                .scale(scale)
                .semantics {
                    stateDescription =
                        if (held) {
                            "Talking"
                        } else if (usable) {
                            "Not talking"
                        } else {
                            "Unavailable"
                        }
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Start talking") {
                                if (usable) {
                                    onHeld(true)
                                    true
                                } else {
                                    false
                                }
                            },
                            CustomAccessibilityAction("Stop talking") {
                                onHeld(false)
                                true
                            },
                        )
                }.pointerInput(available, muted) {
                    if (!usable) return@pointerInput
                    try {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onHeld(true)
                            try {
                                waitForUpOrCancellation()
                            } finally {
                                onHeld(false)
                            }
                        }
                    } finally {
                        onHeld(false)
                    }
                },
        shape = MaterialTheme.shapes.large,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (held) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                contentColor = if (held) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary,
            ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (held) 6.dp else 1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private const val HELD_SCALE = 0.97f
private const val PRESS_MS = 90
