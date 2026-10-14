package com.juggling.tracker

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.juggling.tracker.data.WatchType
import com.juggling.tracker.logic.GarminConnectionStatus
import com.juggling.tracker.logic.JugglingViewModel
import com.juggling.tracker.logic.WearConnectionStatus
import com.juggling.tracker.sensor.PhoneAccelerometerSource
import com.juggling.tracker.shared.WatchProtocol
import com.juggling.tracker.ui.JugglingTrackerApp
import com.juggling.tracker.ui.theme.JugglingTrackerTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    // Receiving from the watches lives in the application, not here, so it
    // keeps going while this screen is recreated or closed.
    private val app: JugglingTrackerApplication get() = application as JugglingTrackerApplication
    private val garminLink: GarminLink get() = app.garminLink

    private val viewModel: JugglingViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return JugglingViewModel(
                    app.sessionRepository,
                    app.recordingRepository,
                    app.analytics,
                    app.settingsManager,
                    app.watchInbox,
                ) as T
            }
        }
    }

    private val sensorManager: SensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val phoneAccelerometer: PhoneAccelerometerSource by lazy {
        PhoneAccelerometerSource(sensorManager) { samples ->
            viewModel.processPhoneSamples(samples)
            // A frozen sensor ends the session from inside the model; stop
            // listening then rather than at full rate until the screen closes.
            if (!viewModel.isUsingPhoneSensor) stopPhoneSensorListener()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // The Garmin card follows the app-wide link; it reports its current
        // state straight away, then every change.
        garminLink.setStatusListener(viewModel.watchStatus::onGarminLinkStatus)

        setContent {
            // The Garmin SDK and its Bluetooth permissions are only needed
            // with a Garmin watch: someone with a Wear OS watch is not asked
            // for them, and choosing Garmin in Settings asks then.
            val watchType = viewModel.settings.watchType
            LaunchedEffect(watchType) {
                if (watchType == WatchType.GARMIN) ensurePermissionsThenInitialize()
            }
            JugglingTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    JugglingTrackerApp(
                        viewModel = viewModel,
                        onStartPhoneSession = ::startPhoneRecording,
                        onStopPhoneSession = ::stopPhoneRecordingAndSave,
                        onCancelPhoneSession = ::cancelPhoneRecording,
                        onStartRawRecording = ::startRawRecording,
                        onStopRawRecording = ::stopRawRecording,
                        onCancelRawRecording = ::cancelRawRecording,
                    )
                }
            }
        }

        // The Wear OS watch app's messages arrive in WatchMessageService, even
        // with this screen closed. Here only the card's status is followed.
        Wearable.getCapabilityClient(this)
            .addListener(wearCapabilityListener, WatchProtocol.WATCH_CAPABILITY)
            .addOnFailureListener { e -> Log.w(TAG, "Cannot watch the Wear OS capability", e) }
    }

    // The watch app coming into or out of reach (installed, uninstalled,
    // Bluetooth dropped) changes its capability, so the card follows along.
    private val wearCapabilityListener = CapabilityClient.OnCapabilityChangedListener { refreshWearStatus() }

    // Asks the Data Layer whether a watch is connected and whether it has the
    // watch app, for the Wear OS card. While a transfer is arriving the card
    // stays on "receiving" and shows this once it ends.
    private fun refreshWearStatus() {
        val nodes = Wearable.getNodeClient(this).connectedNodes
        val capable = Wearable.getCapabilityClient(this)
            .getCapability(WatchProtocol.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        Tasks.whenAllComplete(nodes, capable).addOnCompleteListener(this) {
            val status = WearConnectionStatus.classify(
                connectedWatches = if (nodes.isSuccessful) nodes.result.size else null,
                watchesWithApp = if (capable.isSuccessful) capable.result.nodes.size else 0,
            )
            if (!nodes.isSuccessful) Log.w(TAG, "Wear OS Data Layer unavailable", nodes.exception)
            viewModel.watchStatus.onWearLinkStatus(status)
        }
    }

    private fun checkBluetooth(): Boolean {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        return adapter?.isEnabled == true
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            @Suppress("DEPRECATION")
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun ensurePermissionsThenInitialize() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startGarminLink()
        } else {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE,
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                startGarminLink()
            } else {
                garminLink.reportProblem(
                    GarminConnectionStatus.SDK_ERROR,
                    getString(R.string.garmin_bluetooth_permission),
                )
            }
        }
    }

    private fun startGarminLink() {
        if (checkBluetooth()) {
            garminLink.start()
        } else {
            garminLink.reportProblem(
                GarminConnectionStatus.BLUETOOTH_DISABLED,
                getString(R.string.garmin_bluetooth_off),
            )
        }
    }

    private fun startPhoneRecording(ballCount: Int): Boolean {
        if (viewModel.phoneSession.state.isRecording && phoneAccelerometer.isActive) return true

        viewModel.phoneSession.start(ballCount)
        if (!registerPhoneSensorListener()) {
            viewModel.phoneSession.markSensorUnavailable()
            return false
        }
        return true
    }

    private fun startRawRecording(ballCount: Int): Boolean {
        viewModel.rawRecording.confirmBalls(ballCount)
        if (!registerPhoneSensorListener()) {
            viewModel.rawRecording.cancel()
            return false
        }
        return true
    }

    private fun stopRawRecording() {
        viewModel.rawRecording.stop()
        stopPhoneSensorListener()
    }

    private fun cancelRawRecording() {
        viewModel.rawRecording.cancel()
        stopPhoneSensorListener()
    }

    private fun stopPhoneRecordingAndSave(): Boolean {
        stopPhoneSensorListener()
        return viewModel.stopPhoneSessionAndSave()
    }

    private fun cancelPhoneRecording() {
        stopPhoneSensorListener()
        viewModel.phoneSession.cancel()
    }

    private fun registerPhoneSensorListener(): Boolean = phoneAccelerometer.start()

    private fun stopPhoneSensorListener() {
        phoneAccelerometer.stop()
    }

    override fun onResume() {
        super.onResume()
        refreshWearStatus()
        if (viewModel.isUsingPhoneSensor && !phoneAccelerometer.isActive) {
            if (!registerPhoneSensorListener()) {
                if (viewModel.phoneSession.state.isRecording) {
                    viewModel.phoneSession.markSensorUnavailable()
                } else {
                    viewModel.rawRecording.cancel()
                }
            }
        }
    }

    override fun onPause() {
        stopPhoneSensorListener()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getCapabilityClient(this).removeListener(wearCapabilityListener)
        stopPhoneSensorListener()
        // The Garmin link stays up for the next screen; only this one stops listening.
        garminLink.setStatusListener(null)
    }
}
