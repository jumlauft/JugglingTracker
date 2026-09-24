import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Developer-only screen: choose between a normal juggling session or a
// recording session that captures raw IMU data for algorithm tuning.
// Customer startup bypasses this while ENABLE_RECORDING_MODE is false.
class ModeSelectView extends WatchUi.View {
    public var isRecordMode as Boolean;

    public function initialize() {
        WatchUi.View.initialize();
        isRecordMode = false;
    }

    public function toggle() as Void {
        isRecordMode = !isRecordMode;
        WatchUi.requestUpdate();
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

class ModeSelectDelegate extends WatchUi.BehaviorDelegate {
    private var _view as ModeSelectView;

    public function initialize(view as ModeSelectView) {
        WatchUi.BehaviorDelegate.initialize();
        _view = view;
    }

    public function onNextPage() as Boolean {
        _view.toggle();
        return true;
    }

    public function onPreviousPage() as Boolean {
        _view.toggle();
        return true;
    }

    public function onKey(evt as WatchUi.KeyEvent) as Boolean {
        var key = evt.getKey();
        if (key == WatchUi.KEY_UP || key == WatchUi.KEY_DOWN) {
            _view.toggle();
            return true;
        }
        return false;
    }

    public function onSelect() as Boolean {
        var mode = _view.isRecordMode ? :record : :juggle;
        var ballView = new BallSelectView(mode);
        WatchUi.switchToView(ballView, new BallSelectDelegate(ballView), WatchUi.SLIDE_LEFT);
        return true;
    }
}
