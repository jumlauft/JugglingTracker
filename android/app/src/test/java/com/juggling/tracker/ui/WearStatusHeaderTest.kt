package com.juggling.tracker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juggling.tracker.data.WatchType
import com.juggling.tracker.logic.GarminConnectionStatus
import com.juggling.tracker.logic.WearConnectionStatus
import com.juggling.tracker.ui.theme.JugglingTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With Wear OS picked in Settings, the watch card follows the Wear OS link and
 * routes taps the way the Garmin card does: ready opens the session
 * instructions, not connected opens the connection checklist, and connecting
 * or receiving ignore a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WearStatusHeaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var sessionClicks = 0
    private var linkClicks = 0
    private var garminClicks = 0

    private val errorStates = listOf(
        WearConnectionStatus.NO_WATCH,
        WearConnectionStatus.WATCH_APP_MISSING,
        WearConnectionStatus.UNAVAILABLE,
    )

    private val inertStates = listOf(
        WearConnectionStatus.CHECKING,
        WearConnectionStatus.RECEIVING,
    )

    private fun labelFor(status: WearConnectionStatus) = when (status) {
        WearConnectionStatus.READY -> "Ready to record with Wear OS"
        WearConnectionStatus.RECEIVING -> "Receiving data..."
        WearConnectionStatus.CHECKING -> "Record with Wear OS watch"
        WearConnectionStatus.WATCH_APP_MISSING -> "Juggling app is not installed on the watch"
        WearConnectionStatus.NO_WATCH,
        WearConnectionStatus.UNAVAILABLE -> "Watch is not connected to phone"
    }

    private fun renderDrivenBy(
        initial: WearConnectionStatus,
        watchType: WatchType = WatchType.WEAR_OS,
    ): (WearConnectionStatus) -> Unit {
        var status by mutableStateOf(initial)
        composeTestRule.setContent {
            JugglingTrackerTheme {
                GarminStatusHeader(
                    status = GarminConnectionStatus.READY,
                    message = "",
                    onPhoneRecordClick = {},
                    onGarminLinkClick = { garminClicks++ },
                    onGarminSessionClick = { garminClicks++ },
                    watchType = watchType,
                    wearStatus = status,
                    onWearLinkClick = { linkClicks++ },
                    onWearSessionClick = { sessionClicks++ },
                )
            }
        }
        return { next ->
            composeTestRule.runOnUiThread { status = next }
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun `ready opens the session instructions`() {
        renderDrivenBy(WearConnectionStatus.READY)

        composeTestRule.onNodeWithText(labelFor(WearConnectionStatus.READY)).performClick()

        assertEquals(1, sessionClicks)
        assertEquals(0, linkClicks)
        assertEquals(0, garminClicks)
    }

    @Test
    fun `not connected opens the connection checklist`() {
        val setStatus = renderDrivenBy(errorStates.first())

        errorStates.forEach { status ->
            setStatus(status)
            sessionClicks = 0
            linkClicks = 0

            composeTestRule.onNodeWithText(labelFor(status)).performClick()

            assertEquals("$status should open the checklist", 1, linkClicks)
            assertEquals("$status must not start a session", 0, sessionClicks)
        }
    }

    @Test
    fun `connecting and receiving ignore a tap`() {
        val setStatus = renderDrivenBy(inertStates.first())

        inertStates.forEach { status ->
            setStatus(status)
            sessionClicks = 0
            linkClicks = 0

            composeTestRule.onNodeWithText(labelFor(status)).performClick()

            assertEquals(0, sessionClicks)
            assertEquals(0, linkClicks)
        }
    }

    @Test
    fun `every status is classified`() {
        val classified = errorStates + inertStates + WearConnectionStatus.READY
        assertEquals(WearConnectionStatus.entries.toSet(), classified.toSet())
        assertEquals(WearConnectionStatus.entries.size, classified.size)
    }

    @Test
    fun `each state shows its own label`() {
        val setStatus = renderDrivenBy(WearConnectionStatus.READY)

        WearConnectionStatus.entries.forEach { status ->
            setStatus(status)
            composeTestRule.onNodeWithText(labelFor(status)).assertIsDisplayed()
        }
    }

    @Test
    fun `with Garmin picked the card shows the Garmin link instead`() {
        renderDrivenBy(WearConnectionStatus.READY, watchType = WatchType.GARMIN)

        composeTestRule.onNodeWithText("Ready to record with Garmin").performClick()

        assertEquals(1, garminClicks)
        assertEquals(0, sessionClicks)
    }
}
