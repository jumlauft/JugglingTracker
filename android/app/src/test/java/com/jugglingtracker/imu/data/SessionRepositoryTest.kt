package com.jugglingtracker.imu.data

import com.jugglingtracker.imu.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var repository: SessionRepository

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        repository = SessionRepository(prefs)
    }

    // ── importSession ───────────────────────────────────────────────────

    @Test
    fun `import session adds to sessions list`() {
        repository.importSession(3, 1000L, listOf(10, 20, 15))

        val sessions = repository.getSessions()
        assertEquals(1, sessions.size)
        assertEquals(3, sessions[0].ballCount)
        assertEquals(1000L, sessions[0].timestamp)
        assertEquals(3, sessions[0].runCount)
        assertEquals(45, sessions[0].totalThrows)
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
    fun `import deduplicates by timestamp`() {
        repository.importSession(3, 1000L, listOf(10))
        repository.importSession(3, 1000L, listOf(20))

        assertEquals(1, repository.getSessions().size)
        assertEquals(10, repository.getSessions()[0].totalThrows)
    }

    @Test
    fun `import different timestamps creates separate sessions`() {
        repository.importSession(3, 1000L, listOf(10))
        repository.importSession(3, 2000L, listOf(20))

        assertEquals(2, repository.getSessions().size)
    }

    // ── Persistence round-trip ──────────────────────────────────────────

    @Test
    fun `sessions survive save and reload`() {
        repository.importSession(3, 1000L, listOf(10, 20, 15))
        repository.importSession(5, 2000L, listOf(30))

        // Create a new repository instance that loads from the same SharedPreferences
        val reloaded = SessionRepository(prefs)

        val sessions = reloaded.getSessions()
        assertEquals(2, sessions.size)
        // Sorted by timestamp descending
        assertEquals(2000L, sessions[0].timestamp)
        assertEquals(1000L, sessions[1].timestamp)
    }

    @Test
    fun `reloaded sessions have correct statistics`() {
        repository.importSession(3, 1000L, listOf(10, 20, 30))

        val reloaded = SessionRepository(prefs)
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
        val fresh = SessionRepository(freshPrefs)
        assertTrue(fresh.getSessions().isEmpty())
    }

    @Test
    fun `corrupted json loads without crashing`() {
        val corruptPrefs = FakeSharedPreferences()
        corruptPrefs.edit().putString("sessions_json", "not valid json!!!").commit()

        val repo = SessionRepository(corruptPrefs)
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

        val reloaded = SessionRepository(prefs)
        assertEquals(1, reloaded.getSessions().size)
        assertEquals(1000L, reloaded.getSessions()[0].timestamp)
    }

    // ── addSession ──────────────────────────────────────────────────────

    @Test
    fun `addSession inserts at front`() {
        repository.importSession(3, 1000L, listOf(10))
        val newSession = repository.getSessions()[0].copy(id = 99, timestamp = 3000L, ballCount = 7)
        repository.addSession(newSession)

        assertEquals(2, repository.getSessions().size)
        assertEquals(99, repository.getSessions()[0].id)
    }
}
