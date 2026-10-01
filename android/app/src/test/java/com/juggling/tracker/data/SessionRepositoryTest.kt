package com.juggling.tracker.data

import com.juggling.tracker.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class SessionRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var file: File
    private lateinit var repository: SessionRepository

    // Runs each write at once, so a reload in the same test sees it.
    private val directWriter = Executor { it.run() }

    private fun newRepository(
        sharedPrefs: FakeSharedPreferences = prefs,
        storage: File = file,
    ) = SessionRepository(sharedPrefs, storage, directWriter)

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        file = File(tempFolder.root, "sessions.jsonl")
        repository = newRepository()
    }

    // ── importSession ───────────────────────────────────────────────────

    @Test
    fun `import session adds to sessions list`() {
        repository.importSession(
            3,
            1000L,
            listOf(10, 20, 15),
            durationSeconds = 123L,
            runDurationsMillis = listOf(9000L, 10000L, 8000L),
        )

        val sessions = repository.getSessions()
        assertEquals(1, sessions.size)
        assertEquals(3, sessions[0].ballCount)
        assertEquals(1000L, sessions[0].timestamp)
        assertEquals(3, sessions[0].runCount)
        assertEquals(45, sessions[0].totalThrows)
        assertEquals(123L, sessions[0].durationSeconds)
        assertEquals(listOf(9000L, 10000L, 8000L), sessions[0].runDurationsMillis)
    }

    @Test
    fun `import session calculates correct statistics`() {
        repository.importSession(3, 1000L, listOf(10, 20, 30))

        val session = repository.getSessions()[0]
        assertEquals(20.0, session.avgThrows, 0.001)
        assertEquals(30, session.bestRun)
        // stddev of [10,20,30] = sqrt(200/3) ≈ 8.165
        assertEquals(8.165, session.stdDevThrows, 0.01)
    }

    @Test
    fun `import single run session has zero stddev`() {
        repository.importSession(5, 2000L, listOf(42))

        val session = repository.getSessions()[0]
        assertEquals(0.0, session.stdDevThrows, 0.001)
        assertEquals(42, session.bestRun)
        assertEquals(42, session.totalThrows)
    }

    @Test
    fun `import session ignores empty runs`() {
        repository.importSession(3, 1000L, emptyList())

        assertTrue(repository.getSessions().isEmpty())
    }

    @Test
    fun `a resend under the same timestamp replaces the session`() {
        // The watch resends under the session's one timestamp when the user
        // juggled on after a lost ack and then ended again with more runs.
        repository.importSession(3, 1000L, listOf(10))
        repository.importSession(3, 1000L, listOf(10, 20))

        assertEquals(1, repository.getSessions().size)
        assertEquals(30, repository.getSessions()[0].totalThrows)
        assertEquals(2, repository.getSessions()[0].runCount)
    }

    @Test
    fun `a replaced session survives a reload`() {
        repository.importSession(3, 1000L, listOf(10))
        repository.importSession(3, 1000L, listOf(10, 20))

        val reloaded = newRepository().getSessions()
        assertEquals(1, reloaded.size)
        assertEquals(listOf(10, 20), reloaded[0].runHistory)
    }

    @Test
    fun `replacing keeps the session in its place`() {
        repository.importSession(3, 1000L, listOf(10))
        repository.importSession(3, 2000L, listOf(5))
        repository.importSession(3, 1000L, listOf(10, 20))

        assertEquals(listOf(2000L, 1000L), repository.getSessions().map { it.timestamp })
    }

    @Test
    fun `import returns the stored summary, or null without runs`() {
        assertEquals(10, repository.importSession(3, 1000L, listOf(10))?.totalThrows)
        assertNull(repository.importSession(3, 2000L, emptyList()))
    }

    @Test
    fun `import different timestamps creates separate sessions`() {
        repository.importSession(3, 1000L, listOf(10))
        repository.importSession(3, 2000L, listOf(20))

        assertEquals(2, repository.getSessions().size)
    }

    @Test
    fun `timestamps stay unique when a session in the middle is deleted`() {
        // The UI keys session rows on the timestamp, so a repeat swaps swipe
        // state between rows. The old `id = sessionsCache.size + 1` produced
        // exactly that here: deleting the middle of three and importing a
        // fourth handed out [3, 3, 1].
        repository.importSession(3, 1000L, listOf(10))
        repository.importSession(3, 2000L, listOf(20))
        repository.importSession(3, 3000L, listOf(30))

        repository.deleteSession(repository.getSessions().first { it.timestamp == 2000L })
        repository.importSession(3, 4000L, listOf(40))

        val keys = repository.getSessions().map { it.timestamp }
        assertEquals(listOf(4000L, 3000L, 1000L), keys)
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `stored duplicate timestamps collapse to one session on load`() {
        // importSession never stores two sessions under one timestamp, but
        // storage written by an older build can, and the timestamp is the
        // session's identity.
        prefs.edit().putString(
            "sessions_json",
            """
            [{
                "timestamp":1000,
                "ballCount":3,
                "runCount":1,
                "avgThrows":10.0,
                "stdDevThrows":0.0,
                "bestRun":10,
                "totalThrows":10,
                "runHistory":[10]
            },{
                "timestamp":1000,
                "ballCount":3,
                "runCount":1,
                "avgThrows":20.0,
                "stdDevThrows":0.0,
                "bestRun":20,
                "totalThrows":20,
                "runHistory":[20]
            }]
            """.trimIndent()
        ).commit()

        val sessions = newRepository().getSessions()

        assertEquals(1, sessions.size)
        assertEquals(1000L, sessions[0].timestamp)
    }

    @Test
    fun `import pads missing run durations with zero`() {
        repository.importSession(3, 1000L, listOf(10, 20, 30), runDurationsMillis = listOf(9000L))

        assertEquals(listOf(9000L, 0L, 0L), repository.getSessions()[0].runDurationsMillis)
    }

    // ── Persistence round-trip ──────────────────────────────────────────

    @Test
    fun `sessions survive save and reload`() {
        repository.importSession(
            3,
            1000L,
            listOf(10, 20, 15),
            durationSeconds = 123L,
            runDurationsMillis = listOf(9000L, 10000L, 8000L),
        )
        repository.importSession(5, 2000L, listOf(30))

        // Create a new repository instance that loads from the same SharedPreferences
        val reloaded = newRepository()

        val sessions = reloaded.getSessions()
        assertEquals(2, sessions.size)
        // Sorted by timestamp descending
        assertEquals(2000L, sessions[0].timestamp)
        assertEquals(1000L, sessions[1].timestamp)
        assertEquals(123L, sessions[1].durationSeconds)
        assertEquals(listOf(9000L, 10000L, 8000L), sessions[1].runDurationsMillis)
    }

    @Test
    fun `shape consistency survives save and reload`() {
        repository.importSession(3, 1000L, listOf(10, 20), shapeConsistency = 83)
        repository.importSession(3, 2000L, listOf(10))

        val sessions = newRepository().getSessions()
        assertEquals(null, sessions[0].shapeConsistency)
        assertEquals(83, sessions[1].shapeConsistency)
    }

    @Test
    fun `reloaded sessions have correct statistics`() {
        repository.importSession(3, 1000L, listOf(10, 20, 30))

        val reloaded = newRepository()
        val session = reloaded.getSessions()[0]

        assertEquals(3, session.ballCount)
        assertEquals(3, session.runCount)
        assertEquals(20.0, session.avgThrows, 0.001)
        assertEquals(30, session.bestRun)
        assertEquals(60, session.totalThrows)
        assertEquals(listOf(10, 20, 30), session.runHistory)
    }

    @Test
    fun `empty storage loads without error`() {
        val freshPrefs = FakeSharedPreferences()
        val fresh = newRepository(freshPrefs, File(tempFolder.root, "fresh.jsonl"))
        assertTrue(fresh.getSessions().isEmpty())
    }

    @Test
    fun `legacy sessions without duration load with zero duration`() {
        // The `id` below is a field older builds wrote; the reader now ignores it.
        prefs.edit().putString(
            "sessions_json",
            """
            [{
                "id":1,
                "timestamp":1000,
                "ballCount":3,
                "runCount":2,
                "avgThrows":15.0,
                "stdDevThrows":5.0,
                "bestRun":20,
                "totalThrows":30,
                "runHistory":[10,20]
            }]
            """.trimIndent()
        ).commit()

        val reloaded = newRepository()

        assertEquals(0L, reloaded.getSessions()[0].durationSeconds)
        assertTrue(reloaded.getSessions()[0].runDurationsMillis.isEmpty())
    }

    @Test
    fun `corrupted json loads without crashing`() {
        val corruptPrefs = FakeSharedPreferences()
        corruptPrefs.edit().putString("sessions_json", "not valid json!!!").commit()

        val repo = newRepository(corruptPrefs, File(tempFolder.root, "corrupt.jsonl"))
        // Should log the error and return empty, not crash
        assertTrue(repo.getSessions().isEmpty())
    }

    // ── deleteSession ───────────────────────────────────────────────────

    @Test
    fun `delete session removes it`() {
        repository.importSession(3, 1000L, listOf(10))
        assertEquals(1, repository.getSessions().size)

        repository.deleteSession(repository.getSessions()[0])

        assertTrue(repository.getSessions().isEmpty())
    }

    @Test
    fun `delete persists across reload`() {
        repository.importSession(3, 1000L, listOf(10))
        repository.importSession(3, 2000L, listOf(20))

        repository.deleteSession(repository.getSessions()[0])

        val reloaded = newRepository()
        assertEquals(1, reloaded.getSessions().size)
        assertEquals(1000L, reloaded.getSessions()[0].timestamp)
    }

    // ── storage file ────────────────────────────────────────────────────

    private val legacyHistory = """
        [{
            "timestamp":2000,
            "ballCount":5,
            "runCount":1,
            "avgThrows":30.0,
            "stdDevThrows":0.0,
            "bestRun":30,
            "totalThrows":30,
            "runHistory":[30],
            "shapeConsistency":71
        },{
            "timestamp":1000,
            "ballCount":3,
            "runCount":2,
            "avgThrows":15.0,
            "stdDevThrows":5.0,
            "bestRun":20,
            "totalThrows":30,
            "runHistory":[10,20]
        }]
    """.trimIndent()

    @Test
    fun `history stored by an older build moves to the file`() {
        prefs.edit().putString("sessions_json", legacyHistory).commit()

        val migrated = newRepository().getSessions()

        assertEquals(listOf(2000L, 1000L), migrated.map { it.timestamp })
        assertEquals(71, migrated[0].shapeConsistency)
        assertFalse(prefs.contains("sessions_json"))
        assertEquals(2, file.readLines().size)

        // The next start reads the file alone.
        assertEquals(migrated, newRepository().getSessions())
    }

    @Test
    fun `unreadable older history keeps its key`() {
        prefs.edit().putString("sessions_json", "not valid json!!!").commit()

        assertTrue(newRepository().getSessions().isEmpty())
        assertTrue(prefs.contains("sessions_json"))
        assertFalse(file.exists())
    }

    @Test
    fun `import appends one line`() {
        repository.importSession(3, 1000L, listOf(10))
        val first = file.readLines()
        repository.importSession(3, 2000L, listOf(20))

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals(first[0], lines[0])
    }

    @Test
    fun `a line cut short by a crash is skipped`() {
        repository.importSession(3, 1000L, listOf(10))
        file.appendText("{\"timestamp\":2000,\"ballCo")

        val sessions = newRepository().getSessions()

        assertEquals(listOf(1000L), sessions.map { it.timestamp })
    }

    @Test
    fun `writes run on the writer, not the caller`() {
        val queued = mutableListOf<Runnable>()
        val repo = SessionRepository(prefs, file, Executor { queued.add(it) })

        repo.importSession(3, 1000L, listOf(10))

        assertEquals(1, repo.getSessions().size)
        assertFalse(file.exists())
        queued.forEach { it.run() }
        assertEquals(1, newRepository().getSessions().size)
    }
}
