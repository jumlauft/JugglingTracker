import Toybox.Attention;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

class MainView extends WatchUi.View {
    private const SAMPLE_RATE = 25; // Hz, supported by the FR245 accelerometer
    private const PERIOD_SECONDS = 1; // seconds of buffering per callback
    // Give up waiting for the phone's ack after this long. The full round trip
    // (watch -> phone transport -> app -> ack -> watch) can take several seconds
    // over the Garmin link, so keep this generous to avoid false "sync failed".
    private const SYNC_TIMEOUT_MS = 10000;

    private var _listener as CommListener;
    private var _sending as Boolean;
    private var _detector as JugglingDetector;
    private var _errorMsg as String?;
    // Pushing any view on top of this one (the session-end menu, the back
    // menu, a sync-retry menu) hides this view and, on real hardware, drops
    // the accelerometer listener with it -- the same lifecycle gotcha
    // RecordingView already works around. Without re-registering in onShow,
    // popping back to MainView leaves the screen frozen forever at whatever
    // was last drawn, with detection silently stopped: pressing Start/Stop
    // then Continue, or Start/Stop mid-run, both routed through such a menu.
    private var _sensorActive as Boolean;

    // Timer that fires if a sync attempt does not complete within SYNC_TIMEOUT_MS.
    private var _syncTimer as Timer.Timer?;
    // Payload kept so a sync can be retried after a failure/timeout.
    private var _pendingPayload as Dictionary?;
    // True while the retry/force-quit confirmation dialog is on screen.
    private var _awaitingDecision as Boolean;

    // Last watch-hand catch count at which we vibrated.
    private var _lastVibrateCount as Number;

    // Monotonic timer value when this tracking session screen started.
    private var _sessionStartMs as Number;
    // Monotonic timer value when the user ended the session, before sync menu time.
    private var _sessionEndMs as Number?;

    // Repeating 1s timer that animates the "Sync to phone..." status by adding
    // a dot each second while we wait for the phone's acknowledgement.
    private var _statusTimer as Timer.Timer?;
    private var _syncDots as Number;

