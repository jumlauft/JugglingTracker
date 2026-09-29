package com.juggling.tracker.wear.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrackingMode { JUGGLE, RECORD }

/** The screen currently showing. */
sealed interface Screen {
    /** APP-1: Juggle or Record, defaulting to Juggle. */
    data class ModeSelect(val isRecordMode: Boolean = false) : Screen

    /** APP-3: 3 to 9 balls, opening on 3. */
    data class BallSelect(val mode: TrackingMode, val ballCount: Int = MIN_BALLS) : Screen

    class Tracker(val session: TrackerSession) : Screen

    class Recording(val session: RecordingSession) : Screen

    companion object {
        const val MIN_BALLS = 3
        const val MAX_BALLS = 9
    }
}

/**
 * The start-up flow of `JugglingTrackerApp.mc`, `ModeSelectView.mc` and
 * `BallSelectView.mc`, plus routing of the four Garmin buttons to whichever
 * screen is showing. UP and DOWN come from the rotary input or the on-screen
 * arrows, START from the on-screen button, BACK from a swipe or back key.
 */
class AppNavigator(
    private val newTracker: (balls: Int) -> TrackerSession,
    private val newRecording: (balls: Int) -> RecordingSession,
    private val effects: WatchEffects,
    enableRecordingMode: Boolean = ENABLE_RECORDING_MODE,
) {
    companion object {
        /**
         * Record mode ships enabled on purpose, as on the Garmin (APP-1): it
         * is how the labelled corpus grows. A test pins this value so turning
         * it off is a deliberate decision.
         */
        const val ENABLE_RECORDING_MODE = true
    }

    private val _screen = MutableStateFlow<Screen>(
        if (enableRecordingMode) Screen.ModeSelect() else Screen.BallSelect(TrackingMode.JUGGLE),
    )
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** UP. On mode select both UP and DOWN toggle (APP-2). */
    fun onUp() {
        when (val s = _screen.value) {
            is Screen.ModeSelect -> _screen.value = s.copy(isRecordMode = !s.isRecordMode)
            is Screen.BallSelect -> _screen.value = s.copy(ballCount = increment(s.ballCount))
            is Screen.Recording -> s.session.onUp()
            is Screen.Tracker -> Unit
        }
    }

    /** DOWN. */
    fun onDown() {
        when (val s = _screen.value) {
            is Screen.ModeSelect -> _screen.value = s.copy(isRecordMode = !s.isRecordMode)
            is Screen.BallSelect -> _screen.value = s.copy(ballCount = decrement(s.ballCount))
            is Screen.Recording -> s.session.onDown()
            is Screen.Tracker -> Unit
        }
    }

    /** START. */
    fun onStart() {
        when (val s = _screen.value) {
            is Screen.ModeSelect -> _screen.value =
                Screen.BallSelect(if (s.isRecordMode) TrackingMode.RECORD else TrackingMode.JUGGLE)
            // APP-4: open the tracker for the mode chosen earlier.
            is Screen.BallSelect -> _screen.value = when (s.mode) {
                TrackingMode.JUGGLE -> Screen.Tracker(newTracker(s.ballCount))
                TrackingMode.RECORD -> Screen.Recording(newRecording(s.ballCount))
            }
            is Screen.Tracker -> s.session.onStartStop()
            is Screen.Recording -> s.session.onStart()
        }
    }

    /**
     * BACK. The selection screens leave it to the system, which closes the
     * app; the tracking screens never let it through.
     */
    fun onBack() {
        when (val s = _screen.value) {
            is Screen.ModeSelect, is Screen.BallSelect -> effects.exit()
            is Screen.Tracker -> {
                val menu = s.session.state.value.menu
                if (menu != null) s.session.onMenuBack() else s.session.onBack()
            }
            is Screen.Recording -> {
                val menu = s.session.state.value.menu
                if (menu != null) s.session.onMenuBack() else s.session.onBack()
            }
        }
    }

    /** APP-3: UP walks 3..9 and wraps from 9 to 3. */
    private fun increment(balls: Int) = if (balls < Screen.MAX_BALLS) balls + 1 else Screen.MIN_BALLS

    /** DOWN walks 9..3 and wraps from 3 to 9. */
    private fun decrement(balls: Int) = if (balls > Screen.MIN_BALLS) balls - 1 else Screen.MAX_BALLS
}
