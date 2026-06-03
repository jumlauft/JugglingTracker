import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Sensor;
import Toybox.Communications;
import Toybox.Math;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
// The accelerometer reports samples in milli-g (gravity still included). The
// detector converts each sample to m/s², keeps a low-pass estimate of the
// gravity vector, removes gravity, and computes the magnitude of the remaining
// linear acceleration. True peak detection (local maxima above threshold with
// hysteresis) counts throws. Each detected peak adds 2 to the count (one wrist
// sensor sees one arm; doubled for the other hand). After a pause with no
// throws the run is finished and the count moves to "previous run".
class JugglingDetector {
    private const MILLI_G_TO_MS2 = 9.80665f / 1000.0f;

    // Gravity low-pass filter coefficient.
    // During active juggling the filter slows down (_ACTIVE) to prevent gravity
    // drift from absorbing the sustained arm motion and attenuating the signal.
    private const GRAVITY_ALPHA_IDLE = 0.95f;
    private const GRAVITY_ALPHA_ACTIVE = 0.99f;

    // Ball-count-dependent refractory: REFRACTORY_BASE_MS / (ballCount - 1).
    // 3 balls → 300ms, 5 balls → 150ms, 7 balls → 100ms.
    private const REFRACTORY_BASE_MS = 600;

    private const AUTO_FINISH_DELAY_MS = 2000;

    // Number of balls being juggled (3-9), selected at startup.
    public var ballCount as Number;

    private var _gravityX as Float;
    private var _gravityY as Float;
    private var _gravityZ as Float;
    private var _lastThrowTime as Number;

    // Number of samples to wait before detecting throws, giving the gravity
    // estimate time to settle. Prevents a spurious run at startup.
    private const WARMUP_SAMPLES = 25;
    private var _samplesSeen as Number;
    private var _gravityInitialized as Boolean;

    public var currentCount as Number;
    public var previousCount as Number;

    // Session statistics over completed runs.
    private var _sessionRuns as Number;
    private var _sessionTotal as Number;
    public var sessionMax as Number;

    // Throw counts of each completed run this session, in order.
    private var _runThrows as Array<Number>;

    // True peak detection state.
    // _prevMag / _prevPrevMag: two-sample history for 3-point moving average
    // and local-maximum detection.
    private var _prevMag as Float;
    private var _prevPrevMag as Float;
    // Hysteresis: after detecting a peak we require the signal to drop below
    // threshold * HYSTERESIS_LOW_FACTOR before another peak can fire.
    private const HYSTERESIS_LOW_FACTOR = 0.5f;
    private var _armed as Boolean;  // true = ready to detect next peak

    // Set to true to print per-sample debug info via System.println().
    private const DEBUG_LOG = false;

    public function initialize(balls as Number) {
        ballCount = balls;
        _gravityX = 0.0f;
        _gravityY = 0.0f;
        _gravityZ = 9.80665f;
        _lastThrowTime = 0;
        _samplesSeen = 0;
        _gravityInitialized = false;
        currentCount = 0;
        previousCount = 0;
        _sessionRuns = 0;
        _sessionTotal = 0;
        sessionMax = 0;
        _runThrows = [];
        _prevMag = 0.0f;
        _prevPrevMag = 0.0f;
        _armed = true;
    }

    // Throw counts of all completed runs this session, in order.
    public function runThrows() as Array<Number> {
        return _runThrows;
    }

    // Fold a finished run's throw count into the session statistics and the
    // per-run list. Shared by auto-finish and manual session end.
    private function recordRun(throws as Number) as Void {
        previousCount = throws;
        _sessionRuns += 1;
        _sessionTotal += throws;
        if (throws > sessionMax) {
            sessionMax = throws;
        }
        _runThrows.add(throws);
    }

    // Ends a run that is still in progress (e.g. when the user stops the
    // session manually). Records it if it has any throws. Returns the count.
    public function finishCurrentRun() as Number {
        if (currentCount > 0) {
            var finished = currentCount;
            recordRun(finished);
            currentCount = 0;
            _lastThrowTime = 0;
            return finished;
        }
        return 0;
    }

    // Detection threshold (m/s²) for the current ball count. Lower counts use
    // gentler thresholds since the balls aren't thrown as hard.
    private function threshold() as Float {
        return 5.0f + (0.5f * ballCount);
    }

    // Refractory period (ms) between consecutive throws. Faster patterns
    // (more balls) get a shorter refractory so rapid throws aren't missed.
    private function refractoryMs() as Number {
        return REFRACTORY_BASE_MS / (ballCount - 1);
    }

