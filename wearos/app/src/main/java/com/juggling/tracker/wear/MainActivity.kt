package com.juggling.tracker.wear

import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.juggling.tracker.wear.logic.AccelSample
import com.juggling.tracker.wear.logic.AppNavigator
import com.juggling.tracker.wear.logic.MonotonicClock
import com.juggling.tracker.wear.logic.RecordingSession
import com.juggling.tracker.wear.logic.Screen
import com.juggling.tracker.wear.logic.TrackerSession
import com.juggling.tracker.wear.platform.AccelerometerSource
import com.juggling.tracker.wear.platform.AndroidWatchEffects
import com.juggling.tracker.wear.platform.DataLayerPhoneLink
import com.juggling.tracker.wear.platform.HandlerScheduler
import com.juggling.tracker.wear.ui.WearApp
import kotlinx.coroutines.launch

/**
 * Wires the watch app together: the navigator and sessions from `logic/`,
 * the accelerometer, the Data Layer link to the phone, and the Compose UI.
 */
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        // Record mode writes each run to logcat under this tag, in the
        // RUN_DATA format the tooling in simulation/ parses (REC-4).
        private const val RECORDING_LOG_TAG = "JugglingRecording"
    }

    private lateinit var link: DataLayerPhoneLink
    private lateinit var navigator: AppNavigator
    private lateinit var accelerometer: AccelerometerSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        link = DataLayerPhoneLink(this)
        val scheduler = HandlerScheduler()
        val effects = AndroidWatchEffects(this)
        val clock = MonotonicClock { SystemClock.elapsedRealtime() }

        navigator = AppNavigator(
            newTracker = { balls -> TrackerSession(balls, link, scheduler, clock, effects) },
            newRecording = { balls ->
                RecordingSession(balls, link, scheduler, effects, log = { Log.i(RECORDING_LOG_TAG, it) })
            },
            effects = effects,
        )

        accelerometer = AccelerometerSource(getSystemService(SensorManager::class.java), ::onSamples)

        setContent { WearApp(navigator) }

        // The sensor runs only while a tracking screen is showing and the app
        // is visible. start() is idempotent, so re-showing never double-registers.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    navigator.screen.collect { screen ->
                        if (screen is Screen.Tracker || screen is Screen.Recording) {
                            if (!accelerometer.start()) {
                                Log.e(TAG, "No accelerometer available")
                            }
                        } else {
                            accelerometer.stop()
                        }
                    }
                } finally {
                    accelerometer.stop()
                }
            }
        }
    }

    private fun onSamples(batch: List<AccelSample>) {
        when (val screen = navigator.screen.value) {
            is Screen.Tracker -> screen.session.onSamples(batch)
            is Screen.Recording -> screen.session.onSamples(batch)
            else -> Unit
        }
    }

    override fun onDestroy() {
        link.close()
        super.onDestroy()
    }
}
