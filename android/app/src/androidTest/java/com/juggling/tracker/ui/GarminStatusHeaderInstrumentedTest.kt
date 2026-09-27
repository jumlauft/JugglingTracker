package com.juggling.tracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juggling.tracker.logic.GarminConnectionStatus
import com.juggling.tracker.ui.theme.JugglingTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The instrumented counterpart to the Robolectric suite, for checking the UI
 * against a real framework rather than shadows.
 *
 * CI does not run this: the GitHub runner has no emulator, and adding one costs
 * minutes per job and brings its own flakiness. The JVM tests under
 * `src/test/java/.../ui/` are the ones that gate every pull request; this suite
 * is for confirming on a device before a release, or when a Robolectric shadow
 * is suspected of lying.
 *
 *     ./gradlew connectedDebugAndroidTest    # needs a device or emulator
 *
 * Deliberately small. Anything assertable on the JVM belongs in the unit test
 * source set instead, where it actually runs on every change.
 */
@RunWith(AndroidJUnit4::class)
class GarminStatusHeaderInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun readyStateRendersAndRoutesToTheSessionInstructions() {
        var sessionClicks = 0
        var linkClicks = 0

        composeTestRule.setContent {
            JugglingTrackerTheme {
                GarminStatusHeader(
                    status = GarminConnectionStatus.READY,
                    message = "",
                    onPhoneRecordClick = {},
                    onGarminLinkClick = { linkClicks++ },
                    onGarminSessionClick = { sessionClicks++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Ready to record with Garmin").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start Session with Phone").assertIsDisplayed()

        composeTestRule.onNodeWithText("Ready to record with Garmin").performClick()

        assertEquals(1, sessionClicks)
        assertEquals(0, linkClicks)
    }
}
