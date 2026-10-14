import Toybox.Lang;

// Record mode (developer): START starts and stops each raw accelerometer run,
// then the user labels the actual watch-hand catch count. The samples go to
// the phone, which stores them as CSV for tuning the detection algorithm
// offline; the log gets one RUN_DATA summary line per run, not the samples.
// No WatchUi in here: RecordingView draws it and feeds it the accelerometer.
// The Wear OS RecordingSession.kt and the Apple Watch RecordingSession.swift
// are the same logic. Requirement ids refer to REQUIREMENTS.md.
class RecordingSession {
    public static const SAMPLE_RATE = 25;
    public static const SAMPLE_PERIOD_MS = 40;
    public static const MAX_RUN_SAMPLES = 3000; // 120 seconds at 25 Hz (REC-2)
    // The sample buffer is allocated once (see SampleBuffer), but the detector
    // and the transfer still allocate, so keep a floor and stop cleanly at it.
    public static const MIN_FREE_MEMORY = 16384;
    public static const MEMORY_CHECK_EVERY = 25;
    public static const SYNC_TIMEOUT_MS = 10000;
    public static const STATUS_TICK_MS = 1000;
    // Samples go over in batches. Measured throughput peaks here: per-message
    // cost is flat up to ~150 integers, then climbs steeply, so 100 samples
    // (300 integers) transfers ~25% slower overall than 50 does (REC-5). The
    // touch watches send 1000 per message, which their links carry at about
    // the same cost.
    public static const CHUNK_SAMPLES = 50;
    public static const NEXT_PART_DELAY_MS = 50;

    public static const PHASE_IDLE = 0;
    public static const PHASE_RECORDING = 1;
    public static const PHASE_LABELING = 2;
    public static const PHASE_SYNCING = 3;

    public static const MENU_DISCARD = "rec_discard";
    public static const MENU_QUIT = "rec_quit";
    public static const MENU_SYNC = "rec_sync";

    public static const ITEM_DISCARD_CONFIRM = "discard_confirm";
    public static const ITEM_DISCARD_CANCEL = "discard_cancel";
    public static const ITEM_QUIT_CONFIRM = "quit_confirm";
    public static const ITEM_QUIT_CONTINUE = "quit_continue";
    public static const ITEM_SYNC_RETRY = "sync_retry";
    public static const ITEM_SYNC_SKIP = "sync_skip";
    public static const ITEM_SYNC_QUIT = "sync_quit";

    private var _link as PhoneLink;
    private var _scheduler as Scheduler;
    private var _clock as Clock;
    private var _effects as WatchEffects;

    private var _ballCount as Number;
    private var _phase as Number;
    // Recording buffer for the current run (raw milli-g values), allocated
    // once and reused for every run.
    private var _samples as SampleBuffer;
    // Runs the detector alongside, to compare it with the user's label.
    private var _detector as JugglingDetector;
    private var _labelCount as Number;
    private var _detectedCount as Number;
    private var _runsCompleted as Number;

    private var _syncTimer as Cancellable?;
    private var _statusTimer as Cancellable?;
    private var _nextPartTimer as Cancellable?;
    private var _syncDots as Number;
    private var _menu as MenuSpec?;
    private var _errorMsg as String?;
    private var _failReason as String?;
    private var _pendingPayload as Dictionary?;
    // REC-6: each transmit attempt carries its own generation. An abandoned
    // attempt still reports back later, and without this its failure would
    // land on whichever run is syncing by then and fail it instantly.
    private var _syncGeneration as Number;
    private var _sessionId as Number;
    private var _chunkIndex as Number;
    private var _totalChunks as Number;
    private var _headerSent as Boolean;
    private var _transferDone as Boolean;
    private var _exited as Boolean;
    // True when the accelerometer could not be registered (SENS-4).
    private var _sensorError as Boolean;

