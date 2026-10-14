package com.juggling.tracker.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTransferTest {
    private val transfer = RecordingTransfer()

    private fun start(id: Long = 7L, samples: Int = 4, chunks: Int = 2) = transfer.start(
        mapOf("type" to "rec_start", "id" to id, "balls" to 3, "catches" to 20, "detected" to 19,
            "sampleRate" to 25, "samples" to samples, "chunks" to chunks),
    )

    private fun chunk(i: Int, id: Long = 7L) = transfer.append(
        mapOf("type" to "rec_chunk", "id" to id, "i" to i,
            "x" to listOf(i * 10, i * 10 + 1), "y" to listOf(0, 0), "z" to listOf(1000, 1000)),
    )

    private fun end(id: Long = 7L) = transfer.finish(mapOf("type" to "rec_end", "id" to id))

    @Test
    fun `a complete run comes out whole`() {
        start()
        assertEquals(RecordingTransfer.Chunk.ADDED, chunk(0))
        assertEquals(RecordingTransfer.Chunk.ADDED, chunk(1))

        assertNotNull(end())
        // The transfer is over once rec_end is handled.
        assertNull(end())
    }

    @Test
    fun `the finished run carries its header values and samples in order`() {
        start()
        chunk(0)
        chunk(1)

        val run = end()!!
        assertEquals(7L, run.id)
        assertEquals(3, run.balls)
        assertEquals(20, run.catches)
        assertEquals(19, run.detected)
        assertEquals(25, run.sampleRate)
        assertEquals(listOf(0, 1, 10, 11), run.x)
    }

    @Test
    fun `a repeated chunk is not added twice`() {
        start()
        chunk(0)
        assertEquals(RecordingTransfer.Chunk.REPEATED, chunk(0))
        chunk(1)

        assertEquals(4, end()!!.x.size)
    }

    @Test
    fun `a lost chunk drops the run`() {
        start()
        chunk(0)

        assertEquals(RecordingTransfer.Chunk.GAP, chunk(2))
        assertNull(transfer.runId)
        assertNull(end())
    }

    @Test
    fun `a chunk of another run is ignored`() {
        start()
        assertEquals(RecordingTransfer.Chunk.IGNORED, chunk(0, id = 8L))
    }

    @Test
    fun `a run missing chunks or samples does not come out`() {
        start(samples = 4, chunks = 3)
        chunk(0)
        chunk(1)
        assertNull(end())

        start(samples = 5, chunks = 2)
        chunk(0)
        chunk(1)
        assertNull(end())
    }

    @Test
    fun `rec_end for another run ends the transfer without a run`() {
        start()
        chunk(0)
        chunk(1)

        assertNull(end(id = 8L))
        assertNull(end())
    }
}
