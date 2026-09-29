package com.juggling.tracker.wear

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juggling.tracker.wear.logic.TrackerSession
import com.juggling.tracker.wear.ui.Tags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app on the emulator: real accelerometer, real Data Layer client,
 * real back key. No phone is paired, so nothing here syncs.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityEmulatorTest {
    @get:Rule
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
        click("menu_item_${TrackerSession.ITEM_NOSYNC_QUIT}")
        compose.waitForIdle()
        assertTrue(compose.activity.isFinishing)
    }
}
