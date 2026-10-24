import Toybox.Lang;

// The watch services the Juggle and Record logic needs, as plain classes with
// no WatchUi, Timer, Sensor or Communications in them. GarminPlatform.mc has
// the real ones; the unit tests in test/ pass fakes, so TrackerSession,
// RecordingSession and AppNavigator run without a device. Same shape as
// wearos .../logic/Platform.kt and apple WatchLogic Platform.swift.

// A pending timer that can be stopped, like Timer.Timer.
class Cancellable {
    public function initialize() {
    }

    public function cancel() as Void {
    }
}

// Runs a callback later. `repeat` keeps it firing every `delayMs`.
class Scheduler {
    public function initialize() {
    }

    public function schedule(delayMs as Number, callback as Method, repeat as Boolean) as Cancellable {
        return new Cancellable();
    }
}

// Monotonic milliseconds since the screen opened, and the wall clock.
// Sample times come from nowMs(): System.getTimer() itself turns negative
// after about 25 days of uptime (SENS-5).
class Clock {
    public function initialize() {
    }

    public function nowMs() as Number {
        return 0;
    }

    public function epochSeconds() as Number {
        return 0;
    }
}

// The transport to the phone app. send() reports once, through
// callback.invoke(generation, delivered), whether the message left the
// watch. That is delivery only: a session or run counts as synced only when
// the phone answers with an `ack` through the message listener.
class PhoneLink {
    public function initialize() {
    }

    public function send(payload as Dictionary, callback as Method, generation as Number) as Void {
    }

    // `listener` is called with each message's data Dictionary; null stops it.
    public function setMessageListener(listener as Method?) as Void {
    }
}

// What a session does to the watch besides its own state: buzz, quit,
// redraw, show and close a menu, log a line, and read free memory.
class WatchEffects {
    public function initialize() {
    }

    public function vibrate() as Void {
    }

    // Close the app (System.exit() on the watch).
    public function exit() as Void {
    }

    // The state changed; redraw.
    public function update() as Void {
    }

    // Shows `menu` over the screen. Picking an item calls
    // onSelect.invoke(itemId); backing out calls onBack.invoke(). Either way
    // the menu is already gone when they run.
    public function showMenu(menu as MenuSpec, onSelect as Method, onBack as Method) as Void {
    }

    // Closes the menu the session opened, when it closes itself (an ack
    // arrived while it was up).
    public function closeMenu() as Void {
    }

    public function log(text as String) as Void {
    }

    public function freeMemory() as Number {
        return 0x7fffffff;
    }
}

// A list of choices over a tracking screen, shown as a Menu2. Labels are the
// app's own English strings (JUG-5).
class MenuSpec {
    public var kind as String;
    public var title as String;
    public var items as Array<MenuItemSpec>;

    public function initialize(menuKind as String, menuTitle as String, menuItems as Array<MenuItemSpec>) {
        kind = menuKind;
        title = menuTitle;
        items = menuItems;
    }
}

class MenuItemSpec {
    public var id as String;
    public var label as String;
    public var subLabel as String?;

    public function initialize(itemId as String, itemLabel as String, itemSubLabel as String?) {
        id = itemId;
        label = itemLabel;
        subLabel = itemSubLabel;
    }
}

// The watch's own activity for the stretch being juggled: a Garmin Connect
// activity on the Garmin (ACT-1). TrackerSession starts it when juggling
// starts, pauses it while the session-end menu is up and ends it when the app
// closes: saved with the stretch's stats when the stretch has a run, else
// discarded (ACT-2). Watches without the memory for it get this no-op one.
class ActivityRecorder {
    public function initialize() {
    }

    public function start() as Void {
    }

    public function pause() as Void {
    }

    public function resume() as Void {
    }

    // `average` is the stretch's catches per run.
    public function save(balls as Number, average as Float) as Void {
    }

    public function discard() as Void {
    }
}

// Where a session kept with Continue later waits for the app to open again
// (LATER-1): Application.Storage on the watch, a plain field in the tests.
class SessionStore {
    public function initialize() {
    }

    public function load() as Dictionary? {
        return null;
    }

    public function save(session as Dictionary) as Void {
    }

    public function clear() as Void {
    }
}

// Where AppNavigator gets its sessions and shows its screens. The watch's
// GarminAppHost switches views; the tests' FakeHost only records.
class AppHost {
    public function initialize() {
    }

    public function newTracker(balls as Number) as TrackerSession? {
        return null;
    }

    public function newRecording(balls as Number) as RecordingSession? {
        return null;
    }

    // The session kept with Continue later, reopened with its Previous
    // session menu up, or null when none is kept (LATER-1).
    public function resumeTracker() as TrackerSession? {
        return null;
    }

    // The navigator moved to another screen; `forward` is true when it went
    // deeper into the flow, false when BACK stepped out.
    public function show(navigator as AppNavigator, forward as Boolean) as Void {
    }
}
