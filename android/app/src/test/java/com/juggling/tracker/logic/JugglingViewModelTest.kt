package com.juggling.tracker.logic

import com.juggling.tracker.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JugglingViewModelTest {
    private companion object {
        const val GRAVITY = 9.80665
        const val PERIOD_MS = PhoneJugglingDetector.SAMPLE_PERIOD_MS
    }

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

    // ── Phone session import path ──────────────────────────────────────

    @Test
    fun `start phone session updates phone state`() {
        viewModel.startPhoneSession(ballCount = 4, startedAtMillis = 1000L)

        val state = viewModel.phoneSessionState
        assertTrue(state.isRecording)
        assertEquals(4, state.selectedBallCount)
        assertEquals("Mount the phone to your wrist and start juggling", state.statusMessage)
    }

    @Test
    fun `stop phone session without runs does not create session`() {
        viewModel.startPhoneSession(ballCount = 3, startedAtMillis = 1000L)

        val saved = viewModel.stopPhoneSessionAndSave(stoppedAtMillis = 5000L)

        assertFalse(saved)
        assertTrue(viewModel.completedSessions.isEmpty())
        assertEquals("No phone runs to save", viewModel.phoneSessionState.sensorError)
    }

    @Test
    fun `stop phone session saves detected runs in session history`() = runTest {
        viewModel.startPhoneSession(ballCount = 3, startedAtMillis = 100_000L)
        var sampleMs = feedPhoneBaseline(0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        // Five bursts are three watch-hand catches, which clears
        // MIN_RUN_CATCHES; anything shorter is dropped as a false start.
        repeat(5) { sampleMs = feedPhoneBurst(sampleMs) }

        val saved = viewModel.stopPhoneSessionAndSave(stoppedAtMillis = 112_000L)
        advanceUntilIdle()

        assertTrue(saved)
        assertFalse(viewModel.phoneSessionState.isRecording)
        assertEquals(1, viewModel.completedSessions.size)
        val summary = viewModel.completedSessions[0]
        assertEquals(3, summary.ballCount)
        assertEquals(listOf(3), summary.runHistory)
        assertEquals(12L, summary.durationSeconds)
        assertEquals(1, summary.runDurationsMillis.size)
        assertTrue(summary.runDurationsMillis[0] > 0L)
    }

    @Test
    fun `phone session save emits PhoneSessionSaved event`() = runTest {
        val events = mutableListOf<JugglingEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.startPhoneSession(ballCount = 3, startedAtMillis = 100_000L)
        var sampleMs = feedPhoneBaseline(0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        // Three watch-hand catches, so the run survives MIN_RUN_CATCHES and
        // there is actually a session to save.
        repeat(5) { sampleMs = feedPhoneBurst(sampleMs) }

        viewModel.stopPhoneSessionAndSave(stoppedAtMillis = 105_000L)
        advanceUntilIdle()

        assertEquals(1, events.size)
        val event = events[0] as JugglingEvent.PhoneSessionSaved
        // The event carries the number of runs, not catches.
        assertEquals(1, event.count)
        assertEquals(3, event.ballCount)
    }

    @Test
    fun `a phone session of only false starts saves nothing`() = runTest {
        viewModel.startPhoneSession(ballCount = 3, startedAtMillis = 100_000L)
        var sampleMs = feedPhoneBaseline(0L, PhoneJugglingDetector.WARMUP_SAMPLES + 30)
        // Two catches: a drop, not a run.
        sampleMs = feedPhoneBurst(sampleMs)
        sampleMs = feedPhoneBurst(sampleMs)
        feedPhoneBurst(sampleMs)

        val saved = viewModel.stopPhoneSessionAndSave(stoppedAtMillis = 112_000L)
        advanceUntilIdle()

        assertFalse("a session of false starts has nothing worth saving", saved)
        assertTrue(viewModel.completedSessions.isEmpty())
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

    private fun feedPhoneBaseline(startMs: Long, samples: Int): Long {
        var nowMs = startMs
        repeat(samples) {
            viewModel.processPhoneSample(0.0, 0.0, GRAVITY, nowMs * 1_000_000L)
            nowMs += PERIOD_MS
        }
        return nowMs
    }

    private fun feedPhoneBurst(
        startMs: Long,
        amplitude: Double = 50.0,
        pulseSamples: Int = 3,
        settleSamples: Int = 12,
    ): Long {
        var nowMs = startMs
        repeat(pulseSamples) {
            // Pulse Z-axis opposite gravity to create upward acceleration
            viewModel.processPhoneSample(0.0, 0.0, GRAVITY - amplitude, nowMs * 1_000_000L)
            nowMs += PERIOD_MS
        }
        repeat(settleSamples) {
            viewModel.processPhoneSample(0.0, 0.0, GRAVITY, nowMs * 1_000_000L)
            nowMs += PERIOD_MS
        }
        return nowMs
    }

    // ── Chunked recording transfer ──────────────────────────────────────

    private fun startPayload(samples: Int, chunks: Int, id: Long = 42L) = mapOf(
        "type" to "rec_start", "id" to id, "balls" to 5, "catches" to 12,
        "detected" to 9, "sampleRate" to 25, "samples" to samples, "chunks" to chunks,
    )

    private fun chunkPayload(index: Int, values: List<Int>, id: Long = 42L) = mapOf(
        "type" to "rec_chunk", "id" to id, "i" to index,
        "x" to values, "y" to values, "z" to values,
    )

    @Test
    fun `chunked transfer reassembles a complete run`() = runTest {
        viewModel.startRecordingTransfer(startPayload(samples = 4, chunks = 2))
        viewModel.appendRecordingChunk(chunkPayload(0, listOf(1, 2)))
        viewModel.appendRecordingChunk(chunkPayload(1, listOf(3, 4)))

        assertTrue(viewModel.finishRecordingTransfer(mapOf("type" to "rec_end", "id" to 42L)))
    }

    @Test
    fun `out of order chunk drops the run`() = runTest {
        viewModel.startRecordingTransfer(startPayload(samples = 4, chunks = 2))
        viewModel.appendRecordingChunk(chunkPayload(1, listOf(3, 4)))

        assertFalse(viewModel.finishRecordingTransfer(mapOf("type" to "rec_end", "id" to 42L)))
    }

    @Test
    fun `missing chunk is not saved`() = runTest {
        viewModel.startRecordingTransfer(startPayload(samples = 4, chunks = 2))
        viewModel.appendRecordingChunk(chunkPayload(0, listOf(1, 2)))

        assertFalse(viewModel.finishRecordingTransfer(mapOf("type" to "rec_end", "id" to 42L)))
    }

    @Test
    fun `sample count mismatch is not saved`() = runTest {
        viewModel.startRecordingTransfer(startPayload(samples = 99, chunks = 1))
        viewModel.appendRecordingChunk(chunkPayload(0, listOf(1, 2)))

        assertFalse(viewModel.finishRecordingTransfer(mapOf("type" to "rec_end", "id" to 42L)))
    }

    @Test
    fun `chunk from a different run is ignored`() = runTest {
        viewModel.startRecordingTransfer(startPayload(samples = 2, chunks = 1))
        viewModel.appendRecordingChunk(chunkPayload(0, listOf(7, 8), id = 999L))

        assertFalse(viewModel.finishRecordingTransfer(mapOf("type" to "rec_end", "id" to 42L)))
    }

    @Test
    fun `rec_end without a start does nothing`() = runTest {
        assertFalse(viewModel.finishRecordingTransfer(mapOf("type" to "rec_end", "id" to 42L)))
    }

    @Test
    fun `duplicate chunk delivery is tolerated`() = runTest {
        viewModel.startRecordingTransfer(startPayload(samples = 4, chunks = 2))
        viewModel.appendRecordingChunk(chunkPayload(0, listOf(1, 2)))
        viewModel.appendRecordingChunk(chunkPayload(0, listOf(1, 2)))  // redelivered
        viewModel.appendRecordingChunk(chunkPayload(1, listOf(3, 4)))
        viewModel.appendRecordingChunk(chunkPayload(1, listOf(3, 4)))  // redelivered

        assertTrue(viewModel.finishRecordingTransfer(mapOf("type" to "rec_end", "id" to 42L)))
    }
}
