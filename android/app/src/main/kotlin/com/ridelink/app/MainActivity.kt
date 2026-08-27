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
                // AppContainer never constructs a session in a release build (this session's
                // brief §4) — `null` here means there is no plaintext transport to show, not a
                // bug to work around.
                val coordinator = container.sessionCoordinator
                if (coordinator != null) {
                    MainScreen(coordinator = coordinator, deviceDescription = deviceDescription)
                } else {
                    SecureTransportUnavailableScreen()
                }
            }
        }
    }
}
