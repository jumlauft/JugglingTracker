import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

class JugglingTrackerApp extends Application.AppBase {
    private const ENABLE_RECORDING_MODE = true;

    function initialize() {
        Application.AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        if (ENABLE_RECORDING_MODE) {
            var modeView = new ModeSelectView();
            return [modeView, new ModeSelectDelegate(modeView)];
        }

        var ballView = new BallSelectView(:juggle);
        return [ballView, new BallSelectDelegate(ballView)];
    }
}
