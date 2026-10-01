package com.juggling.tracker.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * The messages between the Wear OS watch app and the phone app over the Wear
 * OS Data Layer, used by both sides. The payloads are the same dictionaries the
 * Garmin app transmits (SYNC-1, REC-5: `session`, `rec_start`, `rec_chunk`,
 * `rec_end`), carried as UTF-8 JSON, so the phone handles both watches with one
 * code path. The Garmin app speaks this through Connect IQ instead and is not
 * covered here.
 *
 * org.json is the Android platform's copy at run time; it is only a
 * compile-time dependency of this module.
 */
object WatchProtocol {
    /** Watch to phone: `session`, `rec_start`, `rec_chunk`, `rec_end`. */
    const val PATH_WATCH_TO_PHONE = "/juggling_tracker/watch_message"

    /** Phone to watch: `ack`. */
    const val PATH_PHONE_TO_WATCH = "/juggling_tracker/phone_message"

    /** Declared by the phone app in `res/values/wear.xml`. */
    const val PHONE_CAPABILITY = "juggling_tracker_phone"

    /** Declared by the watch app (`wearos/.../res/values/wear.xml`); reachable means installed and connected. */
    const val WATCH_CAPABILITY = "juggling_tracker_watch"

    fun encode(payload: Map<String, Any>): ByteArray = toJson(payload).toString().toByteArray(Charsets.UTF_8)

    /** The phone's `ack`, echoing the payload's timestamp when it has one. */
    fun encodeAck(timestamp: Long?): ByteArray {
        val ack = JSONObject().put("type", "ack")
        if (timestamp != null) ack.put("timestamp", timestamp)
        return ack.toString().toByteArray(Charsets.UTF_8)
    }

    /** Returns the payload as a map with lists for arrays, or null for anything that is not a JSON object. */
    fun decode(bytes: ByteArray): Map<String, Any?>? = try {
        fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
    } catch (e: org.json.JSONException) {
        null
    }

    private fun toJson(map: Map<String, Any>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) {
            json.put(key, toJsonValue(value))
        }
        return json
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            toJson(value as Map<String, Any>)
        }
        is Iterable<*> -> JSONArray().also { array -> value.forEach { array.put(toJsonValue(it)) } }
        else -> value
    }

    private fun fromJson(json: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = fromJsonValue(json.get(key))
        }
        return map
    }

    private fun fromJsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> fromJson(value)
        is JSONArray -> (0 until value.length()).map { fromJsonValue(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }
}
