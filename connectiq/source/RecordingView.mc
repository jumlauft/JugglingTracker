import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// Recording mode: Start starts/stops each raw accelerometer run, then the user
// labels the actual watch-hand catch count. The samples are transmitted to the
// phone, which stores them as CSV for tuning the detection algorithm offline;
// the debug log gets one RUN_DATA summary line per run, not the samples.
class RecordingView extends WatchUi.View {
    private const SAMPLE_RATE = 25;
    private const PERIOD_SECONDS = 1;
    private const MAX_RUN_SAMPLES = 3000; // 120 seconds at 25 Hz
    // The sample buffer is allocated once (see SampleBuffer), but the detector
    // and the transfer still allocate, so keep a floor and stop cleanly at it.
    private const MIN_FREE_MEMORY = 16384;
    private const MEMORY_CHECK_EVERY = 25;
    private const SYNC_TIMEOUT_MS = 10000;
    // Samples go over in batches. Measured throughput peaks here: per-message
    // cost is flat up to ~150 integers, then climbs steeply, so 100 samples
    // (300 integers) transfers ~25% slower overall than 50 does.
    private const CHUNK_SAMPLES = 50;

    private const STATE_IDLE = 0;
    private const STATE_RECORDING = 1;
    private const STATE_LABELING = 2;
    private const STATE_SYNCING = 3;

    private var _state as Number;
    private var _ballCount as Number;

    // Recording buffer for the current run (raw milli-g values), allocated
    // once and reused for every run.
    private var _samples as SampleBuffer;

    // Run the detection algorithm in parallel so we can compare its output
    // with the user-provided ground truth.
    private var _detector as JugglingDetector;

    // Labeling UI state
    private var _labelCount as Number;
    private var _detectedCount as Number;

    // Session tracking
    private var _runsCompleted as Number;

    // Sync state
    private var _syncTimer as Timer.Timer?;
    private var _statusTimer as Timer.Timer?;
    private var _syncDots as Number;
    private var _awaitingDecision as Boolean;
    private var _errorMsg as String?;
    private var _listener as RecordingCommListener;
    private var _pendingPayload as Dictionary?;
    private var _sensorActive as Boolean;
    // True when the accelerometer could not be registered. The screen then
    // shows a sensor error instead of a count that would stay at 0 (SENS-4).
    private var _sensorError as Boolean;
    private var _failReason as String?;
    private var _syncGeneration as Number;
    private var _sessionId as Number;
    private var _chunkIndex as Number;
    private var _totalChunks as Number;
    private var _headerSent as Boolean;
    private var _transferDone as Boolean;
    private var _nextPartTimer as Timer.Timer?;

