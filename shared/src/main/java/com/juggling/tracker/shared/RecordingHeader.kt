package com.juggling.tracker.shared

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Who juggled a run: their [name], the wrist wearing the watch or phone
 * ([hand]) and the hand that made the first throw ([firstThrow]), each
 * [RecordingHeader.HAND_LEFT] or [RecordingHeader.HAND_RIGHT].
 */
data class Juggler(val name: String, val hand: String, val firstThrow: String) {
    fun headerFields(): List<String> =
        listOf("juggler=${RecordingHeader.headerSafe(name)}", "hand=$hand", "firstThrow=$firstThrow")
}

/**
 * The first line of a recording CSV, which both phones write and the corpus
 * in connectiq/data uses:
 *
 *   # run=20260922_144802,timestamp=1790081282,balls=3,catches=89,
 *   sampleRate=25,units=milli_g,source=watch,countMode=watch_hand,detectedAtCapture=88
 *
 * (one line), followed by juggler=<name>,hand=left|right,firstThrow=left|right
 * when the juggler is known. Same rules as JugglingCore's RecordingHeader in
 * the Apple apps.
 */
object RecordingHeader {
    const val HAND_LEFT = "left"
    const val HAND_RIGHT = "right"
    private val JUGGLER_KEYS = setOf("juggler", "hand", "firstThrow")

    /**
     * The run id and file name stem: the capture time ([timestamp], epoch
     * seconds) as yyyyMMdd_HHmmss in [timeZone].
     */
    fun runId(timestamp: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .apply { this.timeZone = timeZone }
            .format(Date(timestamp * 1000L))

    /**
     * The header line of one run. [detected] is stored as detectedAtCapture:
     * what the detector counted at the time, which goes stale whenever the
     * detector changes.
     */
    fun line(
        runId: String,
        timestamp: Long,
        balls: Int,
        catches: Int,
        sampleRate: Int,
        source: String,
        detected: Int,
        juggler: Juggler?,
    ): String =
        "# run=$runId,timestamp=$timestamp,balls=$balls,catches=$catches" +
            ",sampleRate=$sampleRate,units=milli_g,source=$source" +
            ",countMode=watch_hand,detectedAtCapture=$detected" +
            (juggler?.headerFields()?.joinToString(",", prefix = ",") ?: "")

    /**
     * The juggler's name as a header value: the characters that separate
     * header fields and lines are replaced by spaces.
     */
    fun headerSafe(name: String): String =
        name.replace(Regex("[,=#\\r\\n]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * [header] with juggler, hand and firstThrow set, replacing any values it
     * already carries so a re-export under another name does not leave both.
     */
    fun withJuggler(header: String, juggler: Juggler): String {
        val kept = header.removePrefix("#").trim()
            .split(",")
            .filter { part ->
                val key = part.substringBefore("=").trim()
                part.isNotBlank() && key !in JUGGLER_KEYS
            }
        return "# " + (kept + juggler.headerFields()).joinToString(",")
    }

    /** The header's key=value fields. */
    fun fields(header: String): Map<String, String> =
        header.removePrefix("#")
            .trim()
            .split(",")
            .mapNotNull { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
            }
            .toMap()

    /** The juggler a header names, or null unless it carries all three fields. */
    fun jugglerOf(fields: Map<String, String>): Juggler? {
        val name = fields["juggler"]?.takeIf { it.isNotBlank() } ?: return null
        val hand = fields["hand"]?.takeIf { it == HAND_LEFT || it == HAND_RIGHT } ?: return null
        val firstThrow = fields["firstThrow"]?.takeIf { it == HAND_LEFT || it == HAND_RIGHT } ?: return null
        return Juggler(name, hand, firstThrow)
    }
}
