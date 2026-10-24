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

/**
 * The watch's own activity for the stretch being juggled (ACT-1, ACT-2), the
 * counterpart of the Garmin's Garmin Connect activity. [TrackerSession]
 * starts it when juggling starts, pauses it while the session-end menu is up
 * and ends it when the app closes: saved with the stretch's stats when the
 * stretch has a run, else discarded.
 */
interface ActivityRecorder {
    fun start()
    fun pause()
    fun resume()
    /** [average] is the stretch's catches per run. */
    fun save(balls: Int, average: Double)
    fun discard()
}

/**
 * Wear OS records no activity yet: Health Connect runs on the phone, so the
 * sessions are to reach it through the phone app (ACT-1, see the README).
 */
object NoActivityRecorder : ActivityRecorder {
    override fun start() = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun save(balls: Int, average: Double) = Unit
    override fun discard() = Unit
}

/** Where a session kept with Continue later waits for the app to open again (LATER-1). */
interface SessionStore {
    fun load(): KeptSession?
    fun save(session: KeptSession)
    fun clear()
}

/** A session kept with Continue later (LATER-1). */
data class KeptSession(
    val balls: Int,
    val elapsedSeconds: Long,
    val runs: List<Int>,
    val runDurationsMillis: List<Long>,
    val shapeSums: List<Double>,
    val shapeCounts: List<Int>,
    /** The phone timestamp once the session has synced (SYNC-5). */
    val timestamp: Long? = null,
)

/** Keeps nothing, for sessions that never continue later (tests that do not need it). */
object NoSessionStore : SessionStore {
    override fun load(): KeptSession? = null
    override fun save(session: KeptSession) = Unit
    override fun clear() = Unit
}
