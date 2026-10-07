package com.juggling.tracker.data

import com.juggling.tracker.model.SessionSummary
import com.juggling.tracker.model.summarizeSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The session history as CSV: what "Export History to CSV" and the weekly
 * backup write, and what "Restore from Backup" reads back.
 *
 * Numbers and dates are written the same way whatever the phone's language: a
 * German phone would otherwise write 12,50 for the average, and that comma
 * splits the row into one column too many.
 *
 * The last column, Timestamp Millis, is the session's exact timestamp (its
 * identity, see [SessionSummary]). Exports from before it existed carry only
 * the Date, to the second in the phone's time zone, so [parse] falls back to
 * that and [newSessions] compares sessions to the second.
 */
object SessionCsv {
    private const val COL_DATE = "Date"
    private const val COL_BALLS = "Ball Count"
    private const val COL_DURATION = "Session Duration Seconds"
    private const val COL_RUN_DURATIONS = "Run Durations Millis"
    private const val COL_RUN_HISTORY = "Watch Hand Run History"
    private const val COL_REGULARITY = "Regularity Percent"
    private const val COL_TIMESTAMP = "Timestamp Millis"

    const val HEADER = "$COL_DATE,$COL_BALLS,Run Count,$COL_DURATION,Watch Hand Average," +
        "Watch Hand Best,Watch Hand Total,$COL_RUN_DURATIONS,$COL_RUN_HISTORY,$COL_REGULARITY,$COL_TIMESTAMP"

    private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /** [sessions] as CSV, one row each in the order given. */
    fun write(sessions: List<SessionSummary>, timeZone: TimeZone = TimeZone.getDefault()): String {
        val dateFormat = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { this.timeZone = timeZone }
        val builder = StringBuilder()
        builder.append(HEADER).append('\n')
        sessions.forEach { session ->
            val date = dateFormat.format(Date(session.timestamp))
            val average = String.format(Locale.US, "%.2f", session.avgThrows)
            val runHistory = session.runHistory.joinToString(";")
            val runDurationsMillis = session.runDurationsMillis.joinToString(";")
            // Empty when the session has no Regularity score.
            val regularity = session.shapeConsistency?.toString() ?: ""
            builder.append("$date,${session.ballCount},${session.runCount},${session.durationSeconds},$average,")
                .append("${session.bestRun},${session.totalThrows},\"$runDurationsMillis\",\"$runHistory\",")
                .append("$regularity,${session.timestamp}\n")
        }
        return builder.toString()
    }

    /** What [parse] read: the sessions, and how many data rows it could not read. */
    data class Parsed(val sessions: List<SessionSummary>, val unreadableRows: Int)

    /**
     * The sessions in a CSV written by [write] or an older export. Rows it
     * cannot read (no date, no runs, cut short) are counted and skipped.
     * Throws [IllegalArgumentException] when the text is not a session
     * history at all.
     */
    fun parse(text: String, timeZone: TimeZone = TimeZone.getDefault()): Parsed {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "Empty file" }
        // A spreadsheet app saving with a German locale separates with ';'.
        val headerLine = lines[0]
        val delimiter = if (',' !in headerLine && ';' in headerLine) ';' else ','
        val header = splitRow(headerLine, delimiter).map { it.trim() }
        val index = header.withIndex().associate { it.value to it.index }
        val balls = index[COL_BALLS]
        val runHistory = index[COL_RUN_HISTORY]
        val date = index[COL_DATE]
        val exact = index[COL_TIMESTAMP]
        require(balls != null && runHistory != null && (date != null || exact != null)) {
            "Not a Juggling Tracker history"
        }
        val dateFormat = SimpleDateFormat(DATE_PATTERN, Locale.US).apply {
            this.timeZone = timeZone
            isLenient = false
        }

        var unreadable = 0
        val sessions = lines.drop(1).mapNotNull { line ->
            val cells = splitRow(line, delimiter).map { it.trim() }
            fun cell(column: Int?) = column?.let { cells.getOrNull(it) }?.takeIf { it.isNotEmpty() }
            val session = try {
                val timestamp = cell(exact)?.toLongOrNull()
                    ?: cell(date)?.let { dateFormat.parse(it)?.time }
                val ballCount = cell(balls)?.toIntOrNull()
                val runs = cell(runHistory)?.split(';')?.map { it.trim().toInt() }.orEmpty()
                if (timestamp == null || ballCount == null || runs.isEmpty()) {
                    null
                } else {
                    summarizeSession(
                        timestamp = timestamp,
                        ballCount = ballCount,
                        runs = runs,
                        durationSeconds = cell(index[COL_DURATION])?.toLongOrNull() ?: 0L,
                        runDurationsMillis = cell(index[COL_RUN_DURATIONS])
                            ?.split(';')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty(),
                        shapeConsistency = cell(index[COL_REGULARITY])?.toIntOrNull(),
                    )
                }
            } catch (e: Exception) {
                null
            }
            if (session == null) unreadable++
            session
        }
        return Parsed(sessions, unreadable)
    }

    /**
     * The sessions of [backup] not already in [existing], each once. Sessions
     * are matched to the second, because older exports only carry the date to
     * the second; two real sessions never start within one second.
     */
    fun newSessions(backup: List<SessionSummary>, existing: List<SessionSummary>): List<SessionSummary> {
        val seen = existing.mapTo(HashSet()) { secondOf(it.timestamp) }
        return backup.filter { seen.add(secondOf(it.timestamp)) }
    }

    private fun secondOf(timestamp: Long) = Math.floorDiv(timestamp, 1000L)

    /** One CSV row's cells; a quoted cell may hold the delimiter and "" for a quote. */
    private fun splitRow(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { cells.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }
}
