import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.WatchUi;

// The Record screen. It draws RecordingSession and feeds it the
// accelerometer; the logic lives in the session.
class RecordingView extends WatchUi.View {
    private const SAMPLE_RATE = 25;
    private const PERIOD_SECONDS = 1;

    private var _session as RecordingSession;
    // Pushing any view (sync retry, quit or discard confirmation) hides this
    // one and drops the accelerometer listener, so it has to be re-acquired on
    // every onShow or later runs record no samples at all (SENS-1).
    private var _sensorActive as Boolean;

    public function initialize(session as RecordingSession) {
        WatchUi.View.initialize();
        _session = session;
        _sensorActive = false;
        startSensor();
    }

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

    private function stopSensor() as Void {
        if (!_sensorActive) {
            return;
        }
        Sensor.unregisterSensorDataListener();
        _sensorActive = false;
    }

    public function onShow() as Void {
        startSensor();
    }

    // Also runs when BACK leaves for ball selection (REC-11).
    public function onHide() as Void {
        stopSensor();
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

    // ── Drawing ────────────────────────────────────────────────────────

    public function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var phase = _session.phase();

        // The error replaces only the screens that need samples; a run
        // already stopped can still be labelled and synced.
        if (_session.sensorError() &&
                (phase == RecordingSession.PHASE_IDLE || phase == RecordingSession.PHASE_RECORDING)) {
            drawSensorError(dc);
            return;
        }

        if (phase == RecordingSession.PHASE_IDLE) {
            drawIdleState(dc, cx, cy);
        } else if (phase == RecordingSession.PHASE_RECORDING) {
            drawRecordingState(dc, cx, cy);
        } else if (phase == RecordingSession.PHASE_SYNCING) {
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
        dc.drawText(cx, y, Graphics.FONT_TINY, "Ready to record", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, "Press Start", Graphics.TEXT_JUSTIFY_CENTER);
        y += medH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        var runs = _session.runsCompleted();
        var hint = runs > 0 ? "Runs: " + runs : "";
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

        var seconds = _session.recordedSamples() / SAMPLE_RATE;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, seconds + "s", Graphics.TEXT_JUSTIFY_CENTER);
        y += medH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY, "Press Start when finished", Graphics.TEXT_JUSTIFY_CENTER);
        y += hintH;
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Catches per hand: " + _session.liveCount(), Graphics.TEXT_JUSTIFY_CENTER);
    }

    private function drawSyncingState(dc as Dc, cx as Number, cy as Number) as Void {
        dc.setColor(Graphics.COLOR_YELLOW, Graphics.COLOR_TRANSPARENT);
        var fontH = dc.getFontHeight(Graphics.FONT_MEDIUM);
        var y = cy - fontH / 2;
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, Format.recordingSyncText(_session), Graphics.TEXT_JUSTIFY_CENTER);

        var banner = _session.errorMessage();
        if (banner != null) {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            var hintH = dc.getFontHeight(Graphics.FONT_XTINY);
            dc.drawText(cx, dc.getHeight() - hintH * 3, Graphics.FONT_XTINY,
                banner, Graphics.TEXT_JUSTIFY_CENTER);
            var reason = _session.failReason();
            if (reason != null) {
                dc.drawText(cx, dc.getHeight() - hintH * 2, Graphics.FONT_XTINY,
                    reason, Graphics.TEXT_JUSTIFY_CENTER);
            }
            dc.drawText(cx, dc.getHeight() - hintH, Graphics.FONT_XTINY,
                _session.dataPending() ? "data: pending" : "data: sent",
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
            "Auto-detected " + _session.detectedCount(), Graphics.TEXT_JUSTIFY_CENTER);
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
            _session.labelCount().toString(), Graphics.TEXT_JUSTIFY_CENTER);
        y += numberH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Start = confirm", Graphics.TEXT_JUSTIFY_CENTER);
        y += hintH;
        dc.drawText(cx, y, Graphics.FONT_XTINY,
            "Back = discard", Graphics.TEXT_JUSTIFY_CENTER);
    }
}
