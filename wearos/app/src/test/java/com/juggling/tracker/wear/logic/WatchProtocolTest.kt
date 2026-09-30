package com.juggling.tracker.wear.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchProtocolTest {
    @Test
    fun `a session payload survives the round trip`() {
        val payload = mapOf(
            "type" to "session",
            "countMode" to "watch_hand",
            "balls" to 5,
            "timestamp" to 1_700_000_000L,
            "durationSeconds" to 42L,
            "runDurationsMillis" to listOf(1200L, 800L),
            "runs" to listOf(12, 7),
        )
        val decoded = WatchProtocol.decode(WatchProtocol.encode(payload))!!
        assertEquals("session", decoded["type"])
        assertEquals(5, (decoded["balls"] as Number).toInt())
        assertEquals(1_700_000_000L, (decoded["timestamp"] as Number).toLong())
        assertEquals(listOf(12, 7), (decoded["runs"] as List<*>).map { (it as Number).toInt() })
        assertEquals(listOf(1200L, 800L), (decoded["runDurationsMillis"] as List<*>).map { (it as Number).toLong() })
    }

    @Test
    fun `garbage decodes to null`() {
        assertNull(WatchProtocol.decode("not json".toByteArray()))
        assertNull(WatchProtocol.decode("[1,2]".toByteArray()))
    }
}
