import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.WatchUi;

// The Juggle screen. It draws TrackerSession and feeds it the accelerometer;
// the logic lives in the session.
class MainView extends WatchUi.View {
    private const SAMPLE_RATE = 25; // Hz, supported by the FR245 accelerometer
    private const PERIOD_SECONDS = 1; // seconds of buffering per callback

    private var _session as TrackerSession;
    // Pushing any view on top of this one (the session-end menu, the discard
    // menu, a sync-retry menu) hides this view and, on real hardware, drops
    // the accelerometer listener with it. Without re-registering in onShow,
    // popping back leaves the screen frozen at whatever was last drawn, with
    // detection silently stopped (SENS-1).
    private var _sensorActive as Boolean;

    public function initialize(session as TrackerSession) {
        WatchUi.View.initialize();
        _session = session;
        _sensorActive = false;
        startSensor();
    }

    // SENS-2: idempotent, so onShow() right after initialize() cannot
    // register twice.
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
            Sensor.registerSensorDataListener(method(:onSensor), options);
            _sensorActive = true;
            _session.setSensorError(false);
        } catch (ex) {
            System.println("Sensor registration error: " + ex.getErrorMessage());
            _session.setSensorError(true);
        }
    }

    public function onShow() as Void {
        startSensor();
    }

    public function onHide() as Void {
        Sensor.unregisterSensorDataListener();
        _sensorActive = false;
    }

    public function onSensor(sensorData as Sensor.SensorData) as Void {
        var accel = sensorData.accelerometerData;
        if (accel == null) {
            return;
        }
        var xs = accel.x;
        var ys = accel.y;
        var zs = accel.z;
        if (xs == null || ys == null || zs == null) {
            return;
        }
        _session.onBatch(xs, ys, zs, _session.nowMs());
    }

    public function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        // While syncing, a fullscreen animated sync status.
        if (_session.isSending()) {
            dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
            var fontH = dc.getFontHeight(Graphics.FONT_MEDIUM);
            var y = (dc.getHeight() / 2) - (fontH / 2);
            dc.drawText(dc.getWidth() / 2, y, Graphics.FONT_MEDIUM, Format.syncing(_session.syncDots()), Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }

        if (_session.sensorError()) {
            drawSensorError(dc);
            return;
        }

        var detector = _session.detector();
        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var leftX = cx / 2;   // Left column
        var rightX = cx + cx / 2;  // Right column

        var numberH = dc.getFontHeight(Graphics.FONT_NUMBER_THAI_HOT);
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var statsH = dc.getFontHeight(Graphics.FONT_XTINY);
        var statusH = dc.getFontHeight(Graphics.FONT_XTINY);

        // Layout (JUG-1): run state, big number with its label below, then stats.
        var blockH = statusH + labelH + numberH + statsH * 4;
        var y = cy - blockH / 2;

        var runActive = detector.isRunActive();
        dc.setColor(runActive ? Graphics.COLOR_GREEN : Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY, Format.runState(runActive), Graphics.TEXT_JUSTIFY_CENTER);
        y += statusH;

        // Current run count, large, centered.
        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_NUMBER_THAI_HOT, detector.currentCount.toString(), Graphics.TEXT_JUSTIFY_CENTER);
        y += numberH;

        // Its label goes just below, in the same colour as the stats. The
        // watch sees one hand, so the count is catches per hand.
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Catches per hand", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        // Two columns of stats below the label.
        dc.drawText(leftX, y, Graphics.FONT_XTINY, "Prev: " + Format.countOrDash(detector.previousCount), Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(rightX, y, Graphics.FONT_XTINY, "Runs: " + detector.sessionRuns().toString(), Graphics.TEXT_JUSTIFY_CENTER);
        y += statsH;

        dc.drawText(leftX, y, Graphics.FONT_XTINY, "Avg: " + Format.averageOrDash(detector.sessionAverage()), Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(rightX, y, Graphics.FONT_XTINY, "Max: " + Format.countOrDash(detector.sessionMax), Graphics.TEXT_JUSTIFY_CENTER);
        y += statsH;

        dc.drawText(cx, y, Graphics.FONT_XTINY, "Time: " + Format.elapsed(_session.elapsedSeconds()), Graphics.TEXT_JUSTIFY_CENTER);
        y += statsH;

        // Regularity: the session's shape consistency, how alike each hand
        // cycle is to the last (SHAPE-4).
        dc.drawText(cx, y, Graphics.FONT_XTINY, "Regularity: " + Format.percentOrDash(detector.shapeConsistencyPercent()), Graphics.TEXT_JUSTIFY_CENTER);

        // Error banner at the bottom if a transfer failed.
        var banner = _session.errorMessage();
        if (banner != null) {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, dc.getHeight() - statsH * 2, Graphics.FONT_XTINY, banner, Graphics.TEXT_JUSTIFY_CENTER);
        }
    }
}

// Shown instead of a tracking screen when the accelerometer could not be
// registered (SENS-4). Short lines so it fits the smallest round screens.
// Keep the wording in step with wearos SensorErrorScreen.
function drawSensorError(dc as Dc) as Void {
    var cx = dc.getWidth() / 2;
    var titleH = dc.getFontHeight(Graphics.FONT_MEDIUM);
    var hintH = dc.getFontHeight(Graphics.FONT_XTINY);
    var y = dc.getHeight() / 2 - (titleH + hintH) / 2;
    dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
    dc.drawText(cx, y, Graphics.FONT_MEDIUM, "Sensor error", Graphics.TEXT_JUSTIFY_CENTER);
    dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
    dc.drawText(cx, y + titleH, Graphics.FONT_XTINY, "Restart the app", Graphics.TEXT_JUSTIFY_CENTER);
}
