package com.juggling.tracker.logic

import com.juggling.tracker.FakeSharedPreferences
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.data.SessionRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The phone may only ack what it has stored: a watch that gets an ack throws
 * its copy away, so an ack for data the phone dropped loses it for good.
 */
class WatchInboxTest {

    private lateinit var tempDir: File
    private lateinit var sessions: SessionRepository
    private lateinit var recordings: RecordingRepository
    private lateinit var inbox: WatchInbox
    private val events = mutableListOf<WatchInbox.Event>()

    @Before
    fun setup() {
        tempDir = createTempDirectory("watch_inbox_test_").toFile()
        sessions = SessionRepository(
            FakeSharedPreferences(),
            File(tempDir, "sessions.jsonl"),
            java.util.concurrent.Executor { it.run() },
        )
        recordings = RecordingRepository(File(tempDir, "recordings"))
        inbox = WatchInbox(sessions, recordings)
        inbox.addListener { events.add(it) }
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    private fun session(timestamp: Long, runs: List<Int>) = mapOf(
        "type" to "session", "countMode" to "watch_hand", "balls" to 3,
        "timestamp" to timestamp, "durationSeconds" to 60L, "runs" to runs,
    )

    private fun recStart(samples: Int, chunks: Int, id: Long = 1_780_000_000L) = mapOf(
        "type" to "rec_start", "id" to id, "balls" to 5, "catches" to 12,
        "detected" to 9, "sampleRate" to 25, "samples" to samples, "chunks" to chunks,
    )

    private fun recChunk(index: Int, values: List<Int>, id: Long = 1_780_000_000L) = mapOf(
        "type" to "rec_chunk", "id" to id, "i" to index,
        "x" to values, "y" to values, "z" to values,
    )

    private fun recEnd(id: Long = 1_780_000_000L) = mapOf("type" to "rec_end", "id" to id)

    // ── Sessions ────────────────────────────────────────────────────────

    @Test
    fun `a stored session is acked with its timestamp`() {
        val ack = inbox.receive(session(1_780_000_000L, listOf(10, 20)), WatchInbox.Source.GARMIN)

        assertEquals(WatchInbox.Ack(1_780_000_000L), ack)
        assertEquals(1, sessions.getSessions().size)
    }

    @Test
    fun `the session is stored before the ack is handed back`() {
        var storedWhenNotified = false
        inbox.addListener { event ->
            if (event is WatchInbox.Event.SessionStored) {
                storedWhenNotified = sessions.getSessions().isNotEmpty()
            }
        }

        inbox.receive(session(1_780_000_000L, listOf(10)), WatchInbox.Source.WEAR_OS)

        assertTrue(storedWhenNotified)
    }

    @Test
    fun `a session that cannot be read is not acked`() {
        assertNull(inbox.receive(mapOf("type" to "session", "balls" to 3), WatchInbox.Source.GARMIN))
        assertNull(inbox.receive(session(1_780_000_000L, emptyList()), WatchInbox.Source.GARMIN))
        assertTrue(sessions.getSessions().isEmpty())
    }

    @Test
    fun `a resent session is acked again and replaces the first copy`() {
        // The ack for the first copy went missing, the user juggled on, then
        // ended the session again: same timestamp, one more run.
        inbox.receive(session(1_780_000_000L, listOf(10)), WatchInbox.Source.GARMIN)
        val ack = inbox.receive(session(1_780_000_000L, listOf(10, 25)), WatchInbox.Source.GARMIN)

        assertEquals(WatchInbox.Ack(1_780_000_000L), ack)
        assertEquals(1, sessions.getSessions().size)
        assertEquals(listOf(10, 25), sessions.getSessions()[0].runHistory)
    }

    @Test
    fun `messages that are not watch payloads get no ack`() {
        assertNull(inbox.receive(mapOf("type" to "ack"), WatchInbox.Source.GARMIN))
        assertNull(inbox.receive(mapOf("balls" to 3), WatchInbox.Source.GARMIN))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `receiving names the watch it came from`() {
        inbox.receive(session(1_780_000_000L, listOf(10)), WatchInbox.Source.WEAR_OS)

        assertEquals(WatchInbox.Event.Receiving(WatchInbox.Source.WEAR_OS), events.first())
    }

    // ── Recorded runs ───────────────────────────────────────────────────

    @Test
    fun `a complete run is written, then acked with its id`() {
        assertNull(inbox.receive(recStart(samples = 4, chunks = 2), WatchInbox.Source.GARMIN))
        assertNull(inbox.receive(recChunk(0, listOf(1, 2)), WatchInbox.Source.GARMIN))
        assertNull(inbox.receive(recChunk(1, listOf(3, 4)), WatchInbox.Source.GARMIN))

        val ack = inbox.receive(recEnd(), WatchInbox.Source.GARMIN)

        assertEquals(WatchInbox.Ack(1_780_000_000L), ack)
        assertEquals(1, recordings.recordingCount())
        assertTrue(WatchInbox.Event.RecordingStored in events)
    }

    @Test
    fun `a run is saved with whoever is juggling`() {
        val ada = RecordingRepository.Juggler("Ada", RecordingRepository.HAND_LEFT, RecordingRepository.HAND_RIGHT)
        val tagged = WatchInbox(sessions, recordings) { ada }
        tagged.receive(recStart(samples = 2, chunks = 1), WatchInbox.Source.WEAR_OS)
        tagged.receive(recChunk(0, listOf(1, 2)), WatchInbox.Source.WEAR_OS)
        tagged.receive(recEnd(), WatchInbox.Source.WEAR_OS)

        assertEquals(ada, recordings.listRecordings().single().juggler)
    }

    @Test
    fun `a run with a lost chunk is not acked, and the watch's resend is`() {
        inbox.receive(recStart(samples = 4, chunks = 2), WatchInbox.Source.WEAR_OS)
        inbox.receive(recChunk(1, listOf(3, 4)), WatchInbox.Source.WEAR_OS)  // chunk 0 never came

        assertNull(inbox.receive(recEnd(), WatchInbox.Source.WEAR_OS))
        assertEquals(0, recordings.recordingCount())

        // No ack, so the watch offers Retry, which starts over from rec_start.
        inbox.receive(recStart(samples = 4, chunks = 2), WatchInbox.Source.WEAR_OS)
        inbox.receive(recChunk(0, listOf(1, 2)), WatchInbox.Source.WEAR_OS)
        inbox.receive(recChunk(1, listOf(3, 4)), WatchInbox.Source.WEAR_OS)

        assertEquals(WatchInbox.Ack(1_780_000_000L), inbox.receive(recEnd(), WatchInbox.Source.WEAR_OS))
        assertEquals(1, recordings.recordingCount())
    }

    @Test
    fun `a run that could not be written is not acked`() {
        // The recordings folder sits under a plain file, so nothing can be written.
        val blocker = File(tempDir, "not_a_folder").apply { writeText("") }
        val unwritable = WatchInbox(sessions, RecordingRepository(File(blocker, "recordings")))

        unwritable.receive(recStart(samples = 2, chunks = 1), WatchInbox.Source.GARMIN)
        unwritable.receive(recChunk(0, listOf(1, 2)), WatchInbox.Source.GARMIN)

        assertNull(unwritable.receive(recEnd(), WatchInbox.Source.GARMIN))
    }

    @Test
    fun `rec_end for a run that never started is not acked`() {
        assertNull(inbox.receive(recEnd(), WatchInbox.Source.GARMIN))
    }

    @Test
    fun `a removed listener hears nothing more`() {
        val heard = mutableListOf<WatchInbox.Event>()
        val listener: (WatchInbox.Event) -> Unit = { heard.add(it) }
        inbox.addListener(listener)
        inbox.removeListener(listener)

        inbox.receive(session(1_780_000_000L, listOf(10)), WatchInbox.Source.GARMIN)

        assertTrue(heard.isEmpty())
    }
}
