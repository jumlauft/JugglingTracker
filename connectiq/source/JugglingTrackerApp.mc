import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

class JugglingTrackerApp extends Application.AppBase {
    function initialize() {
        Application.AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    // Opens on the mode screen, or on ball selection while
    // AppNavigator.ENABLE_RECORDING_MODE is off (APP-1).
    function getInitialView() as [Views] or [Views, InputDelegates] {
        var host = new GarminAppHost();
        var navigator = new AppNavigator(host, AppNavigator.ENABLE_RECORDING_MODE);
        host.markShown(navigator);
        return [host.viewFor(navigator), new AppDelegate(navigator)];
    }
}
