package com.juggling.tracker.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.juggling.tracker.logic.PhoneAccelSample

/**
 * Runs the phone accelerometer (~200 Hz) on its own HandlerThread and hands
 * the samples to [onBatch] on the main thread about ten times a second.
 * Delivering every event on the main thread meant 200 Compose state writes a
 * second competing with drawing; batching keeps the UI work at 10 Hz while
 * the ViewModel still sees every raw sample.
 *
 * Call [start] and [stop] from the main thread.
 */
class PhoneAccelerometerSource(
    private val sensorManager: SensorManager,
    private val onBatch: (List<PhoneAccelSample>) -> Unit,
) {
    companion object {
        private const val SAMPLE_PERIOD_US = 5_000
        private const val FLUSH_PERIOD_NANOS = 100_000_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    // Bumped on every start and stop, so a batch still queued on the main
    // thread from an earlier registration is dropped instead of being fed
    // to the next session's detector.
    private var generation = 0

    val isActive: Boolean
        get() = listener != null

    /** Idempotent; returns false when there is no accelerometer. */
    fun start(): Boolean {
        if (listener != null) return true
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val sensorThread = HandlerThread("PhoneAccelerometer").also { it.start() }
        val gen = ++generation
        val newListener = object : SensorEventListener {
            // Touched only on sensorThread.
            private val batch = ArrayList<PhoneAccelSample>()

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
                batch += PhoneAccelSample(
                    ax = event.values[0].toDouble(),
                    ay = event.values[1].toDouble(),
                    az = event.values[2].toDouble(),
                    timestampNanos = event.timestamp,
                )
                if (event.timestamp - batch[0].timestampNanos >= FLUSH_PERIOD_NANOS) {
                    val out = batch.toList()
                    batch.clear()
                    mainHandler.post { if (gen == generation) onBatch(out) }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = sensorManager.registerListener(
            newListener,
            sensor,
            SAMPLE_PERIOD_US,
            0,
            Handler(sensorThread.looper),
        )
        if (!registered) {
            sensorThread.quitSafely()
            return false
        }
        listener = newListener
        thread = sensorThread
        return true
    }

    fun stop() {
        val current = listener ?: return
        sensorManager.unregisterListener(current)
        listener = null
        generation++
        thread?.quitSafely()
        thread = null
    }
}
