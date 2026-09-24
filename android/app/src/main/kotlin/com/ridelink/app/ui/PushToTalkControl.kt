package com.ridelink.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/** Every cancellation releases the existing transmission gate; no capture/session ownership. */
@Composable
internal fun PushToTalkControl(
    available: Boolean,
    muted: Boolean,
    held: Boolean,
    onHeld: (Boolean) -> Unit,
) {
    DisposableEffect(Unit) { onDispose { onHeld(false) } }
    Button(
        onClick = {},
        enabled = available && !muted,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = RideSpace.rideTouch)
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Start talking") {
                                if (available && !muted) {
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
                    if (!available || muted) return@pointerInput
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
    ) {
        Text(if (held) "Push to Talk held · Release to stop" else "Hold to talk", style = MaterialTheme.typography.titleMedium)
    }
}
