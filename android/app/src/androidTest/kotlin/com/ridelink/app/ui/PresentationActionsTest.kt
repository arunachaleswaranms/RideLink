package com.ridelink.app.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ridelink.app.MainActivity
import com.ridelink.core.model.ContentHash
import com.ridelink.core.model.PeerId
import com.ridelink.core.playback.SharedQueueItem
import com.ridelink.core.playback.SharedQueueState
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PresentationActionsTest {
    @Test
    fun accessiblePttStartStopAndRemovalReleaseTheGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val teardownRelease = AtomicReference<CountDownLatch?>()
        val values = Collections.synchronizedList(mutableListOf<Boolean>())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val layout = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.setContent {
                    RideLinkTheme {
                        Column(Modifier.onGloballyPositioned { layout.countDown() }) {
                            PushToTalkControl(true, false, false) {
                                values.add(it)
                                if (!it) teardownRelease.get()?.countDown()
                            }
                        }
                    }
                }
            }
            assertTrue(layout.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100, 5000)

            fun action(label: String) {
                val nodes = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                val node =
                    nodes.firstOrNull { n -> n.actionList.any { it.label?.toString() == label } }
                        ?: error("Missing $label in ${nodes.map { it.text to it.actionList }}")
                assertTrue(node.performAction(node.actionList.first { it.label?.toString() == label }.id))
                instrumentation.waitForIdleSync()
            }
            action("Start talking")
            assertEquals(true, values.last())
            action("Stop talking")
            assertEquals(false, values.last())
            action("Start talking")
            val released = CountDownLatch(1)
            teardownRelease.set(released)
            scenario.onActivity { it.setContent {} }
            assertTrue(released.await(10, TimeUnit.SECONDS), "Control disposal did not release PTT")
            instrumentation.waitForIdleSync()
            assertEquals(false, values.last(), "Removing the control must release PTT")
        }
    }

    @Test
    fun duplicateTracksRemoveTheSelectedQueueItemExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val removed = Collections.synchronizedList(mutableListOf<String>())
        val hash = ContentHash("sha256:" + "a".repeat(64))
        val peer = PeerId("0123456789abcdef")
        val queue = SharedQueueState(listOf(SharedQueueItem("first", hash, peer, 0), SharedQueueItem("second", hash, peer, 1)), "first")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val layout = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.setContent {
                    RideLinkTheme {
                        Column(Modifier.onGloballyPositioned { layout.countDown() }) {
                            SharedQueueContent(queue, mapOf(hash.value to "Same track")) { removed.add(it) }
                        }
                    }
                }
            }
            assertTrue(layout.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100, 5000)
            val tree = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            val buttons =
                tree
                    .filter { it.text?.toString() == "Remove" }
                    .mapNotNull { node ->
                        generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                    }.distinct()
            assertEquals(2, buttons.size, "Queue nodes: ${tree.map { it.text to it.isClickable }}")
            assertTrue(buttons[1].performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(listOf("second"), removed.toList())
        }
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (root == null) emptyList() else listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
}
