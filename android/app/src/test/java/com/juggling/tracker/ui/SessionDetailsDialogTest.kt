package com.juggling.tracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.juggling.tracker.model.SessionSummary
import com.juggling.tracker.model.summarizeSession
import com.juggling.tracker.ui.theme.JugglingTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Session details always show Regularity: the watch's score when the session
 * has one, and "n/a" for sessions recorded before the watches sent it (or too
 * short to score).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDetailsDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun showsRegularityWhenTheSessionHasOne() {
        show(summarizeSession(1000L, 3, listOf(10, 20), shapeConsistency = 83))

        composeTestRule.onNodeWithText("Regularity").assertIsDisplayed()
        composeTestRule.onNodeWithText("83%").assertIsDisplayed()
    }

    @Test
    fun showsNotAvailableForSessionsWithoutRegularity() {
        show(summarizeSession(1000L, 3, listOf(10, 20)))

        composeTestRule.onNodeWithText("Regularity").assertIsDisplayed()
        composeTestRule.onNodeWithText("n/a").assertIsDisplayed()
    }

    private fun show(session: SessionSummary) {
        composeTestRule.setContent {
            JugglingTrackerTheme {
                SessionDetailsDialog(session = session, onDismiss = {})
            }
        }
    }
}
