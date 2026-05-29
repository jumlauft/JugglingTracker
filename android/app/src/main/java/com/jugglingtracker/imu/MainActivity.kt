package com.jugglingtracker.imu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
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
    private lateinit var barX: ProgressBar
    private lateinit var barY: ProgressBar
    private lateinit var barZ: ProgressBar
    private lateinit var valX: TextView
    private lateinit var valY: TextView
    private lateinit var valZ: TextView

    private val detector = JugglingDetector { count ->
        runOnUiThread { throwCountView.text = count.toString() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.statusText)
        throwCountView = findViewById(R.id.throwCount)
        barX = findViewById(R.id.barX)
        barY = findViewById(R.id.barY)
        barZ = findViewById(R.id.barZ)
        valX = findViewById(R.id.valX)
        valY = findViewById(R.id.valY)
        valZ = findViewById(R.id.valZ)

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
        // The watch sends a structured payload: a map with numeric arrays for
        // each axis, e.g. {"rate": 25, "x": [..], "y": [..], "z": [..]}.
        val payload = message.firstOrNull() as? Map<*, *> ?: return

        val xs = payload["x"] as? List<*> ?: return
        val ys = payload["y"] as? List<*> ?: return
        val zs = payload["z"] as? List<*> ?: return
        if (xs.isEmpty()) return

        try {
            // Feed every sample in the batch through the juggling detector so no
            // throw is missed between batches.
            val count = minOf(xs.size, ys.size, zs.size)
            val now = System.currentTimeMillis()
            for (i in 0 until count) {
                val gx = (xs[i] as Number).toFloat()
                val gy = (ys[i] as Number).toFloat()
                val gz = (zs[i] as Number).toFloat()
                detector.processSample(gx, gy, gz, now)
            }

            // Auto-finish the run after a pause, then reset the counter.
            if (detector.shouldAutoFinish(now)) {
                detector.reset()
            }

            // Display the most recent sample from the batch.
            val x = (xs.last() as Number).toFloat()
            val y = (ys.last() as Number).toFloat()
            val z = (zs.last() as Number).toFloat()

            runOnUiThread {
                statusView.text = "Received ${xs.size} samples — x=$x y=$y z=$z"

                barX.progress = (x + 1000f).toInt().coerceIn(0, 2000)
                barY.progress = (y + 1000f).toInt().coerceIn(0, 2000)
                barZ.progress = (z + 1000f).toInt().coerceIn(0, 2000)

                valX.text = x.toString()
                valY.text = y.toString()
                valZ.text = z.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing IMU data", e)
        }
    }
}
