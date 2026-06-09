package com.jugglingtracker.imu

import android.Manifest
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
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.ServiceUnavailableException
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

    private var garminStatus by mutableStateOf("Waiting for Garmin connection...")
    private var isWatchAppRunning by mutableStateOf(false)

    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = Runnable {
        if (isWatchAppRunning) {
            isWatchAppRunning = false
            garminStatus = "Watch app stopped (timeout)"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JugglingTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    JugglingTrackerApp(
                        viewModel = viewModel,
                        garminStatus = garminStatus,
                        isWatchAppRunning = isWatchAppRunning,
                    )
                }
            }
        }

        ensurePermissionsThenInitialize()
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
            initializeGarminConnectIQ()
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
                initializeGarminConnectIQ()
            } else {
                garminStatus = "Bluetooth permissions are required."
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
                    garminStatus = "SDK ready. Looking for devices..."
                    findAndRegisterDevice()
                }

                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                    garminStatus = "SDK init failed: ${status.name}"
                }

                override fun onSdkShutDown() {
                    garminStatus = "SDK shutdown"
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
                garminStatus = "Found: ${device.friendlyName}. Listening..."
                registerImuAppListener()
            } else {
                garminStatus = "No paired Garmin devices found."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding device", e)
            garminStatus = "Error finding device."
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
            garminStatus = "Failed to register app listener."
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
        if (payload["type"] == "session") {
            @Suppress("UNCHECKED_CAST")
            val typed = payload as Map<String, Any>
            runOnUiThread {
                viewModel.importSessionFromWatch(typed)
            }
            // Acknowledge receipt so the watch knows the data is safely stored
            // and can close. The timestamp lets the watch match the ACK to its
            // pending send.
            val ts = (payload["timestamp"] as? Number)?.toLong()
            sendAck(ts)
        } else if (payload["type"] == "recording") {
            @Suppress("UNCHECKED_CAST")
            val typed = payload as Map<String, Any>
            runOnUiThread {
                viewModel.importRecordingFromWatch(typed)
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
