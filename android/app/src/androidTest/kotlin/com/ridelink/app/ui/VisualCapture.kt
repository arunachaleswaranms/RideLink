package com.ridelink.app.ui

import android.graphics.Bitmap
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
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    repeat(3) {
        val frame = CountDownLatch(1)
        scenario.onActivity { it.window.decorView.postOnAnimation { it.window.decorView.postOnAnimation { frame.countDown() } } }
        assertTrue(frame.await(10, TimeUnit.SECONDS), "No capture frame: $name")
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@repeat
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-qa/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return
    }
    error("Screenshot service unavailable after three frames: $name")
}
