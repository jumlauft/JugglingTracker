package com.juggling.tracker.data

import android.content.Context
import android.util.Log
import com.juggling.tracker.util.CrashlyticsUtils
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stores raw accelerometer recordings as individual CSV files in the app's
 * internal files directory under recordings/. Each run produces one file:
 *
 *   recordings/<run_id>.csv        (run_id is yyyyMMdd_HHmmss of capture)
 *
 * The CSV format is:
 *   # run=20260922_144802,timestamp=1790081282,balls=3,catches=89,
 *   sampleRate=25,units=milli_g,source=watch,countMode=watch_hand,detectedAtCapture=88
 *   x,y,z
 *   -123,456,987
 *   ...
 *
 * The catches and detected values are watch-hand catches: catches made by the
 * hand wearing the watch, not both-hands totals. All sample values are raw
 * milli-g integers as reported by the watch sensor.
 *
 * Summaries of the stored runs are cached, so each file is read once rather
 * than on every [listRecordings]. The first [listRecordings] and every
 * [exportAllZip] still read many files: call them off the main thread.
 *
 * Call [exportAllZip] to produce a zip of every run for analysis. The export
 * adds who juggled, which wrist wore the watch and which hand made the first
 * throw to each header, as juggler=<name>,hand=left|right,firstThrow=left|right.
 */
class RecordingRepository(private val recordingsDir: File) {
    companion object {
        private const val TAG = "RecordingRepository"
        private const val DIR_NAME = "recordings"
        const val SOURCE_WATCH = "watch"
        const val SOURCE_PHONE = "phone"
        const val HAND_LEFT = "left"
        const val HAND_RIGHT = "right"
        private val EXPORT_KEYS = setOf("juggler", "hand", "firstThrow")

        /**
         * The juggler's name as a header value: the characters that separate
         * header fields and lines are replaced by spaces.
         */
        fun headerSafe(name: String): String =
            name.replace(Regex("[,=#\\r\\n]"), " ").replace(Regex("\\s+"), " ").trim()

        /**
         * [header] with juggler, hand and firstThrow set, replacing any values
         * it already carries so a re-export under another name does not leave
         * both.
         */
        fun withJuggler(header: String, juggler: String, hand: String, firstThrow: String): String {
            val kept = header.removePrefix("#").trim()
                .split(",")
                .filter { part ->
                    val key = part.substringBefore("=").trim()
                    part.isNotBlank() && key !in EXPORT_KEYS
                }
            return "# " + (kept + "juggler=${headerSafe(juggler)}" + "hand=$hand" + "firstThrow=$firstThrow")
                .joinToString(",")
        }
    }

    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    // Summaries by file name, filled lazily by listRecordings. Guarded by `this`.
    private val summaryCache = mutableMapOf<String, RecordingSummary>()

    init {
        recordingsDir.mkdirs()
    }

