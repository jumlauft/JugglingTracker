package com.juggling.tracker.model

/**
 * One finished session, from the watch or from a phone recording.
 *
 * `timestamp` is the identity: [SessionRepository][com.juggling.tracker.data.SessionRepository]
 * rejects an import whose timestamp it already holds, and drops duplicates when
 * loading, so no two stored sessions share one. Use it wherever a stable key is
 * needed rather than adding a second id field to keep in sync -- an earlier
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
