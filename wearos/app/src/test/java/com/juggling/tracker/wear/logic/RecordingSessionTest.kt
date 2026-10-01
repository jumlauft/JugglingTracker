package com.juggling.tracker.wear.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Record mode, `RecordingView.mc` and its delegates. Each test names the
 * requirement in `connectiq/REQUIREMENTS.md` it secures.
 */
class RecordingSessionTest {
    private val scheduler = FakeScheduler()
    private val link = FakePhoneLink()
    private val effects = FakeEffects()
    private val logLines = mutableListOf<String>()
    private var epoch = 1_700_000_000L
    private val session = RecordingSession(3, link, scheduler, effects, epochSeconds = { epoch }, log = { logLines += it })
    private var t = 0L

    private val state get() = session.state.value

    private fun feed(samples: List<AccelSample>) {
        samples.chunked(25).forEach { session.onSamples(it) }
        t = Feeds.next(samples)
    }

    /** Records a run of warmup + [catches] and stops it, landing on the label screen. */
    private fun recordRun(catches: Int = 3) {
        session.onStart()
        feed(Feeds.warmup())
        feed(Feeds.catches(t, catches))
        session.onStart()
    }

    /** Lets every part of the transfer through until it waits for the ack. */
    private fun deliverAllParts() {
        link.autoResult = true
        link.complete(true) // the part already in flight
        scheduler.advance(RecordingSession.NEXT_PART_DELAY_MS * 200)
    }

    // ── REC-1 ─────────────────────────────────────────────────────────

    @Test
    fun `REC-1 start drives idle recording labeling syncing and back to idle`() {
        assertEquals(RecordingPhase.IDLE, state.phase)
        session.onStart()
        assertEquals(RecordingPhase.RECORDING, state.phase)
        feed(Feeds.warmup())
        session.onStart()
        assertEquals(RecordingPhase.LABELING, state.phase)
        session.onStart()
        assertEquals(RecordingPhase.SYNCING, state.phase)
        deliverAllParts()
        link.ack(epoch)
        assertEquals(RecordingPhase.IDLE, state.phase)
        assertEquals(1, state.runsCompleted)
    }

    @Test
    fun `REC-1 samples are only kept while recording`() {
        feed(Feeds.baseline(0, 30))
        assertEquals(0, state.recordedSamples)
        session.onStart()
        feed(Feeds.baseline(t, 30))
        assertEquals(30, state.recordedSamples)
    }

