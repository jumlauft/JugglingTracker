package com.juggling.tracker.logic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class GarminConnectionStatus {
    READY,
    RECEIVING,
    NOT_INITIALIZED,
    BLUETOOTH_DISABLED,
    NO_PAIRED_DEVICES,
    CONNECT_IQ_MISSING,
    WATCH_APP_MISSING,
    DISCONNECTED,
    SDK_ERROR
}

/** The Wear OS watch app's link to this phone, over the Data Layer. */
enum class WearConnectionStatus {
    CHECKING,
    READY,
    RECEIVING,
    NO_WATCH,
    WATCH_APP_MISSING,
    UNAVAILABLE;

    companion object {
        /**
         * [connectedWatches] is how many watches the phone is connected to, and
         * [watchesWithApp] how many of them are running a reachable copy of the
         * watch app; null when the Data Layer could not be asked at all.
         */
        fun classify(connectedWatches: Int?, watchesWithApp: Int): WearConnectionStatus = when {
            connectedWatches == null -> UNAVAILABLE
            watchesWithApp > 0 -> READY
            connectedWatches == 0 -> NO_WATCH
            else -> WATCH_APP_MISSING
        }
    }
}

/**
 * What the watch card on the home screen shows: the Garmin and Wear OS links'
 * state, held on "receiving" while a watch sends. Counterpart of the iPhone
 * app's WatchStatusModel.
 */
class WatchStatusModel(private val scope: CoroutineScope) {
    var garminStatus by mutableStateOf(GarminConnectionStatus.NOT_INITIALIZED)
        private set
    /** The Garmin link's last message, such as the connected watch's name. */
    var garminMessage by mutableStateOf("")
        private set
    var wearStatus by mutableStateOf(WearConnectionStatus.CHECKING)
        private set

    // While a watch is sending, its card stays on "receiving"; a status its
    // link reports meanwhile waits here and shows once the transfer ends.
    private var garminReceiving: Job? = null
    private var wearReceiving: Job? = null
    private var garminLinkWhileReceiving: GarminConnectionStatus? = null
    private var wearLinkWhileReceiving: WearConnectionStatus? = null

    /** What the Garmin link reports about the watch's connection. */
    fun onGarminLinkStatus(status: GarminConnectionStatus, message: String) {
        garminMessage = message
        if (garminReceiving != null) garminLinkWhileReceiving = status else garminStatus = status
    }

    /** What the Data Layer reports about the Wear OS watch's connection. */
    fun onWearLinkStatus(status: WearConnectionStatus) {
        if (wearReceiving != null) wearLinkWhileReceiving = status else wearStatus = status
    }

    // The watch card shows "receiving" from a transfer's first message until
    // a moment after its last. A run arrives in several messages; between
    // them the card holds on for up to RECEIVING_STALL_MS, longer than the
    // watch itself waits, so a transfer that died cannot leave it orange.
    fun showReceiving(source: WatchInbox.Source, more: Boolean) {
        val hold = if (more) RECEIVING_STALL_MS else RECEIVING_DISPLAY_MS
        when (source) {
            WatchInbox.Source.GARMIN -> {
                garminStatus = GarminConnectionStatus.RECEIVING
                garminReceiving?.cancel()
                garminReceiving = scope.launch {
                    delay(hold)
                    garminReceiving = null
                    garminStatus = garminLinkWhileReceiving ?: GarminConnectionStatus.READY
                    garminLinkWhileReceiving = null
                }
            }
            WatchInbox.Source.WEAR_OS -> {
                wearStatus = WearConnectionStatus.RECEIVING
                wearReceiving?.cancel()
                wearReceiving = scope.launch {
                    delay(hold)
                    wearReceiving = null
                    wearStatus = wearLinkWhileReceiving ?: WearConnectionStatus.READY
                    wearLinkWhileReceiving = null
                }
            }
        }
    }

    companion object {
        /** How long the watch card shows "receiving" after a transfer's last message. */
        const val RECEIVING_DISPLAY_MS = 1500L

        /**
         * How long the card waits for a transfer's next message before giving
         * up on it. Longer than the watches' own SYNC_TIMEOUT_MS (10 s).
         */
        const val RECEIVING_STALL_MS = 15_000L
    }
}
