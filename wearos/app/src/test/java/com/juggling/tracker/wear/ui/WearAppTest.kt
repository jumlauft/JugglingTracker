package com.juggling.tracker.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juggling.tracker.wear.logic.AppNavigator
import com.juggling.tracker.wear.logic.FakeEffects
import com.juggling.tracker.wear.logic.FakePhoneLink
import com.juggling.tracker.wear.logic.FakeScheduler
import com.juggling.tracker.wear.logic.Feeds
import com.juggling.tracker.wear.logic.RecordingSession
import com.juggling.tracker.wear.logic.Screen
import com.juggling.tracker.wear.logic.TrackerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The watch UI on the JVM under Robolectric: taps, the back gesture, and the
 * menus, driven through the real composables with a fake phone and clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round-watch")
class WearAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scheduler = FakeScheduler()
    private val link = FakePhoneLink()
    private val effects = FakeEffects()
    private val navigator = AppNavigator(
        newTracker = { balls -> TrackerSession(balls, link, scheduler, scheduler.clock, effects, epochSeconds = { 1_700_000_000L }) },
        newRecording = { balls -> RecordingSession(balls, link, scheduler, effects, epochSeconds = { 1_700_000_000L }) },
        effects = effects,
    )

    private fun show() = compose.setContent { WearApp(navigator) }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun tracker() = (navigator.screen.value as Screen.Tracker).session

    private fun openTracker(balls: Int = 3) {
        click(Tags.START)
        repeat(balls - 3) { click(Tags.UP) }
        click(Tags.START)
    }

    private fun juggle(catches: Int) {
        val session = tracker()
        val warm = Feeds.warmup()
        val run = Feeds.catches(Feeds.next(warm), catches)
        compose.runOnUiThread { (warm + run).chunked(25).forEach(session::onSamples) }
        compose.waitForIdle()
    }

    @Test
    fun `mode screen toggles and leads to ball selection`() {
        show()
        compose.onNodeWithTag(Tags.MODE).assertTextEquals("Juggle")
        click(Tags.UP)
        compose.onNodeWithTag(Tags.MODE).assertTextEquals("Record")
        click(Tags.DOWN)
        compose.onNodeWithTag(Tags.MODE).assertTextEquals("Juggle")
        click(Tags.START)
        compose.onNodeWithTag(Tags.BALLS).assertTextEquals("3")
        click(Tags.DOWN)
        compose.onNodeWithTag(Tags.BALLS).assertTextEquals("9")
        click(Tags.UP)
        compose.onNodeWithTag(Tags.BALLS).assertTextEquals("3")
    }

    @Test
    fun `tracker shows the live count and run state`() {
        show()
        openTracker()
        compose.onNodeWithTag(Tags.RUN_STATE).assertTextEquals("WAITING")
        compose.onNodeWithTag(Tags.COUNT).assertTextEquals("0")
        compose.onNodeWithTag(Tags.PREV).assertTextEquals("Prev: -")
        juggle(4)
        compose.onNodeWithTag(Tags.RUN_STATE).assertTextEquals("RUN ACTIVE")
        compose.onNodeWithTag(Tags.COUNT).assertTextEquals("4")
    }

    @Test
    fun `back on the tracker with nothing recorded stays put`() {
        show()
        openTracker()
        pressBack()
        compose.onNodeWithTag(Tags.COUNT).assertIsDisplayed()
        assertEquals(0, effects.exits)
    }

    @Test
    fun `back during a run offers to discard it`() {
        show()
        openTracker()
        juggle(4)
        pressBack()
        compose.onNodeWithText("Discard this run?").assertIsDisplayed()
        compose.onNodeWithText("4 catches").assertIsDisplayed()
        click(Tags.menuItem(TrackerSession.ITEM_DISCARD_YES))
        compose.onNodeWithTag(Tags.COUNT).assertTextEquals("0")
        assertEquals(0, effects.exits)
    }

    @Test
    fun `back out of the discard prompt keeps the run`() {
        show()
        openTracker()
        juggle(4)
        pressBack()
        pressBack()
        compose.onNodeWithTag(Tags.COUNT).assertTextEquals("4")
    }

    @Test
    fun `end opens the session end menu and continue returns`() {
        show()
        openTracker()
        click(Tags.START)
        compose.onNodeWithText("End session?").assertIsDisplayed()
        compose.onNodeWithText("Sync and quit").assertIsDisplayed()
        compose.onNodeWithText("Quit without sync").assertIsDisplayed()
        click(Tags.menuItem(TrackerSession.ITEM_CONTINUE))
        compose.onNodeWithTag(Tags.COUNT).assertIsDisplayed()
    }

    @Test
    fun `sync waits for the phone's ack and then closes`() {
        show()
        openTracker()
        juggle(3)
        click(Tags.START)
        click(Tags.menuItem(TrackerSession.ITEM_SYNC_QUIT))
        compose.onNodeWithTag(Tags.SYNC).assertTextEquals("Sync to phone.")
        assertEquals("session", link.sent.single()["type"])
        assertEquals(0, effects.exits)
        compose.runOnUiThread { link.ack(1_700_000_000L) }
        assertEquals(1, effects.exits)
    }

    @Test
    fun `a failed sync shows the banner and the retry menu`() {
        show()
        openTracker()
        juggle(3)
        click(Tags.START)
        click(Tags.menuItem(TrackerSession.ITEM_SYNC_QUIT))
        compose.runOnUiThread { link.complete(false) }
        compose.onNodeWithText("Retry sync").assertIsDisplayed()
        click(Tags.menuItem(TrackerSession.ITEM_CONTINUE))
        compose.onNodeWithTag(Tags.COUNT).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTagCount(Tags.ERROR) == 0)
    }

    @Test
    fun `record mode records, labels and syncs a run`() {
        show()
        click(Tags.UP) // Record
        click(Tags.START)
        click(Tags.START)
        compose.onNodeWithTag(Tags.REC_STATUS).assertTextEquals("Ready to record")
        click(Tags.START)
        compose.onNodeWithTag(Tags.REC_STATUS).assertTextEquals("RECORDING")
        val session = (navigator.screen.value as Screen.Recording).session
        compose.runOnUiThread {
            val warm = Feeds.warmup()
            session.onSamples(warm)
            session.onSamples(Feeds.catches(Feeds.next(warm), 3))
        }
        click(Tags.START)
        compose.onNodeWithTag(Tags.LABEL).assertTextEquals("3")
        click(Tags.UP)
        compose.onNodeWithTag(Tags.LABEL).assertTextEquals("4")
        click(Tags.START)
        assertEquals("rec_start", link.sent.single()["type"])
        assertEquals(4, link.sent.single()["catches"])
        pressBack() // consumed while syncing
        compose.onNodeWithTag(Tags.SYNC).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