    public function initialize(ballCount as Number) {
        WatchUi.View.initialize();
        _listener = new CommListener(self);
        _sending = false;
        _detector = new JugglingDetector(ballCount);
        _errorMsg = null;
        _syncTimer = null;
        _pendingPayload = null;
        _awaitingDecision = false;
        _statusTimer = null;
        _syncDots = 0;
        _lastVibrateCount = 0;
        _sessionStartMs = System.getTimer();
        _sessionEndMs = null;

        // Listen for the phone's acknowledgement that a session was received.
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));

        _sensorActive = false;
        startSensor();
    }

    // Registers the accelerometer if it is not already active. Idempotent so
    // it is safe to call from both initialize() and onShow() -- the latter
    // fires every time this view is re-shown after a menu is popped, which
    // is the only path that actually needs it after the first time.
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
        } catch (ex) {
            System.println("Sensor registration error: " + ex.getErrorMessage());
        }
    }

    // Re-acquire the sensor listener every time this view becomes visible
    // again -- see the comment on _sensorActive for why this is necessary.
    public function onShow() as Void {
        startSensor();
    }

    public function onUpdate(dc as Dc) as Void {
        if (dc == null || _detector == null) {
            return;
        }

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        // While syncing, show fullscreen animated sync status with proper centering.
        if (_sending) {
            var dots = "";
            for (var d = 0; d < _syncDots; d++) {
                dots += ".";
            }
            dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
            var syncText = "Sync to phone" + dots;
            var fontH = dc.getFontHeight(Graphics.FONT_MEDIUM);
            var y = (dc.getHeight() / 2) - (fontH / 2);
            dc.drawText(dc.getWidth() / 2, y, Graphics.FONT_MEDIUM, syncText, Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var leftX = cx / 2;   // Left column
        var rightX = cx + cx / 2;  // Right column

        var numberH = dc.getFontHeight(Graphics.FONT_NUMBER_THAI_HOT);
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var statsH = dc.getFontHeight(Graphics.FONT_XTINY);
        var statusH = dc.getFontHeight(Graphics.FONT_XTINY);

        // Layout: run state, top section (label + big number), then stats below.
        var blockH = statusH + labelH + numberH + statsH * 3;
        var y = cy - blockH / 2;

        var runActive = _detector.isRunActive();
        var statusText = runActive ? "RUN ACTIVE" : "WAITING";
        dc.setColor(runActive ? Graphics.COLOR_GREEN : Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY, statusText, Graphics.TEXT_JUSTIFY_CENTER);
        y += statusH;

        // Count label, just above the big number (no large gap).
        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Catches in watch hand", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        // Current run count, large, centered.
        dc.drawText(cx, y, Graphics.FONT_NUMBER_THAI_HOT, _detector.currentCount.toString(), Graphics.TEXT_JUSTIFY_CENTER);
        y += numberH;

        // Two columns of stats below the big number.
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        
        // Row 1: Prev (left) | Runs (right)
        var prevStr = _detector.previousCount == 0 ? "-" : _detector.previousCount.toString();
        dc.drawText(leftX, y, Graphics.FONT_XTINY, Lang.format("Prev: $1$", [prevStr]), Graphics.TEXT_JUSTIFY_CENTER);
        var runsStr = _detector.sessionRuns().toString();
        dc.drawText(rightX, y, Graphics.FONT_XTINY, Lang.format("Runs: $1$", [runsStr]), Graphics.TEXT_JUSTIFY_CENTER);
        y += statsH;
        
        // Row 2: Avg (left) | Max (right)
        var avgStr = _detector.sessionAverage() == 0.0f ? "-" : _detector.sessionAverage().format("%.1f");
        dc.drawText(leftX, y, Graphics.FONT_XTINY, Lang.format("Avg: $1$", [avgStr]), Graphics.TEXT_JUSTIFY_CENTER);
        var maxStr = _detector.sessionMax == 0 ? "-" : _detector.sessionMax.toString();
        dc.drawText(rightX, y, Graphics.FONT_XTINY, Lang.format("Max: $1$", [maxStr]), Graphics.TEXT_JUSTIFY_CENTER);
        y += statsH;

        var elapsedStr = formatElapsedSeconds(sessionDurationSeconds());
        dc.drawText(cx, y, Graphics.FONT_XTINY, Lang.format("Time: $1$", [elapsedStr]), Graphics.TEXT_JUSTIFY_CENTER);

        // Error banner at the bottom if a transfer failed.
        if (_errorMsg != null) {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, dc.getHeight() - statsH * 2, Graphics.FONT_XTINY, _errorMsg, Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

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

        // Run watch-hand catch detection locally on every sample in the batch.
        // Interpolate per-sample timestamps so the refractory period works
        // correctly within a single batch (at 25 Hz samples are 40ms apart).
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
            _detector.processSample(x.toNumber(), y.toNumber(), z.toNumber(), sampleMs);
        }

        // Vibrate every 10 watch-hand catches as tactile feedback.
        var count = _detector.currentCount;
        if (count > 0 && count / 10 > _lastVibrateCount / 10) {
            if (Attention has :vibrate) {
                Attention.vibrate([new Attention.VibeProfile(50, 200)]);
            }
            _lastVibrateCount = count;
        }

        var finished = _detector.checkAutoFinish(now);
        if (finished > 0) {
            _lastVibrateCount = 0;
        }
        WatchUi.requestUpdate();
    }

    // Show the session-end menu with three options:
    // - Sync and quit / Retry sync
    // - Quit without syncing
    // - Continue session
    public function showSessionEndMenu(isRetry as Boolean) as Void {
        if (_awaitingDecision) {
            return;  // Already showing a confirmation dialog.
        }
        _awaitingDecision = true;
        if (!isRetry && _sessionEndMs == null) {
            _sessionEndMs = System.getTimer();
        }

        var menu = new WatchUi.Menu2({ :title => isRetry ? "Sync failed" : "End session?" });
        if (isRetry) {
            menu.addItem(new WatchUi.MenuItem("Retry sync", null, :sync_retry, null));
        } else {
            menu.addItem(new WatchUi.MenuItem("Sync and quit", null, :sync_quit, null));
        }
        menu.addItem(new WatchUi.MenuItem("Quit without sync", null, :nosync_quit, null));
        menu.addItem(new WatchUi.MenuItem("Continue", null, :continue_session, null));

        WatchUi.pushView(
            menu,
            new SessionEndDelegate(self),
            WatchUi.SLIDE_IMMEDIATE
        );
    }

    // Execute sync: fold current run, build payload, and attempt transmission.
    private function doSync() as Void {
        if (_sending || _awaitingDecision) {
            return;  // Already transmitting or waiting for the user's decision.
        }

        // Fold any run still in progress into the session.
        _detector.finishCurrentRun();
        if (_sessionEndMs == null) {
            _sessionEndMs = System.getTimer();
        }

        var runs = _detector.runCatches();
        if (runs.size() == 0) {
            // No runs recorded this session; just close the app.
            System.exit();
        }

        _pendingPayload = {
            "type" => "session",
            "countMode" => "watch_hand",
            "balls" => _detector.ballCount,
            "timestamp" => Time.now().value(),
            "durationSeconds" => sessionDurationSeconds(),
            "runDurationsMillis" => _detector.runDurationsMillis(),
            "runs" => runs
        };

        attemptSync();
    }

    // Start (or retry) a transmit attempt and arm the timeout timer.
    private function attemptSync() as Void {
        if (_pendingPayload == null) {
            return;
        }

        _errorMsg = null;
        WatchUi.requestUpdate();

        try {
            _sending = true;
            startSyncTimer();
            startStatusTimer();
            Communications.transmit(_pendingPayload, null, _listener);
        } catch (ex) {
            System.println("Transmission error: " + ex.getErrorMessage());
            _sending = false;
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

    // Repeating 1s timer that drives the "Sync to phone..." dot animation.
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

    // Called once per second while sending: cycle the dots 1 -> 2 -> 3 -> 1.
    public function onStatusTick() as Void {
        _syncDots = (_syncDots % 3) + 1;
        WatchUi.requestUpdate();
    }

    // Fired after SYNC_TIMEOUT_MS. If the phone has not acknowledged receipt by
    // now, treat it as a failure and prompt the user.
    public function onSyncTimeout() as Void {
        _syncTimer = null;
        if (_sending) {
            _sending = false;
            cancelStatusTimer();
            promptRetryOrQuit();
        }
    }

    // Called when the phone sends a message. We only act on the "ack" confirming
    // the session was received and stored; that is the only thing that closes
    // the app, guaranteeing the data reached the phone.
    public function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (!(data instanceof Dictionary)) {
            return;
        }
        var type = data["type"];
        if (type == null || !type.equals("ack")) {
            return;
        }

        if (_pendingPayload == null) {
            return;
        }
        _sending = false;
        cancelSyncTimer();
        cancelStatusTimer();
        _pendingPayload = null;
        if (_awaitingDecision) {
            _awaitingDecision = false;
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        }
        System.exit();
    }

    private function promptRetryOrQuit() as Void {
        _errorMsg = "Sync failed";
        WatchUi.requestUpdate();
        showSessionEndMenu(true);
    }

    private function sessionDurationSeconds() as Number {
        var endMs = _sessionEndMs;
        if (endMs == null) {
            endMs = System.getTimer();
        }
        var elapsedMs = endMs - _sessionStartMs;
        if (elapsedMs < 0) {
            elapsedMs = 0;
        }
        return elapsedMs / 1000;
    }

    private function twoDigits(value as Number) as String {
        if (value < 10) {
            return "0" + value.toString();
        }
        return value.toString();
    }

    private function formatElapsedSeconds(totalSeconds as Number) as String {
        var hours = totalSeconds / 3600;
        var minutes = (totalSeconds / 60) % 60;
        var seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours.toString() + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
        }
        return minutes.toString() + ":" + twoDigits(seconds);
    }

    public function onSyncQuit() as Void {
        _awaitingDecision = false;
        doSync();
    }

    public function onSyncRetry() as Void {
        _awaitingDecision = false;
        _errorMsg = null;
        attemptSync();
    }

    public function onQuitWithoutSync() as Void {
        _awaitingDecision = false;
        cancelSyncTimer();
        System.exit();
    }

    public function onContinueSession() as Void {
        _awaitingDecision = false;
        _sessionEndMs = null;
    }

    public function onTransmitDone() as Void {
        // Intentionally left as a no-op; waiting for the phone ACK.
    }

    public function onTransmitError() as Void {
        if (!_sending) {
            return;
        }
        _sending = false;
        cancelSyncTimer();
        cancelStatusTimer();
        promptRetryOrQuit();
    }

    public function onHide() as Void {
        Sensor.unregisterSensorDataListener();
        _sensorActive = false;
    }

    public function isSessionEmpty() as Boolean {
        return _detector.currentCount == 0 && _detector.sessionRuns() == 0;
    }

    // The BACK button (top-left) used to fall through to the system default,
    // which pops the only view on the stack and kills the app instantly --
    // silently discarding whatever had been juggled. It now means "throw away
    // the run I am looking at", and only ever that: the run in progress if one
    // is active (it is stopped and dropped without ever being recorded),
    // otherwise the last completed run of the session. Ending the session stays
    // on START/STOP, so a back press can neither quit nor sync by accident.
    // The discard is destructive and one button press away, so it always goes
    // through a yes/no confirmation first.
    public function promptDiscardRun() as Void {
        if (_awaitingDecision) {
            return;  // Already showing a confirmation dialog.
        }

        var title;
        var detail;
        if (_detector.isRunActive()) {
            title = "Discard this run?";
            detail = Lang.format("$1$ catches", [_detector.currentCount.toString()]);
        } else if (_detector.sessionRuns() > 0) {
            title = "Discard last run?";
            detail = Lang.format("$1$ catches", [_detector.previousCount.toString()]);
        } else {
            // Nothing recorded yet, so there is nothing to confirm. Swallow the
            // press anyway: letting it reach the system would exit the app,
            // which is the very thing this handler exists to prevent.
            return;
        }

        _awaitingDecision = true;
        // A Menu2 rather than WatchUi.Confirmation: the system supplies a
        // Confirmation's yes/no labels in the *watch's* language, so on a
        // German watch this came up as "Ja"/"Nein" in the middle of an
        // otherwise English app. These labels are ours, so they stay English
        // whatever the watch is set to.
        var menu = new WatchUi.Menu2({ :title => title });
        menu.addItem(new WatchUi.MenuItem("Discard", detail, :discard_yes, null));
        menu.addItem(new WatchUi.MenuItem("Keep", null, :discard_no, null));

        WatchUi.pushView(
            menu,
            new DiscardRunDelegate(self),
            WatchUi.SLIDE_IMMEDIATE
        );
    }

    // Answer to that confirmation. On "Yes" the run is dropped and we go
    // straight back to juggling -- vibration feedback is reset so the next
    // run's 10-catch buzz counts from zero rather than from the discarded
    // run's total. On "No" nothing about the session changes.
    public function onDiscardResponse(confirmed as Boolean) as Void {
        _awaitingDecision = false;
        if (confirmed) {
            _detector.discardLastRun();
            _lastVibrateCount = 0;
        }
        WatchUi.requestUpdate();
    }
}

class DiscardRunDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as MainView;

    public function initialize(view as MainView) {
        WatchUi.Menu2InputDelegate.initialize();
        _view = view;
    }

    public function onSelect(item as WatchUi.MenuItem) as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _view.onDiscardResponse(item.getId() == :discard_yes);
    }

    // Backing out of the prompt means "keep the run". Overriding this replaces
    // the default pop, so it has to pop itself -- and it must tell the view,
    // or _awaitingDecision stays set and locks out every later menu, including
    // the session-end one.
    public function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _view.onDiscardResponse(false);
    }
}

class SessionEndDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as MainView;

    public function initialize(view as MainView) {
        WatchUi.Menu2InputDelegate.initialize();
        _view = view;
    }

    public function onSelect(item as WatchUi.MenuItem) as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        var itemId = item.getId();
        if (itemId == :sync_quit) {
            _view.onSyncQuit();
        } else if (itemId == :sync_retry) {
            _view.onSyncRetry();
        } else if (itemId == :nosync_quit) {
            _view.onQuitWithoutSync();
        } else if (itemId == :continue_session) {
            _view.onContinueSession();
        }
    }

    // Backing out of this menu is the same as picking "Continue". Without
    // this, the default pop dismissed the menu but left _awaitingDecision
    // set, which then made every later START/STOP press a no-op -- no way
    // left to end or sync the session at all.
    public function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _view.onContinueSession();
    }
}

class MainDelegate extends WatchUi.BehaviorDelegate {
    private var _view as MainView;

    public function initialize(view as MainView) {
        WatchUi.BehaviorDelegate.initialize();
        _view = view;
    }

    // The START/STOP button (top-right, KEY_ENTER) shows the session-end menu.
    // The BACK button (top-left, KEY_ESC) offers to discard the current or last
    // run instead of being left to the system default, which would exit
    // immediately and take the whole session with it.
    public function onKey(evt as WatchUi.KeyEvent) as Boolean {
        var key = evt.getKey();
        if (key == WatchUi.KEY_ENTER) {
            _view.showSessionEndMenu(false);
            return true;
        }
        if (key == WatchUi.KEY_ESC) {
            _view.promptDiscardRun();
            return true;
        }
        return false;
    }

    // Some devices deliver the back gesture here rather than through onKey.
    public function onBack() as Boolean {
        _view.promptDiscardRun();
        return true;
    }
}

class CommListener extends Communications.ConnectionListener {
    private var _view as MainView;

    function initialize(view as MainView) {
        Communications.ConnectionListener.initialize();
        _view = view;
    }

    function onComplete() {
        _view.onTransmitDone();
    }

    function onError() {
        System.println("Session data transmission failed");
        _view.onTransmitError();
    }
}
