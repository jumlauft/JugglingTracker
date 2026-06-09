import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// Recording mode: Back starts/stops each raw accelerometer run, then the user
// labels the actual watch-hand catch count. Data is transmitted to the phone
// and also logged via System.println() in a parseable CSV format so it can be
// used to tune the detection algorithm offline.
class RecordingView extends WatchUi.View {
    private const SAMPLE_RATE = 25;
    private const PERIOD_SECONDS = 1;
    private const MAX_RUN_SAMPLES = 750; // 30 seconds at 25 Hz
    private const SYNC_TIMEOUT_MS = 10000;

    private const STATE_IDLE = 0;
    private const STATE_RECORDING = 1;
    private const STATE_LABELING = 2;
    private const STATE_SYNCING = 3;

    private var _state as Number;
    private var _ballCount as Number;

    // Recording buffers for the current run (raw milli-g values)
    private var _accelX as Array<Number>;
    private var _accelY as Array<Number>;
    private var _accelZ as Array<Number>;

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

    public function initialize(ballCount as Number) {
        WatchUi.View.initialize();
        _state = STATE_IDLE;
        _ballCount = ballCount;
        _accelX = [];
        _accelY = [];
        _accelZ = [];
        _detector = new JugglingDetector(ballCount);
        _labelCount = 0;
        _detectedCount = 0;
        _runsCompleted = 0;
        _syncTimer = null;
        _statusTimer = null;
        _syncDots = 0;
        _awaitingDecision = false;
        _errorMsg = null;
        _listener = new RecordingCommListener(self);
        _pendingPayload = null;

        // Listen for the phone's acknowledgement that a recording was received.
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));

        try {
            var options = {
                :period => PERIOD_SECONDS,
                :accelerometer => {
                    :enabled => true,
                    :sampleRate => SAMPLE_RATE
                }
            };
            Sensor.registerSensorDataListener(self.method(:onSensor), options);
        } catch (ex) {
            System.println("Sensor registration error: " + ex.getErrorMessage());
        }
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

    public function handleBackButton() as Boolean {
        if (_state == STATE_IDLE) {
            startRun();
            WatchUi.requestUpdate();
            return true;
        }
        if (_state == STATE_RECORDING) {
            finishRun();
            WatchUi.requestUpdate();
            return true;
        }
        return true;
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
        // ── Parseable CSV log (for simulator console capture) ──
        System.println("RUN_DATA,balls=" + _ballCount +
            ",catches=" + _labelCount +
            ",detected=" + _detectedCount +
            ",rate=" + SAMPLE_RATE +
            ",countMode=watch_hand" +
            ",samples=" + _accelX.size());
        for (var i = 0; i < _accelX.size(); i++) {
            System.println("S," + _accelX[i] + "," + _accelY[i] + "," + _accelZ[i]);
        }
        System.println("RUN_DATA_END");

        // ── Build payload and transmit to companion phone app ──
        _pendingPayload = {
            "type" => "recording",
            "countMode" => "watch_hand",
            "balls" => _ballCount,
            "catches" => _labelCount,
            "detected" => _detectedCount,
            "sampleRate" => SAMPLE_RATE,
            "accelX" => _accelX,
            "accelY" => _accelY,
            "accelZ" => _accelZ,
            "timestamp" => Time.now().value()
        };
        _state = STATE_SYNCING;
        attemptSync();
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

        cancelSyncTimer();
        cancelStatusTimer();

        if (_awaitingDecision) {
            _awaitingDecision = false;
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        }

        // ── Reset for the next run ──
        _pendingPayload = null;
        _detector = new JugglingDetector(_ballCount);
        _accelX = [];
        _accelY = [];
        _accelZ = [];
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

        var menu = new WatchUi.Menu2({ :title => "Sync failed" });
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
        attemptSync();
    }

    public function onSyncSkip() as Void {
        _awaitingDecision = false;
        cancelSyncTimer();
        cancelStatusTimer();

        // Reset for the next run without waiting for ACK.
        _pendingPayload = null;
        _detector = new JugglingDetector(_ballCount);
        _accelX = [];
        _accelY = [];
        _accelZ = [];
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

    public function onTransmitDone() as Void {
        // No-op; waiting for the phone ACK.
    }

    public function onTransmitError() as Void {
        if (_state != STATE_SYNCING) {
            return;
        }
        cancelSyncTimer();
        cancelStatusTimer();
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
            var sampleMs = batchStartMs + i * samplePeriodMs;
            processSample(xs[i], ys[i], zs[i], sampleMs);
        }

        WatchUi.requestUpdate();
    }

    private function processSample(x as Number, y as Number, z as Number, nowMs as Number) as Void {
        if (_state == STATE_RECORDING) {
            // Cap run length to avoid running out of memory.
            if (_accelX.size() >= MAX_RUN_SAMPLES) {
                finishRun();
                return;
            }
            _accelX.add(x);
            _accelY.add(y);
            _accelZ.add(z);
            _detector.processSample(x, y, z, nowMs);
        }
    }

    private function startRun() as Void {
        _state = STATE_RECORDING;
        _accelX = [];
        _accelY = [];
        _accelZ = [];
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

    private function drawIdleState(dc as Dc, cx as Number, cy as Number) as Void {
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var medH = dc.getFontHeight(Graphics.FONT_MEDIUM);
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);

        var blockH = labelH + medH + hintH;
        var y = cy - blockH / 2;

        dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Recording", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, "Ready", Graphics.TEXT_JUSTIFY_CENTER);
        y += medH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        var hint = _runsCompleted > 0
            ? "Back start | Runs: " + _runsCompleted
            : "Back to start";
        dc.drawText(cx, y, Graphics.FONT_XTINY, hint, Graphics.TEXT_JUSTIFY_CENTER);
    }

    private function drawRecordingState(dc as Dc, cx as Number, cy as Number) as Void {
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var medH = dc.getFontHeight(Graphics.FONT_MEDIUM);
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);

        var blockH = labelH + medH + hintH;
        var y = cy - blockH / 2;

        dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "● REC", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        var seconds = _accelX.size() / SAMPLE_RATE;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, seconds + "s", Graphics.TEXT_JUSTIFY_CENTER);
        y += medH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Back stop | Hand: " + _detector.currentCount, Graphics.TEXT_JUSTIFY_CENTER);
    }

    private function drawSyncingState(dc as Dc, cx as Number, cy as Number) as Void {
        var dots = "";
        for (var d = 0; d < _syncDots; d++) {
            dots += ".";
        }
        dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        var syncText = "Sync to phone" + dots;
        var fontH = dc.getFontHeight(Graphics.FONT_MEDIUM);
        var y = cy - fontH / 2;
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, syncText, Graphics.TEXT_JUSTIFY_CENTER);

        if (_errorMsg != null) {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            var hintH = dc.getFontHeight(Graphics.FONT_XTINY);
            dc.drawText(cx, dc.getHeight() - hintH * 2, Graphics.FONT_XTINY,
                _errorMsg, Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    private function drawLabelingState(dc as Dc, cx as Number, cy as Number) as Void {
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var numberH = dc.getFontHeight(Graphics.FONT_NUMBER_THAI_HOT);
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);

        var blockH = labelH + numberH + hintH * 2;
        var y = cy - blockH / 2;

        dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Watch hand?", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_NUMBER_THAI_HOT,
            _labelCount.toString(), Graphics.TEXT_JUSTIFY_CENTER);
        y += numberH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Detected hand: " + _detectedCount, Graphics.TEXT_JUSTIFY_CENTER);
        y += hintH;

        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Up/Down, Start OK", Graphics.TEXT_JUSTIFY_CENTER);
    }

    public function onHide() as Void {
        Sensor.unregisterSensorDataListener();
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
            if (_view.isLabeling()) {
                _view.confirmLabel();
            } else if (_view.isIdle()) {
                _view.endSession();
            }
            return true;
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
        if (_view.isLabeling()) {
            _view.confirmLabel();
            return true;
        }
        return false;
    }
}

// ── Comm listener that forwards results to RecordingView ───────────────

class RecordingCommListener extends Communications.ConnectionListener {
    private var _view as RecordingView;

    function initialize(view as RecordingView) {
        Communications.ConnectionListener.initialize();
        _view = view;
    }

    function onComplete() {
        System.println("Recording data transmitted");
        _view.onTransmitDone();
    }

    function onError() {
        System.println("Recording data transmission failed");
        _view.onTransmitError();
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
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        var id = item.getId();
        if (id == :sync_retry) {
            _view.onSyncRetry();
        } else if (id == :sync_skip) {
            _view.onSyncSkip();
        } else if (id == :sync_quit) {
            _view.onSyncQuit();
        }
    }
}