    public function initialize(ballCount as Number) {
        WatchUi.View.initialize();
        _state = STATE_IDLE;
        _ballCount = ballCount;
        _samples = new SampleBuffer(MAX_RUN_SAMPLES);
        _detector = new JugglingDetector(ballCount);
        _labelCount = 0;
        _detectedCount = 0;
        _runsCompleted = 0;
        _syncTimer = null;
        _statusTimer = null;
        _syncDots = 0;
        _awaitingDecision = false;
        _errorMsg = null;
        _listener = new RecordingCommListener(self, 0);
        _pendingPayload = null;
        _sensorActive = false;
        _sensorError = false;
        _failReason = null;
        _syncGeneration = 0;
        _sessionId = 0;
        _chunkIndex = 0;
        _totalChunks = 0;
        _headerSent = false;
        _transferDone = false;
        _nextPartTimer = null;

        // Listen for the phone's acknowledgement that a recording was received.
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));

        startSensor();
    }

    // Pushing any view (sync retry, quit or discard confirmation) hides this
    // one and drops the accelerometer listener, so it has to be re-acquired on
    // every onShow or later runs record no samples at all.
    private function startSensor() as Void {
        if (_sensorActive) {
            return;
        }
        try {
            var options = {
                :period => PERIOD_SECONDS,
                :accelerometer => {
                    :enabled => true,
                    :sampleRate => SAMPLE_RATE
                }
            };
            Sensor.registerSensorDataListener(self.method(:onSensor), options);
            _sensorActive = true;
            _sensorError = false;
        } catch (ex) {
            System.println("Sensor registration error: " + ex.getErrorMessage());
            _sensorError = true;
            WatchUi.requestUpdate();
        }
    }

    private function stopSensor() as Void {
        if (!_sensorActive) {
            return;
        }
        Sensor.unregisterSensorDataListener();
        _sensorActive = false;
    }

    // ── State queries (used by RecordingDelegate) ──────────────────────

    public function isLabeling() as Boolean {
        return _state == STATE_LABELING;
    }

    public function isSyncing() as Boolean {
        return _state == STATE_SYNCING;
    }

    public function isIdle() as Boolean {
        return _state == STATE_IDLE;
    }

    public function handleStartButton() as Boolean {
        if (_state == STATE_IDLE) {
            if (_sensorError) {
                // Nothing to record without the accelerometer (SENS-4).
                return true;
            }
            startRun();
            WatchUi.requestUpdate();
            return true;
        }
        if (_state == STATE_RECORDING) {
            finishRun();
            WatchUi.requestUpdate();
            return true;
        }
        if (_state == STATE_LABELING) {
            confirmLabel();
            return true;
        }
        return false;
    }

    public function handleBackButton() as Boolean {
        if (_state == STATE_LABELING) {
            promptDiscard();
            return true;
        }
        if (_state == STATE_SYNCING) {
            // Swallow the press rather than leaving it unhandled: an
            // unhandled BACK falls through to the system default, which pops
            // the only view on the stack and exits the app instantly,
            // silently abandoning a transfer that was already in flight.
            return true;
        }
        if (_state == STATE_IDLE) {
            // REC-11: every earlier run has already synced or been dropped,
            // so nothing is lost by stepping back to ball selection.
            returnToBallSelect();
            return true;
        }
        promptQuit();
        return true;
    }

    // Leave the idle screen for ball selection, keeping mode and ball count.
    // Record mode is only reachable through the mode screen, so BACK there
    // steps back once more.
    public function returnToBallSelect() as Void {
        stopSensor();
        cancelSyncTimer();
        cancelStatusTimer();
        cancelNextPartTimer();
        Communications.registerForPhoneAppMessages(null);
        var ballView = new BallSelectView(:record);
        ballView.ballCount = _ballCount;
        ballView.backToModeSelect = true;
        WatchUi.switchToView(ballView, new BallSelectDelegate(ballView), WatchUi.SLIDE_RIGHT);
    }

    public function promptDiscard() as Void {
        if (_awaitingDecision) {
            return;
        }
        _awaitingDecision = true;
        var menu = new WatchUi.Menu2({ :title => "Discard run?" });
        menu.addItem(new WatchUi.MenuItem("Yes", null, :discard_confirm, null));
        menu.addItem(new WatchUi.MenuItem("Continue", null, :discard_cancel, null));
        WatchUi.pushView(
            menu,
            new RecordingDiscardDelegate(self),
            WatchUi.SLIDE_IMMEDIATE
        );
    }

    public function onDiscardConfirmed() as Void {
        _awaitingDecision = false;
        discardRun();
    }

    public function onDiscardCancelled() as Void {
        _awaitingDecision = false;
    }

    // Drop the unlabelled run and return to idle. _runsCompleted is left alone
    // because nothing was transmitted.
    private function discardRun() as Void {
        cancelNextPartTimer();
        _pendingPayload = null;
        _detector = new JugglingDetector(_ballCount);
        _samples.clear();
        _labelCount = 0;
        _detectedCount = 0;
        _errorMsg = null;
        _state = STATE_IDLE;
        WatchUi.requestUpdate();
    }

    // Back while recording offers to leave the app. Start drives every run
    // transition, so Back has no other job here.
    public function promptQuit() as Void {
        if (_awaitingDecision) {
            return;
        }
        _awaitingDecision = true;
        var menu = new WatchUi.Menu2({ :title => "Quit? Lose run" });
        menu.addItem(new WatchUi.MenuItem("Yes", null, :quit_confirm, null));
        menu.addItem(new WatchUi.MenuItem("Continue", null, :quit_continue, null));
        WatchUi.pushView(
            menu,
            new RecordingQuitDelegate(self),
            WatchUi.SLIDE_IMMEDIATE
        );
    }

    public function onQuitConfirmed() as Void {
        _awaitingDecision = false;
        endSession();
    }

    public function onQuitCancelled() as Void {
        _awaitingDecision = false;
    }

    public function incrementLabel() as Void {
        _labelCount += 1;
        WatchUi.requestUpdate();
    }

    public function decrementLabel() as Void {
        if (_labelCount > 0) {
            _labelCount -= 1;
        }
        WatchUi.requestUpdate();
    }

    // User confirmed the watch-hand catch count: log, transmit, and wait for ACK.
    public function confirmLabel() as Void {
        // One-line summary only. The samples reach the phone below; printing
        // them here as well held up the transfer by seconds on the watch.
        System.println("RUN_DATA,balls=" + _ballCount +
            ",catches=" + _labelCount +
            ",detected=" + _detectedCount +
            ",rate=" + SAMPLE_RATE +
            ",countMode=watch_hand" +
            ",samples=" + _samples.size());

        // ── Transmit to the companion phone app, in chunks ──
        _sessionId = Time.now().value();
        _totalChunks = (_samples.size() + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES;
        _chunkIndex = 0;
        _headerSent = false;
        _transferDone = false;
        _pendingPayload = buildPart();
        _state = STATE_SYNCING;
        attemptSync();
    }

    // The part of the transfer that is due next: header, then one batch of
    // samples per chunk, then an end marker the phone answers with an ACK.
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

    public function endSession() as Void {
        System.exit();
    }

    // ── Data transmission with ACK ─────────────────────────────────────

    private function attemptSync() as Void {
        if (_pendingPayload == null) {
            return;
        }
        _errorMsg = null;
        WatchUi.requestUpdate();

        // Each attempt gets its own id. A transmit that is abandoned (skipped)
        // still reports back later, and without this its onError() would land
        // on whichever run is syncing by then and fail it instantly.
        _syncGeneration += 1;
        _listener = new RecordingCommListener(self, _syncGeneration);

        try {
            startSyncTimer();
            startStatusTimer();
            Communications.transmit(_pendingPayload, null, _listener);
        } catch (ex) {
            System.println("Transmit error: " + ex.getErrorMessage());
            cancelSyncTimer();
            cancelStatusTimer();
            promptRetryOrQuit();
        }
    }

    private function startSyncTimer() as Void {
        cancelSyncTimer();
        _syncTimer = new Timer.Timer();
        _syncTimer.start(method(:onSyncTimeout), SYNC_TIMEOUT_MS, false);
    }

    private function cancelSyncTimer() as Void {
        if (_syncTimer != null) {
            _syncTimer.stop();
            _syncTimer = null;
        }
    }

    private function startStatusTimer() as Void {
        cancelStatusTimer();
        _syncDots = 1;
        WatchUi.requestUpdate();
        _statusTimer = new Timer.Timer();
        _statusTimer.start(method(:onStatusTick), 1000, true);
    }

    private function cancelStatusTimer() as Void {
        if (_statusTimer != null) {
            _statusTimer.stop();
            _statusTimer = null;
        }
        _syncDots = 0;
    }

    public function onStatusTick() as Void {
        _syncDots = (_syncDots % 3) + 1;
        WatchUi.requestUpdate();
    }

    public function onSyncTimeout() as Void {
        _syncTimer = null;
        if (_state == STATE_SYNCING) {
            cancelStatusTimer();
            _failReason = "no reply from phone";
            promptRetryOrQuit();
        }
    }

    public function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (!(data instanceof Dictionary)) {
            return;
        }
        var type = data["type"];
        if (type == null || !type.equals("ack")) {
            return;
        }

        if (_state != STATE_SYNCING) {
            return;
        }

        // Every rec_end/session ack the phone sends echoes back the id it
        // received (see MainActivity.sendAck). A sync that timed out or was
        // skipped still gets a reply eventually if the phone finishes late;
        // without this check that stale ack would land on whichever run is
        // syncing by then and mark it delivered before its data was sent.
        if (data["timestamp"] != _sessionId) {
            return;
        }

        cancelSyncTimer();
        cancelStatusTimer();

        if (_awaitingDecision) {
            _awaitingDecision = false;
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        }

        // ── Reset for the next run ──
        cancelNextPartTimer();
        _pendingPayload = null;
        _detector = new JugglingDetector(_ballCount);
        _samples.clear();
        _state = STATE_IDLE;
        _runsCompleted += 1;
        _errorMsg = null;
        WatchUi.requestUpdate();
    }

    private function promptRetryOrQuit() as Void {
        _errorMsg = "Sync failed";
        WatchUi.requestUpdate();
        showSyncMenu();
    }

    private function showSyncMenu() as Void {
        if (_awaitingDecision) {
            return;
        }
        _awaitingDecision = true;

        var title = (_failReason == null) ? "Sync failed" : _failReason;
        var menu = new WatchUi.Menu2({ :title => title });
        menu.addItem(new WatchUi.MenuItem("Retry sync", null, :sync_retry, null));
        menu.addItem(new WatchUi.MenuItem("Skip (lose data)", null, :sync_skip, null));
        menu.addItem(new WatchUi.MenuItem("Quit", null, :sync_quit, null));

        WatchUi.pushView(
            menu,
            new RecordingSyncDelegate(self),
            WatchUi.SLIDE_IMMEDIATE
        );
    }

    public function onSyncRetry() as Void {
        _awaitingDecision = false;
        _errorMsg = null;
        if (_pendingPayload == null) {
            // The ACK landed after the timeout menu opened, so there is
            // nothing left to send. Treat the run as delivered.
            _failReason = null;
            _state = STATE_IDLE;
            _runsCompleted += 1;
            WatchUi.requestUpdate();
            return;
        }
        // Restart from the header: a partial transfer on the phone is discarded
        // when a new rec_start for the same run arrives.
        _headerSent = false;
        _chunkIndex = 0;
        _transferDone = false;
        _pendingPayload = buildPart();
        scheduleNextPart();
    }

    // Transmitting from inside a ConnectionListener callback wedges the single
    // outstanding-transmit slot: the call never reports back. Hopping through a
    // timer runs the next send on a clean stack.
    private function scheduleNextPart() as Void {
        cancelNextPartTimer();
        _nextPartTimer = new Timer.Timer();
        _nextPartTimer.start(method(:onNextPart), 50, false);
    }

    private function cancelNextPartTimer() as Void {
        if (_nextPartTimer != null) {
            _nextPartTimer.stop();
            _nextPartTimer = null;
        }
    }

    public function onNextPart() as Void {
        _nextPartTimer = null;
        if (_state == STATE_SYNCING) {
            attemptSync();
        }
    }

    public function onSyncSkip() as Void {
        _awaitingDecision = false;
        _syncGeneration += 1;
        _failReason = null;
        cancelSyncTimer();
        cancelStatusTimer();

        // Reset for the next run without waiting for ACK.
        cancelNextPartTimer();
        _pendingPayload = null;
        _detector = new JugglingDetector(_ballCount);
        _samples.clear();
        _state = STATE_IDLE;
        _runsCompleted += 1;
        _errorMsg = null;
        WatchUi.requestUpdate();
    }

    public function onSyncQuit() as Void {
        _awaitingDecision = false;
        cancelSyncTimer();
        cancelStatusTimer();
        System.exit();
    }

    public function onTransmitDone(generation as Number) as Void {
        if (generation != _syncGeneration || _state != STATE_SYNCING) {
            return;
        }
        cancelSyncTimer();

        if (!_headerSent) {
            _headerSent = true;
        } else if (_chunkIndex < _totalChunks) {
            _chunkIndex += 1;
        } else {
            // The end marker landed; the phone ACKs once it has written the run.
            _transferDone = true;
            startSyncTimer();
            WatchUi.requestUpdate();
            return;
        }

        _pendingPayload = buildPart();
        scheduleNextPart();
    }

    public function onTransmitError(generation as Number) as Void {
        if (generation != _syncGeneration) {
            return;   // late report from an abandoned attempt
        }
        if (_state != STATE_SYNCING) {
            return;
        }
        cancelSyncTimer();
        cancelStatusTimer();
        _failReason = "send failed";
        promptRetryOrQuit();
    }

    // ── Sensor callback ────────────────────────────────────────────────

    public function onSensor(sensorData as Sensor.SensorData) as Void {
        var accel = sensorData.accelerometerData;
        if (accel == null) {
            return;
        }

        var xs = accel.x;
        var ys = accel.y;
        var zs = accel.z;
        if (xs == null || xs.size() == 0) {
            return;
        }

        var now = System.getTimer();
        var n = xs.size();
        if (ys.size() < n) { n = ys.size(); }
        if (zs.size() < n) { n = zs.size(); }
        var samplePeriodMs = 1000 / SAMPLE_RATE;
        var batchStartMs = now - (n - 1) * samplePeriodMs;

        for (var i = 0; i < n; i++) {
            // A stream that came up short for the period is padded with nulls,
            // and arithmetic on one throws inside the detector, killing the app.
            var x = xs[i];
            var y = ys[i];
            var z = zs[i];
            if (x == null || y == null || z == null) {
                continue;
            }
            var sampleMs = batchStartMs + i * samplePeriodMs;
            processSample(x.toNumber(), y.toNumber(), z.toNumber(), sampleMs);
        }

        WatchUi.requestUpdate();
    }

    private function processSample(x as Number, y as Number, z as Number, nowMs as Number) as Void {
        if (_state == STATE_RECORDING) {
            // Cap run length to avoid running out of memory.
            if (_samples.isFull()) {
                finishRun();
                return;
            }
            if (_samples.size() % MEMORY_CHECK_EVERY == 0) {
                var stats = System.getSystemStats();
                if (stats != null && stats.freeMemory < MIN_FREE_MEMORY) {
                    System.println("Low memory, ending run at " +
                        _samples.size() + " samples, free=" + stats.freeMemory);
                    finishRun();
                    return;
                }
            }
            _samples.add(x, y, z);
            _detector.processSample(x, y, z, nowMs);
        }
    }

    private function startRun() as Void {
        _failReason = null;
        _errorMsg = null;
        _state = STATE_RECORDING;
        _samples.clear();
        _detector = new JugglingDetector(_ballCount);
    }

    private function finishRun() as Void {
        _detectedCount = _detector.currentCount;
        _labelCount = _detectedCount;
        _state = STATE_LABELING;
    }

    // ── Drawing ────────────────────────────────────────────────────────

    public function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;

        // The error replaces only the screens that need samples; a run
        // already stopped can still be labelled and synced.
        if (_sensorError && (_state == STATE_IDLE || _state == STATE_RECORDING)) {
            drawSensorError(dc);
            return;
        }

        if (_state == STATE_IDLE) {
            drawIdleState(dc, cx, cy);
        } else if (_state == STATE_RECORDING) {
            drawRecordingState(dc, cx, cy);
        } else if (_state == STATE_SYNCING) {
            drawSyncingState(dc, cx, cy);
        } else {
            drawLabelingState(dc, cx, cy);
        }
    }

    // Shown instead of the tracking screen when the accelerometer could not
    // be registered (SENS-4). Short lines so it fits the smallest round
    // screens. Keep the wording in step with wearos SensorErrorScreen.
    private function drawSensorError(dc as Dc) as Void {
        var cx = dc.getWidth() / 2;
        var titleH = dc.getFontHeight(Graphics.FONT_MEDIUM);
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);
        var y = dc.getHeight() / 2 - (titleH + hintH) / 2;
        dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, "Sensor error", Graphics.TEXT_JUSTIFY_CENTER);
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y + titleH, Graphics.FONT_XTINY, "Restart the app", Graphics.TEXT_JUSTIFY_CENTER);
    }

    private function drawIdleState(dc as Dc, cx as Number, cy as Number) as Void {
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var medH = dc.getFontHeight(Graphics.FONT_MEDIUM);
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);

        var blockH = labelH + medH + hintH;
        var y = cy - blockH / 2;

        dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Ready to record", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, "Press Start", Graphics.TEXT_JUSTIFY_CENTER);
        y += medH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        var hint = _runsCompleted > 0 ? "Runs: " + _runsCompleted : "";
        dc.drawText(cx, y, Graphics.FONT_XTINY, hint, Graphics.TEXT_JUSTIFY_CENTER);
    }

    private function drawRecordingState(dc as Dc, cx as Number, cy as Number) as Void {
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var medH = dc.getFontHeight(Graphics.FONT_MEDIUM);
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);

        var blockH = labelH + medH + 2 * hintH;
        var y = cy - blockH / 2;

        dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "RECORDING", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        var seconds = _samples.size() / SAMPLE_RATE;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, seconds + "s", Graphics.TEXT_JUSTIFY_CENTER);
        y += medH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY, "Press Start when finished", Graphics.TEXT_JUSTIFY_CENTER);
        y += hintH;
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Catches per hand: " + _detector.currentCount, Graphics.TEXT_JUSTIFY_CENTER);
    }

    private function drawSyncingState(dc as Dc, cx as Number, cy as Number) as Void {
        var dots = "";
        for (var d = 0; d < _syncDots; d++) {
            dots += ".";
        }
        dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        var syncText = "Sync to phone" + dots;
        if (_headerSent && !_transferDone && _totalChunks > 0) {
            syncText = _chunkIndex + "/" + _totalChunks;
        }
        var fontH = dc.getFontHeight(Graphics.FONT_MEDIUM);
        var y = cy - fontH / 2;
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, syncText, Graphics.TEXT_JUSTIFY_CENTER);

        if (_errorMsg != null) {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            var hintH = dc.getFontHeight(Graphics.FONT_XTINY);
            dc.drawText(cx, dc.getHeight() - hintH * 3, Graphics.FONT_XTINY,
                _errorMsg, Graphics.TEXT_JUSTIFY_CENTER);
            if (_failReason != null) {
                dc.drawText(cx, dc.getHeight() - hintH * 2, Graphics.FONT_XTINY,
                    _failReason, Graphics.TEXT_JUSTIFY_CENTER);
            }
            dc.drawText(cx, dc.getHeight() - hintH, Graphics.FONT_XTINY,
                _pendingPayload == null ? "data: sent" : "data: pending",
                Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    private function drawLabelingState(dc as Dc, cx as Number, cy as Number) as Void {
        // Wrapped across short lines: the full sentences do not fit the round
        // display at a legible font size.
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);
        var numberH = dc.getFontHeight(Graphics.FONT_NUMBER_MEDIUM);

        var blockH = hintH * 5 + numberH;
        var y = cy - blockH / 2;

        dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Auto-detected " + _detectedCount, Graphics.TEXT_JUSTIFY_CENTER);
        y += hintH;
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "catches, watch hand", Graphics.TEXT_JUSTIFY_CENTER);
        y += hintH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Actual (Up/Down):", Graphics.TEXT_JUSTIFY_CENTER);
        y += hintH;

        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_NUMBER_MEDIUM,
            _labelCount.toString(), Graphics.TEXT_JUSTIFY_CENTER);
        y += numberH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Start = confirm", Graphics.TEXT_JUSTIFY_CENTER);
        y += hintH;
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Back = discard", Graphics.TEXT_JUSTIFY_CENTER);
    }

    public function onShow() as Void {
        startSensor();
    }

    public function onHide() as Void {
        stopSensor();
    }
}

