import Toybox.Lang;

// Fakes for the plain interfaces in source/Platform.mc, so the sessions and
// the navigator run in the unit tests without a device: a clock that only
// moves when told to, a phone that records what was sent and answers when
// told to, and effects that count buzzes, exits and menus. Same roles as the
// fakes of the Wear OS and Apple Watch logic tests.

class FakeTask extends Cancellable {
    public var dueMs as Number;
    public var seq as Number;
    public var callback as Method;
    public var repeat as Boolean;
    public var periodMs as Number;
    public var cancelled as Boolean;

    public function initialize(due as Number, order as Number, cb as Method, repeats as Boolean, period as Number) {
        Cancellable.initialize();
        dueMs = due;
        seq = order;
        callback = cb;
        repeat = repeats;
        periodMs = period;
        cancelled = false;
    }

    public function cancel() as Void {
        cancelled = true;
    }
}

class FakeScheduler extends Scheduler {
    public var nowMs as Number;
    private var _seq as Number;
    private var _tasks as Array<FakeTask>;

    public function initialize() {
        Scheduler.initialize();
        nowMs = 0;
        _seq = 0;
        _tasks = [] as Array<FakeTask>;
    }

    public function schedule(delayMs as Number, callback as Method, repeat as Boolean) as Cancellable {
        var task = new FakeTask(nowMs + delayMs, _seq, callback, repeat, delayMs);
        _seq += 1;
        _tasks.add(task);
        return task;
    }

    // Lets `ms` pass, running every timer that falls due, in order.
    public function advance(ms as Number) as Void {
        var target = nowMs + ms;
        while (true) {
            var next = null as FakeTask?;
            for (var i = 0; i < _tasks.size(); i++) {
                var task = _tasks[i];
                if (task.cancelled || task.dueMs > target) {
                    continue;
                }
                if (next == null || task.dueMs < next.dueMs || (task.dueMs == next.dueMs && task.seq < next.seq)) {
                    next = task;
                }
            }
            if (next == null) {
                break;
            }
            nowMs = next.dueMs;
            if (next.repeat && next.periodMs > 0) {
                next.dueMs += next.periodMs;
                next.seq = _seq;
                _seq += 1;
            } else {
                _tasks.remove(next);
            }
            next.callback.invoke();
        }
        nowMs = target;
        var live = [] as Array<FakeTask>;
        for (var j = 0; j < _tasks.size(); j++) {
            if (!_tasks[j].cancelled) {
                live.add(_tasks[j]);
            }
        }
        _tasks = live;
    }

    public function pending() as Number {
        var count = 0;
        for (var i = 0; i < _tasks.size(); i++) {
            if (!_tasks[i].cancelled) {
                count += 1;
            }
        }
        return count;
    }
}

class FakeClock extends Clock {
    private var _scheduler as FakeScheduler;
    public var epoch as Number;

    public function initialize(scheduler as FakeScheduler, epochSeconds as Number) {
        Clock.initialize();
        _scheduler = scheduler;
        epoch = epochSeconds;
    }

    public function nowMs() as Number {
        return _scheduler.nowMs;
    }

    public function epochSeconds() as Number {
        return epoch;
    }
}

class FakePhoneLink extends PhoneLink {
    public var sent as Array<Dictionary>;
    // [callback, generation] of every send not yet answered by autoResult.
    private var _pending as Array<Array>;
    public var listener as Method?;
    // When set, every send reports this result at once.
    public var autoResult as Boolean?;
    // When set, send throws, as Communications.transmit can.
    public var throwOnSend as Boolean;

    public function initialize() {
        PhoneLink.initialize();
        sent = [] as Array<Dictionary>;
        _pending = [] as Array<Array>;
        listener = null;
        autoResult = null;
        throwOnSend = false;
    }

    public function send(payload as Dictionary, callback as Method, generation as Number) as Void {
        if (throwOnSend) {
            throw new Lang.InvalidValueException("no link");
        }
        sent.add(payload);
        var auto = autoResult;
        if (auto != null) {
            callback.invoke(generation, auto);
        } else {
            _pending.add([callback, generation]);
        }
    }

    public function setMessageListener(l as Method?) as Void {
        listener = l;
    }

    // Reports the result of the latest send.
    public function complete(delivered as Boolean) as Void {
        completeAt(delivered, _pending.size() - 1);
    }

    // Reports the result of the send at `index` among those still waiting.
    public function completeAt(delivered as Boolean, index as Number) as Void {
        var entry = _pending[index];
        (entry[0] as Method).invoke(entry[1], delivered);
    }

    // A message from the phone.
    public function phone(data as Dictionary) as Void {
        var l = listener;
        if (l != null) {
            l.invoke(data);
        }
    }

    public function ack(timestamp as Number) as Void {
        phone({ "type" => "ack", "timestamp" => timestamp });
    }

    public function types() as Array {
        var out = [];
        for (var i = 0; i < sent.size(); i++) {
            out.add(sent[i]["type"]);
        }
        return out;
    }

    public function last() as Dictionary? {
        return sent.size() == 0 ? null : sent[sent.size() - 1];
    }
}

