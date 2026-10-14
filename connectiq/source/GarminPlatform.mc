import Toybox.Attention;
import Toybox.Communications;
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
        return new TrackerSession(balls, new GarminPhoneLink(), new GarminScheduler(),
            new GarminClock(), new GarminEffects());
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
