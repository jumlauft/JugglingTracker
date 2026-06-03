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

    function getInitialView() as [Views] or [Views, InputDelegates] {
        var selectView = new BallSelectView();
        return [selectView, new BallSelectDelegate(selectView)];
    }
}
