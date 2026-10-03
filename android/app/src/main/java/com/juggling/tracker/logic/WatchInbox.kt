package com.juggling.tracker.logic

import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.model.SessionSummary
import com.juggling.tracker.model.normalizeRunDurations
import com.juggling.tracker.model.parseShapeConsistency
import com.juggling.tracker.model.summarizeSession
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where every message from either watch lands. It stores what the message
 * carries and decides whether the watch gets its `ack`.
 *
 * It lives as long as the app process, not a screen: the Wear OS listener
 * service and the Garmin link both hand their messages here, so a watch can
 * sync while no screen of the phone app is open, and a screen being
 * recreated cannot drop a transfer halfway. Screens follow along through
 * [addListener]. Call it from the main thread.
 *
 * The ack goes back only once the data is stored. A watch that gets no ack
 * keeps its data and offers to retry, so a run that arrived incomplete, or a
 * payload that could not be read, must never be acknowledged.
 */
class WatchInbox(
    private val repository: SessionRepository? = null,
    private val recordingRepository: RecordingRepository? = null,
    private val analytics: FirebaseAnalytics? = null,
    /** Who is juggling now; each recorded run is saved with it. */
    private val currentJuggler: () -> RecordingRepository.Juggler? = { null },
) {
    companion object {
        private const val TAG = "WatchInbox"
    }

    enum class Source(val analyticsName: String) {
        GARMIN("garmin_watch"),
        WEAR_OS("wear_os_watch"),
    }

    /** What a screen showing the phone's data needs to hear about. */
    sealed class Event {
        /**
         * A message from [source] arrived. [more] is true while the transfer
         * it belongs to is still going (a run's header or one of its chunks),
         * false once its last message is in.
         */
        data class Receiving(val source: Source, val more: Boolean = false) : Event()

        /** [session] was stored, replacing an earlier copy under its timestamp if there was one. */
        data class SessionStored(val session: SessionSummary) : Event()

        /** A recorded run was written. */
        object RecordingStored : Event()
    }

    /** The phone's answer to one message: an `ack` carrying [timestamp]. */
    data class Ack(val timestamp: Long?)

    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    fun addListener(listener: (Event) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Event) -> Unit) {
        listeners.remove(listener)
    }

    private fun publish(event: Event) {
        listeners.forEach { it(event) }
    }

    /**
     * Handles one message from a watch and returns the ack to send back, or
     * null when none may go: run headers and chunks are never acked, and
     * neither is anything that was not stored.
     */
    fun receive(payload: Map<*, *>, source: Source): Ack? {
        val type = payload["type"] as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val typed = payload as Map<String, Any>

        return when (type) {
            // The watch chains chunks on delivery and waits for an ack only
            // at the end. Each one still tells the screen the transfer is
            // alive, so the card stays on "receiving" until rec_end.
            "rec_chunk" -> {
                appendRecordingChunk(typed)
                publish(Event.Receiving(source, more = true))
                null
            }
            "rec_start" -> {
                publish(Event.Receiving(source, more = true))
                startRecordingTransfer(typed)
                null
            }
            "session" -> {
                publish(Event.Receiving(source))
                importSession(typed, source)?.let { Ack(ackTimestamp(typed)) }
            }
            "rec_end" -> {
                publish(Event.Receiving(source))
                if (finishRecordingTransfer(typed, source)) Ack(ackTimestamp(typed)) else null
            }
            else -> null
        }
    }

    // A session's ack carries its timestamp and a run's its id, which is what
    // each watch screen checks the ack against.
    private fun ackTimestamp(payload: Map<String, Any>): Long? =
        (payload["timestamp"] as? Number)?.toLong() ?: (payload["id"] as? Number)?.toLong()

    // ── Sessions ────────────────────────────────────────────────────────

    /**
     * Stores one finished session. Payload shape: { type: "session",
     * countMode: "watch_hand", balls: Int, timestamp: Long (epoch s),
     * durationSeconds: Long, runDurationsMillis: List<Number>,
     * runs: List<Number>, shapeConsistency: Number (optional, whole percent) }.
     * Runs are watch-hand catch counts; the phone only records what it receives.
     *
     * Returns the stored session, or null when the payload holds no session.
     */
    fun importSession(payload: Map<String, Any>, source: Source): SessionSummary? {
        val balls = (payload["balls"] as? Number)?.toInt() ?: return null
        // The watch sends epoch seconds; convert to milliseconds for Java Date APIs.
        val timestamp = ((payload["timestamp"] as? Number)?.toLong() ?: return null) * 1000L
        val durationSeconds = ((payload["durationSeconds"] as? Number)?.toLong() ?: 0L).coerceAtLeast(0L)
        val runsRaw = payload["runs"] as? List<*> ?: return null
        val runs = runsRaw.mapNotNull { (it as? Number)?.toInt() }
        if (runs.isEmpty()) return null

        analytics?.logEvent("import_session", Bundle().apply {
            putInt("ball_count", balls)
            putInt("run_count", runs.size)
            putInt("total_throws", runs.sum())
            putString("source", source.analyticsName)
        })

        val runDurationsMillis = normalizeRunDurations(runs.size, parseLongList(payload["runDurationsMillis"]))
        val shapeConsistency = parseShapeConsistency(payload["shapeConsistency"])

        val session = repository?.importSession(balls, timestamp, runs, durationSeconds, runDurationsMillis, shapeConsistency)
            ?: summarizeSession(timestamp, balls, runs, durationSeconds, runDurationsMillis, shapeConsistency)
        publish(Event.SessionStored(session))
        return session
    }

    private fun parseLongList(value: Any?): List<Long> {
        val raw = value as? List<*> ?: return emptyList()
        return raw.mapNotNull { (it as? Number)?.toLong()?.coerceAtLeast(0L) }
    }

    // ── Recorded runs ───────────────────────────────────────────────────
    //
    // The Garmin cannot send a dictionary holding hundreds of numbers, so a
    // run arrives as rec_start, a series of rec_chunk parts, then rec_end.
    // Only rec_end is acknowledged, after the run has been written. A retry
    // from the watch starts again with rec_start.

    private class IncomingRecording(
        val id: Long,
        val balls: Int,
        val catches: Int,
        val detected: Int,
        val sampleRate: Int,
        val totalSamples: Int,
        val totalChunks: Int,
    ) {
        val x = mutableListOf<Int>()
        val y = mutableListOf<Int>()
        val z = mutableListOf<Int>()
        var nextChunk = 0
    }

    private var incoming: IncomingRecording? = null

    fun startRecordingTransfer(payload: Map<String, Any>) {
        val id = (payload["id"] as? Number)?.toLong() ?: return
        incoming = IncomingRecording(
            id = id,
            balls = (payload["balls"] as? Number)?.toInt() ?: return,
            catches = (payload["catches"] as? Number)?.toInt() ?: return,
            detected = (payload["detected"] as? Number)?.toInt() ?: 0,
            sampleRate = (payload["sampleRate"] as? Number)?.toInt() ?: 25,
            totalSamples = (payload["samples"] as? Number)?.toInt() ?: 0,
            totalChunks = (payload["chunks"] as? Number)?.toInt() ?: 0,
        )
    }

    fun appendRecordingChunk(payload: Map<String, Any>) {
        val run = incoming ?: return
        if ((payload["id"] as? Number)?.toLong() != run.id) return

        val index = (payload["i"] as? Number)?.toInt() ?: return
        if (index < run.nextChunk) {
            // Already have this one. The transport can deliver a message more
            // than once, which must not be mistaken for corruption.
            return
        }
        if (index > run.nextChunk) {
            // A chunk was lost; the run would be corrupt, so drop it rather
            // than write bad training data. rec_end then goes unacked and
            // the watch offers to send the run again.
            Log.w(TAG, "Run ${run.id}: chunk $index arrived, expected ${run.nextChunk}; dropping the run")
            incoming = null
            return
        }

        val xs = (payload["x"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: return
        val ys = (payload["y"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: return
        val zs = (payload["z"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: return

        run.x.addAll(xs)
        run.y.addAll(ys)
        run.z.addAll(zs)
        run.nextChunk = index + 1
    }

    /** Returns true when a complete run was written. */
    fun finishRecordingTransfer(payload: Map<String, Any>, source: Source): Boolean {
        val run = incoming ?: return false
        incoming = null
        if ((payload["id"] as? Number)?.toLong() != run.id) return false
        if (run.nextChunk != run.totalChunks) return false
        if (run.x.size != run.totalSamples) return false

        analytics?.logEvent("import_recording", Bundle().apply {
            putInt("ball_count", run.balls)
            putInt("catches", run.catches)
            putString("source", source.analyticsName)
        })

        // A run with no samples has nothing to write, so it counts as stored.
        val written = recordingRepository == null || run.x.isEmpty() ||
            recordingRepository.saveRecording(
                balls = run.balls,
                catches = run.catches,
                detected = run.detected,
                sampleRate = run.sampleRate,
                timestamp = run.id,
                accelX = run.x,
                accelY = run.y,
                accelZ = run.z,
                source = RecordingRepository.SOURCE_WATCH,
                juggler = currentJuggler(),
            ) != null
        if (!written) return false
        publish(Event.RecordingStored)
        return true
    }
}