// ── Input delegate for the recording view ──────────────────────────────

class RecordingDelegate extends WatchUi.BehaviorDelegate {
    private var _view as RecordingView;

    public function initialize(view as RecordingView) {
        WatchUi.BehaviorDelegate.initialize();
        _view = view;
    }

    public function onNextPage() as Boolean {
        if (_view.isLabeling()) {
            _view.decrementLabel();
            return true;
        }
        return false;
    }

    public function onPreviousPage() as Boolean {
        if (_view.isLabeling()) {
            _view.incrementLabel();
            return true;
        }
        return false;
    }

    public function onKey(evt as WatchUi.KeyEvent) as Boolean {
        var key = evt.getKey();
        if (key == WatchUi.KEY_ESC) {
            return _view.handleBackButton();
        }
        if (key == WatchUi.KEY_ENTER) {
            return _view.handleStartButton();
        }
        if (_view.isLabeling()) {
            if (key == WatchUi.KEY_UP) {
                _view.incrementLabel();
                return true;
            } else if (key == WatchUi.KEY_DOWN) {
                _view.decrementLabel();
                return true;
            }
        }
        return false;
    }

    public function onBack() as Boolean {
        return _view.handleBackButton();
    }

    public function onSelect() as Boolean {
        return _view.handleStartButton();
    }
}

