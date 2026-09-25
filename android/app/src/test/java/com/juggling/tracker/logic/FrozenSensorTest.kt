package com.juggling.tracker.logic

import com.juggling.tracker.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A wedged accelerometer keeps delivering events at full rate carrying the
 * identical vector every time. The detector then sees zero linear acceleration
 * and counts nothing, so the session sat on "Ready" indefinitely -- exactly
 * what an idle phone looks like, with no way for the user to tell the
 * difference. This happened for real on a Pixel 7a whose sensor had wedged;
 * auto-rotate was dead at the same time and a reboot cleared it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrozenSensorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private companion object {
        const val PERIOD_MS = PhoneJugglingDetector.SAMPLE_PERIOD_MS
        /** The exact vector the wedged Pixel 7a repeated; note |v| = 16.81, not 9.81. */
        const val STUCK_X = 9.68
        const val STUCK_Y = -9.79
        const val STUCK_Z = -9.65
    }

    private fun feed(
        viewModel: JugglingViewModel,
        count: Int,
        startNanos: Long = 1_000_000_000L,
        sample: (Int) -> Triple<Double, Double, Double>,
    ): Long {
        var tNanos = startNanos
        for (i in 0 until count) {
            val (x, y, z) = sample(i)
            viewModel.processPhoneSample(x, y, z, tNanos)
            tNanos += PERIOD_MS * 1_000_000L
        }
        return tNanos
    }

    @Test
    fun `a frozen accelerometer is reported instead of sitting on Ready`() = runTest {
        val viewModel = JugglingViewModel()
        viewModel.startPhoneSession(3, startedAtMillis = 0L)

        feed(viewModel, JugglingViewModel.FROZEN_SENSOR_SAMPLES + 5) {
            Triple(STUCK_X, STUCK_Y, STUCK_Z)
        }

        val state = viewModel.phoneSessionState
        assertNotNull(
            "a stuck sensor must surface an error, not leave the session idle",
            state.sensorError,
        )
        assertTrue(
            "the error should tell the user what to do, was \"${state.sensorError}\"",
            state.sensorError!!.contains("Accelerometer", ignoreCase = true),
        )
        assertFalse("the dead session must not still claim to be recording", state.isRecording)
    }

    @Test
    fun `a phone lying still is not mistaken for a broken sensor`() = runTest {
        val viewModel = JugglingViewModel()
        viewModel.startPhoneSession(3, startedAtMillis = 0L)

        // A resting phone reads ~1g with the low bits jittering. Far longer
        // than the frozen threshold, so a naive "no movement" check would
        // wrongly fire here.
        var seed = 12345L
        feed(viewModel, JugglingViewModel.FROZEN_SENSOR_SAMPLES * 3) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val noise = ((seed shr 33).toDouble() / Long.MAX_VALUE) * 0.02
            Triple(0.01 + noise, 0.02 - noise, 9.80 + noise)
        }

        val state = viewModel.phoneSessionState
        assertNull(
            "a still but healthy sensor must not be reported as broken: \"${state.sensorError}\"",
            state.sensorError,
        )
        assertTrue("the session should still be running", state.isRecording)
        assertEquals("a still phone has no catches", 0, state.currentCount)
    }

    @Test
    fun `a brief repeated reading does not trip the check`() = runTest {
        val viewModel = JugglingViewModel()
        viewModel.startPhoneSession(3, startedAtMillis = 0L)

        // Identical samples, but stopping just short of the threshold.
        feed(viewModel, JugglingViewModel.FROZEN_SENSOR_SAMPLES - 1) {
            Triple(STUCK_X, STUCK_Y, STUCK_Z)
        }

        assertNull(viewModel.phoneSessionState.sensorError)
        assertTrue(viewModel.phoneSessionState.isRecording)
    }
}
