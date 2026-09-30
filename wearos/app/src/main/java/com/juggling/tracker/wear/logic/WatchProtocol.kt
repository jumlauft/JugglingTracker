package com.juggling.tracker.wear.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * The messages between this app and the phone app over the Wear OS Data
 * Layer. The payloads are the same dictionaries the Garmin app transmits
 * (SYNC-1, REC-5), carried as UTF-8 JSON, so the phone handles both watches
 * with one code path. The phone side of this lives in
 * `android/.../WearMessageCodec.kt`; keep the two in step.
 */
object WatchProtocol {
    /** Watch to phone: `session`, `rec_start`, `rec_chunk`, `rec_end`. */
    const val PATH_WATCH_TO_PHONE = "/juggling_tracker/watch_message"

    /** Phone to watch: `ack`. */
    const val PATH_PHONE_TO_WATCH = "/juggling_tracker/phone_message"

    /** Declared by the phone app in `res/values/wear.xml`. */
    const val PHONE_CAPABILITY = "juggling_tracker_phone"

    fun encode(payload: Map<String, Any>): ByteArray = toJson(payload).toString().toByteArray(Charsets.UTF_8)

    /** Returns null for anything that is not a JSON object. */
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