// ── Comm listener that forwards results to RecordingView ───────────────

class RecordingCommListener extends Communications.ConnectionListener {
    private var _view as RecordingView;
    private var _generation as Number;

    function initialize(view as RecordingView, generation as Number) {
        Communications.ConnectionListener.initialize();
        _view = view;
        _generation = generation;
    }

    function onComplete() {
        System.println("Recording data transmitted");
        _view.onTransmitDone(_generation);
    }

    function onError() {
        System.println("Recording data transmission failed");
        _view.onTransmitError(_generation);
    }
}

// ── Menu delegate for sync retry/skip/quit ─────────────────────────────

class RecordingSyncDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as RecordingView;

    public function initialize(view as RecordingView) {
        WatchUi.Menu2InputDelegate.initialize();
        _view = view;
    }

    public function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        if (id == :sync_retry) {
            _view.onSyncRetry();
        } else if (id == :sync_skip) {
            _view.onSyncSkip();
        } else if (id == :sync_quit) {
            _view.onSyncQuit();
        }
    }

    // Backing out of this menu is the same as picking "Retry sync", the one
    // choice that keeps the run. The default pop dismissed the menu but left
    // _awaitingDecision set with the sync stalled, and BACK and START are
    // both ignored while syncing, so nothing could ever reopen the menu.
    public function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _view.onSyncRetry();
    }
}

