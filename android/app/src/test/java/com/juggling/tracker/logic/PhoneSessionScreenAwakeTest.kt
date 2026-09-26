package com.juggling.tracker.logic

import com.juggling.tracker.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A phone session collects samples for as long as the user juggles, and nothing
 * touches the screen while they do. The display therefore times out, which calls
 * MainActivity.onPause, which unregisters the sensor listener -- so counting
 * stopped partway through with nothing on screen to say so, and the resulting gap
 * in samples auto-finished whatever run was in progress.
 *
 * `shouldKeepScreenOn` is what the UI holds the display awake with. These tests
 * pin it to the exact span where samples matter: on for a running session or raw
 * recording, off the moment either ends, however it ends. The one-line
 * DisposableEffect in JugglingTrackerApp that applies it is not covered here --
 * the project has no Compose UI tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSessionScreenAwakeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private companion object {
        const val GRAVITY = 9.80665
        const val PERIOD_NANOS = PhoneJugglingDetector.SAMPLE_PERIOD_MS * 1_000_000L
        const val START_NANOS = 1_000_000_000L

        /** Committed bursts alternate hands, so five bursts are three catches. */
        const val BURSTS_FOR_A_REAL_RUN = 5
    }

    @Test
    fun `the screen is not held awake before anything is recording`() = runTest {
        val viewModel = JugglingViewModel()

        assertFalse(
            "an idle app must not hold the display awake",
            viewModel.shouldKeepScreenOn,
        )
    }

    @Test
    fun `the screen is held awake for as long as a phone session runs`() = runTest {
        val viewModel = JugglingViewModel()
        viewModel.startPhoneSession(3, startedAtMillis = 0L)

        assertTrue(
            "the display must be held awake as soon as the session starts",
            viewModel.shouldKeepScreenOn,
        )

        var tNanos = feedBaseline(viewModel, START_NANOS, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        repeat(BURSTS_FOR_A_REAL_RUN) { burst ->
            tNanos = feedBurst(viewModel, tNanos)
            assertTrue(
                "the display must stay awake mid-run (after burst ${burst + 1})",
                viewModel.shouldKeepScreenOn,
            )
        }

        assertEquals(
            "the synthetic run should have produced catches",
            3,
            viewModel.phoneSessionState.currentCount,
        )
    }

    @Test
    fun `the screen is released once a session is saved`() = runTest {
        val viewModel = JugglingViewModel()
        viewModel.startPhoneSession(3, startedAtMillis = 0L)

        var tNanos = feedBaseline(viewModel, START_NANOS, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        repeat(BURSTS_FOR_A_REAL_RUN) { tNanos = feedBurst(viewModel, tNanos) }

        assertTrue(
            "the run should have been saved, so this covers the success path",
            viewModel.stopPhoneSessionAndSave(stoppedAtMillis = 60_000L),
        )
        assertFalse(
            "a saved session must give the display back",
            viewModel.shouldKeepScreenOn,
        )
    }

    @Test
    fun `the screen is released when a session is cancelled`() = runTest {
        val viewModel = JugglingViewModel()
        viewModel.startPhoneSession(3, startedAtMillis = 0L)

        viewModel.cancelPhoneSession()

        assertFalse(
            "a cancelled session must give the display back",
            viewModel.shouldKeepScreenOn,
        )
    }

    @Test
    fun `the screen is released when a session stops with nothing to save`() = runTest {
        val viewModel = JugglingViewModel()
        viewModel.startPhoneSession(3, startedAtMillis = 0L)

        assertFalse(
            "no runs means nothing was saved",
            viewModel.stopPhoneSessionAndSave(stoppedAtMillis = 1_000L),
        )
        assertFalse(
            "the display must be released even when the save found no runs",
            viewModel.shouldKeepScreenOn,
        )
    }

    @Test
    fun `the screen is released when the sensor is reported broken`() = runTest {
        val viewModel = JugglingViewModel()
        viewModel.startPhoneSession(3, startedAtMillis = 0L)

        // A wedged accelerometer ends the session from inside the sample path;
        // that route has to release the display too. See FrozenSensorTest.
        var tNanos = START_NANOS
        repeat(JugglingViewModel.FROZEN_SENSOR_SAMPLES + 5) {
            viewModel.processPhoneSample(9.68, -9.79, -9.65, tNanos)
            tNanos += PERIOD_NANOS
        }

        assertFalse("the dead session must not still be recording", viewModel.phoneSessionState.isRecording)
        assertFalse(
            "a session killed by a broken sensor must give the display back",
            viewModel.shouldKeepScreenOn,
        )
    }

    @Test
    fun `the screen is held awake while a raw recording captures samples`() = runTest {
        val viewModel = JugglingViewModel()
        viewModel.startRawRecordingFlow()

        assertFalse(
            "choosing a ball count collects no samples yet",
            viewModel.shouldKeepScreenOn,
        )

        viewModel.confirmRawRecordingBalls(3)
        assertTrue(
            "raw recording streams the same sensor and needs the same protection",
            viewModel.shouldKeepScreenOn,
        )

        viewModel.stopRawRecording()
        assertFalse(
            "the labeling step takes no samples, so the display can sleep",
            viewModel.shouldKeepScreenOn,
        )

        viewModel.cancelRawRecording()
        assertFalse(viewModel.shouldKeepScreenOn)
    }

    /** Samples arrive one per detector period, so none are thrown away by the throttle. */
    private fun feedBaseline(
        viewModel: JugglingViewModel,
        startNanos: Long,
        samples: Int,
    ): Long {
        var tNanos = startNanos
        repeat(samples) {
            viewModel.processPhoneSample(0.0, 0.0, GRAVITY, tNanos)
            tNanos += PERIOD_NANOS
        }
        return tNanos
    }

    /** One catch-motion burst: a sharp pulse, then long enough still to commit it. */
    private fun feedBurst(viewModel: JugglingViewModel, startNanos: Long): Long {
        var tNanos = startNanos
        repeat(3) {
            viewModel.processPhoneSample(0.0, 0.0, GRAVITY - 50.0, tNanos)
            tNanos += PERIOD_NANOS
        }
        repeat(12) {
            viewModel.processPhoneSample(0.0, 0.0, GRAVITY, tNanos)
            tNanos += PERIOD_NANOS
        }
        return tNanos
    }
}
