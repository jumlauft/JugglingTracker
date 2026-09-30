package com.juggling.tracker.wear.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.text.TextLayoutResult
import com.juggling.tracker.wear.logic.AppNavigator
import com.juggling.tracker.wear.logic.FakeEffects
import com.juggling.tracker.wear.logic.FakePhoneLink
import com.juggling.tracker.wear.logic.FakeScheduler
import com.juggling.tracker.wear.logic.Feeds
import com.juggling.tracker.wear.logic.RecordingSession
import com.juggling.tracker.wear.logic.Screen
import com.juggling.tracker.wear.logic.TrackerSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every piece of text on every screen fits on one line, without being cut
 * off at the sides, on the smallest round watch (192 dp). Real font metrics
 * need native graphics. The screens are driven by tapping, so this also shows
 * the START button stays reachable at that size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round-watch")
class TextFitTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scheduler = FakeScheduler()
    private val link = FakePhoneLink()
    private val effects = FakeEffects()
    private val navigator = AppNavigator(
        newTracker = { balls -> TrackerSession(balls, link, scheduler, scheduler.clock, effects) },
        newRecording = { balls -> RecordingSession(balls, link, scheduler, effects) },
        effects = effects,
    )
    private val problems = mutableListOf<String>()

    private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

    /** Records every text on screen that wraps or is cut off at the sides. */
    private fun checkScreen(name: String) {
        compose.waitForIdle()
        val nodes = compose.onAllNodes(isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
        for (node in nodes) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: continue
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            val results = mutableListOf<TextLayoutResult>()
            action.action?.invoke(results)
            val layout = results.firstOrNull() ?: continue
            if (layout.lineCount > 1 || layout.didOverflowWidth) {
                problems += "$name: \"$text\" (${layout.lineCount} lines, too wide=${layout.didOverflowWidth})"
            }
        }
    }

    private fun feed(session: (List<com.juggling.tracker.wear.logic.AccelSample>) -> Unit, catches: Int) {
        val warm = Feeds.warmup()
        compose.runOnUiThread { (warm + Feeds.catches(Feeds.next(warm), catches)).chunked(25).forEach(session) }
    }

    @Test
    fun `no text wraps or is cut off on any screen`() {
        compose.setContent { WearApp(navigator) }
        checkScreen("mode juggle")
        click(Tags.UP)
        checkScreen("mode record")
        click(Tags.UP)
        click(Tags.START)
        checkScreen("balls")
        click(Tags.START)
        checkScreen("tracker waiting")

        val tracker = (navigator.screen.value as Screen.Tracker).session
        feed(tracker::onSamples, 12)
        compose.runOnUiThread { scheduler.advance(3_725_000); tracker.tick() }
        checkScreen("tracker running, over an hour in")
        compose.runOnUiThread { tracker.onBack() }
        checkScreen("discard prompt")
        compose.runOnUiThread { tracker.onMenuBack(); tracker.onStartStop() }
        checkScreen("session end menu")
        compose.runOnUiThread { tracker.onMenuSelect(TrackerSession.ITEM_SYNC_QUIT) }
        compose.runOnUiThread { scheduler.advance(2_000) }
        checkScreen("syncing")
        compose.runOnUiThread { scheduler.advance(TrackerSession.SYNC_TIMEOUT_MS) }
        checkScreen("sync failed menu")
        compose.runOnUiThread { tracker.onMenuSelect(TrackerSession.ITEM_CONTINUE) }
        checkScreen("tracker after failed sync")

        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `no text wraps or is cut off in record mode`() {
        compose.setContent { WearApp(navigator) }
        click(Tags.UP)
        click(Tags.START)
        click(Tags.START)
        checkScreen("record idle")
        click(Tags.START)
        val session = (navigator.screen.value as Screen.Recording).session
        feed(session::onSamples, 12)
        checkScreen("recording")
        click(Tags.START)
        checkScreen("labeling")
        click(Tags.START)
        checkScreen("syncing chunks")
        compose.runOnUiThread { link.complete(false) }
        checkScreen("sync failed menu")
        compose.runOnUiThread { session.onMenuSelect(RecordingSession.ITEM_SYNC_SKIP); session.onBack() }
        checkScreen("quit prompt")

        assertEquals(emptyList<String>(), problems)
    }
}
