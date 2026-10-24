import Toybox.ActivityRecording;
import Toybox.Application;
import Toybox.Attention;
import Toybox.Communications;
import Toybox.FitContributor;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// The real watch services behind Platform.mc. Everything that touches the
// Connect IQ device APIs (timers, the phone link, vibration, menus, exit,
// views) lives here or in the views; the session and navigator classes
// only see the plain interfaces, so the tests can run them with fakes.

class TimerHandle extends Cancellable {
    private var _timer as Timer.Timer;

    public function initialize(timer as Timer.Timer) {
        Cancellable.initialize();
        _timer = timer;
    }

    public function cancel() as Void {
        _timer.stop();
    }
}

class GarminScheduler extends Scheduler {
    public function initialize() {
        Scheduler.initialize();
    }

    public function schedule(delayMs as Number, callback as Method, repeat as Boolean) as Cancellable {
        var timer = new Timer.Timer();
        timer.start(callback, delayMs, repeat);
        return new TimerHandle(timer);
    }
}

class GarminClock extends Clock {
    // System.getTimer() when the screen opened. Sample times are measured
    // from here: the raw timer turns negative after about 25 days of watch
    // uptime and the detector needs times that stay ordered (SENS-5).
    private var _originMs as Number;

    public function initialize() {
        Clock.initialize();
        _originMs = System.getTimer();
    }

    public function nowMs() as Number {
        return System.getTimer() - _originMs;
    }

    public function epochSeconds() as Number {
        return Time.now().value();
    }
}

class GarminPhoneLink extends PhoneLink {
    private var _listener as Method?;

    public function initialize() {
        PhoneLink.initialize();
        _listener = null;
    }

    public function send(payload as Dictionary, callback as Method, generation as Number) as Void {
        Communications.transmit(payload, null, new GarminTransmitListener(callback, generation));
    }

    public function setMessageListener(listener as Method?) as Void {
        _listener = listener;
        if (listener == null) {
            Communications.registerForPhoneAppMessages(null);
        } else {
            Communications.registerForPhoneAppMessages(method(:onPhoneAppMessage));
        }
    }

    public function onPhoneAppMessage(msg as Communications.PhoneAppMessage) as Void {
        var listener = _listener;
        var data = msg.data;
        if (listener != null && data instanceof Dictionary) {
            listener.invoke(data);
        }
    }
}

// Reports one transmit back with the generation it was sent under (SYNC-4,
// REC-6), so a late report from an abandoned attempt can be told apart.
class GarminTransmitListener extends Communications.ConnectionListener {
    private var _callback as Method;
    private var _generation as Number;

    public function initialize(callback as Method, generation as Number) {
        Communications.ConnectionListener.initialize();
        _callback = callback;
        _generation = generation;
    }

    public function onComplete() as Void {
        _callback.invoke(_generation, true);
    }

    public function onError() as Void {
        System.println("Transmission to the phone failed");
        _callback.invoke(_generation, false);
    }
}

class GarminEffects extends WatchEffects {
    public function initialize() {
        WatchEffects.initialize();
    }

    public function vibrate() as Void {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(50, 200)]);
        }
    }

    public function exit() as Void {
        System.exit();
    }

    public function update() as Void {
        WatchUi.requestUpdate();
    }

    public function showMenu(menu as MenuSpec, onSelect as Method, onBack as Method) as Void {
        var view = new WatchUi.Menu2({ :title => menu.title });
        for (var i = 0; i < menu.items.size(); i++) {
            var item = menu.items[i];
            view.addItem(new WatchUi.MenuItem(item.label, item.subLabel, item.id, null));
        }
        WatchUi.pushView(view, new SessionMenuDelegate(onSelect, onBack), WatchUi.SLIDE_IMMEDIATE);
    }

    public function closeMenu() as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
    }

    public function log(text as String) as Void {
        System.println(text);
    }

    public function freeMemory() as Number {
        var stats = System.getSystemStats();
        return stats == null ? 0x7fffffff : stats.freeMemory;
    }
}

// Every menu over a tracking screen. It pops itself before telling the
// session, and backing out is reported too: Menu2's default back pops
// without a word, which left the session waiting on a menu that was gone
// and turned every later press into a no-op (JUG-6, REC-10).
class SessionMenuDelegate extends WatchUi.Menu2InputDelegate {
    private var _onSelect as Method;
    private var _onBack as Method;

    public function initialize(onSelect as Method, onBack as Method) {
        WatchUi.Menu2InputDelegate.initialize();
        _onSelect = onSelect;
        _onBack = onBack;
    }

    public function onSelect(item as WatchUi.MenuItem) as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _onSelect.invoke(item.getId());
    }

    public function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        _onBack.invoke();
    }
}

// Makes the real sessions and shows the navigator's screen as a view.
class GarminAppHost extends AppHost {
    private var _shownScreen as Number;

    public function initialize() {
        AppHost.initialize();
        _shownScreen = -1;
    }

    public function newTracker(balls as Number) as TrackerSession? {
        return new TrackerSession(balls, null, new GarminPhoneLink(), new GarminScheduler(),
            new GarminClock(), new GarminEffects(), makeActivityRecorder(), new GarminSessionStore());
    }

    public function resumeTracker() as TrackerSession? {
        var store = new GarminSessionStore();
        var saved = store.load();
        if (saved == null) {
            return null;
        }
        return new TrackerSession(AppNavigator.MIN_BALLS, saved, new GarminPhoneLink(), new GarminScheduler(),
            new GarminClock(), new GarminEffects(), makeActivityRecorder(), store);
    }

