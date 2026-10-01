package com.juggling.tracker.wear

import android.content.Context
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
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
import com.juggling.tracker.wear.platform.TrackingService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The running app: the navigator and sessions from `logic/`, the
 * accelerometer and the Data Layer link to the phone. It belongs to the
 * process, not to [MainActivity], so a session keeps counting with the screen
 * off and is still there when the app is reopened. All of it runs on the main
 * thread.
 */
class WatchRuntime private constructor(private val context: Context) {
    companion object {
        private const val TAG = "WatchRuntime"
        // Record mode writes a one-line RUN_DATA summary of each confirmed
        // run to logcat under this tag (REC-4).
        private const val RECORDING_LOG_TAG = "JugglingRecording"

        private var instance: WatchRuntime? = null

        @MainThread
        fun get(context: Context): WatchRuntime =
            instance ?: WatchRuntime(context.applicationContext).also { instance = it }

        /** Closes the running app, if any; the next [get] starts at the first screen. */
        @MainThread
        fun shutdown() {
            instance?.close()
        }
    }

    private val scope = MainScope()
    private val link = DataLayerPhoneLink(context)
    private val accelerometer =
        AccelerometerSource(context.getSystemService(SensorManager::class.java), ::onSamples)

    /** Called when the app quits; [MainActivity] finishes itself here. */
    var onExit: (() -> Unit)? = null

    val navigator: AppNavigator

    /** Whether a juggling or record session is open. */
    val isTracking: Boolean
        get() = navigator.screen.value.let { it is Screen.Tracker || it is Screen.Recording }

    init {
        val scheduler = HandlerScheduler()
        val effects = AndroidWatchEffects(context, onExit = ::close)
        val clock = MonotonicClock { SystemClock.elapsedRealtime() }
        navigator = AppNavigator(
            newTracker = { balls -> TrackerSession(balls, link, scheduler, clock, effects) },
            newRecording = { balls ->
                RecordingSession(balls, link, scheduler, effects, log = { Log.i(RECORDING_LOG_TAG, it) })
            },
            effects = effects,
        )

        // The sensor and the foreground service run exactly while a session
        // is open, whether or not the app is on screen.
        scope.launch {
            navigator.screen.collect {
                if (isTracking) {
                    TrackingService.start(context)
                    if (!accelerometer.start()) {
                        Log.e(TAG, "No accelerometer available")
                    }
                } else {
                    accelerometer.stop()
                    TrackingService.stop(context)
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

    private fun close() {
        if (instance === this) instance = null
        scope.cancel()
        accelerometer.close()
        TrackingService.stop(context)
        link.close()
        onExit?.invoke()
        onExit = null
    }
}
