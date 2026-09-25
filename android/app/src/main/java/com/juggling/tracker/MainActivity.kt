package com.juggling.tracker

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import com.juggling.tracker.logic.GarminConnectionStatus
import com.juggling.tracker.logic.JugglingViewModel
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.data.SettingsManager
import com.juggling.tracker.ui.JugglingTrackerApp
import com.juggling.tracker.ui.theme.JugglingTrackerTheme
import com.google.firebase.analytics.FirebaseAnalytics
import com.juggling.tracker.util.CrashlyticsUtils

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        // The store build and the older beta build of the watch app carry
        // different manifest ids, and a watch may have either one on it.
        private const val WATCH_APP_ID = "fa298da6-29c7-46d2-9d76-e07f62d16539"
        private const val WATCH_APP_ID_BETA = "88fa4344-0c76-40a9-83e7-e7fc21328822"
        private val WATCH_APP_IDS = listOf(WATCH_APP_ID, WATCH_APP_ID_BETA)
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val HEARTBEAT_TIMEOUT_MS = 15000L
        private const val PHONE_SAMPLE_PERIOD_US = 5_000
    }

    private val repository: SessionRepository by lazy { SessionRepository(this) }
    private val recordingRepository: RecordingRepository by lazy { RecordingRepository(this) }
    private val settingsManager: SettingsManager by lazy { SettingsManager(this) }
    private val analytics: FirebaseAnalytics by lazy { FirebaseAnalytics.getInstance(this) }

    private val viewModel: JugglingViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return JugglingViewModel(repository, recordingRepository, analytics, settingsManager) as T
            }
        }
    }

    private lateinit var connectIQ: ConnectIQ
    private var iqDevice: IQDevice? = null
    private var iqApp: IQApp? = null

    private var isWatchAppRunning by mutableStateOf(false)

    private val handler = Handler(Looper.getMainLooper())
    private val sensorManager: SensorManager by lazy { getSystemService(SensorManager::class.java) }
    private var phoneSensorRegistered = false

    private val phoneSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
            viewModel.processPhoneSample(
                ax = event.values[0].toDouble(),
                ay = event.values[1].toDouble(),
                az = event.values[2].toDouble(),
                timestampNanos = event.timestamp,
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val heartbeatRunnable = Runnable {
        if (isWatchAppRunning) {
            isWatchAppRunning = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Apply privacy settings to Firebase on startup
        val isAnalyticsEnabled = settingsManager.isAnalyticsEnabled
        analytics.setAnalyticsCollectionEnabled(isAnalyticsEnabled)
        com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            .setCrashlyticsCollectionEnabled(isAnalyticsEnabled)

        setContent {
            JugglingTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    JugglingTrackerApp(
                        viewModel = viewModel,
                        isWatchAppRunning = isWatchAppRunning,
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

        ensurePermissionsThenInitialize()
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
            if (!checkBluetooth()) {
                viewModel.garminStatus = GarminConnectionStatus.BLUETOOTH_DISABLED
                viewModel.statusMessage = "Turn on bluetooth to receive data from watch"
            } else {
                initializeGarminConnectIQ()
            }
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
                if (checkBluetooth()) {
                    initializeGarminConnectIQ()
                } else {
                    viewModel.garminStatus = GarminConnectionStatus.BLUETOOTH_DISABLED
                    viewModel.statusMessage = "Turn on bluetooth to receive data from watch"
                }
            } else {
                viewModel.garminStatus = GarminConnectionStatus.SDK_ERROR
                viewModel.statusMessage = "Bluetooth permissions are required for Garmin sync."
            }
        }
    }

    private fun initializeGarminConnectIQ() {
        try {
            connectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS)

            // Unlike every other SDK call in this file, initialize() is not
            // wrapped by the SDK's own error handling for its final step: it
            // registers a broadcast receiver and binds the Garmin Connect
            // service directly, outside its internal try/catch. A failure
            // there (e.g. a SecurityException from a manufacturer's stricter
            // receiver-registration policy) would otherwise propagate all the
            // way up through onCreate and crash the app on every launch.
            connectIQ.initialize(
                this,
                true,
                object : ConnectIQ.ConnectIQListener {
                    override fun onSdkReady() {
                        findAndRegisterDevice()
                    }

                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                        if (status == ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED) {
                            viewModel.garminStatus = GarminConnectionStatus.CONNECT_IQ_MISSING
                            viewModel.statusMessage = "Garmin Connect app not found. Please install it from the Play Store."
                        } else {
                            viewModel.garminStatus = GarminConnectionStatus.SDK_ERROR
                            viewModel.statusMessage = "Garmin SDK error: ${status.name}. Please restart the app."
                        }
                    }

                    override fun onSdkShutDown() {
                        viewModel.garminStatus = GarminConnectionStatus.NOT_INITIALIZED
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Garmin ConnectIQ SDK", e)
            CrashlyticsUtils.recordException(e)
            viewModel.garminStatus = GarminConnectionStatus.SDK_ERROR
            viewModel.statusMessage = "Garmin SDK failed to start. Please restart the app."
        }
    }

    private fun findAndRegisterDevice() {
        try {
            val devices = connectIQ.knownDevices
            if (!devices.isNullOrEmpty()) {
                // knownDevices can list several paired Garmins. Taking the first
                // blindly can register the listener on a different watch, which
                // then reports "connected" while receiving nothing.
                val device = devices.firstOrNull {
                    connectIQ.getDeviceStatus(it) == IQDevice.IQDeviceStatus.CONNECTED
                } ?: devices[0]
                iqDevice = device
                Log.d(TAG, "knownDevices=" + devices.joinToString { it.friendlyName ?: "?" } +
                    " -> using " + (device.friendlyName ?: "?"))

                // Register for device status changes (Bluetooth connection/disconnection)
                connectIQ.registerForDeviceEvents(device) { _, status ->
                    runOnUiThread {
                        when (status) {
                            IQDevice.IQDeviceStatus.CONNECTED -> {
                                viewModel.garminStatus = GarminConnectionStatus.READY
                                viewModel.statusMessage = "Connected to ${device.friendlyName}"
                                // The app-event registration does not survive the
                                // watch dropping off (USB mode, BLE drop). Without
                                // re-arming it the phone reports "connected" while
                                // nothing is listening, and the watch's transmit
                                // fails instantly for want of a receiver.
                                registerImuAppListener()
                            }
                            IQDevice.IQDeviceStatus.NOT_CONNECTED -> {
                                viewModel.garminStatus = GarminConnectionStatus.DISCONNECTED
                                viewModel.statusMessage = "Watch disconnected from phone"
                            }
                            else -> {
                                viewModel.garminStatus = GarminConnectionStatus.SDK_ERROR
                                viewModel.statusMessage = "Unknown device status: ${status.name}"
                            }
                        }
                    }
                }

                // Initial status check
                val initialStatus = connectIQ.getDeviceStatus(device)
                if (initialStatus == IQDevice.IQDeviceStatus.CONNECTED) {
                    val suffix = if (devices.size > 1) " (${devices.size} paired)" else ""
                    viewModel.garminStatus = GarminConnectionStatus.READY
                    viewModel.statusMessage = "Connected to ${device.friendlyName}$suffix"
                } else {
                    viewModel.garminStatus = GarminConnectionStatus.DISCONNECTED
                    viewModel.statusMessage = "Watch disconnected from phone"
                }

                registerImuAppListener()
            } else {
                viewModel.garminStatus = GarminConnectionStatus.NO_PAIRED_DEVICES
                viewModel.statusMessage = "No paired Garmin devices found. Link your watch in the Garmin ConnectIQ app."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding device", e)
            CrashlyticsUtils.recordException(e)
            viewModel.garminStatus = GarminConnectionStatus.SDK_ERROR
            viewModel.statusMessage = "Error finding device. Ensure Bluetooth is active."
        }
    }

    // Registering for an app the watch does not actually have makes Garmin
    // Connect answer with a payload-less broadcast. The SDK's receiver hands
    // that null payload straight to its deserializer and the resulting NPE
    // escapes onReceive, killing the app. So ask first, and only register for
    // an id the watch is known to carry.
    private fun registerImuAppListener() {
        val device = iqDevice ?: return
        probeWatchApp(device, 0)
    }

    private fun probeWatchApp(device: IQDevice, index: Int) {
        if (index >= WATCH_APP_IDS.size) {
            Log.d(TAG, "appInfo: no known watch app id is installed")
            runOnUiThread {
                viewModel.garminStatus = GarminConnectionStatus.WATCH_APP_MISSING
                viewModel.statusMessage =
                    "Install the Juggling Tracker watch app from the Connect IQ Store."
            }
            return
        }

        val candidateId = WATCH_APP_IDS[index]
        try {
            connectIQ.getApplicationInfo(
                candidateId,
                device,
                object : ConnectIQ.IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(iqApp: IQApp?) {
                        Log.d(TAG, "appInfo: watch has $candidateId (${iqApp?.displayName})")
                        registerForWatchApp(device, candidateId)
                    }

                    override fun onApplicationNotInstalled(applicationId: String?) {
                        Log.d(TAG, "appInfo: watch does not have $applicationId")
                        probeWatchApp(device, index + 1)
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error querying watch app info", e)
            CrashlyticsUtils.recordException(e)
        }
    }

    private fun registerForWatchApp(device: IQDevice, applicationId: String) {
        // Reuse one IQApp instance for the lifetime of the activity. The SDK
        // matches an existing registration by instance, so unregistering with a
        // freshly built IQApp leaves the old listener in place and every
        // message is then delivered once per surviving registration.
        val app = iqApp ?: IQApp(applicationId).also { iqApp = it }

        try {
            try {
                connectIQ.unregisterForApplicationEvents(device, app)
            } catch (e: Exception) {
                Log.d(TAG, "No previous app listener to unregister")
            }
            connectIQ.registerForAppEvents(device, app) { _, _, message, status ->
                Log.d(TAG, "app event: status=$status size=${message?.size ?: -1}")
                if ((status == ConnectIQ.IQMessageStatus.SUCCESS) && !message.isNullOrEmpty()) {
                    onImuMessageReceived(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering app listener", e)
            CrashlyticsUtils.recordException(e)
        }
    }

    private fun onImuMessageReceived(message: List<Any>) {
        val payload = (message.firstOrNull() as? Map<*, *>) ?: return

        // Update heartbeat
        isWatchAppRunning = true
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_TIMEOUT_MS)

        // The watch sends one payload per finished session containing the ball
        // count, a timestamp, and the watch-hand catch count of every run.
        val type = payload["type"] as? String ?: return
        if (type != "session" && type != "rec_start" &&
            type != "rec_chunk" && type != "rec_end"
        ) {
            return
        }

        @Suppress("UNCHECKED_CAST")
        val typed = payload as Map<String, Any>

        // Chunks are frequent and must not each repaint the UI or be acked:
        // the watch chains them on delivery and only waits for an ack at the end.
        if (type == "rec_chunk") {
            runOnUiThread { viewModel.appendRecordingChunk(typed) }
            return
        }

        viewModel.garminStatus = GarminConnectionStatus.RECEIVING

        runOnUiThread {
            when (type) {
                "session" -> viewModel.importSessionFromWatch(typed)
                "rec_start" -> viewModel.startRecordingTransfer(typed)
                "rec_end" -> {
                    val saved = viewModel.finishRecordingTransfer(typed)
                    Log.d(TAG, "rec_end: run saved=$saved")
                }
            }

            // Return to ready after a short delay
            handler.postDelayed({
                if (viewModel.garminStatus == GarminConnectionStatus.RECEIVING) {
                    viewModel.garminStatus = GarminConnectionStatus.READY
                }
            }, 1500)
        }

        // rec_start is not acked; the watch advances on delivery, not on ack.
        if (type == "session" || type == "rec_end") {
            val ts = (payload["timestamp"] as? Number)?.toLong()
                ?: (payload["id"] as? Number)?.toLong()
            sendAck(ts)
        }
    }

    // Send an application-level acknowledgement back to the watch confirming the
    // session was received and stored.
    private fun sendAck(timestamp: Long?) {
        val device = iqDevice ?: return
        val app = iqApp ?: return
        val ack = mutableMapOf<String, Any>("type" to "ack")
        if (timestamp != null) {
            ack["timestamp"] = timestamp
        }
        try {
            connectIQ.sendMessage(device, app, ack) { _, _, status ->
                Log.d(TAG, "ACK send status: $status")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ACK", e)
            CrashlyticsUtils.recordException(e)
        }
    }

    private fun startPhoneRecording(ballCount: Int): Boolean {
        if (viewModel.phoneSessionState.isRecording && phoneSensorRegistered) return true

        viewModel.startPhoneSession(ballCount)
        if (!registerPhoneSensorListener()) {
            viewModel.markPhoneSensorUnavailable("Phone accelerometer unavailable")
            return false
        }
        return true
    }

    private fun startRawRecording(ballCount: Int): Boolean {
        viewModel.confirmRawRecordingBalls(ballCount)
        if (!registerPhoneSensorListener()) {
            viewModel.cancelRawRecording()
            return false
        }
        return true
    }

    private fun stopRawRecording() {
        viewModel.stopRawRecording()
        stopPhoneSensorListener()
    }

    private fun cancelRawRecording() {
        viewModel.cancelRawRecording()
        stopPhoneSensorListener()
    }

    private fun stopPhoneRecordingAndSave(): Boolean {
        stopPhoneSensorListener()
        return viewModel.stopPhoneSessionAndSave()
    }

    private fun cancelPhoneRecording() {
        stopPhoneSensorListener()
        viewModel.cancelPhoneSession()
    }

    private fun registerPhoneSensorListener(): Boolean {
        if (phoneSensorRegistered) return true
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val registered = sensorManager.registerListener(
            phoneSensorListener,
            accelerometer,
            PHONE_SAMPLE_PERIOD_US,
            0,
            handler,
        )
        phoneSensorRegistered = registered
        return registered
    }

    private fun stopPhoneSensorListener() {
        if (!phoneSensorRegistered) return
        sensorManager.unregisterListener(phoneSensorListener)
        phoneSensorRegistered = false
    }

    override fun onResume() {
        super.onResume()
        val isRecordingActive = viewModel.phoneSessionState.isRecording || 
                viewModel.rawRecordingState.step == com.juggling.tracker.logic.RawRecordingStep.RECORDING
        
        if (isRecordingActive && !phoneSensorRegistered) {
            if (!registerPhoneSensorListener()) {
                if (viewModel.phoneSessionState.isRecording) {
                    viewModel.markPhoneSensorUnavailable("Phone accelerometer unavailable")
                } else {
                    viewModel.cancelRawRecording()
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
        stopPhoneSensorListener()
        handler.removeCallbacks(heartbeatRunnable)
        if (::connectIQ.isInitialized && iqDevice != null && iqApp != null) {
            try {
                connectIQ.unregisterForApplicationEvents(iqDevice, iqApp)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering", e)
            }
        }
    }
}
