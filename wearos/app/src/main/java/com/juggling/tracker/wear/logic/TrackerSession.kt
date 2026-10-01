package com.juggling.tracker.wear.logic

import com.juggling.tracker.shared.JugglingDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One accelerometer reading in milli-g including gravity, stamped in ms. */
data class AccelSample(val x: Int, val y: Int, val z: Int, val timeMs: Long)

/** Everything the Juggle screen draws (JUG-1). */
data class TrackerUiState(
    val ballCount: Int,
    val runActive: Boolean = false,
    val currentCount: Int = 0,
    val previousCount: Int = 0,
    val runs: Int = 0,
    val average: Double = 0.0,
    val max: Int = 0,
    val elapsedSeconds: Long = 0,
    /** Session shape consistency in percent, -1 until a run has been scored. */
    val shapeConsistency: Int = -1,
    val errorMessage: String? = null,
    val sending: Boolean = false,
    val syncDots: Int = 0,
    val menu: MenuSpec? = null,
    val exited: Boolean = false,
)

/**
 * Juggle mode: the Wear OS counterpart of `connectiq/source/MainView.mc` and
 * its delegates. It owns the detector, the session-end and discard menus, and
 * the sync to the phone. Requirement ids refer to `connectiq/REQUIREMENTS.md`.
 *
 * Inputs map onto the Garmin buttons: [onStartStop] is START/STOP, [onBack]
 * is BACK, and a menu reports through [onMenuSelect] / [onMenuBack].
 */
