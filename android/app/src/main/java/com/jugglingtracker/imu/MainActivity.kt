package com.jugglingtracker.imu

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// NOTE: The Garmin Connect IQ Android SDK must be added to your Gradle build.
// The sample below uses the ConnectIQ types conceptually. Replace with the
// specific SDK package names and dependency coordinates provided by Garmin.

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.statusText)

        statusView.text = "Waiting for Garmin Forerunner 245 connection..."

        initializeGarminConnectIQ()
    }

    private fun initializeGarminConnectIQ() {
        // TODO: Add the Garmin Connect IQ initialization here.
        // Example pattern:
        // connectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQDeviceType.ANY)
        // connectIQ.initialize(this, object : ConnectIQ.ConnectIQListener {
        //     override fun onInitializationSuccess() { ... }
        //     override fun onInitializationError(error: ConnectIQ.IQSdkError) { ... }
        // })
        statusView.text = "Garmin Connect IQ SDK initialized (placeholder)."
    }

    private fun registerImuAppListener() {
        // TODO: Register a listener for your watch app’s application messages.
        // Use your Forerunner 245 device and app ID to subscribe.
    }

    private fun onImuMessageReceived(payload: Map<String, Any>) {
        runOnUiThread {
            statusView.text = "IMU payload received: ${'$'}{payload["type"]}"
        }
    }
}
