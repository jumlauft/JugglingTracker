import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

// Startup screen showing how many balls (3-9) are being juggled. AppNavigator
// holds the count and handles the buttons; this only draws it.
class BallSelectView extends WatchUi.View {
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

        var numberH = dc.getFontHeight(Graphics.FONT_NUMBER_THAI_HOT);
        var labelH = dc.getFontHeight(Graphics.FONT_TINY);
        var hintH = dc.getFontHeight(Graphics.FONT_XTINY);

        var blockH = labelH + numberH + hintH;
        var y = cy - blockH / 2;

        dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_TINY, "Balls", Graphics.TEXT_JUSTIFY_CENTER);
        y += labelH;

        dc.drawText(cx, y, Graphics.FONT_NUMBER_THAI_HOT, _navigator.ballCount.toString(), Graphics.TEXT_JUSTIFY_CENTER);
        y += numberH;

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_XTINY, "Up/Down then Start", Graphics.TEXT_JUSTIFY_CENTER);
    }
}
