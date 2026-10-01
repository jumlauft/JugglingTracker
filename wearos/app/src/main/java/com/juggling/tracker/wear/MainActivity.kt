package com.juggling.tracker.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import com.juggling.tracker.wear.ui.WearApp

/**
 * Shows the [WatchRuntime], which holds the sessions. When the screen times
 * out the app stays up in ambient mode rather than keeping the display fully
 * on; the session keeps counting either way.
 */
class MainActivity : ComponentActivity() {
    private lateinit var runtime: WatchRuntime
    private val finishOnExit: () -> Unit = { finish() }
    private var isAmbient by mutableStateOf(false)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
        }

        override fun onUpdateAmbient() = Unit

        override fun onExitAmbient() {
            isAmbient = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        runtime = WatchRuntime.get(this)
        runtime.onExit = finishOnExit
        setContent {
            val sensorFailed by runtime.sensorFailed.collectAsState()
            WearApp(runtime.navigator, sensorFailed = sensorFailed, isAmbient = isAmbient)
        }
    }

    override fun onDestroy() {
        if (runtime.onExit === finishOnExit) runtime.onExit = null
        // Leaving the app outside a session starts it afresh next time, as
        // before; an open session stays, to be picked up again.
        if (isFinishing && !runtime.isTracking) WatchRuntime.shutdown()
        super.onDestroy()
    }
}
