package com.juggling.tracker.shared

import org.json.JSONObject
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
    @Test
    fun `a session from the wear watch decodes to the garmin payload shape`() {
        val json = """
            {"type":"session","countMode":"watch_hand","balls":5,"timestamp":1700000000,
             "durationSeconds":42,"runDurationsMillis":[1200,800],"runs":[12,7]}
        """.trimIndent()

        val payload = WatchProtocol.decode(json.toByteArray())!!

        assertEquals("session", payload["type"])
        assertEquals(5, (payload["balls"] as Number).toInt())
        assertEquals(1_700_000_000L, (payload["timestamp"] as Number).toLong())
        assertEquals(listOf(12, 7), (payload["runs"] as List<*>).map { (it as Number).toInt() })
        assertEquals(listOf(1200L, 800L), (payload["runDurationsMillis"] as List<*>).map { (it as Number).toLong() })
    }

    @Test
    fun `a recording chunk keeps its sample arrays`() {
        val json = """{"type":"rec_chunk","id":1700000000,"i":0,"x":[1,2],"y":[3,4],"z":[5,6]}"""
        val payload = WatchProtocol.decode(json.toByteArray())!!
        assertEquals(listOf(1, 2), payload["x"])
        assertEquals(0, payload["i"])
    }

    @Test
    fun `a full 1000-sample wear chunk keeps every sample`() {
        val values = (0 until 1000).map { -16_000 + it }
        val array = values.joinToString(",", "[", "]")
        val json = """{"type":"rec_chunk","id":1700000000,"i":2,"x":$array,"y":$array,"z":$array}"""
        val payload = WatchProtocol.decode(json.toByteArray())!!
        assertEquals(values, payload["x"])
        assertEquals(values, payload["z"])
    }

    @Test
    fun `the ack echoes the timestamp when there is one`() {
        val withTs = JSONObject(String(WatchProtocol.encodeAck(1_700_000_000L)))
        assertEquals("ack", withTs.getString("type"))
        assertEquals(1_700_000_000L, withTs.getLong("timestamp"))

        val without = JSONObject(String(WatchProtocol.encodeAck(null)))
        assertEquals(false, without.has("timestamp"))
    }

    @Test
    fun `the ack decodes back on the watch`() {
        val ack = WatchProtocol.decode(WatchProtocol.encodeAck(1_700_000_000L))!!
        assertEquals("ack", ack["type"])
        assertEquals(1_700_000_000L, (ack["timestamp"] as Number).toLong())
    }
}