    // Average throws per completed run this session (0.0 if no runs yet).
    public function sessionAverage() as Float {
        if (_sessionRuns == 0) {
            return 0.0f;
        }
        return _sessionTotal.toFloat() / _sessionRuns;
    }

    // Number of completed runs in the current session.
    public function sessionRuns() as Number {
        return _sessionRuns;
    }

    // Feed one raw accelerometer sample (milli-g) at time nowMs.
    public function processSample(gxMilliG as Number, gyMilliG as Number, gzMilliG as Number, nowMs as Number) as Void {
        var ax = gxMilliG * MILLI_G_TO_MS2;
        var ay = gyMilliG * MILLI_G_TO_MS2;
        var az = gzMilliG * MILLI_G_TO_MS2;

        // Seed the gravity estimate from the first real sample so it matches the
        // watch's actual orientation instead of an assumed "down" direction.
        if (!_gravityInitialized) {
            _gravityX = ax;
            _gravityY = ay;
            _gravityZ = az;
            _gravityInitialized = true;
        } else {
            // Slow down gravity adaptation during active juggling to prevent
            // the estimate from drifting toward sustained arm motion.
            var alpha = (currentCount > 0) ? GRAVITY_ALPHA_ACTIVE : GRAVITY_ALPHA_IDLE;
            _gravityX = alpha * _gravityX + (1.0f - alpha) * ax;
            _gravityY = alpha * _gravityY + (1.0f - alpha) * ay;
            _gravityZ = alpha * _gravityZ + (1.0f - alpha) * az;
        }

        // Linear acceleration = total - gravity.
        var lx = ax - _gravityX;
        var ly = ay - _gravityY;
        var lz = az - _gravityZ;

        // Acceleration magnitude — captures throw energy in all directions
        // (cascade, shower, columns) rather than only the vertical component.
        var mag = Math.sqrt(lx * lx + ly * ly + lz * lz).toFloat();

        // 3-point moving average to smooth single-sample noise spikes.
        var smoothed = ((_prevPrevMag + _prevMag + mag) / 3.0f).toFloat();

        _samplesSeen += 1;

        // Ignore the first few samples while the gravity estimate settles so a
        // stationary watch never registers a startup run.
        if (_samplesSeen <= WARMUP_SAMPLES) {
            _prevPrevMag = _prevMag;
            _prevMag = mag;
            return;
        }

        // True peak detection: a peak is a local maximum in the smoothed signal
        // that exceeds the threshold, with hysteresis to avoid multiple
        // detections from noisy oscillation near the threshold.
        var thresholdValue = threshold();
        var refractory = refractoryMs();

        // Re-arm once signal drops below hysteresis band.
        if (!_armed && smoothed < thresholdValue * HYSTERESIS_LOW_FACTOR) {
            _armed = true;
        }

        // Detect local maximum: previous smoothed value was higher than both
        // its neighbors (current and the one before it), exceeded threshold,
        // detector is armed, and refractory period has elapsed.
        // We evaluate the *previous* smoothed value so we have a one-sample
        // look-ahead to confirm the signal is falling.
        var prevSmoothed = ((_prevPrevMag + _prevMag + _prevMag) / 3.0f).toFloat();
        // Approximate: we don't have the sample before _prevPrevMag, so use
        // the two-point average for the "before" neighbor.
        var isPeak = (_prevMag >= mag) && (_prevMag >= _prevPrevMag) &&
                     (prevSmoothed > thresholdValue) &&
                     _armed &&
                     (nowMs - _lastThrowTime > refractory);

        if (isPeak) {
            currentCount += 2;  // Both hands: one detected peak = 2 throws
            _lastThrowTime = nowMs;
            _armed = false;  // Require signal to drop before next detection
        }

        if (DEBUG_LOG) {
            System.println("JDET: mag=" + mag.format("%.1f") +
                " sm=" + smoothed.format("%.1f") +
                " thr=" + thresholdValue.format("%.1f") +
                " arm=" + _armed +
                " pk=" + isPeak +
                " cnt=" + currentCount);
        }

        _prevPrevMag = _prevMag;
        _prevMag = mag;
    }

    // Finishes the current run if it has been idle long enough. Returns the
    // number of throws in the just-finished run, or -1 if no run finished.
    public function checkAutoFinish(nowMs as Number) as Number {
        if (currentCount > 0 && _lastThrowTime > 0 && (nowMs - _lastThrowTime > AUTO_FINISH_DELAY_MS)) {
            var finished = currentCount;

            // Fold the finished run into the session statistics.
            recordRun(finished);

            currentCount = 0;
            _lastThrowTime = 0;

            return finished;
        }
        return -1;
    }
}

// Startup screen letting the user pick how many balls (3-9) they are juggling.
// Up/down adjust the count; select/enter confirms and opens the main tracker.
class BallSelectView extends WatchUi.View {
    public static const MIN_BALLS = 3;
    public static const MAX_BALLS = 9;

