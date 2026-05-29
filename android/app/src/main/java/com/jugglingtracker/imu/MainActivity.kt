package com.jugglingtracker.imu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException

// NOTE: The Garmin Connect IQ Android SDK must be added to your Gradle build.
// The sample below uses the ConnectIQ types conceptually. Replace with the
// specific SDK package names and dependency coordinates provided by Garmin.

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val WATCH_APP_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var connectIQ: ConnectIQ
    private var iqDevice: IQDevice? = null
    private var iqApp: IQApp? = null

    private lateinit var statusView: TextView
    private lateinit var throwCountView: TextView
    private lateinit var sessionAverageView: TextView
    private lateinit var sessionMaxView: TextView
    private lateinit var ballCountView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.statusText)
        throwCountView = findViewById(R.id.throwCount)
        sessionAverageView = findViewById(R.id.sessionAverage)
        sessionMaxView = findViewById(R.id.sessionMax)
        ballCountView = findViewById(R.id.ballCount)

        statusView.text = "Waiting for Garmin Forerunner 245 connection..."

        ensurePermissionsThenInitialize()
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ uses the new runtime Bluetooth permissions.
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
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
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                initializeGarminConnectIQ()
            } else {
                statusView.text = "Bluetooth permissions are required to connect to the watch."
            }
        }
    }

    private fun initializeGarminConnectIQ() {
        connectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS)

        connectIQ.initialize(this, true, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                statusView.text = "SDK initialized. Looking for devices..."
                findAndRegisterDevice()
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                statusView.text = "SDK initialization failed: $status"
            }

            override fun onSdkShutDown() {
                // Handle SDK shutdown
            }
        })
    }

    private fun findAndRegisterDevice() {
        try {
            val devices = connectIQ.knownDevices
            if (devices != null && devices.isNotEmpty()) {
                // For simplicity, we use the first known device
                iqDevice = devices[0]
                statusView.text = "Found device: ${iqDevice?.friendlyName}. Registering app..."
                registerImuAppListener()
            } else {
                statusView.text = "No paired Garmin devices found."
            }
        } catch (e: InvalidStateException) {
            statusView.text = "SDK in invalid state."
        } catch (e: ServiceUnavailableException) {
            statusView.text = "ConnectIQ service unavailable."
        }
    }

    private fun registerImuAppListener() {
        val device = iqDevice ?: return
        iqApp = IQApp(WATCH_APP_ID)

        try {
            connectIQ.registerForAppEvents(device, iqApp, object : ConnectIQ.IQApplicationEventListener {
                override fun onMessageReceived(device: IQDevice, app: IQApp, message: List<Any>, status: ConnectIQ.IQMessageStatus) {
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS && message.isNotEmpty()) {
                        onImuMessageReceived(message)
                    }
                }
            })
            statusView.text = "Listening for data from ${device.friendlyName}..."
        } catch (e: Exception) {
            Log.e(TAG, "Error registering app listener", e)
            statusView.text = "Failed to register app listener."
        }
    }

    private fun onImuMessageReceived(message: List<Any>) {
        // After each run the watch sends a summary payload with the number of
        // balls being juggled, the number of throws in the just-finished run,
        // plus the running session statistics,
        // e.g. {"balls": 5, "throws": 42, "average": 31.5, "max": 60}.
        val payload = message.firstOrNull() as? Map<*, *> ?: return

        val throws = (payload["throws"] as? Number)?.toInt() ?: return
        val average = (payload["average"] as? Number)?.toFloat() ?: 0f
        val max = (payload["max"] as? Number)?.toInt() ?: 0
        val balls = (payload["balls"] as? Number)?.toInt() ?: 0

        try {
            runOnUiThread {
                statusView.text = "Run finished: $throws throws"
                throwCountView.text = throws.toString()
                sessionAverageView.text = String.format("%.1f", average)
                sessionMaxView.text = max.toString()
                ballCountView.text = balls.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing run summary", e)
        }
    }
}
