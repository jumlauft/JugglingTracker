import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Sensor;
import Toybox.Communications;
import Toybox.Math;
import Toybox.System;

// Detects juggling throws from the watch's raw accelerometer stream.
//
// The accelerometer reports samples in milli-g (gravity still included). The
// detector converts each sample to m/s^2, keeps a low-pass estimate of the
// gravity vector, projects the gravity-removed acceleration onto the "up" axis,
// and counts a throw when that vertical acceleration exceeds a threshold (after
// a refractory period). After a pause with no throws, the run is finished and
// the count is moved to "previous run".
class JugglingDetector {
    private const MILLI_G_TO_MS2 = 9.80665f / 1000.0f;
    private const GRAVITY_ALPHA = 0.9f;
    private const REFRACTORY_PERIOD_MS = 500;
    private const AUTO_FINISH_DELAY_MS = 2000;

    // Number of balls being juggled (3-9), selected at startup. Higher ball
    // counts throw faster/higher, so the detection threshold scales with it.
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
    }

    // Detection threshold (m/s^2) for the current ball count. Higher ball
    // counts are thrown harder, so the threshold rises with the ball count.
    private function threshold() as Float {
        return 9.0f + ballCount;
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
        // Otherwise the initial mismatch produces a fake throw at startup.
        if (!_gravityInitialized) {
            _gravityX = ax;
            _gravityY = ay;
            _gravityZ = az;
            _gravityInitialized = true;
        } else {
            // Low-pass filter to estimate gravity.
            _gravityX = GRAVITY_ALPHA * _gravityX + (1.0f - GRAVITY_ALPHA) * ax;
            _gravityY = GRAVITY_ALPHA * _gravityY + (1.0f - GRAVITY_ALPHA) * ay;
            _gravityZ = GRAVITY_ALPHA * _gravityZ + (1.0f - GRAVITY_ALPHA) * az;
        }

        // Linear acceleration = total - gravity.
        var lx = ax - _gravityX;
        var ly = ay - _gravityY;
        var lz = az - _gravityZ;

        var gMag = Math.sqrt(_gravityX * _gravityX + _gravityY * _gravityY + _gravityZ * _gravityZ);
        var verticalAccel = 0.0f;
        if (gMag > 0.0) {
            verticalAccel = -((lx * _gravityX) + (ly * _gravityY) + (lz * _gravityZ)) / gMag;
        }

        // Ignore the first few samples while the gravity estimate settles so a
        // stationary watch never registers a startup run.
        _samplesSeen += 1;
        if (_samplesSeen <= WARMUP_SAMPLES) {
            return;
        }

        if ((verticalAccel > threshold()) && (nowMs - _lastThrowTime > REFRACTORY_PERIOD_MS)) {
            currentCount += 2;
            _lastThrowTime = nowMs;
        }
    }

    // Finishes the current run if it has been idle long enough. Returns the
    // number of throws in the just-finished run, or -1 if no run finished.
    public function checkAutoFinish(nowMs as Number) as Number {
        if (currentCount > 0 && _lastThrowTime > 0 && (nowMs - _lastThrowTime > AUTO_FINISH_DELAY_MS)) {
            previousCount = currentCount;

            // Fold the finished run into the session statistics.
            _sessionRuns += 1;
            _sessionTotal += currentCount;
            if (currentCount > sessionMax) {
                sessionMax = currentCount;
            }

            var finished = currentCount;
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
        WatchUi.switchToView(new MainView(balls), new MainDelegate(), WatchUi.SLIDE_LEFT);
        return true;
    }
}

class MainView extends WatchUi.View {
    private const SAMPLE_RATE = 25; // Hz, supported by the FR245 accelerometer
    private const PERIOD_SECONDS = 1; // seconds of buffering per callback

    private var _listener as CommListener;
    private var _sending as Boolean;
    private var _detector as JugglingDetector;

    public function initialize(ballCount as Number) {
        WatchUi.View.initialize();
        _listener = new CommListener(self);
        _sending = false;
        _detector = new JugglingDetector(ballCount);

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
        }
    }

    public function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;

        var numberH = dc.getFontHeight(Graphics.FONT_NUMBER_THAI_HOT);
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var statsH = dc.getFontHeight(Graphics.FONT_XTINY);

        // Lay the rows out as one vertical block centred on the display:
        //   ball count / "Throws" label / big number / Prev / Avg / Max
        var blockH = statsH + labelH + numberH + statsH * 3;
        var y = cy - blockH / 2;

        // Selected ball count at the top.
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY, Lang.format("$1$ balls", [_detector.ballCount]), Graphics.TEXT_JUSTIFY_CENTER);
        y += statsH;

        // "Throws" label, just above the big number (no large gap).
        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Throws", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        // Current run count, large.
        dc.drawText(cx, y, Graphics.FONT_NUMBER_THAI_HOT, _detector.currentCount.toString(), Graphics.TEXT_JUSTIFY_CENTER);
        y += numberH;

        // Previous run and session stats, each on its own line so they fit
        // within the round display. Show "-" if no run has completed yet.
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        var prevStr = _detector.previousCount == 0 ? "-" : _detector.previousCount.toString();
        dc.drawText(cx, y, Graphics.FONT_XTINY, Lang.format("Prev: $1$", [prevStr]), Graphics.TEXT_JUSTIFY_CENTER);
        y += statsH;
        var runsStr = _detector.sessionRuns() == 0 ? "-" : _detector.sessionRuns().toString();
        dc.drawText(cx, y, Graphics.FONT_XTINY, Lang.format("Runs: $1$", [runsStr]), Graphics.TEXT_JUSTIFY_CENTER);
        y += statsH;
        var avgStr = _detector.sessionAverage() == 0.0f ? "-" : _detector.sessionAverage().format("%.1f");
        dc.drawText(cx, y, Graphics.FONT_XTINY, Lang.format("Avg: $1$", [avgStr]), Graphics.TEXT_JUSTIFY_CENTER);
        y += statsH;
        var maxStr = _detector.sessionMax == 0 ? "-" : _detector.sessionMax.toString();
        dc.drawText(cx, y, Graphics.FONT_XTINY, Lang.format("Max: $1$", [maxStr]), Graphics.TEXT_JUSTIFY_CENTER);
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
        var finishedRun = _detector.checkAutoFinish(now);
        WatchUi.requestUpdate();

        // When a run just finished, send its throw count plus the session
        // statistics to the phone. Raw accelerometer samples are no longer
        // transmitted.
        if (finishedRun < 0) {
            return;
        }

        // Only one message may be in flight at a time. Skip if the previous
        // transmit has not completed yet to avoid overflowing the Connect IQ
        // messaging channel.
        if (_sending) {
            return;
        }

        var payload = {
            "balls" => _detector.ballCount,
            "throws" => finishedRun,
            "runs" => _detector.sessionRuns(),
            "average" => _detector.sessionAverage(),
            "max" => _detector.sessionMax
        };

        try {
            _sending = true;
            Communications.transmit(payload, null, _listener);
        } catch (ex) {
            _sending = false;
        }
    }

    public function onTransmitDone() as Void {
        _sending = false;
    }

    public function onHide() as Void {
        Sensor.unregisterSensorDataListener();
    }
}

class MainDelegate extends WatchUi.InputDelegate {
    public function initialize() {
        WatchUi.InputDelegate.initialize();
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
        _view.onTransmitDone();
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
