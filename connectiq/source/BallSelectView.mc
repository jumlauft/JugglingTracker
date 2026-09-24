import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Startup screen letting the user pick how many balls (3-9) they are juggling.
// Up/down adjust the count; select/enter confirms and opens the main tracker.
class BallSelectView extends WatchUi.View {
    public static const MIN_BALLS = 3;
    public static const MAX_BALLS = 9;

    public var ballCount as Number;
    public var mode as Symbol;

    public function initialize(selectedMode as Symbol) {
        WatchUi.View.initialize();
        ballCount = MIN_BALLS;
        mode = selectedMode;
    }

    public function increment() as Void {
        ballCount = (ballCount < MAX_BALLS) ? ballCount + 1 : MIN_BALLS;
        WatchUi.requestUpdate();
    }

    public function decrement() as Void {
        ballCount = (ballCount > MIN_BALLS) ? ballCount - 1 : MAX_BALLS;
        WatchUi.requestUpdate();
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

    // Confirm the selection and switch to the appropriate screen.
    public function onSelect() as Boolean {
        var balls = _view.ballCount;
        if (_view.mode == :record) {
            var recView = new RecordingView(balls);
            WatchUi.switchToView(recView, new RecordingDelegate(recView), WatchUi.SLIDE_LEFT);
        } else {
            var mainView = new MainView(balls);
            WatchUi.switchToView(mainView, new MainDelegate(mainView), WatchUi.SLIDE_LEFT);
        }
        return true;
    }
}
