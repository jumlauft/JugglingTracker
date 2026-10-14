package com.juggling.tracker.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSummaryTest {
    private val payload = mapOf<String, Any?>(
        "type" to "session", "countMode" to "watch_hand", "balls" to 3, "timestamp" to 1_700_000_000L,
        "durationSeconds" to 95L, "runDurationsMillis" to listOf(6000, -5), "runs" to listOf(12, 30),
        "shapeConsistency" to 83,
    )

    @Test
    fun `a watch session payload becomes its summary`() {
        val session = sessionFromWatchPayload(payload)!!

        assertEquals(1_700_000_000_000L, session.timestamp)
        assertEquals(3, session.ballCount)
        assertEquals(listOf(12, 30), session.runHistory)
        assertEquals(21.0, session.avgThrows, 0.0)
        assertEquals(30, session.bestRun)
        assertEquals(95L, session.durationSeconds)
        assertEquals(listOf(6000L, 0L), session.runDurationsMillis)
        assertEquals(83, session.shapeConsistency)
    }

    @Test
    fun `run durations are one per run`() {
        val session = sessionFromWatchPayload(payload + ("runDurationsMillis" to listOf(1, 2, 3)))!!
        assertEquals(listOf(1L, 2L), session.runDurationsMillis)

        val none = sessionFromWatchPayload(payload - "runDurationsMillis")!!
        assertEquals(listOf(0L, 0L), none.runDurationsMillis)
    }

    @Test
    fun `a Regularity outside 0 to 100 is dropped`() {
        assertNull(sessionFromWatchPayload(payload + ("shapeConsistency" to 101))!!.shapeConsistency)
        assertNull(sessionFromWatchPayload(payload - "shapeConsistency")!!.shapeConsistency)
    }

    @Test
    fun `a payload without balls, timestamp or runs holds no session`() {
        assertNull(sessionFromWatchPayload(payload - "balls"))
        assertNull(sessionFromWatchPayload(payload - "timestamp"))
        assertNull(sessionFromWatchPayload(payload + ("runs" to emptyList<Int>())))
    }

    @Test
    fun `a negative duration counts as zero`() {
        assertEquals(0L, sessionFromWatchPayload(payload + ("durationSeconds" to -3L))!!.durationSeconds)
    }
}
