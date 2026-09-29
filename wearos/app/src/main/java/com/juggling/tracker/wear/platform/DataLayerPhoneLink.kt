package com.juggling.tracker.wear.platform

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.juggling.tracker.wear.logic.PhoneLink
import com.juggling.tracker.wear.logic.WatchProtocol

/**
 * [PhoneLink] over the Wear OS Data Layer. A message goes to the paired phone
 * that advertises the Juggling Tracker capability; the phone's ack comes back
 * as a message on [WatchProtocol.PATH_PHONE_TO_WATCH].
 *
 * Like the Garmin transport, the phone app has to be running to receive: if
 * it is not, the send still succeeds but no ack arrives, and the session's
 * timeout offers a retry.
 */
class DataLayerPhoneLink(context: Context) : PhoneLink {
    companion object {
        private const val TAG = "DataLayerPhoneLink"
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)
    private var listener: ((Map<String, Any?>) -> Unit)? = null

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path != WatchProtocol.PATH_PHONE_TO_WATCH) return@OnMessageReceivedListener
        val payload = WatchProtocol.decode(event.data)
        if (payload == null) {
            Log.w(TAG, "Ignoring undecodable message from ${event.sourceNodeId}")
            return@OnMessageReceivedListener
        }
        listener?.invoke(payload)
    }

    init {
        messageClient.addListener(messageListener)
    }

    override fun send(payload: Map<String, Any>, onResult: (delivered: Boolean) -> Unit) {
        val bytes = WatchProtocol.encode(payload)
        capabilityClient
            .getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .continueWithTask { task ->
                val nodes = task.result.nodes
                // Prefer a phone connected directly over one reached via the cloud.
                val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                    ?: throw IllegalStateException("No phone with Juggling Tracker is reachable")
                messageClient.sendMessage(phone.id, WatchProtocol.PATH_WATCH_TO_PHONE, bytes)
            }
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Send of ${payload["type"]} failed", task.exception)
                }
                onResult(task.isSuccessful)
            }
    }

    override fun setMessageListener(listener: ((Map<String, Any?>) -> Unit)?) {
        this.listener = listener
    }

    fun close() {
        listener = null
        messageClient.removeListener(messageListener)
    }
}
