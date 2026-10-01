package com.juggling.tracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.juggling.tracker.logic.GarminConnectionStatus
import com.juggling.tracker.logic.WatchInbox
import com.juggling.tracker.util.CrashlyticsUtils

/**
 * The Garmin watch's link to this phone, through the Connect IQ SDK.
 *
 * It lives as long as the app process, not a screen. Before, every screen
 * started the SDK again, so a recreated screen (dark mode, font size, split
 * screen) registered a second set of listeners, and leaving the screen with
 * Back stopped receiving even though the app was still running. Messages go
 * to the [WatchInbox], which stores them; the ack goes back only once it has.
 *
 * The app still has to be running to receive: unlike the Wear OS Data Layer,
 * the SDK cannot start it for a message.
 */
class GarminLink(context: Context, private val inbox: WatchInbox) {
    companion object {
        private const val TAG = "GarminLink"
        // The store build and the older beta build of the watch app carry
        // different manifest ids, and a watch may have either one on it.
        private const val WATCH_APP_ID = "fa298da6-29c7-46d2-9d76-e07f62d16539"
        private const val WATCH_APP_ID_BETA = "88fa4344-0c76-40a9-83e7-e7fc21328822"
        private val WATCH_APP_IDS = listOf(WATCH_APP_ID, WATCH_APP_ID_BETA)
        private const val HEARTBEAT_TIMEOUT_MS = 15000L
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var connectIQ: ConnectIQ? = null
    private var sdkReady = false
    private var starting = false
    private var iqDevice: IQDevice? = null
    // The device whose status changes we already listen to, so a refresh
    // never stacks a second listener on it.
    private var deviceEventsFor: IQDevice? = null
    private var iqApp: IQApp? = null

    var status = GarminConnectionStatus.NOT_INITIALIZED
        private set
    var message = ""
        private set
    private var statusListener: ((GarminConnectionStatus, String) -> Unit)? = null

    /** True while the watch app has sent something in the last 15 s. */
    var isWatchAppRunning by mutableStateOf(false)
        private set

    private val heartbeatRunnable = Runnable { isWatchAppRunning = false }

    /** Hears every status change, starting with the current one; null stops it. */
    fun setStatusListener(listener: ((GarminConnectionStatus, String) -> Unit)?) {
        statusListener = listener
        listener?.invoke(status, message)
    }

    /** For problems the screen finds before the SDK can start: permissions, Bluetooth off. */
    fun reportProblem(status: GarminConnectionStatus, message: String) {
        setStatus(status, message)
    }

    private fun setStatus(status: GarminConnectionStatus, message: String) {
        this.status = status
        this.message = message
        statusListener?.invoke(status, message)
    }

    /**
     * Starts the SDK the first time and checks the watch again on later
     * calls. Call once the Bluetooth permissions are granted and Bluetooth is on.
     */
    fun start() {
        if (sdkReady) {
            findAndRegisterDevice()
            return
        }
        if (starting) return
        starting = true

        try {
            val sdk = connectIQ ?: ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
                .also { connectIQ = it }

            // Unlike every other SDK call in this file, initialize() is not
            // wrapped by the SDK's own error handling for its final step: it
            // registers a broadcast receiver and binds the Garmin Connect
            // service directly, outside its internal try/catch. A failure
            // there (e.g. a SecurityException from a manufacturer's stricter
            // receiver-registration policy) would otherwise propagate all the
            // way up and crash the app on every launch.
            //
            // autoUI is off: the SDK's own prompts need a screen, and this
            // outlives screens. Its errors land in onInitializeError instead.
            sdk.initialize(
                appContext,
                false,
                object : ConnectIQ.ConnectIQListener {
                    override fun onSdkReady() {
                        starting = false
                        sdkReady = true
                        findAndRegisterDevice()
                    }

                    override fun onInitializeError(errorStatus: ConnectIQ.IQSdkErrorStatus) {
                        starting = false
                        when (errorStatus) {
                            ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> setStatus(
                                GarminConnectionStatus.CONNECT_IQ_MISSING,
                                "Garmin Connect app not found. Please install it from the Play Store.",
                            )
                            ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> setStatus(
                                GarminConnectionStatus.SDK_ERROR,
                                "Please update the Garmin Connect app from the Play Store.",
                            )
                            else -> setStatus(
                                GarminConnectionStatus.SDK_ERROR,
                                "Garmin SDK error: ${errorStatus.name}. Please restart the app.",
                            )
                        }
                    }

                    override fun onSdkShutDown() {
                        starting = false
                        sdkReady = false
                        // Its listeners went with it; the next start registers anew.
                        deviceEventsFor = null
                        setStatus(GarminConnectionStatus.NOT_INITIALIZED, "")
                    }
                },
            )
        } catch (e: Exception) {
            starting = false
            Log.e(TAG, "Error initializing Garmin ConnectIQ SDK", e)
            CrashlyticsUtils.recordException(e)
            setStatus(GarminConnectionStatus.SDK_ERROR, "Garmin SDK failed to start. Please restart the app.")
        }
    }

    private fun findAndRegisterDevice() {
        val sdk = connectIQ ?: return
        try {
            val devices = sdk.knownDevices
            if (devices.isNullOrEmpty()) {
                setStatus(
                    GarminConnectionStatus.NO_PAIRED_DEVICES,
                    "No paired Garmin devices found. Link your watch in the Garmin ConnectIQ app.",
                )
                return
            }

            // knownDevices can list several paired Garmins. Taking the first
            // blindly can register the listener on a different watch, which
            // then reports "connected" while receiving nothing.
            val device = devices.firstOrNull {
                sdk.getDeviceStatus(it) == IQDevice.IQDeviceStatus.CONNECTED
            } ?: devices[0]
            iqDevice = device
            Log.d(TAG, "knownDevices=" + devices.joinToString { it.friendlyName ?: "?" } +
                " -> using " + (device.friendlyName ?: "?"))

            listenForDeviceEvents(sdk, device)

            // Initial status check
            if (sdk.getDeviceStatus(device) == IQDevice.IQDeviceStatus.CONNECTED) {
                val suffix = if (devices.size > 1) " (${devices.size} paired)" else ""
                setStatus(GarminConnectionStatus.READY, "Connected to ${device.friendlyName}$suffix")
            } else {
                setStatus(GarminConnectionStatus.DISCONNECTED, "Watch disconnected from phone")
            }

            registerImuAppListener()
        } catch (e: Exception) {
            Log.e(TAG, "Error finding device", e)
            CrashlyticsUtils.recordException(e)
            setStatus(GarminConnectionStatus.SDK_ERROR, "Error finding device. Ensure Bluetooth is active.")
        }
    }

    // Bluetooth connection and disconnection of the watch.
    private fun listenForDeviceEvents(sdk: ConnectIQ, device: IQDevice) {
        if (deviceEventsFor?.deviceIdentifier == device.deviceIdentifier) return
        deviceEventsFor?.let { previous ->
            try {
                sdk.unregisterForDeviceEvents(previous)
            } catch (e: Exception) {
                Log.d(TAG, "No device listener to unregister", e)
            }
        }
        deviceEventsFor = device

        sdk.registerForDeviceEvents(device) { _, deviceStatus ->
            handler.post {
                when (deviceStatus) {
                    IQDevice.IQDeviceStatus.CONNECTED -> {
                        setStatus(GarminConnectionStatus.READY, "Connected to ${device.friendlyName}")
                        // The app-event registration does not survive the
                        // watch dropping off (USB mode, BLE drop). Without
                        // re-arming it the phone reports "connected" while
                        // nothing is listening, and the watch's transmit
                        // fails instantly for want of a receiver.
                        registerImuAppListener()
                    }
                    IQDevice.IQDeviceStatus.NOT_CONNECTED ->
                        setStatus(GarminConnectionStatus.DISCONNECTED, "Watch disconnected from phone")
                    else ->
                        setStatus(GarminConnectionStatus.SDK_ERROR, "Unknown device status: ${deviceStatus.name}")
                }
            }
        }
    }

    // Registering for an app the watch does not actually have makes Garmin
    // Connect answer with a payload-less broadcast. The SDK's receiver hands
    // that null payload straight to its deserializer and the resulting NPE
    // escapes onReceive, killing the app. So ask first, and only register for
    // an id the watch is known to carry.
    private fun registerImuAppListener() {
        val device = iqDevice ?: return
        probeWatchApp(device, 0)
    }

    private fun probeWatchApp(device: IQDevice, index: Int) {
        val sdk = connectIQ ?: return
        if (index >= WATCH_APP_IDS.size) {
            Log.d(TAG, "appInfo: no known watch app id is installed")
            handler.post {
                setStatus(
                    GarminConnectionStatus.WATCH_APP_MISSING,
                    "Install the Juggling Tracker watch app from the Connect IQ Store.",
                )
            }
            return
        }

        val candidateId = WATCH_APP_IDS[index]
        try {
            sdk.getApplicationInfo(
                candidateId,
                device,
                object : ConnectIQ.IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(app: IQApp?) {
                        Log.d(TAG, "appInfo: watch has $candidateId (${app?.displayName})")
                        registerForWatchApp(device, candidateId)
                    }

                    override fun onApplicationNotInstalled(applicationId: String?) {
                        Log.d(TAG, "appInfo: watch does not have $applicationId")
                        probeWatchApp(device, index + 1)
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error querying watch app info", e)
            CrashlyticsUtils.recordException(e)
        }
    }

    private fun registerForWatchApp(device: IQDevice, applicationId: String) {
        val sdk = connectIQ ?: return
        // Reuse one IQApp instance for the lifetime of the process. The SDK
        // matches an existing registration by instance, so unregistering with
        // a freshly built IQApp leaves the old listener in place and every
        // message is then delivered once per surviving registration.
        val app = iqApp ?: IQApp(applicationId).also { iqApp = it }

        try {
            try {
                sdk.unregisterForApplicationEvents(device, app)
            } catch (e: Exception) {
                Log.d(TAG, "No previous app listener to unregister")
            }
            sdk.registerForAppEvents(device, app) { _, _, received, messageStatus ->
                Log.d(TAG, "app event: status=$messageStatus size=${received?.size ?: -1}")
                if (messageStatus == ConnectIQ.IQMessageStatus.SUCCESS && !received.isNullOrEmpty()) {
                    handler.post { onWatchMessage(received) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering app listener", e)
            CrashlyticsUtils.recordException(e)
        }
    }

    private fun onWatchMessage(message: List<Any>) {
        val payload = (message.firstOrNull() as? Map<*, *>) ?: return

        isWatchAppRunning = true
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_TIMEOUT_MS)

        val ack = inbox.receive(payload, WatchInbox.Source.GARMIN) ?: return
        sendAck(ack.timestamp)
    }

    // Tells the watch the session or run is stored; only then may it let go of it.
    private fun sendAck(timestamp: Long?) {
        val sdk = connectIQ ?: return
        val device = iqDevice ?: return
        val app = iqApp ?: return
        val ack = mutableMapOf<String, Any>("type" to "ack")
        if (timestamp != null) {
            ack["timestamp"] = timestamp
        }
        try {
            sdk.sendMessage(device, app, ack) { _, _, sendStatus ->
                Log.d(TAG, "ACK send status: $sendStatus")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ACK", e)
            CrashlyticsUtils.recordException(e)
        }
    }
}
