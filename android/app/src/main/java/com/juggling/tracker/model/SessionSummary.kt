package com.juggling.tracker.model

data class SessionSummary(
    val id: Int,
    val timestamp: Long,
    val ballCount: Int,
    val runCount: Int,
    val avgThrows: Double,
    val stdDevThrows: Double,
    val avgConsistency: Double,
    val bestRun: Int,
    val totalThrows: Int,
    val runHistory: List<Int>,
    val durationSeconds: Long = 0L,
    val runDurationsMillis: List<Long> = emptyList(),
)