    /** Identifier for a recording, also its file name. Derived from capture time. */
    private fun runIdFor(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp * 1000L))

    /**
     * Save one recording run to its own CSV file. Catches are watch-hand catches.
     *
     * One run per file, named by its run id, matching connectiq/data. detected
     * is stored as detectedAtCapture because it is what the detector counted at
     * the time rather than a property of the measurement, and goes stale
     * whenever the detector changes.
     */
    fun saveRecording(
        balls: Int,
        catches: Int,
        detected: Int,
        sampleRate: Int,
        timestamp: Long,
        accelX: List<Int>,
        accelY: List<Int>,
        accelZ: List<Int>,
        source: String,
    ): File? {
        if (accelX.isEmpty()) return null
        val n = minOf(accelX.size, accelY.size, accelZ.size)

        val runId = runIdFor(timestamp)
        val file = File(recordingsDir, "$runId.csv")

        return try {
            file.bufferedWriter().use { w ->
                w.write(
                    "# run=$runId,timestamp=$timestamp,balls=$balls,catches=$catches" +
                        ",sampleRate=$sampleRate,units=milli_g,source=$source" +
                        ",countMode=watch_hand,detectedAtCapture=$detected"
                )
                w.newLine()
                w.write("x,y,z")
                w.newLine()
                for (i in 0 until n) {
                    w.write("${accelX[i]},${accelY[i]},${accelZ[i]}")
                    w.newLine()
                }
            }
            Log.d(TAG, "Saved recording: ${file.name} ($n samples)")
            val summary = RecordingSummary(
                fileName = file.name,
                balls = balls,
                catches = catches,
                detected = detected,
                sampleRate = sampleRate,
                timestamp = timestamp,
                samples = n,
                source = source,
            )
            synchronized(this) { summaryCache[file.name] = summary }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recording", e)
            synchronized(this) { summaryCache.remove(file.name) }
            CrashlyticsUtils.recordException(e)
            null
        }
    }

    /** One stored recording, summarised from its header line. */
    data class RecordingSummary(
        val fileName: String,
        val balls: Int,
        val catches: Int,
        val detected: Int,
        val sampleRate: Int,
        val timestamp: Long,
        val samples: Int,
        val source: String,
    ) {
        val durationSeconds: Double
            get() = if (sampleRate > 0) samples.toDouble() / sampleRate else 0.0

        val fromWatch: Boolean
            get() = source == SOURCE_WATCH
    }

    /**
     * Summaries of every stored recording, newest first. Only files not yet
     * summarised are read; the rest come from the cache.
     */
    fun listRecordings(): List<RecordingSummary> {
        // The files are read outside the lock so a save never waits on a
        // first full scan. A save that lands meanwhile caches its own
        // summary, which wins over one read from a half-written file.
        val unread = synchronized(this) {
            val files = recordingsDir.listFiles()?.filter { it.extension == "csv" } ?: emptyList()
            summaryCache.keys.retainAll(files.mapTo(HashSet()) { it.name })
            files.filter { it.name !in summaryCache }
        }
        val read = unread.mapNotNull { readSummary(it) }
        return synchronized(this) {
            read.forEach { summary ->
                if (File(recordingsDir, summary.fileName).exists()) {
                    summaryCache.putIfAbsent(summary.fileName, summary)
                }
            }
            summaryCache.values.sortedByDescending { it.timestamp }
        }
    }

    /** One file's summary from its header line, or null if it has none. */
    private fun readSummary(file: File): RecordingSummary? {
        return try {
            var header: String? = null
            var samples = 0
            file.forEachLine { line ->
                when {
                    line.startsWith("#") -> if (header == null) header = line
                    line.isBlank() || line.startsWith("x,") -> Unit
                    else -> samples++
                }
            }
            val fields = (header ?: return null)
                .removePrefix("#")
                .trim()
                .split(",")
                .mapNotNull { part ->
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
                }
                .toMap()

            RecordingSummary(
                fileName = file.name,
                balls = fields["balls"]?.toIntOrNull() ?: return null,
                catches = fields["catches"]?.toIntOrNull() ?: 0,
                detected = (fields["detectedAtCapture"] ?: fields["detected"])
                    ?.toIntOrNull() ?: 0,
                sampleRate = fields["sampleRate"]?.toIntOrNull() ?: 0,
                timestamp = fields["timestamp"]?.toLongOrNull() ?: 0L,
                samples = samples,
                // Recordings written before source was stored have only the
                // sample rate to go on: the watch runs at 25 Hz.
                source = fields["source"]
                    ?: if ((fields["sampleRate"]?.toIntOrNull() ?: 0) <= 25) {
                        SOURCE_WATCH
                    } else {
                        SOURCE_PHONE
                    },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read recording ${file.name}", e)
            null
        }
    }

    /** Number of recording files stored. */
    fun recordingCount(): Int {
        return recordingsDir.listFiles()?.count { it.extension == "csv" } ?: 0
    }

    private fun csvFiles(): List<File> =
        recordingsDir.listFiles()
            ?.filter { it.extension == "csv" }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Read one recording, keeping only the x,y,z columns and tagging its header
     * with [juggler], [hand] and [firstThrow]. Runs captured during the abandoned gyroscope
     * experiment carry three further columns that the watch only ever filled
     * with zeros, and they have to be dropped so every exported run matches
     * the three-column corpus format.
     */
    private fun normalizedCsv(file: File, juggler: String, hand: String, firstThrow: String): String {
        val sb = StringBuilder()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val kept = if (line.startsWith("#")) {
                withJuggler(line, juggler, hand, firstThrow)
            } else {
                line.split(",").take(3).joinToString(",")
            }
            sb.append(kept).append('\n')
        }
        return sb.toString()
    }

    /**
     * Write every stored recording to [out] as a zip holding one CSV per run,
     * named and formatted to drop straight into connectiq/data. [juggler] is
     * who juggled, [hand] which wrist wore the watch and [firstThrow] which
     * hand threw first, each [HAND_LEFT] or [HAND_RIGHT].
     */
    fun exportAllZip(out: OutputStream, juggler: String, hand: String, firstThrow: String) {
        ZipOutputStream(out.buffered()).use { zip ->
            csvFiles().forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(normalizedCsv(file, juggler, hand, firstThrow).toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** Delete all stored recording files. */
    @Synchronized
    fun clearAll() {
        recordingsDir.listFiles()?.forEach { it.delete() }
        summaryCache.clear()
    }
}
