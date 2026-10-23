package com.ridelink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import com.ridelink.app.session.ForgetPeerResult
import com.ridelink.core.security.ForgetPeerPolicy
import com.ridelink.core.security.TrustedPeer
import com.ridelink.core.sessionfsm.SessionStatus

/**
 * The *Paired phones* list and its *Forget* control (STATUS §4 problem 126, ADR-012 Amendment A1).
 *
 * Forgetting is the only recovery ADR-012 allows from a changed identity key, so it is a deliberate,
 * confirmed action: the dialog says that the next connection asks for the six-digit code again, and
 * that nothing else on this phone changes. The button is disabled — with the reason shown — outside
 * `IDLE`, from the same policy the coordinator enforces; the coordinator would refuse it anyway.
 *
 * Each row shows the display name and the pinned identity redacted to 6 hex (ARCHITECTURE §11), which
 * is enough to tell two records apart and far too little to identify a device.
 */
@Composable
internal fun PairedPhonesSection(
    peers: List<TrustedPeer>,
    status: SessionStatus,
    onForget: (TrustedPeer) -> ForgetPeerResult,
) {
    if (peers.isEmpty()) return
    // The peer_id of the row being confirmed. Survives rotation; the record itself is re-read from
    // [peers], so a dialog can never act on a phone that is no longer in the list.
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    val unavailable = forgetPeerUnavailableHint(status)

    Column(verticalArrangement = Arrangement.spacedBy(RideSpace.sm)) {
        NavigationGroup {
            peers.forEach { peer ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = RideSpace.touch + RideSpace.sm)
                        .padding(horizontal = RideSpace.lg, vertical = RideSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RideSpace.lg),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            peer.identitySpkiSha256.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            failure = null
                            confirming = peer.peerId.value
                        },
                        enabled = unavailable == null,
                    ) { Text("Forget") }
                }
            }
        }
        (failure ?: unavailable)?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val target = peers.firstOrNull { it.peerId.value == confirming }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Forget ${target.displayName}?") },
            text = { Text(forgetPeerConfirmation(target.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    failure = forgetPeerResultLabel(onForget(target))
                }) { Text("Forget phone") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Why *Forget* is disabled in [status], or null when it is offered (STATUS §4 problem 126). Reads the
 * same [ForgetPeerPolicy] the coordinator enforces, so the button and the refusal cannot disagree.
 */
internal fun forgetPeerUnavailableHint(status: SessionStatus): String? =
    when {
        ForgetPeerPolicy.permits(status) -> null
        status == SessionStatus.DISCOVERING -> "Tap Stop searching first, then forget the phone."
        status == SessionStatus.PAIRING || status == SessionStatus.CONNECTING ->
            "Finish or cancel the code check first, then end the session to forget the phone."
        status == SessionStatus.ENDING -> "Closing the connection…"
        else -> "End the session first, then forget the phone."
    }

/** The confirmation dialog's body: what forgetting does, and what it deliberately leaves alone. */
internal fun forgetPeerConfirmation(name: String): String =
    "This phone will stop trusting $name. The next time the two phones connect, both screens will " +
        "show a six-digit code that you must compare and confirm again. Forget this phone on the " +
        "other phone too. This phone's own identity, your music and your settings are not changed."

internal fun forgetPeerResultLabel(result: ForgetPeerResult): String? =
    when (result) {
        ForgetPeerResult.FORGOTTEN -> null
        ForgetPeerResult.REFUSED_SESSION_ACTIVE -> "End the session first, then forget the phone."
        ForgetPeerResult.FAILED -> "Could not forget the phone. It is still trusted. Try again."
    }