// ── Menu delegate for the quit confirmation ────────────────────────────

class RecordingQuitDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as RecordingView;

    public function initialize(view as RecordingView) {
        WatchUi.Menu2InputDelegate.initialize();
        _view = view;
    }

    public function onSelect(item as WatchUi.MenuItem) as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        if (item.getId() == :quit_confirm) {
            _view.onQuitConfirmed();
        } else {
            _view.onQuitCancelled();
        }
    }

    // Backing out means "Continue". Overriding this replaces the default pop,
    // so it has to pop itself and tell the view, or _awaitingDecision stays
    // set and every later prompt becomes a no-op.
    public function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _view.onQuitCancelled();
    }
}

// ── Menu delegate for the discard confirmation ─────────────────────────

class RecordingDiscardDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as RecordingView;

    public function initialize(view as RecordingView) {
        WatchUi.Menu2InputDelegate.initialize();
        _view = view;
    }

    public function onSelect(item as WatchUi.MenuItem) as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        if (item.getId() == :discard_confirm) {
            _view.onDiscardConfirmed();
        } else {
            _view.onDiscardCancelled();
        }
    }

    // Backing out means "Continue". Overriding this replaces the default pop,
    // so it has to pop itself and tell the view, or _awaitingDecision stays
    // set and every later prompt becomes a no-op.
    public function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _view.onDiscardCancelled();
    }
}
