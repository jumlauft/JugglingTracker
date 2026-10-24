import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

class JugglingTrackerApp extends Application.AppBase {
    private var _navigator as AppNavigator?;

    function initialize() {
        Application.AppBase.initialize();
        _navigator = null;
    }

    function onStart(state as Dictionary?) as Void {
    }

    // LATER-6: closed by the system mid-session, the session is kept as with
    // Continue later and its activity saved rather than orphaned.
    function onStop(state as Dictionary?) as Void {
        var navigator = _navigator;
        if (navigator != null) {
            var juggle = navigator.tracker;
            if (navigator.screen == AppNavigator.SCREEN_TRACKER && juggle != null) {
                juggle.onAppStop();
            }
        }
    }

    // Opens on the mode screen, or on ball selection while
    // AppNavigator.ENABLE_RECORDING_MODE is off (APP-1).
    function getInitialView() as [Views] or [Views, InputDelegates] {
        var host = new GarminAppHost();
        var navigator = new AppNavigator(host, AppNavigator.ENABLE_RECORDING_MODE);
        _navigator = navigator;
        host.markShown(navigator);
        return [host.viewFor(navigator), new AppDelegate(navigator)];
    }
}