    public function newRecording(balls as Number) as RecordingSession? {
        return new RecordingSession(balls, new GarminPhoneLink(), new GarminScheduler(),
            new GarminClock(), new GarminEffects());
    }

    // The app's first screen is shown by getInitialView, not switchToView.
    public function markShown(navigator as AppNavigator) as Void {
        _shownScreen = navigator.screen;
    }

    public function show(navigator as AppNavigator, forward as Boolean) as Void {
        if (navigator.screen == _shownScreen) {
            WatchUi.requestUpdate();
            return;
        }
        _shownScreen = navigator.screen;
        WatchUi.switchToView(viewFor(navigator), new AppDelegate(navigator),
            forward ? WatchUi.SLIDE_LEFT : WatchUi.SLIDE_RIGHT);
    }

    public function viewFor(navigator as AppNavigator) as WatchUi.View {
        var screen = navigator.screen;
        var juggle = navigator.tracker;
        var rec = navigator.recording;
        if (screen == AppNavigator.SCREEN_TRACKER && juggle != null) {
            return new MainView(juggle);
        }
        if (screen == AppNavigator.SCREEN_RECORDING && rec != null) {
            return new RecordingView(rec);
        }
        if (screen == AppNavigator.SCREEN_BALL_SELECT) {
            return new BallSelectView(navigator);
        }
        return new ModeSelectView(navigator);
    }
}

// A session kept with Continue later, in Application.Storage so it survives
// the app closing, a restart of the watch and other activities in between
// (LATER-1).
class GarminSessionStore extends SessionStore {
    private const KEY = "kept_session";

    public function initialize() {
        SessionStore.initialize();
    }

    public function load() as Dictionary? {
        var value = Application.Storage.getValue(KEY);
        return value instanceof Dictionary ? value : null;
    }

    public function save(session as Dictionary) as Void {
        Application.Storage.setValue(KEY, session as Application.PropertyValueType);
    }

    public function clear() as Void {
        Application.Storage.deleteValue(KEY);
    }
}

// ACT-1: each stretch of juggling becomes a Garmin Connect activity named
// "Juggling session", sport Other (generic), with the ball count and the stretch's
// average catches per run as its own fields (labelled in
// resources/fit_contributions.xml). Strava gets it through Garmin Connect.
//
// Connect IQ cannot keep or reopen an activity once the app has closed, so a
// session continued later becomes a second activity (ACT-3). The activity is
// therefore always saved before the app closes, never left open: a recording
// left open by a closed app is orphaned and can block the watch's activity
// list until a restart.
(:activity_recorder)
class GarminActivityRecorder extends ActivityRecorder {
    private const BALLS_FIELD_ID = 0;
    private const AVERAGE_FIELD_ID = 1;

    private var _session as ActivityRecording.Session?;
    private var _balls as FitContributor.Field?;
    private var _average as FitContributor.Field?;

    public function initialize() {
        ActivityRecorder.initialize();
        _session = null;
        _balls = null;
        _average = null;
    }

    public function start() as Void {
        if (_session != null || !(Toybox has :ActivityRecording)) {
            return;
        }
        try {
            var session = ActivityRecording.createSession({
                :name => "Juggling session",
                // Generic shows as "Other"; Training showed as strength
                // training ("Krafttraining") in Garmin Connect.
                :sport => ActivityRecording.SPORT_GENERIC,
                :subSport => ActivityRecording.SUB_SPORT_GENERIC
            });
            _balls = session.createField("balls", BALLS_FIELD_ID, FitContributor.DATA_TYPE_UINT8,
                { :mesgType => FitContributor.MESG_TYPE_SESSION, :units => "balls" });
            _average = session.createField("avg_catches", AVERAGE_FIELD_ID, FitContributor.DATA_TYPE_FLOAT,
                { :mesgType => FitContributor.MESG_TYPE_SESSION, :units => "catches" });
            session.start();
            _session = session;
        } catch (ex) {
            // Juggling still works without an activity.
            System.println("Activity not started: " + ex.getErrorMessage());
            _session = null;
        }
    }

    public function pause() as Void {
        var session = _session;
        if (session != null && session.isRecording()) {
            session.stop();
        }
    }

    public function resume() as Void {
        var session = _session;
        if (session != null && !session.isRecording()) {
            session.start();
        }
    }

    public function save(balls as Number, average as Float) as Void {
        var session = _session;
        if (session == null) {
            return;
        }
        var ballsField = _balls;
        var averageField = _average;
        if (ballsField != null) {
            ballsField.setData(balls);
        }
        if (averageField != null) {
            averageField.setData(average);
        }
        if (session.isRecording()) {
            session.stop();
        }
        session.save();
        release();
    }

    public function discard() as Void {
        var session = _session;
        if (session == null) {
            return;
        }
        if (session.isRecording()) {
            session.stop();
        }
        session.discard();
        release();
    }

    private function release() as Void {
        _session = null;
        _balls = null;
        _average = null;
    }
}

(:activity_recorder)
function makeActivityRecorder() as ActivityRecorder {
    return new GarminActivityRecorder();
}

// ACT-4: watches with too little memory for an activity (Instinct 2 and 2S,
// Descent G1, see monkey.jungle) build this one: juggling and Continue
// later work as everywhere else, only no activity is saved.
(:no_activity_recorder)
function makeActivityRecorder() as ActivityRecorder {
    return new ActivityRecorder();
}
