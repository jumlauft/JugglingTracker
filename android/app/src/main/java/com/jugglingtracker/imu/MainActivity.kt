package com.jugglingtracker.imu

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
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
import com.jugglingtracker.imu.logic.GarminConnectionStatus
import com.jugglingtracker.imu.logic.JugglingViewModel
import com.jugglingtracker.imu.data.SessionRepository
import com.jugglingtracker.imu.data.RecordingRepository
import com.jugglingtracker.imu.ui.JugglingTrackerApp
import com.jugglingtracker.imu.ui.theme.JugglingTrackerTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val WATCH_APP_ID = "a77c0c66-f421-49f5-889f-0bf4a446dfea"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val HEARTBEAT_TIMEOUT_MS = 15000L
    }

    private val repository: SessionRepository by lazy { SessionRepository(this) }
    private val recordingRepository: RecordingRepository by lazy { RecordingRepository(this) }
    
    private val viewModel: JugglingViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return JugglingViewModel(repository, recordingRepository) as T
            }
        }
    }

    private lateinit var connectIQ: ConnectIQ
    private var iqDevice: IQDevice? = null
    private var iqApp: IQApp? = null

    private var isWatchAppRunning by mutableStateOf(false)

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = Runnable {
        if (isWatchAppRunning) {
            isWatchAppRunning = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            JugglingTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    JugglingTrackerApp(
                        viewModel = viewModel,
                        isWatchAppRunning = isWatchAppRunning,
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
        connectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS)

        connectIQ.initialize(
            this,
            true,
            object : ConnectIQ.ConnectIQListener {
                override fun onSdkReady() {
                    findAndRegisterDevice()
                }

                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                    viewModel.garminStatus = GarminConnectionStatus.SDK_ERROR
                    viewModel.statusMessage = "Garmin SDK error: ${status.name}. Please restart the app."
                }

                override fun onSdkShutDown() {
                    viewModel.garminStatus = GarminConnectionStatus.NOT_INITIALIZED
                }
            },
        )
    }

    private fun findAndRegisterDevice() {
        try {
            val devices = connectIQ.knownDevices
            if (!devices.isNullOrEmpty()) {
                val device = devices[0]
                iqDevice = device
                viewModel.garminStatus = GarminConnectionStatus.READY
                viewModel.statusMessage = "Connected to ${device.friendlyName}"
                registerImuAppListener()
            } else {
                viewModel.garminStatus = GarminConnectionStatus.NO_PAIRED_DEVICES
                viewModel.statusMessage = "No paired Garmin devices found. Link your watch in the Garmin ConnectIQ app."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding device", e)
            viewModel.garminStatus = GarminConnectionStatus.SDK_ERROR
            viewModel.statusMessage = "Error finding device. Ensure Bluetooth is active."
        }
    }

    private fun registerImuAppListener() {
        val device = iqDevice ?: return
        val app = IQApp(WATCH_APP_ID)
        iqApp = app

        try {
            connectIQ.registerForAppEvents(device, app) { _, _, message, status ->
                if ((status == ConnectIQ.IQMessageStatus.SUCCESS) && message.isNotEmpty()) {
                    onImuMessageReceived(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering app listener", e)
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
        if (payload["type"] == "session" || payload["type"] == "recording") {
            @Suppress("UNCHECKED_CAST")
            val typed = payload as Map<String, Any>
            
            // Briefly show receiving state
            viewModel.garminStatus = GarminConnectionStatus.RECEIVING
            
            runOnUiThread {
                if (payload["type"] == "session") {
                    viewModel.importSessionFromWatch(typed)
                } else {
                    viewModel.importRecordingFromWatch(typed)
                }
                
                // Return to ready after a short delay
                handler.postDelayed({
                    if (viewModel.garminStatus == GarminConnectionStatus.RECEIVING) {
                        viewModel.garminStatus = GarminConnectionStatus.READY
                    }
                }, 1500)
            }

            val ts = (payload["timestamp"] as? Number)?.toLong()
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