class TrackerSession(
    ballCount: Int,
    private val link: PhoneLink,
    private val scheduler: Scheduler,
    private val clock: MonotonicClock,
    private val effects: WatchEffects,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    companion object {
        // The full round trip (watch -> phone -> ack -> watch) can take a few
        // seconds, so keep this generous to avoid false "sync failed".
        const val SYNC_TIMEOUT_MS = 10_000L
        const val STATUS_TICK_MS = 1_000L
        const val VIBRATE_EVERY = 10

        const val MENU_SESSION_END = "session_end"
        const val MENU_DISCARD = "discard_run"

        const val ITEM_SYNC_QUIT = "sync_quit"
        const val ITEM_SYNC_RETRY = "sync_retry"
        const val ITEM_NOSYNC_QUIT = "nosync_quit"
        const val ITEM_CONTINUE = "continue_session"
        const val ITEM_DISCARD_YES = "discard_yes"
        const val ITEM_DISCARD_NO = "discard_no"

        const val SYNC_FAILED = "Sync failed"
    }

    private val detector = JugglingDetector(ballCount)
    private val sessionStartMs = clock.nowMs()
    private var sessionEndMs: Long? = null

    private var sending = false
    private var errorMessage: String? = null
    private var pendingPayload: Map<String, Any>? = null
    // Bumped on every transmit attempt so a late report from an abandoned
    // attempt cannot land on the current one (SYNC-4).
    private var syncGeneration = 0
    private var syncTimer: Cancellable? = null
    private var statusTimer: Cancellable? = null
    private var syncDots = 0
    private var lastVibrateCount = 0
    private var menu: MenuSpec? = null
    private var exited = false

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<TrackerUiState> = _state.asStateFlow()

    init {
        // Listen for the phone's acknowledgement that a session was stored.
        link.setMessageListener(::onPhoneMessage)
    }

    /**
     * Feeds a batch of samples. On the Garmin, pushing a menu hides the view
     * and drops its sensor listener, so detection pauses while a menu is up;
     * this keeps that behaviour.
     */
    fun onSamples(samples: List<AccelSample>) {
        if (exited || menu != null || samples.isEmpty()) return
        for (s in samples) {
            detector.processSample(s.x, s.y, s.z, s.timeMs)
        }

        // JUG-7: buzz once every 10 watch-hand catches.
        val count = detector.currentCount
        if (count > 0 && count / VIBRATE_EVERY > lastVibrateCount / VIBRATE_EVERY) {
            effects.vibrate()
            lastVibrateCount = count
        }

        val finished = detector.checkAutoFinish(samples.last().timeMs)
        if (finished > 0) {
            lastVibrateCount = 0
        }
        publish()
    }

    /** Refreshes the elapsed-time display. */
    fun tick() = publish()

    // ── START/STOP: the session-end menu (JUG-2) ──────────────────────

    fun onStartStop() = showSessionEndMenu(isRetry = false)

    private fun showSessionEndMenu(isRetry: Boolean) {
        if (menu != null || exited) return
        if (!isRetry && sessionEndMs == null) {
            sessionEndMs = clock.nowMs()
        }
        val items = buildList {
            if (isRetry) {
                add(MenuItemSpec(ITEM_SYNC_RETRY, "Retry sync"))
            } else {
                add(MenuItemSpec(ITEM_SYNC_QUIT, "Sync and quit"))
            }
            add(MenuItemSpec(ITEM_NOSYNC_QUIT, "Quit without sync"))
            add(MenuItemSpec(ITEM_CONTINUE, "Continue"))
        }
        menu = MenuSpec(MENU_SESSION_END, if (isRetry) SYNC_FAILED else "End session?", items)
        publish()
    }

    // ── BACK: discard one run, never exit (JUG-3, JUG-4) ──────────────

    fun onBack() {
        if (menu != null || exited) return
        val title: String
        val detail: String
        if (detector.isRunActive()) {
            title = "Discard this run?"
            detail = "${detector.currentCount} catches"
        } else if (detector.sessionRuns() > 0) {
            title = "Discard last run?"
            detail = "${detector.previousCount} catches"
        } else {
            // Nothing recorded yet: swallow the press. Letting it through
            // would close the app, which is what this handler exists to stop.
            return
        }
        menu = MenuSpec(
            MENU_DISCARD,
            title,
            listOf(
                MenuItemSpec(ITEM_DISCARD_YES, "Discard", detail),
                MenuItemSpec(ITEM_DISCARD_NO, "Keep"),
            ),
        )
        publish()
    }

    // ── Menu answers ──────────────────────────────────────────────────

    fun onMenuSelect(itemId: String) {
        val open = menu ?: return
        menu = null
        when (open.kind) {
            MENU_DISCARD -> onDiscardResponse(itemId == ITEM_DISCARD_YES)
            MENU_SESSION_END -> when (itemId) {
                ITEM_SYNC_QUIT -> doSync()
                ITEM_SYNC_RETRY -> {
                    errorMessage = null
                    attemptSync()
                }
                ITEM_NOSYNC_QUIT -> {
                    cancelSyncTimer()
                    exit()
                }
                ITEM_CONTINUE -> onContinueSession()
            }
        }
        publish()
    }

    /**
     * Backing out of any menu clears it (JUG-6): out of the discard prompt it
     * means Keep, out of the session-end menu it means Continue.
     */
    fun onMenuBack() {
        val open = menu ?: return
        menu = null
        when (open.kind) {
            MENU_DISCARD -> onDiscardResponse(false)
            MENU_SESSION_END -> onContinueSession()
        }
        publish()
    }

    private fun onDiscardResponse(confirmed: Boolean) {
        if (confirmed) {
            detector.discardLastRun()
            // The next run's 10-catch buzz counts from zero again.
            lastVibrateCount = 0
        }
    }

    private fun onContinueSession() {
        sessionEndMs = null
        // JUG-8: nothing is failing any more once the user is juggling again.
        errorMessage = null
    }

    // ── Sync (SYNC-1..4) ──────────────────────────────────────────────

    private fun doSync() {
        if (sending || menu != null) return

        // Fold any run still in progress into the session.
        detector.finishCurrentRun()
        if (sessionEndMs == null) {
            sessionEndMs = clock.nowMs()
        }

        val runs = detector.runCatches()
        if (runs.isEmpty()) {
            // SYNC-3: nothing worth sending, just close.
            exit()
            return
        }

        val payload = mutableMapOf<String, Any>(
            "type" to "session",
            "countMode" to "watch_hand",
            "balls" to detector.ballCount,
            "timestamp" to epochSeconds(),
            "durationSeconds" to sessionDurationSeconds(),
            "runDurationsMillis" to detector.runDurationsMillis(),
            "runs" to runs,
        )
        // Left out until a run has been long enough to score, so the phone
        // shows no value rather than a made-up one (SYNC-1).
        val shape = detector.shapeConsistencyPercent()
        if (shape >= 0) payload["shapeConsistency"] = shape
        pendingPayload = payload
        attemptSync()
    }

    private fun attemptSync() {
        val payload = pendingPayload ?: return
        errorMessage = null
        syncGeneration += 1
        val generation = syncGeneration

        sending = true
        startSyncTimer()
        startStatusTimer()
        try {
            link.send(payload) { delivered ->
                if (!delivered) onTransmitError(generation)
                // Delivery alone closes nothing: only the phone's ack does.
            }
        } catch (e: RuntimeException) {
            sending = false
            cancelSyncTimer()
            cancelStatusTimer()
            promptRetryOrQuit()
        }
        publish()
    }

    private fun onTransmitError(generation: Int) {
        if (generation != syncGeneration) return // late report from an abandoned attempt
        if (!sending) return
        sending = false
        cancelSyncTimer()
        cancelStatusTimer()
        promptRetryOrQuit()
        publish()
    }

    private fun onSyncTimeout() {
        syncTimer = null
        if (sending) {
            sending = false
            cancelStatusTimer()
            promptRetryOrQuit()
            publish()
        }
    }

    /** SYNC-2: only the phone's `ack` closes the app. */
    private fun onPhoneMessage(data: Map<String, Any?>) {
        if (data["type"] != "ack") return
        if (pendingPayload == null) return
        sending = false
        cancelSyncTimer()
        cancelStatusTimer()
        pendingPayload = null
        menu = null
        exit()
        publish()
    }

    private fun promptRetryOrQuit() {
        errorMessage = SYNC_FAILED
        showSessionEndMenu(isRetry = true)
    }

    private fun startSyncTimer() {
        cancelSyncTimer()
        syncTimer = scheduler.schedule(SYNC_TIMEOUT_MS) { onSyncTimeout() }
    }

    private fun cancelSyncTimer() {
        syncTimer?.cancel()
        syncTimer = null
    }

    // Animates "Sync to phone..." with one more dot each second.
    private fun startStatusTimer() {
        cancelStatusTimer()
        syncDots = 1
        scheduleStatusTick()
    }

    private fun scheduleStatusTick() {
        statusTimer = scheduler.schedule(STATUS_TICK_MS) {
            syncDots = (syncDots % 3) + 1
            scheduleStatusTick()
            publish()
        }
    }

    private fun cancelStatusTimer() {
        statusTimer?.cancel()
        statusTimer = null
        syncDots = 0
    }

    private fun exit() {
        if (exited) return
        exited = true
        cancelSyncTimer()
        cancelStatusTimer()
        link.setMessageListener(null)
        effects.exit()
    }

    private fun sessionDurationSeconds(): Long {
        val end = sessionEndMs ?: clock.nowMs()
        return ((end - sessionStartMs).coerceAtLeast(0L)) / 1000L
    }

    private fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot() = TrackerUiState(
        ballCount = detector.ballCount,
        runActive = detector.isRunActive(),
        currentCount = detector.currentCount,
        previousCount = detector.previousCount,
        runs = detector.sessionRuns(),
        average = detector.sessionAverage(),
        max = detector.sessionMax,
        elapsedSeconds = sessionDurationSeconds(),
        shapeConsistency = detector.shapeConsistencyPercent(),
        errorMessage = errorMessage,
        sending = sending,
        syncDots = syncDots,
        menu = menu,
        exited = exited,
    )
}
