package com.ridelink.app.ui

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ridelink.app.MainActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Screenshot service may briefly be unavailable; wait for a new frame, never accept a missing image. */
internal fun captureScreen(
    scenario: ActivityScenario<MainActivity>,
    name: String,
    suffix: String = "",
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    repeat(20) {
        val frame = CountDownLatch(1)
        scenario.onActivity { it.window.decorView.postOnAnimation { it.window.decorView.postOnAnimation { frame.countDown() } } }
        assertTrue(frame.await(10, TimeUnit.SECONDS), "No capture frame: $name")
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5000)
        // Compose's accessibility snapshot may trail layout. Require the new fixture's identity,
        // not merely a main-loop idle event, before accepting its native screenshot.
        val visibleText = visibleText(instrumentation.uiAutomation.rootInActiveWindow)
        if (!visibleText.contains("Fixture $name")) return@repeat
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@repeat
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-qa/$name$suffix.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return
    }
    error("Fixture or screenshot unavailable after twenty frames: $name")
}

private fun visibleText(node: AccessibilityNodeInfo?): String =
    if (node ==
        null
    ) {
        ""
    } else {
        "${node.text ?: ""} ${node.contentDescription ?: ""} " +
            (0 until node.childCount).joinToString(" ") { visibleText(node.getChild(it)) }
    }

internal fun accessibilityNodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
    if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { accessibilityNodes(node.getChild(it)) }

/** Only requested by the explicit software-keyboard fixture run. */
internal fun awaitKeyboard(
    scenario: ActivityScenario<MainActivity>,
    visible: Boolean,
) {
    repeat(60) {
        var matches = false
        scenario.onActivity { activity ->
            matches = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == visible
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 5000)
        if (matches) return
    }
    error("Keyboard visibility did not become $visible")
}
