package com.juggling.tracker.model

data class SessionSummary(
    val id: Int,
    val timestamp: Long,
    val ballCount: Int,
    val runCount: Int,
    val avgThrows: Double,
    val stdDevThrows: Double,
    val bestRun: Int,
    val totalThrows: Int,
    val runHistory: List<Int>,
    val durationSeconds: Long = 0L,
    val runDurationsMillis: List<Long> = emptyList(),
)

/**
 * Forces one duration per run: extra entries dropped, missing ones zero-filled,
 * negatives clamped. The watch can send a `runDurationsMillis` list that
 * disagrees with `runs`, so both the import path and storage normalise before
 * building a summary.
 */
internal fun normalizeRunDurations(runCount: Int, runDurationsMillis: List<Long>): List<Long> {
    val sanitized = runDurationsMillis.take(runCount).map { it.coerceAtLeast(0L) }
    if (sanitized.size == runCount) return sanitized
    return sanitized + List(runCount - sanitized.size) { 0L }
}