class FakeEffects extends WatchEffects {
    public var vibrations as Number;
    public var exits as Number;
    public var updates as Number;
    public var menusShown as Number;
    public var menusClosed as Number;
    public var lastMenu as MenuSpec?;
    public var logs as Array<String>;
    public var free as Number;

    public function initialize() {
        WatchEffects.initialize();
        vibrations = 0;
        exits = 0;
        updates = 0;
        menusShown = 0;
        menusClosed = 0;
        lastMenu = null;
        logs = [] as Array<String>;
        free = 0x7fffffff;
    }

    public function vibrate() as Void {
        vibrations += 1;
    }

    public function exit() as Void {
        exits += 1;
    }

    public function update() as Void {
        updates += 1;
    }

    public function showMenu(menu as MenuSpec, onSelect as Method, onBack as Method) as Void {
        menusShown += 1;
        lastMenu = menu;
    }

    public function closeMenu() as Void {
        menusClosed += 1;
    }

    public function log(text as String) as Void {
        logs.add(text);
    }

    public function freeMemory() as Number {
        return free;
    }
}

// The watch's activity: what state it is in and what was saved.
class FakeActivityRecorder extends ActivityRecorder {
    public static const IDLE = "none";
    public static const RECORDING = "recording";
    public static const PAUSED = "paused";

    public var state as String;
    // The average catches per run of every activity saved, in order.
    public var savedAverages as Array<Float>;
    public var savedBalls as Array<Number>;
    public var discarded as Number;

    public function initialize() {
        ActivityRecorder.initialize();
        state = IDLE;
        savedAverages = [] as Array<Float>;
        savedBalls = [] as Array<Number>;
        discarded = 0;
    }

    public function start() as Void {
        state = RECORDING;
    }

    public function pause() as Void {
        if (state.equals(RECORDING)) {
            state = PAUSED;
        }
    }

    public function resume() as Void {
        if (state.equals(PAUSED)) {
            state = RECORDING;
        }
    }

    public function save(balls as Number, average as Float) as Void {
        state = IDLE;
        savedBalls.add(balls);
        savedAverages.add(average);
    }

    public function discard() as Void {
        state = IDLE;
        discarded += 1;
    }
}

// Application.Storage as one field, kept across relaunches of the app.
class FakeSessionStore extends SessionStore {
    public var value as Dictionary?;

    public function initialize() {
        SessionStore.initialize();
        value = null;
    }

    public function load() as Dictionary? {
        return value;
    }

    public function save(session as Dictionary) as Void {
        value = session;
    }

    public function clear() as Void {
        value = null;
    }
}

// Makes sessions on the shared fakes and counts the screens shown.
class FakeHost extends AppHost {
    public var scheduler as FakeScheduler;
    public var clock as FakeClock;
    public var link as FakePhoneLink;
    public var effects as FakeEffects;
    public var recorder as FakeActivityRecorder;
    public var store as FakeSessionStore;
    public var shows as Number;

    public function initialize(epochSeconds as Number) {
        AppHost.initialize();
        scheduler = new FakeScheduler();
        clock = new FakeClock(scheduler, epochSeconds);
        link = new FakePhoneLink();
        effects = new FakeEffects();
        recorder = new FakeActivityRecorder();
        store = new FakeSessionStore();
        shows = 0;
    }

    public function newTracker(balls as Number) as TrackerSession? {
        return new TrackerSession(balls, null, link, scheduler, clock, effects, recorder, store);
    }

    public function resumeTracker() as TrackerSession? {
        var saved = store.load();
        if (saved == null) {
            return null;
        }
        return new TrackerSession(AppNavigator.MIN_BALLS, saved, link, scheduler, clock, effects, recorder, store);
    }

    public function newRecording(balls as Number) as RecordingSession? {
        return new RecordingSession(balls, link, scheduler, clock, effects);
    }

    public function show(navigator as AppNavigator, forward as Boolean) as Void {
        shows += 1;
    }
}

// ── Sample feeds ───────────────────────────────────────────────────────
// The shapes DetectorTest.mc feeds the detector, as arrays for a session:
// a still watch with gravity on Z, and catch-like impulses against it, in
// milli-g at 25 Hz.

class Feed {
    public var xs as Array;
    public var ys as Array;
    public var zs as Array;

    public function initialize() {
        xs = [];
        ys = [];
        zs = [];
    }

    public function still(samples as Number) as Feed {
        for (var i = 0; i < samples; i++) {
            add(0, 0, BASELINE_Z);
        }
        return self;
    }

    // One impulse: three spiked samples, then twelve still ones to commit it.
    public function burst() as Feed {
        var spikeZ = BASELINE_Z - (50.0 * MILLI_G_PER_MS2).toNumber();
        for (var i = 0; i < 3; i++) {
            add(0, 0, spikeZ);
        }
        return still(12);
    }

    // `catches` watch-hand catches need 2 * catches - 1 bursts (DET-8).
    public function catches(count as Number) as Feed {
        for (var i = 0; i < count * 2 - 1; i++) {
            burst();
        }
        return self;
    }

    public function add(x as Number?, y as Number?, z as Number?) as Void {
        xs.add(x);
        ys.add(y);
        zs.add(z);
    }

    public function size() as Number {
        return xs.size();
    }
}
