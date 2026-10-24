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
) {
    /** A kept session reopened and waiting on its Previous session menu (LATER-1). */
    val isWaitingToResume: Boolean get() = menu?.kind == TrackerSession.MENU_RESUME
}

/**
 * Juggle mode: the Wear OS counterpart of `connectiq/source/TrackerSession.mc`.
 * It owns the detector, the session-end and discard menus, and
 * the sync to the phone. Requirement ids refer to `connectiq/REQUIREMENTS.md`.
 *
 * Inputs map onto the Garmin buttons: [onStartStop] is START/STOP, [onBack]
 * is BACK, and a menu reports through [onMenuSelect] / [onMenuBack].
 *
 * With [saved], it is the session kept with Continue later, reopened with its
 * Previous session menu up (LATER-1); its ball count then comes from [saved].
 */
class TrackerSession(
    ballCount: Int,
    private val link: PhoneLink,
    private val scheduler: Scheduler,
    private val clock: MonotonicClock,
    private val effects: WatchEffects,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val recorder: ActivityRecorder = NoActivityRecorder,
    private val store: SessionStore = NoSessionStore,
    saved: KeptSession? = null,
) {
    companion object {
        // The full round trip (watch -> phone -> ack -> watch) can take a few
        // seconds, so keep this generous to avoid false "sync failed".
        const val SYNC_TIMEOUT_MS = 10_000L
        const val STATUS_TICK_MS = 1_000L
        const val VIBRATE_EVERY = 10

        const val MENU_SESSION_END = "session_end"
        const val MENU_DISCARD = "discard_run"
        const val MENU_RESUME = "resume_session"

        const val ITEM_SYNC_QUIT = "sync_quit"
        const val ITEM_SYNC_RETRY = "sync_retry"
        const val ITEM_NOSYNC_QUIT = "nosync_quit"
        const val ITEM_CONTINUE = "continue_session"
        const val ITEM_CONTINUE_LATER = "continue_later"
        const val ITEM_DISCARD_SESSION = "discard_session"
        const val ITEM_DISCARD_YES = "discard_yes"
        const val ITEM_DISCARD_NO = "discard_no"

        const val SYNC_FAILED = "Sync failed"
    }

    private val detector = JugglingDetector(saved?.balls ?: ballCount)
    private val sessionStartMs = clock.nowMs()
    private var sessionEndMs: Long? = null
    // Time spent in the session-end menu before picking Continue. The session
    // clock stands still while that menu is up, so this is left out of the
    // elapsed time shown and sent to the phone.
    private var pausedMs = 0L
    // The session's id on the phone, fixed by the first sync. Ending again
    // after Continue resends the whole session under the same id, so the
    // phone replaces its first copy instead of storing the runs twice (SYNC-5).
    private var sessionTimestamp: Long? = null
    // Session time from before the app was last closed with Continue later.
    private var priorSeconds = 0L
    // The watch's activity for this stretch of juggling (ACT-1, ACT-2): one
    // per time the app is open, as on the Garmin.
    private var activityStarted = false
    private var activityEnded = false
    // Runs completed before this stretch began; the activity's stats cover
    // only the runs after them.
    private var stretchFirstRun = 0

    /** Told when the Previous session is discarded, so the navigator shows the first screen (LATER-4). */
    var onDiscarded: (() -> Unit)? = null

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
        if (saved != null) restore(saved) else startActivity()
        publish()
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
            // ACT-2: the activity's timer stands still with the session clock.
            if (activityStarted && !activityEnded) recorder.pause()
        }
        val items = buildList {
            if (isRetry) {
                add(MenuItemSpec(ITEM_SYNC_RETRY, "Retry sync"))
            } else {
                add(MenuItemSpec(ITEM_SYNC_QUIT, "Sync and quit"))
            }
            add(MenuItemSpec(ITEM_NOSYNC_QUIT, "Quit without sync"))
            add(MenuItemSpec(ITEM_CONTINUE_LATER, "Continue later"))
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
            MENU_SESSION_END, MENU_RESUME -> when (itemId) {
                ITEM_SYNC_QUIT -> doSync()
                ITEM_SYNC_RETRY -> {
                    errorMessage = null
                    attemptSync()
                }
                ITEM_NOSYNC_QUIT -> {
                    cancelSyncTimer()
                    exit()
                }
                ITEM_CONTINUE_LATER -> continueLater()
                ITEM_DISCARD_SESSION -> discardSession()
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
            // LATER-5: the session stays kept, as it was, and the app closes.
            MENU_RESUME -> {
                cancelSyncTimer()
                exitKeeping()
            }
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
        // Resume the session clock from where the menu stopped it.
        sessionEndMs?.let { pausedMs += clock.nowMs() - it }
        sessionEndMs = null
        // ACT-2: the activity runs on, or starts for a Previous session.
        if (!activityStarted) startActivity() else if (!activityEnded) recorder.resume()
        // JUG-8: nothing is failing any more once the user is juggling again.
        errorMessage = null
        // JUG-8: abandon the sync. Its ack can still arrive late, and with the
        // payload kept it would close the app mid-juggle. Ending again sends
        // the whole session afresh under the same timestamp.
        sending = false
        cancelSyncTimer()
        cancelStatusTimer()
        pendingPayload = null
        syncGeneration += 1
    }

    // ── Sync (SYNC-1..5) ──────────────────────────────────────────────

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

        val timestamp = sessionTimestamp ?: epochSeconds().also { sessionTimestamp = it }
        val payload = mutableMapOf<String, Any>(
            "type" to "session",
            "countMode" to "watch_hand",
            "balls" to detector.ballCount,
            "timestamp" to timestamp,
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
        // Only the ack for this session counts: the phone echoes the timestamp
        // it stored, and a late ack for a session sent earlier must not close
        // the app before this one is stored.
        val ackTimestamp = (data["timestamp"] as? Number)?.toLong()
        if (ackTimestamp != sessionTimestamp) return
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

    // ── Continue later (LATER-1..5) and the activity (ACT-1, ACT-2) ───

    /** JUG-9: keeps the session and closes the app; with no completed run it just closes. */
    private fun continueLater() {
        cancelSyncTimer()
        if (keepForLater()) exitKeeping() else exit()
    }

    /** Writes the session to the store; false when it has no completed run. */
    private fun keepForLater(): Boolean {
        detector.finishCurrentRun()
        if (sessionEndMs == null) sessionEndMs = clock.nowMs()
        val runs = detector.runCatches()
        if (runs.isEmpty()) return false
        store.save(
            KeptSession(
                balls = detector.ballCount,
                elapsedSeconds = sessionDurationSeconds(),
                runs = runs,
                runDurationsMillis = detector.runDurationsMillis(),
                shapeSums = detector.shapeRunSums(),
                shapeCounts = detector.shapeRunCounts(),
                timestamp = sessionTimestamp,
            ),
        )
        return true
    }

    /**
     * Reopens a kept session (LATER-1): its runs, time and phone timestamp,
     * with the clock stopped and the Previous session menu up until answered.
     */
    private fun restore(saved: KeptSession) {
        val runs = saved.runs
        val durations = saved.runDurationsMillis.take(runs.size) +
            List((runs.size - saved.runDurationsMillis.size).coerceAtLeast(0)) { 0L }
        val shapeOk = saved.shapeSums.size == runs.size && saved.shapeCounts.size == runs.size
        detector.restoreRuns(
            runs,
            durations,
            if (shapeOk) saved.shapeSums else List(runs.size) { 0.0 },
            if (shapeOk) saved.shapeCounts else List(runs.size) { 0 },
        )
        stretchFirstRun = runs.size
        priorSeconds = saved.elapsedSeconds
        sessionTimestamp = saved.timestamp
        sessionEndMs = clock.nowMs()
        val detail = "${detector.ballCount} balls, ${runs.size} ${if (runs.size == 1) "run" else "runs"}"
        menu = MenuSpec(
            MENU_RESUME,
            "Previous session",
            listOf(
                MenuItemSpec(ITEM_CONTINUE, "Continue", detail),
                MenuItemSpec(ITEM_SYNC_QUIT, "Sync and quit"),
                MenuItemSpec(ITEM_DISCARD_SESSION, "Discard"),
            ),
        )
    }

    /** LATER-4: throws the kept session away and goes to the first screen. */
    private fun discardSession() {
        store.clear()
        exitQuietly()
        onDiscarded?.invoke()
    }

    private fun startActivity() {
        activityStarted = true
        stretchFirstRun = detector.sessionRuns()
        recorder.start()
    }

    /** ACT-2: saved with this stretch's stats when it has a run, else discarded. */
    private fun endActivity() {
        if (!activityStarted || activityEnded) return
        activityEnded = true
        val runs = detector.runCatches()
        val stretch = runs.drop(stretchFirstRun.coerceAtMost(runs.size))
        if (stretch.isEmpty()) {
            recorder.discard()
        } else {
            recorder.save(detector.ballCount, stretch.sum().toDouble() / stretch.size)
        }
    }

    /** Closes the app with the session finished: nothing stays kept. */
    private fun exit() {
        if (exited) return
        store.clear()
        exitKeeping()
    }

    /** Closes the app, leaving the store as it is. */
    private fun exitKeeping() {
        if (exited) return
        exitQuietly()
        effects.exit()
    }

    /** Ends the session without closing the app. */
    private fun exitQuietly() {
        if (exited) return
        exited = true
        cancelSyncTimer()
        cancelStatusTimer()
        link.setMessageListener(null)
        endActivity()
    }

    private fun sessionDurationSeconds(): Long {
        val end = sessionEndMs ?: clock.nowMs()
        return priorSeconds + ((end - sessionStartMs - pausedMs).coerceAtLeast(0L)) / 1000L
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
