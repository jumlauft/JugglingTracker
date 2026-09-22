package com.juggling.tracker.data

import android.content.Context
import android.util.Log
import com.juggling.tracker.util.CrashlyticsUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stores raw accelerometer recordings as individual CSV files in the app's
 * internal files directory under recordings/. Each run produces one file:
 *
 *   recordings/rec_<timestamp>_<balls>b_<catches>c.csv
 *
 * The CSV format is:
 *   # balls=3,catches=12,detected=10,sampleRate=25,timestamp=1717430000,countMode=watch_hand
 *   x,y,z
 *   -123,456,987
 *   ...
 *
 * The catches and detected values are watch-hand catches: catches made by the
 * hand wearing the watch, not both-hands totals. All sample values are raw
 * milli-g integers as reported by the watch sensor.
 * Call [exportAllCsv] to produce a single merged CSV suitable for analysis.
 */
class RecordingRepository(private val recordingsDir: File) {
    companion object {
        private const val TAG = "RecordingRepository"
        private const val DIR_NAME = "recordings"
    }

    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    init {
        recordingsDir.mkdirs()
    }

    /** Save one recording run to a CSV file. Catches are watch-hand catches. */
    fun saveRecording(
        balls: Int,
        catches: Int,
        detected: Int,
        sampleRate: Int,
        timestamp: Long,
        accelX: List<Int>,
        accelY: List<Int>,
        accelZ: List<Int>,
    ): File? {
        if (accelX.isEmpty()) return null
        val n = minOf(accelX.size, accelY.size, accelZ.size)

        val fileName = "rec_${timestamp}_${balls}b_${catches}c.csv"
        val file = File(recordingsDir, fileName)

        return try {
            file.bufferedWriter().use { w ->
                w.write("# balls=$balls,catches=$catches,detected=$detected,sampleRate=$sampleRate,timestamp=$timestamp,countMode=watch_hand")
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
    ) {
        val durationSeconds: Double
            get() = if (sampleRate > 0) samples.toDouble() / sampleRate else 0.0

        /** The watch samples at 25 Hz, the phone at 200 Hz. */
        val fromWatch: Boolean
            get() = sampleRate <= 25
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
                val fields = (header ?: return@mapNotNull null)
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
                    balls = fields["balls"]?.toIntOrNull() ?: return@mapNotNull null,
                    catches = fields["catches"]?.toIntOrNull() ?: 0,
                    detected = fields["detected"]?.toIntOrNull() ?: 0,
                    sampleRate = fields["sampleRate"]?.toIntOrNull() ?: 0,
                    timestamp = fields["timestamp"]?.toLongOrNull() ?: 0L,
                    samples = samples,
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

    /**
     * Merge all stored recordings into one CSV string for export.
     * Each run is separated by a header comment line.
     */
    fun exportAllCsv(): String {
        val files = recordingsDir.listFiles()
            ?.filter { it.extension == "csv" }
            ?.sortedBy { it.name }
            ?: return ""

        if (files.isEmpty()) return ""

        val sb = StringBuilder()
        files.forEach { file ->
            sb.append(file.readText())
            // Ensure trailing newline between files
            if (!sb.endsWith('\n')) sb.append('\n')
        }
        return sb.toString()
    }

    /** Delete all stored recording files. */
    fun clearAll() {
        recordingsDir.listFiles()?.forEach { it.delete() }
    }
}
