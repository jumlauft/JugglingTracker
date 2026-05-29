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

        statusView.setText(R.string.status_waiting_garmin)

        ensurePermissionsThenInitialize()
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ uses the new runtime Bluetooth permissions.
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
                statusView.setText(R.string.error_bluetooth_required)
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
                statusView.setText(R.string.status_sdk_initialized)
                findAndRegisterDevice()
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                statusView.text = getString(R.string.status_sdk_init_failed, status.name)
            }

            override fun onSdkShutDown() {
                // Handle SDK shutdown
            }
        },
        )
    }

    private fun findAndRegisterDevice() {
        try {
            val devices = connectIQ.knownDevices
            if (!devices.isNullOrEmpty()) {
                // For simplicity, we use the first known device
                val device = devices[0]
                iqDevice = device
                statusView.text = getString(R.string.status_found_device, device.friendlyName)
                registerImuAppListener()
            } else {
                statusView.setText(R.string.status_no_devices)
            }
        } catch (e: InvalidStateException) {
            Log.e(TAG, "SDK in invalid state", e)
            statusView.setText(R.string.status_invalid_state)
        } catch (e: ServiceUnavailableException) {
            Log.e(TAG, "ConnectIQ service unavailable", e)
            statusView.setText(R.string.status_service_unavailable)
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
            statusView.text = getString(R.string.status_listening, device.friendlyName)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering app listener", e)
            statusView.setText(R.string.status_register_failed)
        }
    }

    private fun onImuMessageReceived(message: List<Any>) {
        // After each run the watch sends a summary payload with the number of
        // balls being juggled, the number of throws in the just-finished run,
        // plus the running session statistics,
        // e.g. {"balls": 5, "throws": 42, "average": 31.5, "max": 60}.
        val payload = (message.firstOrNull() as? Map<*, *>) ?: return

        val throws = (payload["throws"] as? Number)?.toInt() ?: return
        val average = (payload["average"] as? Number)?.toFloat() ?: 0f
        val max = (payload["max"] as? Number)?.toInt() ?: 0
        val balls = (payload["balls"] as? Number)?.toInt() ?: 0

        runOnUiThread {
            statusView.text = getString(R.string.status_run_finished, throws)
            throwCountView.text = throws.toString()
            sessionAverageView.text = String.format(java.util.Locale.US, "%.1f", average)
            sessionMaxView.text = max.toString()
            ballCountView.text = balls.toString()
        }
    }
}
