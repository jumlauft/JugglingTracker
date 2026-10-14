import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Startup screen choosing between a normal juggling session and a recording
// session that captures raw IMU data for algorithm tuning. AppNavigator holds
// the choice; this only draws it. Skipped while
// AppNavigator.ENABLE_RECORDING_MODE is false.
class ModeSelectView extends WatchUi.View {
    private var _navigator as AppNavigator;

    public function initialize(navigator as AppNavigator) {
        WatchUi.View.initialize();
        _navigator = navigator;
    }

    public function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;

        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var medH = dc.getFontHeight(Graphics.FONT_MEDIUM);
        var explainH = dc.getFontHeight(Graphics.FONT_XTINY);
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);

        var blockH = labelH + medH + explainH + hintH;
        var y = cy - blockH / 2;

        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Mode", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        var isRecordMode = _navigator.isRecordMode;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var modeText = isRecordMode ? "Record" : "Juggle";
        dc.drawText(cx, y, Graphics.FONT_MEDIUM, modeText, Graphics.TEXT_JUSTIFY_CENTER);
        y += medH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        var explainText = isRecordMode ? "Save raw sensor data" : "Track catches live";
        dc.drawText(cx, y, Graphics.FONT_XTINY, explainText, Graphics.TEXT_JUSTIFY_CENTER);
        y += explainH;

        dc.drawText(cx, y, Graphics.FONT_XTINY, "Up/Down then Start", Graphics.TEXT_JUSTIFY_CENTER);
    }
}