    public var ballCount as Number;

    public function initialize() {
        WatchUi.View.initialize();
        ballCount = MIN_BALLS;
    }

    public function increment() as Void {
        if (ballCount < MAX_BALLS) {
            ballCount += 1;
            WatchUi.requestUpdate();
        }
    }

    public function decrement() as Void {
        if (ballCount > MIN_BALLS) {
            ballCount -= 1;
            WatchUi.requestUpdate();
        }
    }

    public function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;

        var numberH = dc.getFontHeight(Graphics.FONT_NUMBER_THAI_HOT);
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);

        var blockH = labelH + numberH + hintH;
        var y = cy - blockH / 2;

        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Balls", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        dc.drawText(cx, y, Graphics.FONT_NUMBER_THAI_HOT, ballCount.toString(), Graphics.TEXT_JUSTIFY_CENTER);
        y += numberH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY, "Up/Down then Start", Graphics.TEXT_JUSTIFY_CENTER);
    }
}

class BallSelectDelegate extends WatchUi.BehaviorDelegate {
    private var _view as BallSelectView;

    public function initialize(view as BallSelectView) {
        WatchUi.BehaviorDelegate.initialize();
        _view = view;
    }

    public function onNextPage() as Boolean {
        _view.decrement();
        return true;
    }

    public function onPreviousPage() as Boolean {
        _view.increment();
        return true;
    }

    public function onKey(evt as WatchUi.KeyEvent) as Boolean {
        var key = evt.getKey();
        if (key == WatchUi.KEY_UP) {
            _view.increment();
            return true;
        } else if (key == WatchUi.KEY_DOWN) {
            _view.decrement();
            return true;
        }
        return false;
    }

