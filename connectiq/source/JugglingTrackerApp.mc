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
    private const THRESHOLD = 14.0f; // m/s^2, tuned for 3-ball juggling

    private var _gravityX as Float;
    private var _gravityY as Float;
    private var _gravityZ as Float;
    private var _lastThrowTime as Number;

    public var currentCount as Number;
    public var previousCount as Number;

    // Session statistics over completed runs.
    private var _sessionRuns as Number;
    private var _sessionTotal as Number;
    public var sessionMax as Number;

    public function initialize() {
        _gravityX = 0.0f;
        _gravityY = 0.0f;
        _gravityZ = 9.80665f;
        _lastThrowTime = 0;
        currentCount = 0;
        previousCount = 0;
        _sessionRuns = 0;
        _sessionTotal = 0;
        sessionMax = 0;
    }

    // Average throws per completed run this session (0.0 if no runs yet).
    public function sessionAverage() as Float {
        if (_sessionRuns == 0) {
            return 0.0f;
        }
        return _sessionTotal.toFloat() / _sessionRuns;
    }

    // Feed one raw accelerometer sample (milli-g) at time nowMs.
    public function processSample(gxMilliG as Number, gyMilliG as Number, gzMilliG as Number, nowMs as Number) as Void {
        var ax = gxMilliG * MILLI_G_TO_MS2;
        var ay = gyMilliG * MILLI_G_TO_MS2;
        var az = gzMilliG * MILLI_G_TO_MS2;

        // Low-pass filter to estimate gravity.
        _gravityX = GRAVITY_ALPHA * _gravityX + (1.0f - GRAVITY_ALPHA) * ax;
        _gravityY = GRAVITY_ALPHA * _gravityY + (1.0f - GRAVITY_ALPHA) * ay;
        _gravityZ = GRAVITY_ALPHA * _gravityZ + (1.0f - GRAVITY_ALPHA) * az;

        // Linear acceleration = total - gravity.
        var lx = ax - _gravityX;
        var ly = ay - _gravityY;
        var lz = az - _gravityZ;

        var gMag = Math.sqrt(_gravityX * _gravityX + _gravityY * _gravityY + _gravityZ * _gravityZ);
        var verticalAccel = 0.0f;
        if (gMag > 0.0) {
            verticalAccel = -((lx * _gravityX) + (ly * _gravityY) + (lz * _gravityZ)) / gMag;
        }

        if ((verticalAccel > THRESHOLD) && (nowMs - _lastThrowTime > REFRACTORY_PERIOD_MS)) {
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

class MainView extends WatchUi.View {
    private const SAMPLE_RATE = 25; // Hz, supported by the FR245 accelerometer
    private const PERIOD_SECONDS = 1; // seconds of buffering per callback

    private var _listener as CommListener;
    private var _sending as Boolean;
    private var _detector as JugglingDetector;

    public function initialize() {
        WatchUi.View.initialize();
        _listener = new CommListener(self);
        _sending = false;
        _detector = new JugglingDetector();

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

        // Current run count, large and centered.
        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy - dc.getFontHeight(Graphics.FONT_NUMBER_THAI_HOT), Graphics.FONT_TINY, "Throws", Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(cx, cy, Graphics.FONT_NUMBER_THAI_HOT, _detector.currentCount.toString(), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Previous run count plus session stats, smaller below.
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        var lineH = dc.getFontHeight(Graphics.FONT_TINY);
        var statsY = cy + dc.getFontHeight(Graphics.FONT_NUMBER_THAI_HOT) / 2;
        dc.drawText(
            cx,
            statsY,
            Graphics.FONT_TINY,
            Lang.format("Prev: $1$", [_detector.previousCount]),
            Graphics.TEXT_JUSTIFY_CENTER
        );
        dc.drawText(
            cx,
            statsY + lineH,
            Graphics.FONT_TINY,
            Lang.format("Avg: $1$  Max: $2$", [_detector.sessionAverage().format("%.1f"), _detector.sessionMax]),
            Graphics.TEXT_JUSTIFY_CENTER
        );
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
            "throws" => finishedRun,
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
        return [new MainView(), new MainDelegate()];
    }
}
