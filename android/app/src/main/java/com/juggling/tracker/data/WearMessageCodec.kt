package com.juggling.tracker.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The Wear OS watch app's messages over the Data Layer. They carry the same
 * dictionaries the Garmin watch transmits (`session`, `rec_start`,
 * `rec_chunk`, `rec_end`) as UTF-8 JSON, so both watches share one import
 * path in MainActivity. The watch side is `wearos/.../logic/WatchProtocol.kt`;
 * keep the paths and capability names equal.
 */
object WearMessageCodec {
    private const val TAG = "WearMessageCodec"

    /** Watch to phone. */
    const val PATH_WATCH_TO_PHONE = "/juggling_tracker/watch_message"

    /** Phone to watch: the `ack`. */
    const val PATH_PHONE_TO_WATCH = "/juggling_tracker/phone_message"

    /** Returns the payload as a map with lists for arrays, or null if it is not a JSON object. */
    fun decode(bytes: ByteArray): Map<String, Any>? = try {
        toMap(JSONObject(String(bytes, Charsets.UTF_8)))
    } catch (e: JSONException) {
        Log.w(TAG, "Ignoring a watch message that is not a JSON object", e)
        null
    }

    fun encodeAck(timestamp: Long?): ByteArray {
        val ack = JSONObject().put("type", "ack")
        if (timestamp != null) ack.put("timestamp", timestamp)
        return ack.toString().toByteArray(Charsets.UTF_8)
    }

    private fun toMap(json: JSONObject): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            toValue(json.get(key))?.let { map[key] = it }
        }
        return map
    }

    private fun toValue(value: Any?): Any? = when (value) {
        is JSONObject -> toMap(value)
        is JSONArray -> (0 until value.length()).mapNotNull { toValue(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }
}
