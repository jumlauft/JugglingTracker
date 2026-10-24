package com.juggling.tracker.wear

import android.content.Context
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import com.juggling.tracker.wear.logic.AccelSample
import com.juggling.tracker.wear.logic.AppNavigator
import com.juggling.tracker.wear.logic.MonotonicClock
import com.juggling.tracker.wear.logic.NoActivityRecorder
import com.juggling.tracker.wear.logic.RecordingSession
import com.juggling.tracker.wear.logic.Screen
import com.juggling.tracker.wear.logic.TrackerSession
import com.juggling.tracker.wear.platform.AccelerometerSource
import com.juggling.tracker.wear.platform.AndroidWatchEffects
import com.juggling.tracker.wear.platform.DataLayerPhoneLink
import com.juggling.tracker.wear.platform.HandlerScheduler
import com.juggling.tracker.wear.platform.PrefsSessionStore
import com.juggling.tracker.wear.platform.TrackingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The running app: the navigator and sessions from `logic/`, the
 * accelerometer and the Data Layer link to the phone. It belongs to the
 * process, not to [MainActivity], so a session keeps counting with the screen
 * off and is still there when the app is reopened. All of it runs on the main
 * thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    private val _sensorFailed = MutableStateFlow(false)

    /**
     * True when the accelerometer is missing or would not start, so the
     * tracking screens show an error instead of a count stuck at 0 (SENS-4).
     */
    val sensorFailed: StateFlow<Boolean> = _sensorFailed.asStateFlow()

    /** Called when the app quits; [MainActivity] finishes itself here. */
    var onExit: (() -> Unit)? = null

    val navigator: AppNavigator

    /**
     * Whether a juggling or record session is open. A kept session waiting
     * on its Previous session menu (LATER-1) is not yet: nothing runs for it
     * until it is continued.
     */
    val isTracking: Boolean
        get() = navigator.screen.value.let {
            (it is Screen.Tracker && !it.session.state.value.isWaitingToResume) || it is Screen.Recording
        }

    init {
        val scheduler = HandlerScheduler()
        val effects = AndroidWatchEffects(context, onExit = ::close)
        val clock = MonotonicClock { SystemClock.elapsedRealtime() }
        val store = PrefsSessionStore(context)
        // ACT-1: no activity on Wear OS yet; Health Connect is on the phone.
        val recorder = NoActivityRecorder
        navigator = AppNavigator(
            newTracker = { balls ->
                TrackerSession(balls, link, scheduler, clock, effects, recorder = recorder, store = store)
            },
            newRecording = { balls ->
                RecordingSession(balls, link, scheduler, effects, log = { Log.i(RECORDING_LOG_TAG, it) })
            },
            effects = effects,
            resumeTracker = {
                store.load()?.let { saved ->
                    TrackerSession(saved.balls, link, scheduler, clock, effects, recorder = recorder, store = store, saved = saved)
                }
            },
        )

        // The sensor and the foreground service run exactly while a session
        // is open, whether or not the app is on screen.
        scope.launch {
            navigator.screen
                .flatMapLatest { screen ->
                    when (screen) {
                        is Screen.Tracker -> screen.session.state.map { !it.isWaitingToResume }
                        is Screen.Recording -> flowOf(true)
                        else -> flowOf(false)
                    }
                }
                .distinctUntilChanged()
                .collect { tracking ->
                    if (tracking) {
                        TrackingService.start(context)
                        val started = accelerometer.start()
                        if (!started) {
                            Log.e(TAG, "No accelerometer available")
                        }
                        _sensorFailed.value = !started
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
