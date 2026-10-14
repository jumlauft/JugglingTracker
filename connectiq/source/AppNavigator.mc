import Toybox.Lang;

// The start-up flow (mode screen, ball selection) and the routing of the four
// buttons to whichever screen is showing, with no WatchUi in it. AppDelegate
// calls it; GarminAppHost switches the views when the screen changes. The
// Wear OS AppNavigator.kt and the Apple Watch AppNavigator.swift are the same
// flow. Each button handler returns whether it used the press; a BACK it
// does not use goes to the system, which closes the app.
class AppNavigator {
    // Record mode ships enabled on purpose (APP-1): the recordings users send
    // back are what grows the corpus. Turning it off starts the app on ball
    // selection for Juggle mode instead.
    public static const ENABLE_RECORDING_MODE = true;

    public static const SCREEN_MODE_SELECT = 0;
    public static const SCREEN_BALL_SELECT = 1;
    public static const SCREEN_TRACKER = 2;
    public static const SCREEN_RECORDING = 3;

    public static const MIN_BALLS = 3;
    public static const MAX_BALLS = 9;

    private var _host as AppHost;
    private var _enableRecordingMode as Boolean;

    public var screen as Number;
    // Mode screen: whether Record is highlighted (APP-1, APP-2).
    public var isRecordMode as Boolean;
    // Ball selection: the mode chosen before it, :juggle or :record (APP-4),
    // and the count (APP-3).
    public var mode as Symbol;
    public var ballCount as Number;
    public var tracker as TrackerSession?;
    public var recording as RecordingSession?;

    public function initialize(host as AppHost, enableRecordingMode as Boolean) {
        _host = host;
        _enableRecordingMode = enableRecordingMode;
        screen = enableRecordingMode ? SCREEN_MODE_SELECT : SCREEN_BALL_SELECT;
        isRecordMode = false;
        mode = :juggle;
        ballCount = MIN_BALLS;
        tracker = null;
        recording = null;
    }

    // UP. On the mode screen UP and DOWN both toggle (APP-2).
    public function onUp() as Boolean {
        if (screen == SCREEN_MODE_SELECT) {
            isRecordMode = !isRecordMode;
            _host.show(self, true);
            return true;
        }
        if (screen == SCREEN_BALL_SELECT) {
            // APP-3: the buttons step 3..9 and wrap round to 3. The touch
            // watches scroll with a crown or bezel and stop at 9 instead (a
            // hardware-specific difference, see CLAUDE.md).
            ballCount = ballCount < MAX_BALLS ? ballCount + 1 : MIN_BALLS;
            _host.show(self, true);
            return true;
        }
        var rec = recording;
        if (screen == SCREEN_RECORDING && rec != null) {
            return rec.onUp();
        }
        return false;
    }

    // DOWN. Wraps from 3 round to 9 on ball selection.
    public function onDown() as Boolean {
        if (screen == SCREEN_MODE_SELECT) {
            isRecordMode = !isRecordMode;
            _host.show(self, true);
            return true;
        }
        if (screen == SCREEN_BALL_SELECT) {
            ballCount = ballCount > MIN_BALLS ? ballCount - 1 : MAX_BALLS;
            _host.show(self, true);
            return true;
        }
        var rec = recording;
        if (screen == SCREEN_RECORDING && rec != null) {
            return rec.onDown();
        }
        return false;
    }

    // START.
    public function onStart() as Boolean {
        if (screen == SCREEN_MODE_SELECT) {
            mode = isRecordMode ? :record : :juggle;
            ballCount = MIN_BALLS;
            screen = SCREEN_BALL_SELECT;
            _host.show(self, true);
            return true;
        }
        if (screen == SCREEN_BALL_SELECT) {
            // APP-4: open the screen for the mode chosen earlier.
            if (mode == :record) {
                recording = _host.newRecording(ballCount);
                screen = SCREEN_RECORDING;
            } else {
                tracker = _host.newTracker(ballCount);
                screen = SCREEN_TRACKER;
            }
            _host.show(self, true);
            return true;
        }
        var juggle = tracker;
        if (screen == SCREEN_TRACKER && juggle != null) {
            juggle.onStartStop();
            return true;
        }
        var rec = recording;
        if (screen == SCREEN_RECORDING && rec != null) {
            return rec.onStart();
        }
        return false;
    }

    // BACK steps back one screen (APP-5): ball selection returns to the mode
    // screen, and the idle record screen to ball selection (REC-11). On the
    // first screen it is left to the system, which closes the app; the
    // tracking screens never let it through.
    public function onBack() as Boolean {
        if (screen == SCREEN_MODE_SELECT) {
            return false;
        }
        if (screen == SCREEN_BALL_SELECT) {
            if (!_enableRecordingMode) {
                return false;
            }
            isRecordMode = (mode == :record);
            screen = SCREEN_MODE_SELECT;
            _host.show(self, false);
            return true;
        }
        var juggle = tracker;
        if (screen == SCREEN_TRACKER && juggle != null) {
            // On the watch a menu takes its own input, so this only routes
            // BACK when the screen itself has the buttons.
            if (juggle.menu() != null) {
                juggle.onMenuBack();
            } else {
                juggle.onBack();
            }
            return true;
        }
        var rec = recording;
        if (screen == SCREEN_RECORDING && rec != null) {
            if (rec.menu() != null) {
                rec.onMenuBack();
            } else if (rec.onBack()) {
                // REC-11: back to ball selection, keeping Record mode and the
                // ball count. The session has released its timers and the
                // phone listener; the view releases the sensor as it hides.
                mode = :record;
                ballCount = rec.ballCount();
                recording = null;
                screen = SCREEN_BALL_SELECT;
                _host.show(self, false);
            }
            return true;
        }
        return false;
    }
}
