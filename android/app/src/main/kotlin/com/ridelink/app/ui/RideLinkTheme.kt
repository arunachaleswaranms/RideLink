package com.ridelink.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Suppress("MagicNumber") // Semantic palette, intentionally independent of wallpaper colours.
private val RideDark =
    darkColorScheme(
        primary = Color(0xFF8DDFCE),
        onPrimary = Color(0xFF00382F),
        primaryContainer = Color(0xFF164E42),
        onPrimaryContainer = Color(0xFFAEF2DB),
        onTertiary = Color(0xFF3E2E00),
        secondary = Color(0xFFBACCC7),
        secondaryContainer = Color(0xFF283A35),
        onSecondaryContainer = Color(0xFFD0E8DE),
        tertiary = Color(0xFFF2CD80),
        surfaceContainerHighest = Color(0xFF22302B),
        surfaceContainer = Color(0xFF18211F),
        background = Color(0xFF101716),
        surface = Color(0xFF18211F),
        surfaceVariant = Color(0xFF283A35),
        onSurface = Color(0xFFF0F5F2),
        onBackground = Color(0xFFF0F5F2),
        onSurfaceVariant = Color(0xFFC2CEC8),
    )

@Suppress("MagicNumber") // Semantic palette.
private val RideLight =
    lightColorScheme(
        primary = Color(0xFF006B59),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFC4F1E2),
        onPrimaryContainer = Color(0xFF00382F),
        onTertiary = Color.White,
        secondary = Color(0xFF46645B),
        secondaryContainer = Color(0xFFDBEAE3),
        onSecondaryContainer = Color(0xFF243E33),
        tertiary = Color(0xFF735600),
        surfaceContainerHighest = Color(0xFFE2ECE6),
        surfaceContainer = Color(0xFFFFFFFF),
        background = Color(0xFFF5F8F5),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE2ECE6),
        onSurface = Color(0xFF17221D),
        onBackground = Color(0xFF17221D),
        onSurfaceVariant = Color(0xFF475C51),
    )

@Composable
fun RideLinkTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    DisposableEffect(window, dark) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = !dark
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
    MaterialTheme(
        colorScheme = if (dark) RideDark else RideLight,
        shapes =
            Shapes(
                small = RoundedCornerShape(RideSpace.sm),
                medium = RoundedCornerShape(RideSpace.lg),
                large = RoundedCornerShape(RideSpace.lg),
            ),
        content = content,
    )
}

/** Expansion is presentation-only state; hidden diagnostics do no rendering work. */
@Composable
internal fun DiagnosticDisclosure(
    title: String = "Diagnostics",
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = RideSpace.touch),
        ) { Text(if (expanded) "Hide $title" else "Show $title") }
        if (expanded) content()
    }
}
