package com.juggling.tracker.wear.platform

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.juggling.tracker.wear.logic.AccelSample
import com.juggling.tracker.wear.logic.JugglingDetector
import com.juggling.tracker.wear.logic.SampleThrottle

/**
 * Delivers the accelerometer the way the Garmin sensor API does for the
 * detector: 25 Hz samples in milli-g including gravity (DET-1), batched about
 * once a second. Android reports m/s² and usually runs faster than asked, so
 * samples are converted and thinned to one per 40 ms.
 *
 * The sensor hardware holds samples for up to a second and hands them over in
 * one go, on a thread of their own; only the finished batch reaches the main
 * thread, which then wakes once a second instead of for every sample.
 */
class AccelerometerSource(
    private val sensorManager: SensorManager,
    private val onBatch: (List<AccelSample>) -> Unit,
) {
    companion object {
        private const val MS2_TO_MILLI_G = 1000.0 / 9.80665
        private const val BATCH_SIZE = JugglingDetector.SAMPLE_RATE
        // The batch length: the live count still updates every second.
        private const val MAX_REPORT_LATENCY_US = 1_000_000
    }

    private val thread = HandlerThread("Accelerometer").apply { start() }
    private val sensorHandler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Used only on the sensor thread.
    private val batch = ArrayList<AccelSample>(BATCH_SIZE)
    private val throttle = SampleThrottle()

    // Used only on the main thread.
    private var active = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            val timeMs = event.timestamp / 1_000_000L
            if (!throttle.accept(timeMs)) return
            batch += AccelSample(
                x = (event.values[0] * MS2_TO_MILLI_G).toInt(),
                y = (event.values[1] * MS2_TO_MILLI_G).toInt(),
                z = (event.values[2] * MS2_TO_MILLI_G).toInt(),
                timeMs = timeMs,
            )
            if (batch.size >= BATCH_SIZE) flush()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Idempotent, so it is safe to call on every screen change (SENS-2). */
    fun start(): Boolean {
        if (active) return true
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        active = sensorManager.registerListener(
            listener,
            sensor,
            (JugglingDetector.SAMPLE_PERIOD_MS * 1000).toInt(),
            MAX_REPORT_LATENCY_US,
            sensorHandler,
        )
        return active
    }

    /**
     * Stops the sensor and drops samples not yet delivered: by now the screen
     * that wanted them is gone.
     */
    fun stop() {
        if (!active) return
        sensorManager.unregisterListener(listener)
        active = false
        sensorHandler.post {
            batch.clear()
            throttle.reset()
        }
    }

    /** Stops the sensor for good and ends its thread. */
    fun close() {
        stop()
        thread.quitSafely()
    }

    private fun flush() {
        val out = batch.toList()
        batch.clear()
        mainHandler.post { onBatch(out) }
    }
}
