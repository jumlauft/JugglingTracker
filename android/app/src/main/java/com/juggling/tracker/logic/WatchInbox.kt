package com.juggling.tracker.logic

import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.juggling.tracker.data.RecordingRepository
import com.juggling.tracker.data.SessionRepository
import com.juggling.tracker.shared.Juggler
import com.juggling.tracker.shared.RecordingTransfer
import com.juggling.tracker.shared.SessionSummary
import com.juggling.tracker.shared.sessionFromWatchPayload
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
    private val currentJuggler: () -> Juggler? = { null },
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
     * Stores one finished session (payload shape at [sessionFromWatchPayload]).
     * Runs are watch-hand catch counts; the phone only records what it receives.
     *
     * Returns the stored session, or null when the payload holds no session
     * or it could not be written; then no ack may go.
     */
    fun importSession(payload: Map<String, Any>, source: Source): SessionSummary? {
        val parsed = sessionFromWatchPayload(payload) ?: return null

        analytics?.logEvent("import_session", Bundle().apply {
            putInt("ball_count", parsed.ballCount)
            putInt("run_count", parsed.runCount)
            putInt("total_throws", parsed.totalThrows)
            putString("source", source.analyticsName)
        })

        val session = if (repository != null) {
            repository.importSession(
                parsed.ballCount,
                parsed.timestamp,
                parsed.runHistory,
                parsed.durationSeconds,
                parsed.runDurationsMillis,
                parsed.shapeConsistency,
            ) ?: return null
        } else {
            parsed
        }
        publish(Event.SessionStored(session))
        return session
    }

    // ── Recorded runs ───────────────────────────────────────────────────
    //
    // A run arrives as rec_start, rec_chunk parts and rec_end, put back
    // together by the shared RecordingTransfer. Only rec_end is acknowledged,
    // after the run has been written.

    private val transfer = RecordingTransfer()

    fun startRecordingTransfer(payload: Map<String, Any>) {
        transfer.start(payload)
    }

    fun appendRecordingChunk(payload: Map<String, Any>) {
        val runId = transfer.runId
        if (transfer.append(payload) == RecordingTransfer.Chunk.GAP) {
            Log.w(TAG, "Run $runId: chunk ${payload["i"]} arrived out of order; dropping the run")
        }
    }

    /** Returns true when a complete run was written. */
    fun finishRecordingTransfer(payload: Map<String, Any>, source: Source): Boolean {
        val run = transfer.finish(payload) ?: return false

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
