package com.ridelink.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.ridelink.app.ui.MainScreen
import com.ridelink.app.ui.SecureTransportUnavailableScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RideLinkApplication).container
        val deviceDescription = "${Build.MANUFACTURER} ${Build.MODEL}"

        setContent {
            MaterialTheme {
                container.fold(
                    onSuccess = { MainScreen(coordinator = it.sessionCoordinator, deviceDescription = deviceDescription) },
                    // The only way to land here is a device-identity failure. There is deliberately
                    // no plaintext path to offer instead (ADR-007 Amendment A1).
                    onFailure = { SecureTransportUnavailableScreen(reason = it.message.orEmpty()) },
                )
            }
        }
    }
}