    @Test
    fun `REC-1 back while labeling offers to discard`() {
        recordRun()
        session.onBack()
        assertEquals("Discard run?", state.menu!!.title)
        assertEquals(listOf("Yes", "Continue"), state.menu!!.items.map { it.label })
        session.onMenuSelect(RecordingSession.ITEM_DISCARD_CONFIRM)
        assertEquals(RecordingPhase.IDLE, state.phase)
        assertEquals(0, state.runsCompleted)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun `REC-1 back while recording offers to quit`() {
        session.onStart()
        session.onBack()
        assertEquals("Quit? Lose run", state.menu!!.title)
        session.onMenuSelect(RecordingSession.ITEM_QUIT_CONTINUE)
        assertEquals(0, effects.exits)
        assertEquals(RecordingPhase.RECORDING, state.phase)

        session.onBack()
        assertEquals("Quit? Lose run", state.menu!!.title)
        session.onMenuSelect(RecordingSession.ITEM_QUIT_CONFIRM)
        assertEquals(1, effects.exits)
    }

    // ── REC-2 ─────────────────────────────────────────────────────────

    @Test
    fun `REC-2 a run stops by itself at 3000 samples`() {
        session.onStart()
        feed(Feeds.baseline(0, RecordingSession.MAX_RUN_SAMPLES + 10))
        assertEquals(RecordingPhase.LABELING, state.phase)
        assertEquals(RecordingSession.MAX_RUN_SAMPLES, state.recordedSamples)
    }

    // ── REC-3 ─────────────────────────────────────────────────────────

    @Test
    fun `REC-3 the detected count is the starting label and it never goes below zero`() {
        recordRun(4)
        assertEquals(4, state.detectedCount)
        assertEquals(4, state.labelCount)
        session.onUp()
        assertEquals(5, state.labelCount)
        repeat(8) { session.onDown() }
        assertEquals(0, state.labelCount)
    }

    // ── REC-4 ─────────────────────────────────────────────────────────

    @Test
    fun `REC-4 each confirmed run logs a one-line summary without the samples`() {
        recordRun(3)
        session.onUp()
        session.onStart()
        assertEquals(
            listOf("RUN_DATA,balls=3,catches=4,detected=3,rate=25,countMode=watch_hand,samples=${state.recordedSamples}"),
            logLines,
        )
    }

    // ── REC-5 ─────────────────────────────────────────────────────────

    @Test
    fun `REC-5 runs transfer as rec_start, one chunk per CHUNK_SAMPLES samples, then rec_end`() {
        recordRun(3)
        val samples = state.recordedSamples
        session.onStart()
        deliverAllParts()

        val chunks = (samples + RecordingSession.CHUNK_SAMPLES - 1) / RecordingSession.CHUNK_SAMPLES
        assertEquals(listOf("rec_start") + List(chunks) { "rec_chunk" } + "rec_end", link.types)

        val start = link.sent.first()
        assertEquals(epoch, start["id"])
        assertEquals(epoch, start["timestamp"])
        assertEquals(3, start["balls"])
        assertEquals(3, start["catches"])
        assertEquals(3, start["detected"])
        assertEquals(25, start["sampleRate"])
        assertEquals(samples, start["samples"])
        assertEquals(chunks, start["chunks"])
        assertEquals("watch_hand", start["countMode"])

        val sentSamples = link.sent.filter { it["type"] == "rec_chunk" }.sumOf { (it["x"] as List<*>).size }
        assertEquals(samples, sentSamples)
        assertEquals((0 until chunks).toList(), link.sent.filter { it["type"] == "rec_chunk" }.map { it["i"] })

        // Only rec_end is acknowledged: until then the run is still syncing.
        assertEquals(RecordingPhase.SYNCING, state.phase)
        assertTrue(state.transferDone)
        link.ack(epoch)
        assertEquals(RecordingPhase.IDLE, state.phase)
    }

    @Test
    fun `REC-5 a full-length run goes over in 3 chunks that fit a Data Layer message`() {
        session.onStart()
        // Six-digit readings on every axis, well past what the sensor reports,
        // so this bounds the largest chunk the watch can ever send.
        val loud = (0 until RecordingSession.MAX_RUN_SAMPLES).map {
            AccelSample(-99_999, -99_999, -99_999, it * Feeds.PERIOD_MS)
        }
        feed(loud)
        session.onStart() // label screen
        session.onStart() // confirm
        deliverAllParts()

        val chunks = link.sent.filter { it["type"] == "rec_chunk" }
        assertEquals(3, chunks.size)
        assertEquals(3, link.sent.first()["chunks"])
        // A Data Layer message carries up to about 100 KB; keep half of that spare.
        val largest = chunks.maxOf { WatchProtocol.encode(it).size }
        assertTrue("largest chunk is $largest bytes", largest < 50_000)
    }

    // ── REC-6─────────────────────────────────────────────────────────

    @Test
    fun `REC-6 a late failure from a skipped run does not fail the next one`() {
        recordRun(3)
        session.onStart() // sends rec_start, which never reports
        scheduler.advance(RecordingSession.SYNC_TIMEOUT_MS)
        assertEquals("no reply from phone", state.menu!!.title)
        session.onMenuSelect(RecordingSession.ITEM_SYNC_SKIP)
        assertEquals(RecordingPhase.IDLE, state.phase)

        epoch += 100
        recordRun(3)
        session.onStart()
        link.complete(false, index = 0) // the skipped run's transmit fails late
        assertEquals(RecordingPhase.SYNCING, state.phase)
        assertNull(state.menu)
    }

    @Test
    fun `a send failure offers retry skip or quit and retry restarts from the header`() {
        recordRun(3)
        session.onStart()
        link.complete(false)
        assertEquals("send failed", state.menu!!.title)
        assertEquals(listOf("Retry sync", "Skip (lose data)", "Quit"), state.menu!!.items.map { it.label })
        assertEquals("Sync failed", state.errorMessage)

        session.onMenuSelect(RecordingSession.ITEM_SYNC_RETRY)
        scheduler.advance(RecordingSession.NEXT_PART_DELAY_MS)
        assertEquals(listOf("rec_start", "rec_start"), link.types)
    }

    // ── REC-7 ─────────────────────────────────────────────────────────

    @Test
    fun `REC-7 the next part is sent from a timer, not from the delivery callback`() {
        recordRun(3)
        session.onStart()
        assertEquals(1, link.sent.size)
        link.complete(true)
        assertEquals("nothing is sent from inside the callback", 1, link.sent.size)
        scheduler.advance(RecordingSession.NEXT_PART_DELAY_MS)
        assertEquals(listOf("rec_start", "rec_chunk"), link.types)
    }

    // ── REC-8 ─────────────────────────────────────────────────────────

    @Test
    fun `REC-8 a stale ack for another run is rejected`() {
        recordRun(3)
        session.onStart()
        deliverAllParts()
        link.ack(epoch - 5)
        assertEquals(RecordingPhase.SYNCING, state.phase)
        assertEquals(0, state.runsCompleted)
        link.ack(epoch)
        assertEquals(RecordingPhase.IDLE, state.phase)
    }

    // ── REC-9 ─────────────────────────────────────────────────────────

    @Test
    fun `REC-9 back is consumed while syncing`() {
        recordRun(3)
        session.onStart()
        session.onBack()
        assertNull(state.menu)
        assertEquals(RecordingPhase.SYNCING, state.phase)
        assertEquals(0, effects.exits)
    }

    // ── REC-10 ────────────────────────────────────────────────────────

    @Test
    fun `REC-10 backing out of the sync failure menu retries`() {
        recordRun(3)
        session.onStart()
        link.complete(false)
        session.onMenuBack()
        assertNull(state.menu)
        scheduler.advance(RecordingSession.NEXT_PART_DELAY_MS)
        assertEquals(2, link.sent.size)
    }

    @Test
    fun `REC-11 back on the idle screen leaves without a prompt`() {
        assertTrue(session.onBack())
        assertNull(state.menu)
        assertEquals(0, effects.exits)
        // Closed: it ignores any input that still arrives.
        assertFalse(session.onStart())
        assertEquals(RecordingPhase.IDLE, state.phase)
    }

    @Test
    fun `REC-11 back after a synced run still leaves`() {
        recordRun(3)
        session.onStart()
        link.ack(epoch)
        assertEquals(RecordingPhase.IDLE, state.phase)
        assertTrue(session.onBack())
        assertEquals(0, effects.exits)
    }

    @Test
    fun `REC-10 backing out of the quit or discard prompt means continue`() {
        session.onStart()
        session.onBack()
        session.onMenuBack()
        assertNull(state.menu)
        assertEquals(0, effects.exits)
        assertEquals(RecordingPhase.RECORDING, state.phase)

        feed(Feeds.warmup())
        session.onStart()
        session.onBack()
        session.onMenuBack()
        assertEquals(RecordingPhase.LABELING, state.phase)
        session.onStart()
        assertEquals(RecordingPhase.SYNCING, state.phase)
    }

    @Test
    fun `sync progress shows chunks while they go over`() {
        recordRun(3)
        session.onStart()
        assertEquals("Sync to phone.", Format.recordingSyncText(state))
        link.complete(true)
        scheduler.advance(RecordingSession.NEXT_PART_DELAY_MS)
        assertEquals("0/${state.totalChunks}", Format.recordingSyncText(state))
    }
}
