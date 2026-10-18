package com.ridelink.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ridelink.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * ADR-017 Amendment A1: replacing this phone's identity is a new SPKI, so it happens only after an
 * explicit confirmation — never on the first tap, never on Cancel, and never while one is running.
 */
@RunWith(AndroidJUnit4::class)
class IdentityReplacementScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun show(
        replacing: Boolean,
        replacements: AtomicInteger,
    ): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    RideLinkTheme {
                        IdentityReplacementScreen(
                            incompatibleIdentity = "spki:abcdef…",
                            replacing = replacing,
                            onReplace = { replacements.incrementAndGet() },
                        )
                    }
                }
            }
        }

    @Test
    fun theFirstTapOnlyAsksAndCancelReplacesNothing() {
        val replacements = AtomicInteger()
        show(replacing = false, replacements).use {
            compose.onNodeWithText("Replace identity…").performClick()
            compose.onNodeWithText("Replace this phone's identity?").assertIsDisplayed()
            assertEquals(0, replacements.get(), "opening the confirmation must not replace anything")

            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
            assertEquals(0, replacements.get())
        }
    }

    @Test
    fun onlyTheConfirmationReplacesAndItDoesSoOnce() {
        val replacements = AtomicInteger()
        show(replacing = false, replacements).use {
            compose.onNodeWithText("Replace identity…").performClick()
            compose.onNodeWithText("Replace identity").performClick()
            compose.waitForIdle()
            assertEquals(1, replacements.get())
        }
    }

    @Test
    fun aReplacementInProgressOffersNoSecondOne() {
        val replacements = AtomicInteger()
        show(replacing = true, replacements).use {
            compose.onNodeWithText("Replacing identity…").assertIsDisplayed()
            compose.onNodeWithText("Replace identity…").assertDoesNotExist()
            assertEquals(0, replacements.get())
        }
    }
}
