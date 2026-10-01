package com.juggling.tracker.wear

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juggling.tracker.wear.logic.AppNavigator
import com.juggling.tracker.wear.logic.JugglingDetector
import com.juggling.tracker.wear.logic.MonotonicClock
import com.juggling.tracker.wear.logic.RecordingSession
import com.juggling.tracker.wear.logic.Screen
import com.juggling.tracker.wear.logic.TrackerSession
import com.juggling.tracker.wear.platform.HandlerScheduler
import com.juggling.tracker.wear.ui.Tags
import com.juggling.tracker.wear.ui.WearApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The watch UI on a Wear OS emulator, with the phone replaced by
 * [RecordingPhone] and the accelerometer by recordings from connectiq/data.
 * Screenshots of each step land in /data/local/tmp/wear-screens.
 */
@RunWith(AndroidJUnit4::class)
class WearAppEmulatorTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val phone = RecordingPhone()
    private val effects = CountingEffects()
    private val clock = MonotonicClock { android.os.SystemClock.elapsedRealtime() }
    private val navigator = AppNavigator(
        newTracker = { balls -> TrackerSession(balls, phone, HandlerScheduler(), clock, effects) },
        newRecording = { balls -> RecordingSession(balls, phone, HandlerScheduler(), effects) },
        effects = effects,
    )

    private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

    private fun swipeBack() {
        compose.onRoot().performTouchInput { swipeRight(startX = left + 4f, endX = right - 4f) }
        compose.waitForIdle()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onRoot() =
        onNode(androidx.compose.ui.test.isRoot())

    @Test
    fun startUpFlowLeadsToTheTracker() {
        compose.setContent { WearApp(navigator) }
        compose.onNodeWithTag(Tags.MODE).assertTextEquals("Juggle")
        Emulator.screenshot("01_mode_select")
        click(Tags.START)
        click(Tags.UP)
        click(Tags.UP)
        compose.onNodeWithTag(Tags.BALLS).assertTextEquals("5")
        Emulator.screenshot("02_ball_select")
        click(Tags.START)
        compose.onNodeWithTag(Tags.RUN_STATE).assertTextEquals("WAITING")
        Emulator.screenshot("03_tracker_waiting")
    }

    @Test
    fun replayingARealRecordingShowsItsRunsAndSyncsThem() {
        val samples = Emulator.recording("20260922_144802") // 3 balls
        // What the detector makes of it, run by run, fed the way the app feeds it.
        val reference = JugglingDetector(3)
        samples.chunked(25).forEach { batch ->
            batch.forEach { reference.processSample(it.x, it.y, it.z, it.timeMs) }
            reference.checkAutoFinish(batch.last().timeMs)
        }
        reference.finishCurrentRun()
        val expectedRuns = reference.runCatches()

        compose.setContent { WearApp(navigator) }
        click(Tags.START)
        click(Tags.START)
        val session = (navigator.screen.value as Screen.Tracker).session
        compose.runOnUiThread { samples.chunked(25).forEach(session::onSamples) }
        compose.waitForIdle()
        Emulator.screenshot("04_tracker_after_recording")

        click(Tags.START)
        compose.onNodeWithText("End session?").assertIsDisplayed()
        Emulator.screenshot("05_session_end_menu")
        click("menu_item_${TrackerSession.ITEM_SYNC_QUIT}")
        compose.onNodeWithTag(Tags.SYNC).assertIsDisplayed()
        Emulator.screenshot("06_syncing")

        val payload = phone.sent.single()
        assertEquals("session", payload["type"])
        assertEquals(expectedRuns, payload["runs"])
        assertEquals(0, effects.exits)
        compose.runOnUiThread { phone.ack(payload["timestamp"] as Long) }
        compose.waitForIdle()
        assertEquals(1, effects.exits)
    }

    @Test
    fun swipingBackOffersToDiscardInsteadOfClosing() {
        compose.setContent { WearApp(navigator) }
        click(Tags.START)
        click(Tags.START)
        swipeBack()
        // Nothing recorded yet: the swipe is swallowed.
        compose.onNodeWithTag(Tags.COUNT).assertIsDisplayed()
        assertEquals(0, effects.exits)

        val session = (navigator.screen.value as Screen.Tracker).session
        val samples = Emulator.recording("20260922_144802").take(400)
        compose.runOnUiThread { samples.chunked(25).forEach(session::onSamples) }
        compose.waitForIdle()
        swipeBack()
        compose.onNodeWithTag("menu_title").assertIsDisplayed()
        Emulator.screenshot("07_discard_prompt")
        swipeBack()
        compose.onNodeWithTag(Tags.COUNT).assertIsDisplayed()
        assertEquals(0, effects.exits)
    }

    @Test
    fun swipingBackStepsBackThroughTheRecordScreens() {
        compose.setContent { WearApp(navigator) }
        click(Tags.UP)
        click(Tags.START)
        click(Tags.UP)
        click(Tags.START)
        compose.onNodeWithTag(Tags.REC_STATUS).assertTextEquals("Ready to record")
        swipeBack()
        compose.onNodeWithTag(Tags.BALLS).assertTextEquals("4")
        swipeBack()
        compose.onNodeWithTag(Tags.MODE).assertTextEquals("Record")
        assertEquals(0, effects.exits)
        swipeBack()
        assertEquals(1, effects.exits)
    }

    @Test
    fun recordModeLabelsARun() {
        compose.setContent { WearApp(navigator) }
        click(Tags.UP)
        click(Tags.START)
        click(Tags.START)
        Emulator.screenshot("08_record_idle")
        click(Tags.START)
        val session = (navigator.screen.value as Screen.Recording).session
        val samples = Emulator.recording("20260603_201719").take(500)
        compose.runOnUiThread { samples.chunked(25).forEach(session::onSamples) }
        compose.waitForIdle()
        Emulator.screenshot("09_recording")
        click(Tags.START)
        compose.onNodeWithTag(Tags.LABEL).assertIsDisplayed()
        Emulator.screenshot("10_labeling")
        click(Tags.UP)
        click(Tags.START)
        compose.waitUntil(5_000) { phone.sent.lastOrNull()?.get("type") == "rec_end" }
        assertEquals(500, phone.sent.first()["samples"])
        compose.runOnUiThread { phone.ack(phone.sent.first()["id"] as Long) }
        compose.onNodeWithTag(Tags.REC_STATUS).assertTextEquals("Ready to record")
    }

    @Test
    fun aMissingSensorShowsAnErrorInsteadOfAZeroCount() {
        compose.setContent { WearApp(navigator, sensorFailed = true) }
        click(Tags.START)
        click(Tags.START)
        compose.onNodeWithTag(Tags.SENSOR_ERROR).assertTextEquals("Sensor error")
        compose.onNodeWithText("Restart the app").assertIsDisplayed()
        Emulator.screenshot("11_sensor_error")
        // End still leads out of the session.
        click(Tags.START)
        compose.onNodeWithTag(Tags.MENU_TITLE).assertIsDisplayed()
    }

    @Test
    fun aMissingSensorLeavesNothingToRecord() {
        compose.setContent { WearApp(navigator, sensorFailed = true) }
        click(Tags.UP)
        click(Tags.START)
        click(Tags.START)
        compose.onNodeWithTag(Tags.SENSOR_ERROR).assertIsDisplayed()
        compose.onNodeWithTag(Tags.START).assertDoesNotExist()
    }
}