    public function initialize(ballCount as Number, link as PhoneLink, scheduler as Scheduler,
            clock as Clock, effects as WatchEffects) {
        _link = link;
        _scheduler = scheduler;
        _clock = clock;
        _effects = effects;
        _ballCount = ballCount;
        _phase = PHASE_IDLE;
        _samples = new SampleBuffer(MAX_RUN_SAMPLES);
        _detector = new JugglingDetector(ballCount);
        _labelCount = 0;
        _detectedCount = 0;
        _runsCompleted = 0;
        _syncTimer = null;
        _statusTimer = null;
        _nextPartTimer = null;
        _syncDots = 0;
        _menu = null;
        _errorMsg = null;
        _failReason = null;
        _pendingPayload = null;
        _syncGeneration = 0;
        _sessionId = 0;
        _chunkIndex = 0;
        _totalChunks = 0;
        _headerSent = false;
        _transferDone = false;
        _exited = false;
        _sensorError = false;

        // Listen for the phone's acknowledgement that a run was stored.
        _link.setMessageListener(method(:onPhoneMessage));
    }

    // ── What the screen shows ─────────────────────────────────────────

    public function ballCount() as Number { return _ballCount; }
    public function phase() as Number { return _phase; }
    public function runsCompleted() as Number { return _runsCompleted; }
    public function recordedSamples() as Number { return _samples.size(); }
    public function liveCount() as Number { return _detector.currentCount; }
    public function detectedCount() as Number { return _detectedCount; }
    public function labelCount() as Number { return _labelCount; }
    public function syncDots() as Number { return _syncDots; }
    public function headerSent() as Boolean { return _headerSent; }
    public function transferDone() as Boolean { return _transferDone; }
    public function chunkIndex() as Number { return _chunkIndex; }
    public function totalChunks() as Number { return _totalChunks; }
    public function errorMessage() as String? { return _errorMsg; }
    public function failReason() as String? { return _failReason; }
    public function dataPending() as Boolean { return _pendingPayload != null; }
    public function menu() as MenuSpec? { return _menu; }
    public function isExited() as Boolean { return _exited; }
    public function sensorError() as Boolean { return _sensorError; }
    public function nowMs() as Number { return _clock.nowMs(); }

    public function setSensorError(failed as Boolean) as Void {
        _sensorError = failed;
        _effects.update();
    }

    // ── Buttons ───────────────────────────────────────────────────────

    // START drives every transition (REC-1). Returns whether it was used.
    public function onStart() as Boolean {
        if (_menu != null || _exited) {
            return false;
        }
        var handled = true;
        if (_phase == PHASE_IDLE) {
            // SENS-4: nothing to record without the accelerometer.
            if (!_sensorError) {
                startRun();
            }
        } else if (_phase == PHASE_RECORDING) {
            finishRun();
        } else if (_phase == PHASE_LABELING) {
            confirmLabel();
        } else {
            handled = false;
        }
        _effects.update();
        return handled;
    }

    // BACK on the idle "Press Start" screen closes this session and returns
    // true, so the navigator goes back to ball selection (REC-11): every run
    // has already synced or been dropped, so nothing is lost. Otherwise it
    // offers to discard while labelling and to quit while recording. While
    // syncing it is consumed and does nothing (REC-9): falling through to the
    // system would close the app mid-transfer.
    public function onBack() as Boolean {
        if (_menu != null || _exited) {
            return false;
        }
        if (_phase == PHASE_IDLE) {
            close();
            return true;
        }
        if (_phase == PHASE_LABELING) {
            openMenu(new MenuSpec(MENU_DISCARD, "Discard run?", [
                new MenuItemSpec(ITEM_DISCARD_CONFIRM, "Yes", null),
                new MenuItemSpec(ITEM_DISCARD_CANCEL, "Continue", null)
            ] as Array<MenuItemSpec>));
        } else if (_phase == PHASE_RECORDING) {
            openMenu(new MenuSpec(MENU_QUIT, "Quit? Lose run", [
                new MenuItemSpec(ITEM_QUIT_CONFIRM, "Yes", null),
                new MenuItemSpec(ITEM_QUIT_CONTINUE, "Continue", null)
            ] as Array<MenuItemSpec>));
        }
        return false;
    }

    // UP while labelling. Returns whether it was used.
    public function onUp() as Boolean {
        if (_phase != PHASE_LABELING || _menu != null) {
            return false;
        }
        _labelCount += 1;
        _effects.update();
        return true;
    }

