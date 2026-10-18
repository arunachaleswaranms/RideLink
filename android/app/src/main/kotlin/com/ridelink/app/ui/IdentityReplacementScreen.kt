package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily

/**
 * Shown instead of the app when this phone's identity key cannot sign a TLS handshake
 * (ADR-017 Amendment A1, STATUS §4 problem 122).
 *
 * Replacing the identity changes this phone's SPKI — to a peer, a different phone (ADR-012) — so it
 * is offered, explained and confirmed, never done on the user's behalf. Until then RideLink builds
 * nothing around the unusable key: no discovery, no listener, no pairing — and, deliberately, no
 * local music either, because the music player lives in the same composition root
 * ([com.ridelink.app.di.AppContainer]) as the session it can be synchronised with. Splitting that
 * root to keep music running would create a second construction path for one-time-only state; the
 * screen says so instead, and the user's library and settings are untouched.
 */
@Composable
fun IdentityReplacementScreen(
    incompatibleIdentity: String,
    replacing: Boolean,
    onReplace: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(RideSpace.xl),
            verticalArrangement = Arrangement.spacedBy(RideSpace.md, Alignment.CenterVertically),
        ) {
            Text("RideLink", style = MaterialTheme.typography.headlineMedium)
            Text("This phone's secure identity needs replacing", style = MaterialTheme.typography.titleMedium)
            Text(
                "It was created by an earlier version of RideLink and cannot complete a secure " +
                    "connection on Android. Until it is replaced, RideLink cannot find, pair or connect " +
                    "to the other phone, and there is no unencrypted fallback.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Replacing it gives this phone a new identity. If the other phone already trusts this one, " +
                    "it will show a security warning: forget the pairing on both phones, then pair again " +
                    "and compare a new six-digit code.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Music on this phone is also unavailable until you decide. Your music library and " +
                    "settings are kept, and music is available again as soon as the identity is replaced.",
                style = MaterialTheme.typography.bodyMedium,
            )
            DiagnosticDisclosure("identity details") {
                Text(
                    "Current identity $incompatibleIdentity: its key is not authorised for the signature TLS uses.",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (replacing) {
                CircularProgressIndicator()
                Text("Replacing identity…", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { confirming = true }) { Text("Replace identity…") }
            }
        }
    }
    if (confirming && !replacing) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Replace this phone's identity?") },
            text = {
                Text(
                    "A new identity key is created on this phone. The old key is kept but never used again. " +
                        "Any pairing with this phone has to be redone with a new six-digit code.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onReplace()
                }) { Text("Replace identity") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
