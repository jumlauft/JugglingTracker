package com.juggling.tracker.wear.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingPhase { IDLE, RECORDING, LABELING, SYNCING }

/** Everything the Record screen draws. */
data class RecordingUiState(
    val ballCount: Int,
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val runsCompleted: Int = 0,
    val recordedSamples: Int = 0,
    val liveCount: Int = 0,
    val detectedCount: Int = 0,
    val labelCount: Int = 0,
    val syncDots: Int = 0,
    val headerSent: Boolean = false,
    val transferDone: Boolean = false,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val errorMessage: String? = null,
    val failReason: String? = null,
    val dataPending: Boolean = false,
    val menu: MenuSpec? = null,
    val exited: Boolean = false,
)

/**
 * Record mode (developer): the Wear OS counterpart of
 * `connectiq/source/RecordingView.mc`. It captures raw accelerometer runs,
 * lets the user correct the detector's count to the true watch-hand count,
 * and transfers each run to the phone in chunks. Requirement ids refer to
 * `connectiq/REQUIREMENTS.md`.
 *
 * Inputs map onto the Garmin buttons: [onStart] is START, [onBack] is BACK,
 * [onUp] / [onDown] adjust the label, and a menu reports through
 * [onMenuSelect] / [onMenuBack].
 */
class RecordingSession(
    private val ballCount: Int,
    private val link: PhoneLink,
    private val scheduler: Scheduler,
    private val effects: WatchEffects,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val SAMPLE_RATE = JugglingDetector.SAMPLE_RATE
        const val MAX_RUN_SAMPLES = 3000 // 120 s at 25 Hz (REC-2)
        const val SYNC_TIMEOUT_MS = 10_000L
        const val STATUS_TICK_MS = 1_000L
        // The Garmin sends 50 samples per chunk because its link slows down
        // past ~150 integers (REC-5). A Data Layer message carries up to
        // ~100 KB and costs about the same whatever its size, so here a chunk
        // is 1000 samples, about 21 KB of JSON at worst: a full 120 s run
        // goes over in 3 chunks instead of 60.
        const val CHUNK_SAMPLES = 1000
        const val NEXT_PART_DELAY_MS = 50L

        const val MENU_DISCARD = "rec_discard"
        const val MENU_QUIT = "rec_quit"
        const val MENU_SYNC = "rec_sync"

        const val ITEM_DISCARD_CONFIRM = "discard_confirm"
        const val ITEM_DISCARD_CANCEL = "discard_cancel"
        const val ITEM_QUIT_CONFIRM = "quit_confirm"
        const val ITEM_QUIT_CONTINUE = "quit_continue"
        const val ITEM_SYNC_RETRY = "sync_retry"
        const val ITEM_SYNC_SKIP = "sync_skip"
        const val ITEM_SYNC_QUIT = "sync_quit"
    }

    private var phase = RecordingPhase.IDLE
    private val accelX = mutableListOf<Int>()
    private val accelY = mutableListOf<Int>()
    private val accelZ = mutableListOf<Int>()
    // Runs the detector alongside, to compare it with the user's label.
    private var detector = JugglingDetector(ballCount)
    private var labelCount = 0
    private var detectedCount = 0
    private var runsCompleted = 0

    private var syncTimer: Cancellable? = null
    private var statusTimer: Cancellable? = null
    private var nextPartTimer: Cancellable? = null
    private var syncDots = 0
    private var menu: MenuSpec? = null
    private var errorMessage: String? = null
    private var failReason: String? = null
    private var pendingPayload: Map<String, Any>? = null
    // REC-6: each transmit attempt carries its own generation.
    private var syncGeneration = 0
    private var sessionId = 0L
    private var chunkIndex = 0
    private var totalChunks = 0
    private var headerSent = false
    private var transferDone = false
    private var exited = false

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    init {
        link.setMessageListener(::onPhoneMessage)
    }

    // ── Buttons ───────────────────────────────────────────────────────

    /** START drives every transition (REC-1). Returns whether it was used. */
    fun onStart(): Boolean {
        if (menu != null || exited) return false
        val handled = when (phase) {
            RecordingPhase.IDLE -> {
                startRun()
                true
            }
            RecordingPhase.RECORDING -> {
                finishRun()
                true
            }
            RecordingPhase.LABELING -> {
                confirmLabel()
                true
            }
            RecordingPhase.SYNCING -> false
        }
        publish()
        return handled
    }

    /**
     * BACK on the idle "Press Start" screen closes this session and returns
     * true, so the caller goes back to ball selection (REC-11): every run has
     * already synced or been dropped, so nothing is lost. Otherwise it offers
     * to discard while labelling and to quit while recording. While syncing
     * it is consumed and does nothing (REC-9): falling through would close
     * the app mid-transfer.
     */
    fun onBack(): Boolean {
        if (menu != null || exited) return false
        when (phase) {
            RecordingPhase.IDLE -> {
                close()
                return true
            }
            RecordingPhase.LABELING -> promptDiscard()
            RecordingPhase.SYNCING -> Unit
            RecordingPhase.RECORDING -> promptQuit()
        }
        publish()
        return false
    }

    fun onUp() {
        if (phase != RecordingPhase.LABELING || menu != null) return
        labelCount += 1
        publish()
    }

    /** REC-3: the label never goes below 0. */
    fun onDown() {
        if (phase != RecordingPhase.LABELING || menu != null) return
        if (labelCount > 0) labelCount -= 1
        publish()
    }

    // ── Menus ─────────────────────────────────────────────────────────

    private fun promptDiscard() {
        menu = MenuSpec(
            MENU_DISCARD,
            "Discard run?",
            listOf(MenuItemSpec(ITEM_DISCARD_CONFIRM, "Yes"), MenuItemSpec(ITEM_DISCARD_CANCEL, "Continue")),
        )
    }

    private fun promptQuit() {
        menu = MenuSpec(
            MENU_QUIT,
            "Quit? Lose run",
            listOf(MenuItemSpec(ITEM_QUIT_CONFIRM, "Yes"), MenuItemSpec(ITEM_QUIT_CONTINUE, "Continue")),
        )
    }

    private fun showSyncMenu() {
        if (menu != null) return
        menu = MenuSpec(
            MENU_SYNC,
            failReason ?: "Sync failed",
            listOf(
                MenuItemSpec(ITEM_SYNC_RETRY, "Retry sync"),
                MenuItemSpec(ITEM_SYNC_SKIP, "Skip (lose data)"),
                MenuItemSpec(ITEM_SYNC_QUIT, "Quit"),
            ),
        )
    }

    fun onMenuSelect(itemId: String) {
        val open = menu ?: return
        menu = null
        when (open.kind) {
            MENU_DISCARD -> if (itemId == ITEM_DISCARD_CONFIRM) discardRun()
            MENU_QUIT -> if (itemId == ITEM_QUIT_CONFIRM) exit()
            MENU_SYNC -> when (itemId) {
                ITEM_SYNC_RETRY -> onSyncRetry()
                ITEM_SYNC_SKIP -> onSyncSkip()
                ITEM_SYNC_QUIT -> {
                    cancelSyncTimer()
                    cancelStatusTimer()
                    exit()
                }
            }
        }
        publish()
    }

    /**
     * REC-10: backing out of any menu clears it. Out of the sync-failure menu
     * it retries, the only choice that keeps the run; out of the quit or
     * discard prompt it means Continue.
     */
    fun onMenuBack() {
        val open = menu ?: return
        menu = null
        if (open.kind == MENU_SYNC) {
            onSyncRetry()
        }
        publish()
    }

    // ── Samples ───────────────────────────────────────────────────────

    /** Samples count only while recording, and not while a menu is up. */
    fun onSamples(samples: List<AccelSample>) {
        if (exited || menu != null) return
        for (s in samples) {
            if (phase != RecordingPhase.RECORDING) break
            // REC-2: cap the run length.
            if (accelX.size >= MAX_RUN_SAMPLES) {
                finishRun()
                break
            }
            accelX.add(s.x)
            accelY.add(s.y)
            accelZ.add(s.z)
            detector.processSample(s.x, s.y, s.z, s.timeMs)
        }
        publish()
    }

    // ── Run lifecycle ─────────────────────────────────────────────────

    private fun startRun() {
        failReason = null
        errorMessage = null
        phase = RecordingPhase.RECORDING
        clearSamples()
        detector = JugglingDetector(ballCount)
    }

    /** REC-3: the detector's own count is the starting label. */
    private fun finishRun() {
        detectedCount = detector.currentCount
        labelCount = detectedCount
        phase = RecordingPhase.LABELING
    }

    /** Drop the unlabelled run; nothing was sent, so runsCompleted stays. */
    private fun discardRun() {
        cancelNextPartTimer()
        pendingPayload = null
        resetRun()
        labelCount = 0
        detectedCount = 0
    }

    private fun confirmLabel() {
        // REC-4: a one-line summary; the samples themselves go to the phone.
        log(
            "RUN_DATA,balls=$ballCount,catches=$labelCount,detected=$detectedCount," +
                "rate=$SAMPLE_RATE,countMode=watch_hand,samples=${accelX.size}",
        )

        sessionId = epochSeconds()
        totalChunks = (accelX.size + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES
        chunkIndex = 0
        headerSent = false
        transferDone = false
        pendingPayload = buildPart()
        phase = RecordingPhase.SYNCING
        attemptSync()
    }

    /** The part due next: header, one chunk per [CHUNK_SAMPLES] samples, then the end marker. */
    private fun buildPart(): Map<String, Any> {
        if (!headerSent) {
            return mapOf(
                "type" to "rec_start",
                "id" to sessionId,
                "countMode" to "watch_hand",
                "balls" to ballCount,
                "catches" to labelCount,
                "detected" to detectedCount,
                "sampleRate" to SAMPLE_RATE,
                "samples" to accelX.size,
                "chunks" to totalChunks,
                "timestamp" to sessionId,
            )
        }
        if (chunkIndex < totalChunks) {
            val from = chunkIndex * CHUNK_SAMPLES
            val to = minOf(from + CHUNK_SAMPLES, accelX.size)
            return mapOf(
                "type" to "rec_chunk",
                "id" to sessionId,
                "i" to chunkIndex,
                "x" to accelX.subList(from, to).toList(),
                "y" to accelY.subList(from, to).toList(),
                "z" to accelZ.subList(from, to).toList(),
            )
        }
        return mapOf("type" to "rec_end", "id" to sessionId)
    }

    // ── Transfer ──────────────────────────────────────────────────────

    private fun attemptSync() {
        val payload = pendingPayload ?: return
        errorMessage = null
        syncGeneration += 1
        val generation = syncGeneration
        startSyncTimer()
        startStatusTimer()
        try {
            link.send(payload) { delivered ->
                if (delivered) onTransmitDone(generation) else onTransmitError(generation)
            }
        } catch (e: RuntimeException) {
            cancelSyncTimer()
            cancelStatusTimer()
            promptRetryOrQuit()
        }
    }

    private fun onTransmitDone(generation: Int) {
        if (generation != syncGeneration || phase != RecordingPhase.SYNCING) return
        cancelSyncTimer()

        if (!headerSent) {
            headerSent = true
        } else if (chunkIndex < totalChunks) {
            chunkIndex += 1
        } else {
            // The end marker landed; the phone acks once it has written the run.
            transferDone = true
            startSyncTimer()
            publish()
            return
        }
        pendingPayload = buildPart()
        scheduleNextPart()
        publish()
    }

    private fun onTransmitError(generation: Int) {
        if (generation != syncGeneration) return // late report from an abandoned attempt
        if (phase != RecordingPhase.SYNCING) return
        cancelSyncTimer()
        cancelStatusTimer()
        failReason = "send failed"
        promptRetryOrQuit()
        publish()
    }

    private fun onSyncTimeout() {
        syncTimer = null
        if (phase == RecordingPhase.SYNCING) {
            cancelStatusTimer()
            failReason = "no reply from phone"
            promptRetryOrQuit()
            publish()
        }
    }

    private fun promptRetryOrQuit() {
        errorMessage = "Sync failed"
        showSyncMenu()
    }

    private fun onPhoneMessage(data: Map<String, Any?>) {
        if (data["type"] != "ack") return
        if (phase != RecordingPhase.SYNCING) return
        // REC-8: accept only the ack for the run that is syncing now. A late
        // ack for a timed-out or skipped run must not mark this one delivered.
        val ackId = (data["timestamp"] as? Number)?.toLong()
        if (ackId != sessionId) return

        cancelSyncTimer()
        cancelStatusTimer()
        menu = null
        cancelNextPartTimer()
        pendingPayload = null
        resetRun()
        runsCompleted += 1
        publish()
    }

    private fun onSyncRetry() {
        errorMessage = null
        if (pendingPayload == null) {
            // The ack landed after the menu opened; nothing is left to send.
            failReason = null
            phase = RecordingPhase.IDLE
            runsCompleted += 1
            return
        }
        // Restart from the header: the phone discards a partial transfer
        // when a new rec_start for the same run arrives.
        headerSent = false
        chunkIndex = 0
        transferDone = false
        pendingPayload = buildPart()
        scheduleNextPart()
    }

    private fun onSyncSkip() {
        syncGeneration += 1
        failReason = null
        cancelSyncTimer()
        cancelStatusTimer()
        cancelNextPartTimer()
        pendingPayload = null
        resetRun()
        runsCompleted += 1
    }

    /**
     * REC-7: the next part goes out from a timer rather than from inside the
     * delivery callback, as on the Garmin, where sending from the callback
     * wedged the transmit slot.
     */
    private fun scheduleNextPart() {
        cancelNextPartTimer()
        nextPartTimer = scheduler.schedule(NEXT_PART_DELAY_MS) {
            nextPartTimer = null
            if (phase == RecordingPhase.SYNCING) {
                attemptSync()
                publish()
            }
        }
    }

    private fun resetRun() {
        detector = JugglingDetector(ballCount)
        clearSamples()
        phase = RecordingPhase.IDLE
        errorMessage = null
    }

    private fun clearSamples() {
        accelX.clear()
        accelY.clear()
        accelZ.clear()
    }

    private fun startSyncTimer() {
        cancelSyncTimer()
        syncTimer = scheduler.schedule(SYNC_TIMEOUT_MS) { onSyncTimeout() }
    }

    private fun cancelSyncTimer() {
        syncTimer?.cancel()
        syncTimer = null
    }

    private fun cancelNextPartTimer() {
        nextPartTimer?.cancel()
        nextPartTimer = null
    }

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
        close()
        effects.exit()
    }

    /** Stops every timer and the phone listener; the session ignores all input after. */
    private fun close() {
        exited = true
        cancelSyncTimer()
        cancelStatusTimer()
        cancelNextPartTimer()
        link.setMessageListener(null)
        publish()
    }

    private fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot() = RecordingUiState(
        ballCount = ballCount,
        phase = phase,
        runsCompleted = runsCompleted,
        recordedSamples = accelX.size,
        liveCount = detector.currentCount,
        detectedCount = detectedCount,
        labelCount = labelCount,
        syncDots = syncDots,
        headerSent = headerSent,
        transferDone = transferDone,
        chunkIndex = chunkIndex,
        totalChunks = totalChunks,
        errorMessage = errorMessage,
        failReason = failReason,
        dataPending = pendingPayload != null,
        menu = menu,
        exited = exited,
    )
}
