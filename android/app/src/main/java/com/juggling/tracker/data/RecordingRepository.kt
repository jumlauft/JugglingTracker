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
 * When the juggler is known at save time, the header also ends with who
 * juggled, which wrist wore the watch and which hand made the first throw, as
 * juggler=<name>,hand=left|right,firstThrow=left|right. Call [exportAllZip] to
 * produce a zip of every run for analysis; it keeps each run's own juggler and
 * tags only the runs without one.
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
        fun withJuggler(header: String, juggler: Juggler): String {
            val kept = header.removePrefix("#").trim()
                .split(",")
                .filter { part ->
                    val key = part.substringBefore("=").trim()
                    part.isNotBlank() && key !in EXPORT_KEYS
                }
            return "# " + (kept + juggler.headerFields()).joinToString(",")
        }

        /** The header's key=value fields. */
        private fun headerFields(header: String): Map<String, String> =
            header.removePrefix("#")
                .trim()
                .split(",")
                .mapNotNull { part ->
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
                }
                .toMap()

        /** The juggler a header names, or null unless it carries all three fields. */
        private fun jugglerOf(fields: Map<String, String>): Juggler? {
            val name = fields["juggler"]?.takeIf { it.isNotBlank() } ?: return null
            val hand = fields["hand"]?.takeIf { it == HAND_LEFT || it == HAND_RIGHT } ?: return null
            val firstThrow = fields["firstThrow"]?.takeIf { it == HAND_LEFT || it == HAND_RIGHT } ?: return null
            return Juggler(name, hand, firstThrow)
        }
    }

    /**
     * Who juggled a run: their [name], the wrist wearing the watch ([hand]) and
     * the hand that made the first throw ([firstThrow]), each [HAND_LEFT] or
     * [HAND_RIGHT].
     */
    data class Juggler(val name: String, val hand: String, val firstThrow: String) {
        fun headerFields(): List<String> =
            listOf("juggler=${headerSafe(name)}", "hand=$hand", "firstThrow=$firstThrow")
    }

    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

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
     * whenever the detector changes. [juggler], when known, is stored with the
     * run so a later export keeps it even after someone else has juggled.
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
        juggler: Juggler? = null,
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
                        ",countMode=watch_hand,detectedAtCapture=$detected" +
                        (juggler?.headerFields()?.joinToString(",", prefix = ",") ?: "")
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
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recording", e)
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
        /** Who juggled, or null for runs saved before the juggler was stored. */
        val juggler: Juggler? = null,
    ) {
        val durationSeconds: Double
            get() = if (sampleRate > 0) samples.toDouble() / sampleRate else 0.0

        val fromWatch: Boolean
            get() = source == SOURCE_WATCH
    }

    /** Summaries of every stored recording, newest first. */
    fun listRecordings(): List<RecordingSummary> {
        val files = recordingsDir.listFiles()?.filter { it.extension == "csv" } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                var header: String? = null
                var samples = 0
                file.forEachLine { line ->
                    when {
                        line.startsWith("#") -> if (header == null) header = line
                        line.isBlank() || line.startsWith("x,") -> Unit
                        else -> samples++
                    }
                }
                val fields = headerFields(header ?: return@mapNotNull null)

                RecordingSummary(
                    fileName = file.name,
                    balls = fields["balls"]?.toIntOrNull() ?: return@mapNotNull null,
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
                    juggler = jugglerOf(fields),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read recording ${file.name}", e)
                null
            }
        }.sortedByDescending { it.timestamp }
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
     * Read one recording, keeping only the x,y,z columns. A header that names
     * its juggler keeps them; any other is tagged with [fallback], when given.
     * Runs captured during the abandoned gyroscope experiment carry three
     * further columns that the watch only ever filled with zeros, and they have
     * to be dropped so every exported run matches the three-column corpus format.
     */
    private fun normalizedCsv(file: File, fallback: Juggler?): String {
        val sb = StringBuilder()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val kept = if (line.startsWith("#")) {
                val own = jugglerOf(headerFields(line))
                when {
                    own != null -> withJuggler(line, own)
                    fallback != null -> withJuggler(line, fallback)
                    else -> line
                }
            } else {
                line.split(",").take(3).joinToString(",")
            }
            sb.append(kept).append('\n')
        }
        return sb.toString()
    }

    /**
     * Write every stored recording to [out] as a zip holding one CSV per run,
     * named and formatted to drop straight into connectiq/data. Each run keeps
     * the juggler stored with it; runs saved without one are tagged with
     * [fallback].
     */
    fun exportAllZip(out: OutputStream, fallback: Juggler?) {
        ZipOutputStream(out.buffered()).use { zip ->
            csvFiles().forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(normalizedCsv(file, fallback).toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** Delete all stored recording files. */
    fun clearAll() {
        recordingsDir.listFiles()?.forEach { it.delete() }
    }
}
