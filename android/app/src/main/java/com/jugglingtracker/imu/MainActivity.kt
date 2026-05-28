package com.jugglingtracker.imu

import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
    }

    private lateinit var connectIQ: ConnectIQ
    private var iqDevice: IQDevice? = null
    private var iqApp: IQApp? = null

    private lateinit var statusView: TextView
    private lateinit var barX: ProgressBar
    private lateinit var barY: ProgressBar
    private lateinit var barZ: ProgressBar
    private lateinit var valX: TextView
    private lateinit var valY: TextView
    private lateinit var valZ: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.statusText)
        barX = findViewById(R.id.barX)
        barY = findViewById(R.id.barY)
        barZ = findViewById(R.id.barZ)
        valX = findViewById(R.id.valX)
        valY = findViewById(R.id.valY)
        valZ = findViewById(R.id.valZ)

        statusView.text = "Waiting for Garmin Forerunner 245 connection..."

        initializeGarminConnectIQ()
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
        // The watch sends a list of samples. 
        // Each sample is expected to be a Map<String, Any>
        val samples = message.filterIsInstance<Map<String, Any>>()
        if (samples.isEmpty()) return

        // Use the latest sample for visualization
        val latest = samples.last()
        val x = (latest["x"] as? Number)?.toFloat() ?: 0f
        val y = (latest["y"] as? Number)?.toFloat() ?: 0f
        val z = (latest["z"] as? Number)?.toFloat() ?: 0f

        runOnUiThread {
            statusView.text = "Received ${samples.size} samples"
            
            // Accelerometer values are typically in mG (milli-Gs). 
            // We map -2000 to 2000 range to 0 to 2000 progress bar.
            barX.progress = (x + 1000).toInt().coerceIn(0, 2000)
            barY.progress = (y + 1000).toInt().coerceIn(0, 2000)
            barZ.progress = (z + 1000).toInt().coerceIn(0, 2000)

            valX.text = x.toString()
            valY.text = y.toString()
            valZ.text = z.toString()
        }
    }
}
