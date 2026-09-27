package com.juggling.tracker.ui

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.juggling.tracker.logic.JugglingViewModel
import com.juggling.tracker.ui.theme.JugglingTrackerTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Closes the gap left open when the screen-off defect was fixed: the
 * `DisposableEffect` that holds the screen awake had no test, because there was
 * no Compose harness to render it in. PhoneSessionScreenAwakeTest covers the
 * ViewModel's `shouldKeepScreenOn`; this covers the composable actually applying
 * it to the view, which is the half that kept the display alive.
 *
 * Without it, the display times out mid-session -- juggling never touches the
 * screen -- and `onPause` tears the sensor listener down, so counting stops
 * silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepScreenOnTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: JugglingViewModel
    private lateinit var view: View

    /**
     * Renders the whole app, which is where the effect lives. The callbacks are
     * inert: the test drives the ViewModel directly, because the real ones are
     * MainActivity's sensor plumbing.
     */
    private fun renderApp() {
        viewModel = JugglingViewModel()
        composeTestRule.setContent {
            view = LocalView.current
            JugglingTrackerTheme {
                JugglingTrackerApp(
                    viewModel = viewModel,
                    isWatchAppRunning = false,
                    onStartPhoneSession = { true },
                    onStopPhoneSession = { true },
                    onCancelPhoneSession = {},
                    onStartRawRecording = { true },
                    onStopRawRecording = {},
                    onCancelRawRecording = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the screen is not held awake before a session starts`() {
        renderApp()

        assertFalse(view.keepScreenOn)
    }

    @Test
    fun `starting a phone session holds the screen awake`() {
        renderApp()

        composeTestRule.runOnUiThread { viewModel.startPhoneSession(ballCount = 3) }
        composeTestRule.waitForIdle()

        assertTrue(
            "the display must stay on for the whole session, or onPause stops counting",
            view.keepScreenOn,
        )
    }

    @Test
    fun `saving the session releases the screen`() {
        renderApp()
        composeTestRule.runOnUiThread { viewModel.startPhoneSession(ballCount = 3) }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { viewModel.stopPhoneSessionAndSave() }
        composeTestRule.waitForIdle()

        assertFalse("nothing is recording, so the screen may sleep again", view.keepScreenOn)
    }

    @Test
    fun `cancelling the session releases the screen`() {
        renderApp()
        composeTestRule.runOnUiThread { viewModel.startPhoneSession(ballCount = 3) }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { viewModel.cancelPhoneSession() }
        composeTestRule.waitForIdle()

        assertFalse(view.keepScreenOn)
    }

    @Test
    fun `a raw recording holds the screen awake too`() {
        // The raw capture flow streams from the same sensor and is the other
        // state `shouldKeepScreenOn` covers.
        renderApp()

        composeTestRule.runOnUiThread {
            viewModel.startRawRecordingFlow()
            viewModel.confirmRawRecordingBalls(5)
        }
        composeTestRule.waitForIdle()

        assertTrue(view.keepScreenOn)
    }
}
