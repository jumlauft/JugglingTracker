package com.juggling.tracker.wear.platform

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.juggling.tracker.wear.logic.PhoneLink
import com.juggling.tracker.shared.WatchProtocol

/**
 * [PhoneLink] over the Wear OS Data Layer. A message goes to the paired phone
 * that advertises the Juggling Tracker capability; the phone's ack comes back
 * as a message on [WatchProtocol.PATH_PHONE_TO_WATCH].
 *
 * Like the Garmin transport, the phone app has to be running to receive: if
 * it is not, the send still succeeds but no ack arrives, and the session's
 * timeout offers a retry.
 *
 * The phone's node is looked up once and reused, so a recording's chunks go
 * out without a capability query each. If a send to that node fails, the
 * phone is looked up again and the send tried once more before it counts as
 * failed.
 */
class DataLayerPhoneLink(context: Context) : PhoneLink {
    companion object {
        private const val TAG = "DataLayerPhoneLink"
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)
    private var listener: ((Map<String, Any?>) -> Unit)? = null

    // Written from Play services callbacks, read from the main thread.
    @Volatile
    private var phoneNodeId: String? = null

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
        val cached = phoneNodeId
        val sent = if (cached == null) {
            sendToFreshPhone(bytes)
        } else {
            messageClient.sendMessage(cached, WatchProtocol.PATH_WATCH_TO_PHONE, bytes)
                .continueWithTask { task ->
                    if (task.isSuccessful) return@continueWithTask task
                    // The phone may have gone, or another one taken its place.
                    Log.i(TAG, "Send to cached phone failed, looking it up again", task.exception)
                    phoneNodeId = null
                    sendToFreshPhone(bytes)
                }
        }
        sent.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Send of ${payload["type"]} failed", task.exception)
                phoneNodeId = null
            }
            onResult(task.isSuccessful)
        }
    }

    private fun sendToFreshPhone(bytes: ByteArray): Task<Int> =
        findPhone().onSuccessTask { nodeId ->
            messageClient.sendMessage(nodeId!!, WatchProtocol.PATH_WATCH_TO_PHONE, bytes)
        }

    /** The reachable phone with the app, preferring one nearby over one via the cloud. */
    private fun findPhone(): Task<String> =
        capabilityClient
            .getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .onSuccessTask { info ->
                val nodes = info!!.nodes
                val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                    ?: throw IllegalStateException("No phone with Juggling Tracker is reachable")
                phoneNodeId = phone.id
                Tasks.forResult(phone.id)
            }

    override fun setMessageListener(listener: ((Map<String, Any?>) -> Unit)?) {
        this.listener = listener
    }

    fun close() {
        listener = null
        messageClient.removeListener(messageListener)
    }
}
