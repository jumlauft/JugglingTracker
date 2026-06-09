package com.jugglingtracker.imu.logic

import com.jugglingtracker.imu.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JugglingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: JugglingViewModel

    @Before
    fun setup() {
        viewModel = JugglingViewModel()
    }

    // ── Happy path ──────────────────────────────────────────────────────

    @Test
    fun `import session from watch`() = runTest {
        val payload = mapOf(
            "type" to "session",
            "balls" to 3,
            "timestamp" to 1716931200L,
            "durationSeconds" to 123L,
            "runDurationsMillis" to listOf(9000L, 10000L, 8000L),
            "runs" to listOf(10, 20, 15)
        )

        viewModel.importSessionFromWatch(payload)
        advanceUntilIdle()

        assertEquals(1, viewModel.completedSessions.size)
        val summary = viewModel.completedSessions[0]
        assertEquals(3, summary.ballCount)
        assertEquals(3, summary.runCount)
        assertEquals(45, summary.totalThrows)
        assertEquals(20, summary.bestRun)
        assertEquals(15.0, summary.avgThrows, 0.001)
        assertEquals(123L, summary.durationSeconds)
        assertEquals(listOf(9000L, 10000L, 8000L), summary.runDurationsMillis)
    }

    @Test
    fun `import session converts epoch seconds to milliseconds`() = runTest {
        val epochSeconds = 1716931200L
        val payload = mapOf(
            "type" to "session",
            "balls" to 3,
            "timestamp" to epochSeconds,
            "runs" to listOf(10)
        )

        viewModel.importSessionFromWatch(payload)
        advanceUntilIdle()

        assertEquals(epochSeconds * 1000L, viewModel.completedSessions[0].timestamp)
    }

    @Test
    fun `import session defaults missing duration to zero`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "type" to "session",
            "balls" to 3,
            "timestamp" to 1000L,
            "runs" to listOf(10)
        ))
        advanceUntilIdle()

        assertEquals(0L, viewModel.completedSessions[0].durationSeconds)
        assertEquals(listOf(0L), viewModel.completedSessions[0].runDurationsMillis)
    }

    @Test
    fun `import session pads missing run durations with zero`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "type" to "session",
            "balls" to 3,
            "timestamp" to 1000L,
            "runDurationsMillis" to listOf(9000L),
            "runs" to listOf(10, 20)
        ))
        advanceUntilIdle()

        assertEquals(listOf(9000L, 0L), viewModel.completedSessions[0].runDurationsMillis)
    }

    @Test
    fun `import single run session has zero stddev`() = runTest {
        val payload = mapOf(
            "type" to "session",
            "balls" to 5,
            "timestamp" to 1000L,
            "runs" to listOf(42)
        )

        viewModel.importSessionFromWatch(payload)
        advanceUntilIdle()

        val summary = viewModel.completedSessions[0]
        assertEquals(1, summary.runCount)
        assertEquals(42, summary.bestRun)
        assertEquals(42, summary.totalThrows)
        assertEquals(0.0, summary.stdDevThrows, 0.001)
    }

    @Test
    fun `import multiple sessions preserves order newest first`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3, "timestamp" to 100L, "runs" to listOf(5)
        ))
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3, "timestamp" to 200L, "runs" to listOf(10)
        ))
        advanceUntilIdle()

        assertEquals(2, viewModel.completedSessions.size)
        // Most recent added at index 0
        assertEquals(200L * 1000L, viewModel.completedSessions[0].timestamp)
        assertEquals(100L * 1000L, viewModel.completedSessions[1].timestamp)
    }

    // ── Missing / invalid fields ────────────────────────────────────────

    @Test
    fun `import ignores payload missing balls`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "type" to "session",
            "timestamp" to 1000L,
            "runs" to listOf(10)
        ))
        advanceUntilIdle()

        assertTrue(viewModel.completedSessions.isEmpty())
    }

    @Test
    fun `import ignores payload missing timestamp`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "type" to "session",
            "balls" to 3,
            "runs" to listOf(10)
        ))
        advanceUntilIdle()

        assertTrue(viewModel.completedSessions.isEmpty())
    }

    @Test
    fun `import ignores payload missing runs`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "type" to "session",
            "balls" to 3,
            "timestamp" to 1000L
        ))
        advanceUntilIdle()

        assertTrue(viewModel.completedSessions.isEmpty())
    }

    @Test
    fun `import ignores payload with empty runs`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "type" to "session",
            "balls" to 3,
            "timestamp" to 1000L,
            "runs" to emptyList<Int>()
        ))
        advanceUntilIdle()

        assertTrue(viewModel.completedSessions.isEmpty())
    }

    @Test
    fun `import ignores payload with wrong type for balls`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "type" to "session",
            "balls" to "three",
            "timestamp" to 1000L,
            "runs" to listOf(10)
        ))
        advanceUntilIdle()

        assertTrue(viewModel.completedSessions.isEmpty())
    }

    @Test
    fun `import ignores payload with wrong type for timestamp`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "type" to "session",
            "balls" to 3,
            "timestamp" to "not-a-number",
            "runs" to listOf(10)
        ))
        advanceUntilIdle()

        assertTrue(viewModel.completedSessions.isEmpty())
    }

    @Test
    fun `import filters non-numeric entries from runs`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3,
            "timestamp" to 1000L,
            "runs" to listOf(10, "bad", 20)
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.completedSessions.size)
        val summary = viewModel.completedSessions[0]
        assertEquals(2, summary.runCount)
        assertEquals(30, summary.totalThrows)
    }

    @Test
    fun `import handles runs list containing only non-numeric values`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3,
            "timestamp" to 1000L,
            "runs" to listOf("a", "b")
        ))
        advanceUntilIdle()

        assertTrue(viewModel.completedSessions.isEmpty())
    }

    @Test
    fun `import accepts Number subtypes for balls`() = runTest {
        // Garmin SDK may send doubles or longs instead of ints
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3.0,
            "timestamp" to 1000L,
            "runs" to listOf(10)
        ))
        advanceUntilIdle()

        assertEquals(1, viewModel.completedSessions.size)
        assertEquals(3, viewModel.completedSessions[0].ballCount)
    }

    @Test
    fun `import completely empty payload is ignored`() = runTest {
        viewModel.importSessionFromWatch(emptyMap())
        advanceUntilIdle()

        assertTrue(viewModel.completedSessions.isEmpty())
    }

    // ── Delete ──────────────────────────────────────────────────────────

    @Test
    fun `delete session removes it from list`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3, "timestamp" to 1000L, "runs" to listOf(10)
        ))
        advanceUntilIdle()
        assertEquals(1, viewModel.completedSessions.size)

        viewModel.deleteSession(viewModel.completedSessions[0])

        assertTrue(viewModel.completedSessions.isEmpty())
    }

    @Test
    fun `delete non-existent session is a no-op`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3, "timestamp" to 1000L, "runs" to listOf(10)
        ))
        advanceUntilIdle()

        val fake = viewModel.completedSessions[0].copy(id = 999)
        viewModel.deleteSession(fake)

        assertEquals(1, viewModel.completedSessions.size)
    }

    // ── CSV export ──────────────────────────────────────────────────────

    @Test
    fun `csv export empty sessions produces header only`() {
        val csv = viewModel.getSessionsCsv()

        assertTrue(csv.startsWith("Date,Ball Count,"))
        assertEquals(1, csv.lines().count { it.isNotBlank() })
    }

    @Test
    fun `csv export includes session data`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3,
            "timestamp" to 1716931200L,
            "durationSeconds" to 123L,
            "runDurationsMillis" to listOf(9000L, 10000L),
            "runs" to listOf(10, 20)
        ))
        advanceUntilIdle()

        val csv = viewModel.getSessionsCsv()
        val lines = csv.lines().filter { it.isNotBlank() }

        assertEquals(2, lines.size)
        val dataLine = lines[1]
        assertTrue(dataLine.contains(",3,"))       // ball count
        assertTrue(dataLine.contains(",123,"))     // session duration seconds
        assertTrue(dataLine.contains(",30,"))       // watch-hand catch total
        assertTrue(dataLine.contains("\"9000;10000\""))  // run durations
        assertTrue(dataLine.contains("\"10;20\""))  // run history
    }

    @Test
    fun `csv export multiple sessions produces one line each`() = runTest {
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3, "timestamp" to 100L, "runs" to listOf(5)
        ))
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 5, "timestamp" to 200L, "runs" to listOf(8)
        ))
        advanceUntilIdle()

        val csv = viewModel.getSessionsCsv()
        val lines = csv.lines().filter { it.isNotBlank() }

        assertEquals(3, lines.size) // header + 2 data rows
    }

    // ── Event emission ──────────────────────────────────────────────────

    @Test
    fun `import emits SyncCompleted event with run count`() = runTest {
        val events = mutableListOf<JugglingEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3, "timestamp" to 1000L, "runs" to listOf(10, 20, 30)
        ))
        advanceUntilIdle()

        assertEquals(1, events.size)
        val event = events[0] as JugglingEvent.SyncCompleted
        assertEquals(3, event.count)
    }

    // ── Statistics accuracy ─────────────────────────────────────────────

    @Test
    fun `import calculates correct stddev for multiple runs`() = runTest {
        // runs: [10, 20, 30], mean=20, variance = ((100+0+100)/3) = 66.67, stddev ≈ 8.165
        viewModel.importSessionFromWatch(mapOf(
            "balls" to 3, "timestamp" to 1000L, "runs" to listOf(10, 20, 30)
        ))
        advanceUntilIdle()

        val summary = viewModel.completedSessions[0]
        assertEquals(20.0, summary.avgThrows, 0.001)
        assertEquals(8.165, summary.stdDevThrows, 0.01)
    }
}
