package com.juggling.tracker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juggling.tracker.logic.GarminConnectionStatus
import com.juggling.tracker.ui.theme.JugglingTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The header's tap target depends on the connection state: "ready" opens the
 * session instructions, any error opens the link checklist, and the two states
 * the user cannot act on do nothing. Nothing in the app tested that routing, so
 * a state moved between branches would have gone unnoticed.
 *
 * Runs under Robolectric, so it executes inside `testDebugUnitTest` and
 * therefore in CI on every pull request, rather than needing an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GarminStatusHeaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var sessionClicks = 0
    private var linkClicks = 0
    private var phoneClicks = 0

    /** Every state whose card should open the link checklist when tapped. */
    private val errorStates = listOf(
        GarminConnectionStatus.CONNECT_IQ_MISSING,
        GarminConnectionStatus.WATCH_APP_MISSING,
        GarminConnectionStatus.DISCONNECTED,
        GarminConnectionStatus.BLUETOOTH_DISABLED,
        GarminConnectionStatus.NO_PAIRED_DEVICES,
        GarminConnectionStatus.SDK_ERROR,
    )

    /** The states the header deliberately makes inert. */
    private val inertStates = listOf(
        GarminConnectionStatus.RECEIVING,
        GarminConnectionStatus.NOT_INITIALIZED,
    )

    /**
     * The label the Garmin card shows. Only READY, RECEIVING and
     * WATCH_APP_MISSING show their own status line; the rest fall back to the
     * generic "not linked" string, and NOT_INITIALIZED shows the idle label.
     */
    private fun labelFor(status: GarminConnectionStatus) = when (status) {
        GarminConnectionStatus.READY -> "Ready to record with Garmin"
        GarminConnectionStatus.RECEIVING -> "Receiving data..."
        GarminConnectionStatus.WATCH_APP_MISSING -> "Juggling app is not installed on the watch"
        GarminConnectionStatus.NOT_INITIALIZED -> "Record with Garmin watch"
        else -> "Watch is not linked correctly to phone"
    }

    /** Renders the header once, driven by a state the test can move. */
    private fun renderDrivenBy(initial: GarminConnectionStatus): (GarminConnectionStatus) -> Unit {
        var status by mutableStateOf(initial)
        composeTestRule.setContent {
            JugglingTrackerTheme {
                GarminStatusHeader(
                    status = status,
                    message = "a message from the SDK",
                    onPhoneRecordClick = { phoneClicks++ },
                    onGarminLinkClick = { linkClicks++ },
                    onGarminSessionClick = { sessionClicks++ },
                )
            }
        }
        return { next ->
            composeTestRule.runOnUiThread { status = next }
            composeTestRule.waitForIdle()
        }
    }

    private fun tapGarminCard(status: GarminConnectionStatus) {
        composeTestRule.onNodeWithText(labelFor(status)).performClick()
    }

    // ── Which callback a tap reaches ─────────────────────────────────────

    @Test
    fun `ready opens the session instructions`() {
        renderDrivenBy(GarminConnectionStatus.READY)

        tapGarminCard(GarminConnectionStatus.READY)

        assertEquals(1, sessionClicks)
        assertEquals(0, linkClicks)
    }

    @Test
    fun `every error state opens the link checklist`() {
        val setStatus = renderDrivenBy(errorStates.first())

        errorStates.forEach { status ->
            setStatus(status)
            sessionClicks = 0
            linkClicks = 0

            tapGarminCard(status)

            assertEquals("$status should open the checklist", 1, linkClicks)
            assertEquals("$status must not start a session", 0, sessionClicks)
        }
    }

    @Test
    fun `the inert states ignore a tap`() {
        val setStatus = renderDrivenBy(inertStates.first())

        inertStates.forEach { status ->
            setStatus(status)
            sessionClicks = 0
            linkClicks = 0

            tapGarminCard(status)

            assertEquals("$status must not start a session", 0, sessionClicks)
            assertEquals("$status must not open the checklist", 0, linkClicks)
        }
    }

    @Test
    fun `every status is classified`() {
        // A status added to the enum without a routing decision would otherwise
        // fall into the `else` branch silently and start opening the checklist.
        val classified = errorStates + inertStates + GarminConnectionStatus.READY

        assertEquals(
            "each GarminConnectionStatus needs a deliberate routing decision",
            GarminConnectionStatus.entries.toSet(),
            classified.toSet(),
        )
        assertEquals(GarminConnectionStatus.entries.size, classified.size)
    }

    @Test
    fun `the phone card starts a phone session`() {
        renderDrivenBy(GarminConnectionStatus.READY)

        composeTestRule.onNodeWithText("Start Session with Phone").performClick()

        assertEquals(1, phoneClicks)
        assertEquals(0, sessionClicks)
    }

    // ── What the card says ───────────────────────────────────────────────

    @Test
    fun `each state shows its own label`() {
        val setStatus = renderDrivenBy(GarminConnectionStatus.READY)

        GarminConnectionStatus.entries.forEach { status ->
            setStatus(status)

            composeTestRule.onNodeWithText(labelFor(status)).assertIsDisplayed()
        }
    }
}
