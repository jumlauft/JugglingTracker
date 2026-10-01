package com.juggling.tracker.logic

/** One raw phone accelerometer event: m/s² including gravity, SensorEvent.timestamp. */
data class PhoneAccelSample(
    val ax: Double,
    val ay: Double,
    val az: Double,
    val timestampNanos: Long,
)
