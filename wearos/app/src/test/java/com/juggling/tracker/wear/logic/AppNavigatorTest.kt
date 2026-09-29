package com.juggling.tracker.wear.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start-up flow, `JugglingTrackerApp.mc`, `ModeSelectView.mc`, `BallSelectView.mc`. */
class AppNavigatorTest {
    private val scheduler = FakeScheduler()
    private val effects = FakeEffects()
    private val trackerBalls = mutableListOf<Int>()
    private val recordingBalls = mutableListOf<Int>()

    private fun navigator(enableRecordingMode: Boolean = AppNavigator.ENABLE_RECORDING_MODE) = AppNavigator(
        newTracker = { balls ->
            trackerBalls += balls
            TrackerSession(balls, FakePhoneLink(), scheduler, scheduler.clock, effects)
        },
        newRecording = { balls ->
            recordingBalls += balls
            RecordingSession(balls, FakePhoneLink(), scheduler, effects)
        },
        effects = effects,
        enableRecordingMode = enableRecordingMode,
    )

    @Test
    fun `APP-1 opens on the mode screen defaulting to juggle`() {
        assertEquals(Screen.ModeSelect(isRecordMode = false), navigator().screen.value)
    }

    @Test
    fun `APP-1 record mode ships enabled on purpose`() {
        assertTrue(AppNavigator.ENABLE_RECORDING_MODE)
    }

    @Test
    fun `APP-1 with record mode disabled the app starts on ball selection`() {
        assertEquals(Screen.BallSelect(TrackingMode.JUGGLE, 3), navigator(enableRecordingMode = false).screen.value)
    }

    @Test
    fun `APP-2 up and down both toggle the mode and start confirms`() {
        val nav = navigator()
        nav.onUp()
        assertEquals(Screen.ModeSelect(isRecordMode = true), nav.screen.value)
        nav.onDown()
        assertEquals(Screen.ModeSelect(isRecordMode = false), nav.screen.value)
        nav.onDown()
        nav.onStart()
        assertEquals(Screen.BallSelect(TrackingMode.RECORD, 3), nav.screen.value)
    }

    @Test
    fun `APP-3 ball selection starts at three and wraps both ways`() {
        val nav = navigator()
        nav.onStart()
        assertEquals(3, (nav.screen.value as Screen.BallSelect).ballCount)
        nav.onDown()
        assertEquals(9, (nav.screen.value as Screen.BallSelect).ballCount)
        nav.onUp()
        assertEquals(3, (nav.screen.value as Screen.BallSelect).ballCount)
        repeat(6) { nav.onUp() }
        assertEquals(9, (nav.screen.value as Screen.BallSelect).ballCount)
    }

    @Test
    fun `APP-4 start opens the tracker for the chosen mode with the chosen balls`() {
        val juggle = navigator()
        juggle.onStart()
        juggle.onUp()
        juggle.onUp()
        juggle.onStart()
        assertTrue(juggle.screen.value is Screen.Tracker)
        assertEquals(listOf(5), trackerBalls)

        val record = navigator()
        record.onUp()
        record.onStart()
        record.onStart()
        assertTrue(record.screen.value is Screen.Recording)
        assertEquals(listOf(3), recordingBalls)
    }

    @Test
    fun `back on the selection screens closes the app`() {
        val nav = navigator()
        nav.onBack()
        assertEquals(1, effects.exits)
    }

    @Test
    fun `back on the tracker goes to the session, never to the system`() {
        val nav = navigator()
        nav.onStart()
        nav.onStart()
        nav.onBack()
        assertEquals(0, effects.exits)
        nav.onStart() // START/STOP on the tracker
        val tracker = (nav.screen.value as Screen.Tracker).session
        assertEquals("End session?", tracker.state.value.menu!!.title)
        nav.onBack() // backs out of the menu
        assertEquals(null, tracker.state.value.menu)
        assertEquals(0, effects.exits)
    }
}