    // DOWN while labelling. REC-3: the label never goes below 0.
    public function onDown() as Boolean {
        if (_phase != PHASE_LABELING || _menu != null) {
            return false;
        }
        if (_labelCount > 0) {
            _labelCount -= 1;
        }
        _effects.update();
        return true;
    }

    // ── Menus ─────────────────────────────────────────────────────────

    private function openMenu(spec as MenuSpec) as Void {
        _menu = spec;
        _effects.showMenu(spec, method(:onMenuSelect), method(:onMenuBack));
        _effects.update();
    }

    private function showSyncMenu() as Void {
        if (_menu != null) {
            return;
        }
        var reason = _failReason;
        openMenu(new MenuSpec(MENU_SYNC, reason == null ? "Sync failed" : reason, [
            new MenuItemSpec(ITEM_SYNC_RETRY, "Retry sync", null),
            new MenuItemSpec(ITEM_SYNC_SKIP, "Skip (lose data)", null),
            new MenuItemSpec(ITEM_SYNC_QUIT, "Quit", null)
        ] as Array<MenuItemSpec>));
    }

    public function onMenuSelect(itemId as Object) as Void {
        var open = _menu;
        if (open == null) {
            return;
        }
        _menu = null;
        if (ITEM_DISCARD_CONFIRM.equals(itemId)) {
            discardRun();
        } else if (ITEM_QUIT_CONFIRM.equals(itemId)) {
            exit();
        } else if (ITEM_SYNC_RETRY.equals(itemId)) {
            onSyncRetry();
        } else if (ITEM_SYNC_SKIP.equals(itemId)) {
            onSyncSkip();
        } else if (ITEM_SYNC_QUIT.equals(itemId)) {
            cancelSyncTimer();
            cancelStatusTimer();
            exit();
        }
        _effects.update();
    }

    // REC-10: backing out of any menu clears it. Out of the sync-failure menu
    // it retries, the only choice that keeps the run; out of the quit or
    // discard prompt it means Continue.
    public function onMenuBack() as Void {
        var open = _menu;
        if (open == null) {
            return;
        }
        _menu = null;
        if (open.kind.equals(MENU_SYNC)) {
            onSyncRetry();
        }
        _effects.update();
    }

    // ── Samples ───────────────────────────────────────────────────────

    // One accelerometer batch, its last sample taken at `nowMs`. Samples
    // count only while recording, and not while a menu is up.
    public function onBatch(xs as Array, ys as Array, zs as Array, nowMs as Number) as Void {
        if (_exited || _menu != null) {
            return;
        }
        var n = batchLength(xs, ys, zs);
        var batchStartMs = nowMs - (n - 1) * SAMPLE_PERIOD_MS;
        for (var i = 0; i < n; i++) {
            if (_phase != PHASE_RECORDING) {
                break;
            }
            // SENS-3: a short stream is padded with nulls; skip them.
            var x = xs[i];
            var y = ys[i];
            var z = zs[i];
            if (x == null || y == null || z == null) {
                continue;
            }
            processSample(x.toNumber(), y.toNumber(), z.toNumber(), batchStartMs + i * SAMPLE_PERIOD_MS);
        }
        _effects.update();
    }

    private function processSample(x as Number, y as Number, z as Number, nowMs as Number) as Void {
        // REC-2: cap the run length to avoid running out of memory.
        if (_samples.isFull()) {
            finishRun();
            return;
        }
        if (_samples.size() % MEMORY_CHECK_EVERY == 0) {
            var free = _effects.freeMemory();
            if (free < MIN_FREE_MEMORY) {
                _effects.log("Low memory, ending run at " + _samples.size() + " samples, free=" + free);
                finishRun();
                return;
            }
        }
        _samples.add(x, y, z);
        _detector.processSample(x, y, z, nowMs);
    }

    // ── Run lifecycle ─────────────────────────────────────────────────

    private function startRun() as Void {
        _failReason = null;
        _errorMsg = null;
        _phase = PHASE_RECORDING;
        _samples.clear();
        _detector = new JugglingDetector(_ballCount);
    }

    // REC-3: the detector's own count is the starting label.
    private function finishRun() as Void {
        _detectedCount = _detector.currentCount;
        _labelCount = _detectedCount;
        _phase = PHASE_LABELING;
    }

