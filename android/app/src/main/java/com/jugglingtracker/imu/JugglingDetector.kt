package com.jugglingtracker.imu

import kotlin.math.sqrt

/**
 * Counts juggling throws from a stream of raw accelerometer samples coming from
 * the Garmin watch.
 *
 * The watch sends raw accelerometer samples in **milli-g** (so they still
 * include gravity). This detector converts each sample to m/s^2, maintains a
 * low-pass estimate of the gravity vector, and projects the gravity-removed
 * acceleration onto the "up" axis. When that vertical acceleration exceeds a
 * ball-count dependent threshold (and the refractory period has elapsed) a
 * throw is counted.
 *
 * The detection algorithm mirrors the logic from the DisplayIMU phone app, but
 * with gravity estimated from the watch stream rather than read from a separate
 * gravity sensor.
 */
class JugglingDetector(
    /** Called whenever the running throw count changes. */
    private val onThrowCountChanged: (Int) -> Unit,
) {
    companion object {
        // Convert milli-g (watch units) to m/s^2.
        private const val MILLI_G_TO_MS2 = 9.80665f / 1000f

        // Low-pass smoothing factor for the gravity estimate. Closer to 1 keeps
        // the gravity vector steadier.
        private const val GRAVITY_ALPHA = 0.9f

        // Minimum time between two counted throws.
        private const val REFRACTORY_PERIOD_MS = 500L

        // After this long with no new throw, the current run is finished.
        private const val AUTO_FINISH_DELAY_MS = 2000L

        private val THRESHOLDS = mapOf(
            3 to 12f,
            4 to 13f,
            5 to 14f,
            6 to 15f,
            7 to 16f,
            8 to 17f,
            9 to 18f,
        )
    }

    /** Whether the IMU sits on the starting hand (affects the first throw). */
    var imuOnStartingHand: Boolean = false

    /** Number of balls being juggled, used to pick the detection threshold. */
    var ballCount: Int = 3

    private val threshold: Float
        get() = THRESHOLDS[ballCount] ?: 14f

    var throwCount: Int = 0
        private set

    private var lastThrowTime = 0L

    // Running low-pass estimate of gravity in m/s^2, seeded pointing "down".
    private val gravity = floatArrayOf(0f, 0f, 9.80665f)

    /** Reset the current run to zero throws. */
    fun reset() {
        throwCount = 0
        lastThrowTime = 0L
        onThrowCountChanged(throwCount)
    }

    /**
     * Feed one raw accelerometer sample (in milli-g) from the watch.
     * Returns the current throw count after processing the sample.
     */
    fun processSample(gxMilliG: Float, gyMilliG: Float, gzMilliG: Float, nowMs: Long): Int {
        // Total acceleration in m/s^2 (still includes gravity).
        val ax = gxMilliG * MILLI_G_TO_MS2
        val ay = gyMilliG * MILLI_G_TO_MS2
        val az = gzMilliG * MILLI_G_TO_MS2

        // Low-pass filter to estimate the gravity component.
        gravity[0] = GRAVITY_ALPHA * gravity[0] + (1 - GRAVITY_ALPHA) * ax
        gravity[1] = GRAVITY_ALPHA * gravity[1] + (1 - GRAVITY_ALPHA) * ay
        gravity[2] = GRAVITY_ALPHA * gravity[2] + (1 - GRAVITY_ALPHA) * az

        // Linear acceleration = total - gravity.
        val lx = ax - gravity[0]
        val ly = ay - gravity[1]
        val lz = az - gravity[2]

        // Project linear acceleration onto the "up" (negative gravity) axis.
        val gMag = sqrt(
            (gravity[0] * gravity[0]) +
                (gravity[1] * gravity[1]) +
                (gravity[2] * gravity[2]),
        )
        val verticalAccel = if (gMag > 0f) {
            -((lx * gravity[0]) + (ly * gravity[1]) + (lz * gravity[2])) / gMag
        } else {
            0f
        }

        if ((verticalAccel > threshold) && (nowMs - lastThrowTime > REFRACTORY_PERIOD_MS)) {
            if (throwCount == 0 && imuOnStartingHand) {
                throwCount = 1
            } else {
                throwCount += 2
            }
            lastThrowTime = nowMs
            onThrowCountChanged(throwCount)
        }

        return throwCount
    }

    /**
     * Returns true if the current run should auto-finish, i.e. there are throws
     * counted and no new throw has occurred within [AUTO_FINISH_DELAY_MS].
     */
    fun shouldAutoFinish(nowMs: Long): Boolean {
        return throwCount > 0 && lastThrowTime > 0L && (nowMs - lastThrowTime > AUTO_FINISH_DELAY_MS)
    }
}
