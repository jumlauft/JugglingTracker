package com.juggling.tracker.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RecordingRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repository: RecordingRepository

    @Before
    fun setup() {
        tempDir = createTempDirectory("recording_test_").toFile()
        repository = RecordingRepository(tempDir)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    // ── saveRecording ───────────────────────────────────────────────────

    @Test
    fun `save recording creates csv file`() {
        val file = repository.saveRecording(
            balls = 3, catches = 5, detected = 4, sampleRate = 25,
            timestamp = 1000L,
            accelX = listOf(100, 200, 300),
            accelY = listOf(400, 500, 600),
            accelZ = listOf(700, 800, 900),
            source = RecordingRepository.SOURCE_WATCH,
        )

        assertNotNull(file)
        assertTrue(file!!.exists())
        // named by run id so it is stable if the labels are later corrected
        assertTrue(file.name.matches(Regex("""\d{8}_\d{6}\.csv""")))
    }

    @Test
    fun `save recording writes correct csv format`() {
        val file = repository.saveRecording(
            balls = 3, catches = 2, detected = 2, sampleRate = 25,
            timestamp = 42L,
            accelX = listOf(10, 20),
            accelY = listOf(30, 40),
            accelZ = listOf(50, 60),
            source = RecordingRepository.SOURCE_WATCH,
        )!!

        val lines = file.readLines()
        val runId = file.name.removeSuffix(".csv")
        assertEquals(
            "# run=$runId,timestamp=42,balls=3,catches=2,sampleRate=25" +
                ",units=milli_g,source=watch,countMode=watch_hand,detectedAtCapture=2",
            lines[0],
        )
        assertEquals("x,y,z", lines[1])
        assertEquals("10,30,50", lines[2])
        assertEquals("20,40,60", lines[3])
    }

    @Test
    fun `save recording with empty data returns null`() {
        val file = repository.saveRecording(
            balls = 3, catches = 0, detected = 0, sampleRate = 25,
            timestamp = 1000L,
            accelX = emptyList(),
            accelY = emptyList(),
            accelZ = emptyList(),
            source = RecordingRepository.SOURCE_WATCH,
        )

        assertNull(file)
    }

    @Test
    fun `save recording truncates to shortest axis`() {
        val file = repository.saveRecording(
            balls = 3, catches = 1, detected = 1, sampleRate = 25,
            timestamp = 1000L,
            accelX = listOf(1, 2, 3),
            accelY = listOf(4, 5),
            accelZ = listOf(7, 8, 9, 10),
            source = RecordingRepository.SOURCE_WATCH,
        )!!

        val dataLines = file.readLines().drop(2)
        assertEquals(2, dataLines.size)
    }

    // ── recordingCount ──────────────────────────────────────────────────

    @Test
    fun `recording count starts at zero`() {
        assertEquals(0, repository.recordingCount())
    }

    @Test
    fun `recording count increases after save`() {
        repository.saveRecording(3, 5, 4, 25, 1L, listOf(1), listOf(2), listOf(3), RecordingRepository.SOURCE_WATCH)
        repository.saveRecording(3, 3, 3, 25, 2L, listOf(4), listOf(5), listOf(6), RecordingRepository.SOURCE_WATCH)

        assertEquals(2, repository.recordingCount())
    }

    // ── exportAllZip ────────────────────────────────────────────────────

    private fun zipEntries(): Map<String, String> {
        val out = java.io.ByteArrayOutputStream()
        repository.exportAllZip(out)
        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    @Test
    fun `export all zip is empty for no recordings`() {
        assertTrue(zipEntries().isEmpty())
    }

    @Test
    fun `export all zip holds one entry per recording`() {
        repository.saveRecording(3, 1, 1, 25, 1L, listOf(10), listOf(20), listOf(30), RecordingRepository.SOURCE_WATCH)
        repository.saveRecording(5, 2, 2, 25, 2L, listOf(40), listOf(50), listOf(60), RecordingRepository.SOURCE_WATCH)

        val entries = zipEntries()
        assertEquals(2, entries.size)
        assertTrue(entries.keys.all { it.endsWith(".csv") })

        val all = entries.values.joinToString("\n")
        assertTrue(all.contains("balls=3"))
        assertTrue(all.contains("balls=5"))
        assertTrue(all.contains("10,20,30"))
        assertTrue(all.contains("40,50,60"))
    }

    @Test
    fun `export all zip drops the legacy gyroscope columns`() {
        // Runs captured during the abandoned gyroscope experiment have three
        // extra columns the watch only ever filled with zeros.
        File(tempDir, "20260101_120000.csv").writeText(
            "# run=20260101_120000,balls=3,catches=1,sampleRate=25\n" +
                "x,y,z,gx,gy,gz\n" +
                "10,20,30,0,0,0\n" +
                "11,21,31,0,0,0\n"
        )

        val csv = zipEntries().values.single()
        assertTrue(csv.contains("x,y,z\n"))
        assertFalse(csv.contains("gx"))
        assertTrue(csv.contains("10,20,30\n"))
        assertFalse(csv.contains("10,20,30,0"))
    }

    // ── clearAll ────────────────────────────────────────────────────────

    @Test
    fun `clear all removes all recordings`() {
        repository.saveRecording(3, 1, 1, 25, 1L, listOf(1), listOf(2), listOf(3), RecordingRepository.SOURCE_WATCH)
        repository.saveRecording(3, 2, 2, 25, 2L, listOf(4), listOf(5), listOf(6), RecordingRepository.SOURCE_WATCH)

        repository.clearAll()

        assertEquals(0, repository.recordingCount())
        assertTrue(zipEntries().isEmpty())
    }

    // ── listRecordings ──────────────────────────────────────────────────

    @Test
    fun `list recordings summarises watch and phone sources`() {
        repository.saveRecording(
            balls = 3, catches = 89, detected = 88, sampleRate = 25,
            timestamp = 2000L,
            accelX = List(50) { 1 }, accelY = List(50) { 2 }, accelZ = List(50) { 3 },
            source = RecordingRepository.SOURCE_WATCH,
        )
        repository.saveRecording(
            balls = 5, catches = 12, detected = 9, sampleRate = 200,
            timestamp = 1000L,
            accelX = List(400) { 1 }, accelY = List(400) { 2 }, accelZ = List(400) { 3 },
            source = RecordingRepository.SOURCE_PHONE,
        )

        val all = repository.listRecordings()
        assertEquals(2, all.size)

        // newest first
        val watch = all[0]
        assertEquals(3, watch.balls)
        assertEquals(89, watch.catches)
        assertEquals(88, watch.detected)
        assertEquals(25, watch.sampleRate)
        assertEquals(50, watch.samples)
        assertTrue(watch.fromWatch)
        assertEquals(2.0, watch.durationSeconds, 0.001)

        val phone = all[1]
        assertEquals(200, phone.sampleRate)
        assertFalse(phone.fromWatch)
        assertEquals(2.0, phone.durationSeconds, 0.001)
    }

    @Test
    fun `list recordings is empty when nothing stored`() {
        assertTrue(repository.listRecordings().isEmpty())
    }

    @Test
    fun `list recordings skips a file with no header`() {
        File(tempDir, "broken.csv").writeText("x,y,z\n1,2,3\n")
        assertTrue(repository.listRecordings().isEmpty())
    }
}
