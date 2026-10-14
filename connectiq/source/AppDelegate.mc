import Toybox.Lang;
import Toybox.WatchUi;

// The one input delegate of the app's screens: it hands the four buttons to
// AppNavigator, which passes them on to the screen showing. Menus over the
// tracking screens take their own input (SessionMenuDelegate).
//
// A button reaches onKey first; only when that leaves it unused does the
// system call the behaviour (onPreviousPage, onNextPage, onSelect, onBack),
// so devices that deliver only behaviours, or only keys, both work. A BACK
// neither uses goes to the system, which closes the app.
class AppDelegate extends WatchUi.BehaviorDelegate {
    private var _navigator as AppNavigator;

    public function initialize(navigator as AppNavigator) {
        WatchUi.BehaviorDelegate.initialize();
        _navigator = navigator;
    }

    public function onKey(evt as WatchUi.KeyEvent) as Boolean {
        var key = evt.getKey();
        if (key == WatchUi.KEY_UP) {
            return _navigator.onUp();
        }
        if (key == WatchUi.KEY_DOWN) {
            return _navigator.onDown();
        }
        if (key == WatchUi.KEY_ENTER) {
            return _navigator.onStart();
        }
        if (key == WatchUi.KEY_ESC) {
            return _navigator.onBack();
        }
        return false;
    }

    public function onPreviousPage() as Boolean {
        return _navigator.onUp();
    }

    public function onNextPage() as Boolean {
        return _navigator.onDown();
    }

    // A tap on a touchscreen Garmin also arrives here. On the Juggle screen
    // only the START/STOP key may open the session-end menu, so a stray
    // touch mid-juggle does nothing.
    public function onSelect() as Boolean {
        if (_navigator.screen == AppNavigator.SCREEN_TRACKER) {
            return false;
        }
        return _navigator.onStart();
    }

    public function onBack() as Boolean {
        return _navigator.onBack();
    }
}
