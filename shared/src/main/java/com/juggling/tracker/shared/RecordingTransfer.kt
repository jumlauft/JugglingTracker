package com.juggling.tracker.shared

/**
 * Puts one recorded run back together on the phone. The Garmin cannot send a
 * dictionary holding hundreds of numbers, so every watch sends a run as
 * `rec_start`, a series of `rec_chunk` parts, then `rec_end`. Only a complete
 * run comes out of [finish]; the caller writes it and only then acks
 * `rec_end`. A retry from the watch starts again with `rec_start`.
 *
 * Same rules as JugglingCore's RecordingTransfer in the Apple apps.
 */
class RecordingTransfer {
    /** A complete run: its id (capture time, epoch seconds) and samples in milli-g. */
    class Recording(
        val id: Long,
        val balls: Int,
        val catches: Int,
        val detected: Int,
        val sampleRate: Int,
        val x: List<Int>,
        val y: List<Int>,
        val z: List<Int>,
    )

    /** What [append] did with a chunk. */
    enum class Chunk {
        /** The next chunk of the run; its samples were added. */
        ADDED,

        /** A chunk already added, delivered again; the transport may repeat a message. */
        REPEATED,

        /** Not for the run in progress, or not readable. */
        IGNORED,

        /** A chunk was skipped, so the run would be corrupt and was dropped. */
        GAP,
    }

    private class Incoming(
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

    private var incoming: Incoming? = null

    /** The id of the run being received, if any. */
    val runId: Long? get() = incoming?.id

    /** Starts receiving the run a `rec_start` payload announces, in place of any earlier one. */
    fun start(payload: Map<String, Any?>) {
        val id = (payload["id"] as? Number)?.toLong() ?: return
        incoming = Incoming(
            id = id,
            balls = (payload["balls"] as? Number)?.toInt() ?: return,
            catches = (payload["catches"] as? Number)?.toInt() ?: return,
            detected = (payload["detected"] as? Number)?.toInt() ?: 0,
            sampleRate = (payload["sampleRate"] as? Number)?.toInt() ?: 25,
            totalSamples = (payload["samples"] as? Number)?.toInt() ?: 0,
            totalChunks = (payload["chunks"] as? Number)?.toInt() ?: 0,
        )
    }

    /** Adds one `rec_chunk` payload to the run in progress. */
    fun append(payload: Map<String, Any?>): Chunk {
        val run = incoming ?: return Chunk.IGNORED
        if ((payload["id"] as? Number)?.toLong() != run.id) return Chunk.IGNORED
        val index = (payload["i"] as? Number)?.toInt() ?: return Chunk.IGNORED
        if (index < run.nextChunk) return Chunk.REPEATED
        if (index > run.nextChunk) {
            // Dropped rather than written as bad training data: rec_end then
            // goes unacked and the watch offers to send the run again.
            incoming = null
            return Chunk.GAP
        }
        val xs = samples(payload["x"]) ?: return Chunk.IGNORED
        val ys = samples(payload["y"]) ?: return Chunk.IGNORED
        val zs = samples(payload["z"]) ?: return Chunk.IGNORED
        run.x.addAll(xs)
        run.y.addAll(ys)
        run.z.addAll(zs)
        run.nextChunk = index + 1
        return Chunk.ADDED
    }

    /**
     * Ends the run a `rec_end` payload closes. Returns it when every chunk and
     * sample arrived, else null; either way the transfer is over.
     */
    fun finish(payload: Map<String, Any?>): Recording? {
        val run = incoming ?: return null
        incoming = null
        if ((payload["id"] as? Number)?.toLong() != run.id) return null
        if (run.nextChunk != run.totalChunks) return null
        if (run.x.size != run.totalSamples) return null
        return Recording(run.id, run.balls, run.catches, run.detected, run.sampleRate, run.x, run.y, run.z)
    }

    private fun samples(value: Any?): List<Int>? =
        (value as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
}
