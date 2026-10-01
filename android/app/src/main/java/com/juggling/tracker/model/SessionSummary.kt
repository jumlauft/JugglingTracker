package com.juggling.tracker.model

/**
 * One finished session, from the watch or from a phone recording.
 *
 * `timestamp` is the identity: [SessionRepository][com.juggling.tracker.data.SessionRepository]
 * replaces the session it holds under a timestamp when the same one arrives
 * again, and drops duplicates when loading, so no two stored sessions share
 * one. A watch resends under the same timestamp when it retries, or when the
 * user carried on juggling after an ack went missing and then ended the
 * session again with more runs. Use it wherever a stable key is needed rather
 * than adding a second id field to keep in sync -- an earlier
 * `id = sessionsCache.size + 1` repeated itself as soon as a session in the
 * middle was deleted.
 */
data class SessionSummary(
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
    /**
     * How alike each hand cycle was to the one before it, as a whole percent
     * averaged over the session. Null when the watch sent none: older watch
     * builds, phone recordings, or no run long enough to score.
     */
    val shapeConsistency: Int? = null,
)

/** The watch sends a whole percent; anything outside 0..100 is not one. */
internal fun parseShapeConsistency(value: Any?): Int? =
    (value as? Number)?.toInt()?.takeIf { it in 0..100 }

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

/** Builds the stored summary of one session from its per-run counts, which must not be empty. */
internal fun summarizeSession(
    timestamp: Long,
    ballCount: Int,
    runs: List<Int>,
    durationSeconds: Long = 0L,
    runDurationsMillis: List<Long> = emptyList(),
    shapeConsistency: Int? = null,
): SessionSummary {
    val avg = runs.average()
    val stdDev = if (runs.size > 1) {
        kotlin.math.sqrt(runs.sumOf { (it - avg) * (it - avg) } / runs.size)
    } else 0.0
    return SessionSummary(
        timestamp = timestamp,
        ballCount = ballCount,
        runCount = runs.size,
        avgThrows = avg,
        stdDevThrows = stdDev,
        bestRun = runs.maxOrNull() ?: 0,
        totalThrows = runs.sum(),
        runHistory = runs,
        durationSeconds = durationSeconds,
        runDurationsMillis = normalizeRunDurations(runs.size, runDurationsMillis),
        shapeConsistency = shapeConsistency?.takeIf { it in 0..100 },
    )
}
