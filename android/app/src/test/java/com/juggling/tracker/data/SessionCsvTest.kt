package com.juggling.tracker.data

import com.juggling.tracker.model.summarizeSession
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class SessionCsvTest {
    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    private val sessions = listOf(
        summarizeSession(1716931234567L, 5, listOf(12, 30), 95L, listOf(6000L, 14000L), shapeConsistency = 83),
        summarizeSession(1716900000000L, 3, listOf(40), 60L, listOf(20000L)),
    )

    @Test
    fun `what write produces parses back to the same sessions`() {
        val parsed = SessionCsv.parse(SessionCsv.write(sessions, berlin), berlin)

        assertEquals(sessions, parsed.sessions)
        assertEquals(0, parsed.unreadableRows)
    }

    @Test
    fun `an export from before the timestamp column restores from its date`() {
        val old = """
            Date,Ball Count,Run Count,Session Duration Seconds,Watch Hand Average,Watch Hand Best,Watch Hand Total,Run Durations Millis,Watch Hand Run History,Regularity Percent
            2024-05-28 23:20:00,3,2,123,15.00,20,30,"9000;10000","10;20",
        """.trimIndent()

        val session = SessionCsv.parse(old, berlin).sessions.single()

        assertEquals(1716931200000L, session.timestamp)
        assertEquals(3, session.ballCount)
        assertEquals(listOf(10, 20), session.runHistory)
        assertEquals(listOf(9000L, 10000L), session.runDurationsMillis)
        assertEquals(123L, session.durationSeconds)
        assertNull(session.shapeConsistency)
    }

    @Test
    fun `a file saved by a spreadsheet with semicolons still reads`() {
        val csv = SessionCsv.write(sessions, berlin)
            .lines().joinToString("\r\n") { line ->
                // Quoted cells keep their ';'; every other comma becomes one.
                var quoted = false
                line.map { c -> if (c == '"') { quoted = !quoted; c } else if (c == ',' && !quoted) ';' else c }
                    .joinToString("")
            }

        assertEquals(sessions, SessionCsv.parse("\uFEFF" + csv, berlin).sessions)
    }

    @Test
    fun `rows it cannot read are skipped and counted`() {
        val csv = SessionCsv.write(sessions, berlin) + "garbage,row\n" +
            "2024-05-28 23:20:00,3,0,0,0,0,0,\"\",\"\",,\n"

        val parsed = SessionCsv.parse(csv, berlin)

        assertEquals(sessions, parsed.sessions)
        assertEquals(2, parsed.unreadableRows)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file that is not a session history is refused`() {
        SessionCsv.parse("name,email\nJonas,j@example.com\n", berlin)
    }

    @Test
    fun `new sessions skips those already stored, matching to the second`() {
        val stored = listOf(summarizeSession(1716931234000L, 5, listOf(12)))
        val backup = sessions + sessions

        // 1716931234567 is the stored session's second, as an old export would round it.
        assertEquals(listOf(sessions[1]), SessionCsv.newSessions(backup, stored))
    }
}