    // Drop the unlabelled run and return to idle. _runsCompleted is left alone
    // because nothing was transmitted.
    private function discardRun() as Void {
        cancelNextPartTimer();
        _pendingPayload = null;
        resetRun();
        _labelCount = 0;
        _detectedCount = 0;
    }

    // The user confirmed the watch-hand catch count: log, transmit, and wait
    // for the ack.
    private function confirmLabel() as Void {
        // REC-4: one summary line only. The samples reach the phone below;
        // printing them here as well held up the transfer by seconds.
        _effects.log("RUN_DATA,balls=" + _ballCount +
            ",catches=" + _labelCount +
            ",detected=" + _detectedCount +
            ",rate=" + SAMPLE_RATE +
            ",countMode=watch_hand" +
            ",samples=" + _samples.size());

        _sessionId = _clock.epochSeconds();
        _totalChunks = (_samples.size() + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES;
        _chunkIndex = 0;
        _headerSent = false;
        _transferDone = false;
        _pendingPayload = buildPart();
        _phase = PHASE_SYNCING;
        attemptSync();
    }

    // The part of the transfer that is due next: header, then one batch of
    // samples per chunk, then an end marker the phone answers with an ack.
    private function buildPart() as Dictionary {
        if (!_headerSent) {
            return {
                "type" => "rec_start",
                "id" => _sessionId,
                "countMode" => "watch_hand",
                "balls" => _ballCount,
                "catches" => _labelCount,
                "detected" => _detectedCount,
                "sampleRate" => SAMPLE_RATE,
                "samples" => _samples.size(),
                "chunks" => _totalChunks,
                "timestamp" => _sessionId
            };
        }
        if (_chunkIndex < _totalChunks) {
            var from = _chunkIndex * CHUNK_SAMPLES;
            var to = from + CHUNK_SAMPLES;
            if (to > _samples.size()) {
                to = _samples.size();
            }
            var xs = new [to - from];
            var ys = new [to - from];
            var zs = new [to - from];
            for (var i = from; i < to; i++) {
                xs[i - from] = _samples.get(i, 0);
                ys[i - from] = _samples.get(i, 1);
                zs[i - from] = _samples.get(i, 2);
            }
            return {
                "type" => "rec_chunk",
                "id" => _sessionId,
                "i" => _chunkIndex,
                "x" => xs,
                "y" => ys,
                "z" => zs
            };
        }
        return { "type" => "rec_end", "id" => _sessionId };
    }

    // ── Transfer with ack ─────────────────────────────────────────────

    private function attemptSync() as Void {
        var payload = _pendingPayload;
        if (payload == null) {
            return;
        }
        _errorMsg = null;
        _syncGeneration += 1;
        startSyncTimer();
        startStatusTimer();
        try {
            _link.send(payload, method(:onTransmitResult), _syncGeneration);
        } catch (ex) {
            _effects.log("Transmit error: " + ex.getErrorMessage());
            cancelSyncTimer();
            cancelStatusTimer();
            promptRetryOrQuit();
        }
        _effects.update();
    }

    public function onTransmitResult(generation as Number, delivered as Boolean) as Void {
        if (generation != _syncGeneration || _phase != PHASE_SYNCING) {
            return;   // late report from an abandoned attempt
        }
        if (!delivered) {
            cancelSyncTimer();
            cancelStatusTimer();
            _failReason = "send failed";
            promptRetryOrQuit();
            _effects.update();
            return;
        }
        cancelSyncTimer();

        if (!_headerSent) {
            _headerSent = true;
        } else if (_chunkIndex < _totalChunks) {
            _chunkIndex += 1;
        } else {
            // The end marker landed; the phone acks once it has written the run.
            _transferDone = true;
            startSyncTimer();
            _effects.update();
            return;
        }
        _pendingPayload = buildPart();
        scheduleNextPart();
        _effects.update();
    }

    public function onSyncTimeout() as Void {
        _syncTimer = null;
        if (_phase == PHASE_SYNCING) {
            cancelStatusTimer();
            _failReason = "no reply from phone";
            promptRetryOrQuit();
            _effects.update();
        }
    }

    private function promptRetryOrQuit() as Void {
        _errorMsg = "Sync failed";
        showSyncMenu();
    }

    public function onPhoneMessage(data as Dictionary) as Void {
        var type = data["type"];
        if (type == null || !type.equals("ack")) {
            return;
        }
        if (_phase != PHASE_SYNCING) {
            return;
        }
        // REC-8: every rec_end ack echoes the id the phone received. A sync
        // that timed out or was skipped still gets a reply if the phone
        // finishes late; unchecked, that stale ack would land on whichever
        // run is syncing by then and mark it delivered before it was sent.
        if (data["timestamp"] != _sessionId) {
            return;
        }

        cancelSyncTimer();
        cancelStatusTimer();
        if (_menu != null) {
            _menu = null;
            _effects.closeMenu();
        }
        cancelNextPartTimer();
        _pendingPayload = null;
        resetRun();
        _runsCompleted += 1;
        _effects.update();
    }

    private function onSyncRetry() as Void {
        _errorMsg = null;
        if (_pendingPayload == null) {
            // The ack landed after the menu opened, so there is nothing left
            // to send. Treat the run as delivered.
            _failReason = null;
            _phase = PHASE_IDLE;
            _runsCompleted += 1;
            return;
        }
        // Restart from the header: a partial transfer on the phone is
        // discarded when a new rec_start for the same run arrives.
        _headerSent = false;
        _chunkIndex = 0;
        _transferDone = false;
        _pendingPayload = buildPart();
        scheduleNextPart();
    }

    private function onSyncSkip() as Void {
        _syncGeneration += 1;
        _failReason = null;
        cancelSyncTimer();
        cancelStatusTimer();
        cancelNextPartTimer();
        _pendingPayload = null;
        resetRun();
        // A skipped run never reached the phone, so "Runs" does not count it.
    }

    // REC-7: transmitting from inside a ConnectionListener callback wedges
    // the single outstanding-transmit slot: the call never reports back.
    // Hopping through a timer runs the next send on a clean stack.
    private function scheduleNextPart() as Void {
        cancelNextPartTimer();
        _nextPartTimer = _scheduler.schedule(NEXT_PART_DELAY_MS, method(:onNextPart), false);
    }

    public function onNextPart() as Void {
        _nextPartTimer = null;
        if (_phase == PHASE_SYNCING) {
            attemptSync();
        }
    }

    private function resetRun() as Void {
        _detector = new JugglingDetector(_ballCount);
        _samples.clear();
        _phase = PHASE_IDLE;
        _errorMsg = null;
    }

    private function startSyncTimer() as Void {
        cancelSyncTimer();
        _syncTimer = _scheduler.schedule(SYNC_TIMEOUT_MS, method(:onSyncTimeout), false);
    }

    private function cancelSyncTimer() as Void {
        var timer = _syncTimer;
        if (timer != null) {
            timer.cancel();
            _syncTimer = null;
        }
    }

    private function cancelNextPartTimer() as Void {
        var timer = _nextPartTimer;
        if (timer != null) {
            timer.cancel();
            _nextPartTimer = null;
        }
    }

    private function startStatusTimer() as Void {
        cancelStatusTimer();
        _syncDots = 1;
        _statusTimer = _scheduler.schedule(STATUS_TICK_MS, method(:onStatusTick), true);
    }

    private function cancelStatusTimer() as Void {
        var timer = _statusTimer;
        if (timer != null) {
            timer.cancel();
            _statusTimer = null;
        }
        _syncDots = 0;
    }

    public function onStatusTick() as Void {
        _syncDots = (_syncDots % 3) + 1;
        _effects.update();
    }

    private function exit() as Void {
        if (_exited) {
            return;
        }
        close();
        _effects.exit();
    }

    // Stops every timer and the phone listener; the session ignores all
    // input after this. Dropping the listener also breaks the reference
    // cycle through the link, so the 18 KB sample buffer is freed when the
    // user steps back to ball selection.
    private function close() as Void {
        _exited = true;
        cancelSyncTimer();
        cancelStatusTimer();
        cancelNextPartTimer();
        _link.setMessageListener(null);
        _effects.update();
    }
}
