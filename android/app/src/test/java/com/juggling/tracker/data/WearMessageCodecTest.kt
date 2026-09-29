package com.juggling.tracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearMessageCodecTest {

    @Test
    fun `a session from the wear watch decodes to the garmin payload shape`() {
        val json = """
            {"type":"session","countMode":"watch_hand","balls":5,"timestamp":1700000000,
             "durationSeconds":42,"runDurationsMillis":[1200,800],"runs":[12,7]}
        """.trimIndent()

        val payload = WearMessageCodec.decode(json.toByteArray())!!

        assertEquals("session", payload["type"])
        assertEquals(5, (payload["balls"] as Number).toInt())
        assertEquals(1_700_000_000L, (payload["timestamp"] as Number).toLong())
        assertEquals(listOf(12, 7), (payload["runs"] as List<*>).map { (it as Number).toInt() })
        assertEquals(listOf(1200L, 800L), (payload["runDurationsMillis"] as List<*>).map { (it as Number).toLong() })
    }

    @Test
    fun `a recording chunk keeps its sample arrays`() {
        val json = """{"type":"rec_chunk","id":1700000000,"i":0,"x":[1,2],"y":[3,4],"z":[5,6]}"""
        val payload = WearMessageCodec.decode(json.toByteArray())!!
        assertEquals(listOf(1, 2), payload["x"])
        assertEquals(0, payload["i"])
    }

    @Test
    fun `malformed messages decode to null`() {
        assertNull(WearMessageCodec.decode("not json".toByteArray()))
        assertNull(WearMessageCodec.decode("[1,2,3]".toByteArray()))
    }

    @Test
    fun `the ack echoes the timestamp when there is one`() {
        val withTs = JSONObject(String(WearMessageCodec.encodeAck(1_700_000_000L)))
        assertEquals("ack", withTs.getString("type"))
        assertEquals(1_700_000_000L, withTs.getLong("timestamp"))

        val without = JSONObject(String(WearMessageCodec.encodeAck(null)))
        assertEquals(false, without.has("timestamp"))
    }
}
