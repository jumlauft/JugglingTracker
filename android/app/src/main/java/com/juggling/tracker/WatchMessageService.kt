package com.juggling.tracker

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.juggling.tracker.data.WearMessageCodec
import com.juggling.tracker.logic.WatchInbox
import com.juggling.tracker.util.CrashlyticsUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receives the Wear OS watch app's messages. Google Play services starts it,
 * and the app with it, whenever the watch sends something, so a watch can
 * sync while the phone app is closed. It carries the same payloads as the
 * Garmin watch, so it hands them to the same [WatchInbox], and sends the ack
 * back over the Data Layer to the watch that sent the message.
 */
class WatchMessageService : WearableListenerService() {
    companion object {
        private const val TAG = "WatchMessageService"
        private const val HANDLE_TIMEOUT_SECONDS = 10L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearMessageCodec.PATH_WATCH_TO_PHONE) return
        val payload = WearMessageCodec.decode(event.data) ?: return
        val inbox = (application as JugglingTrackerApplication).watchInbox
        val nodeId = event.sourceNodeId

        // This runs on a binder thread, but the inbox and the screens that
        // follow it live on the main thread. Wait for it, so the service is
        // not torn down before the message is stored and the ack is on its way.
        val handled = CountDownLatch(1)
        mainHandler.post {
            try {
                val ack = inbox.receive(payload, WatchInbox.Source.WEAR_OS)
                if (ack != null) sendAck(nodeId, ack.timestamp)
            } finally {
                handled.countDown()
            }
        }
        if (!handled.await(HANDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "A watch message was not handled within $HANDLE_TIMEOUT_SECONDS s")
        }
    }

    private fun sendAck(nodeId: String, timestamp: Long?) {
        try {
            Wearable.getMessageClient(applicationContext)
                .sendMessage(nodeId, WearMessageCodec.PATH_PHONE_TO_WATCH, WearMessageCodec.encodeAck(timestamp))
                .addOnFailureListener { e -> Log.e(TAG, "Error sending Wear OS ACK", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Wear OS ACK", e)
            CrashlyticsUtils.recordException(e)
        }
    }
}
