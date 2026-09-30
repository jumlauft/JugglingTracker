package com.juggling.tracker.wear.logic

/** A pending timer that can be stopped, like Garmin's `Timer.Timer`. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Runs actions later on the main thread. The sessions take this instead of
 * Android's Handler so their timeouts can be driven by a fake clock in tests.
 */
interface Scheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

/** Monotonic milliseconds, like Garmin's `System.getTimer()`. */
fun interface MonotonicClock {
    fun nowMs(): Long
}

/**
 * The transport to the phone app, standing in for Garmin's
 * `Communications.transmit` / `registerForPhoneAppMessages`.
 *
 * [send] reports once whether the message left the watch, like a
 * ConnectionListener's onComplete / onError. That is delivery only: a session
 * counts as synced only when the phone answers with an `ack` through
 * [setMessageListener].
 */
interface PhoneLink {
    fun send(payload: Map<String, Any>, onResult: (delivered: Boolean) -> Unit)
    fun setMessageListener(listener: ((Map<String, Any?>) -> Unit)?)
}

/** The watch-side effects a session has besides drawing: buzz and quit. */
interface WatchEffects {
    fun vibrate()
    /** Close the app, like `System.exit()` on the Garmin. */
    fun exit()
}

/**
 * A list of choices shown over a tracking screen, the counterpart of a Garmin
 * `Menu2`. Labels are the app's own English strings (JUG-5).
 */
data class MenuSpec(
    val kind: String,
    val title: String,
    val items: List<MenuItemSpec>,
)

data class MenuItemSpec(
    val id: String,
    val label: String,
    val subLabel: String? = null,
)
