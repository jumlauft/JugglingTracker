package com.juggling.tracker.wear

import android.os.SystemClock
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juggling.tracker.wear.logic.TrackerSession
import com.juggling.tracker.wear.ui.Tags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app on the emulator: real accelerometer, real Data Layer client,
 * real back key. No phone is paired, so nothing here syncs.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityEmulatorTest {
    // The session outlives the activity, so close it once the activity is
    // gone: the next test starts at the first screen.
    @get:Rule(order = 0)
    val closeRuntime = object : ExternalResource() {
        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { WatchRuntime.shutdown() }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

    @Test
    fun backKeyOnTheTrackerNeverClosesTheApp() {
        compose.onNodeWithTag(Tags.MODE).assertTextEquals("Juggle")
        click(Tags.START)
        click(Tags.START)
        compose.onNodeWithTag(Tags.RUN_STATE).assertTextEquals("WAITING")

        Espresso.pressBackUnconditionally()
        compose.waitForIdle()
        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithTag(Tags.COUNT).assertTextEquals("0")
        Emulator.screenshot("11_real_app_tracker")
    }

    @Test
    fun quitWithoutSyncClosesTheApp() {
        click(Tags.START)
        click(Tags.START)
        click(Tags.START)
        compose.onNodeWithText("End session?").assertExists()
        // Hold on to the scenario: once the activity is destroyed the rule
        // can no longer hand it out.
        val scenario = compose.activityRule.scenario
        click("menu_item_${TrackerSession.ITEM_NOSYNC_QUIT}")
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }
}