    // Confirm the selection and switch to the main tracking screen.
    public function onSelect() as Boolean {
        var balls = _view.ballCount;
        var mainView = new MainView(balls);
        WatchUi.switchToView(mainView, new MainDelegate(mainView), WatchUi.SLIDE_LEFT);
        return true;
    }
}

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

    // Timer that fires if a sync attempt does not complete within SYNC_TIMEOUT_MS.
    private var _syncTimer as Timer.Timer?;
    // Payload kept so a sync can be retried after a failure/timeout.
    private var _pendingPayload as Dictionary?;
    // True while the retry/force-quit confirmation dialog is on screen.
    private var _awaitingDecision as Boolean;

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

        // Listen for the phone's acknowledgement that a session was received.
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

        // Layout: top section (label + big number), then 2 columns of stats below
        var blockH = labelH + numberH + statsH * 2;
        var y = cy - blockH / 2;

        // "Throws" label, just above the big number (no large gap).
        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Throws", Graphics.TEXT_JUSTIFY_CENTER);
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

        // Run throw detection locally on every sample in the batch.
        var now = System.getTimer();
        var n = xs.size();
        if (ys.size() < n) { n = ys.size(); }
        if (zs.size() < n) { n = zs.size(); }
        for (var i = 0; i < n; i++) {
            _detector.processSample(xs[i], ys[i], zs[i], now);
        }
        _detector.checkAutoFinish(now);
        WatchUi.requestUpdate();
    }

    // End the current session and transmit it to the phone as a single payload
    // containing the throws of every run, the ball count, and a timestamp.
    // On success the app closes. If the transfer fails or does not complete
    // within SYNC_TIMEOUT_MS, the user is prompted to retry or force quit
    // (losing the data); the app is never closed silently on failure.
    // Show the session-end menu with three options:
    // - Sync and quit / Retry sync
    // - Quit without syncing
    // - Continue session
    public function showSessionEndMenu(isRetry as Boolean) as Void {
        if (_awaitingDecision) {
            return;  // Already showing a confirmation dialog.
        }
        _awaitingDecision = true;

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

        var runs = _detector.runThrows();
        if (runs.size() == 0) {
            // No runs recorded this session; just close the app.
            System.exit();
        }

        _pendingPayload = {
            "type" => "session",
            "balls" => _detector.ballCount,
            "timestamp" => Time.now().value(),
            "runs" => runs
        };

        attemptSync();
    }

    // Start (or retry) a transmit attempt and arm the 3s timeout timer.
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

    // Fired 3s after a transmit was started. If the phone has not acknowledged
    // receipt by now, treat it as a failure and prompt the user.
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

        // The phone confirmed receipt. If there is still data pending (whether
        // we are actively waiting or the timeout already prompted the user),
        // the sync genuinely succeeded, so close the app. Pop the confirmation
        // dialog first if it happens to be on screen (timeout race).
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

    // Show session end menu with three options on retry.
    private function promptRetryOrQuit() as Void {
        _errorMsg = "Sync failed";
        WatchUi.requestUpdate();
        showSessionEndMenu(true);
    }

    // Called from menu: sync and quit option selected.
    public function onSyncQuit() as Void {
        _awaitingDecision = false;
        doSync();
    }

    // Called from menu: retry sync option selected.
    public function onSyncRetry() as Void {
        _awaitingDecision = false;
        _errorMsg = null;
        attemptSync();
    }

    // Called from menu: quit without syncing option selected.
    public function onQuitWithoutSync() as Void {
        _awaitingDecision = false;
        cancelSyncTimer();
        System.exit();
    }

    // Called from menu: continue session option selected.
    public function onContinueSession() as Void {
        _awaitingDecision = false;
        // Menu is already popped by SessionEndDelegate; just resume tracking.
    }

    // The message left the watch's transport layer. This does NOT mean the
    // phone app received it, so we do not exit here. We keep _sending true and
    // the timer running, and only close once the phone sends back an "ack"
    // (see onPhoneMessage). If no ack arrives within the timeout, the user is
    // prompted to retry or force quit.
    public function onTransmitDone() as Void {
        // Intentionally left as a no-op; waiting for the phone ACK.
    }

    // Called on a failed transfer: prompt the user to retry or force quit.
    public function onTransmitError() as Void {
        if (!_sending) {
            return;  // Already handled (e.g. by the timeout).
        }
        _sending = false;
        cancelSyncTimer();
        cancelStatusTimer();
        promptRetryOrQuit();
    }

    public function onHide() as Void {
        Sensor.unregisterSensorDataListener();
    }

    // Returns true if the session has no recorded throws yet.
    public function isSessionEmpty() as Boolean {
        return _detector.currentCount == 0 && _detector.sessionRuns() == 0;
    }

    // Prompt the user to confirm quitting without syncing.
    public function promptQuitWithoutSync() as Void {
        if (_awaitingDecision) {
            return;  // Already showing a confirmation dialog.
        }
        _awaitingDecision = true;
        var menu = new WatchUi.Menu2({ :title => "Quit without sync?" });
        menu.addItem(new WatchUi.MenuItem("Yes", null, :quit_confirm, null));
        menu.addItem(new WatchUi.MenuItem("No", null, :quit_cancel, null));
        menu.addItem(new WatchUi.MenuItem("Continue", null, :quit_continue, null));
        WatchUi.pushView(
            menu,
            new QuitConfirmationDelegate(self),
            WatchUi.SLIDE_IMMEDIATE
        );
    }

    // Called when user confirms quit without sync.
    public function onQuitConfirmed() as Void {
        _awaitingDecision = false;
        System.exit();
    }

    // Called when user cancels quit.
    public function onQuitCancelled() as Void {
        _awaitingDecision = false;
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
    }
}

// Menu shown when user presses Back during a session.
// "Yes" quits without syncing; "No" returns to the session.
class QuitConfirmationDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as MainView;

    public function initialize(view as MainView) {
        WatchUi.Menu2InputDelegate.initialize();
        _view = view;
    }

    public function onSelect(item as WatchUi.MenuItem) as Void {
        if (item.getId() == :quit_confirm) {
            _view.onQuitConfirmed();
        } else if (item.getId() == :quit_continue) {
            _view.onQuitCancelled();
        } else {
            _view.onQuitCancelled();
        }
    }
}

// Menu shown when user presses START/STOP or when sync fails.
// Handles sync, quit without sync, and continue session options.
class SessionEndDelegate extends WatchUi.Menu2InputDelegate {
    private var _view as MainView;

    public function initialize(view as MainView) {
        WatchUi.Menu2InputDelegate.initialize();
        _view = view;
    }

    public function onSelect(item as WatchUi.MenuItem) as Void {
        // Close the menu first, then act on the choice.
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
}

class MainDelegate extends WatchUi.BehaviorDelegate {
    private var _view as MainView;

    public function initialize(view as MainView) {
        WatchUi.BehaviorDelegate.initialize();
        _view = view;
    }

    // The START/STOP button (top-right, KEY_ENTER) shows the session-end menu.
    public function onKey(evt as WatchUi.KeyEvent) as Boolean {
        if (evt.getKey() == WatchUi.KEY_ENTER) {
            _view.showSessionEndMenu(false);
            return true;
        }
        return false;
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
        _view.onTransmitError();
    }
}

class JugglingTrackerApp extends Application.AppBase {
    function initialize() {
        Application.AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        var selectView = new BallSelectView();
        return [selectView, new BallSelectDelegate(selectView)];
    }
}
