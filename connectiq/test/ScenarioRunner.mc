import Toybox.Lang;
import Toybox.Test;

// Replays one shared watch scenario (../scenarios/*.json) through the whole
// Garmin logic layer: AppNavigator, both sessions, a fake phone, a fake
// clock and fake effects. ScenarioTest.mc is generated from the JSON files
// by ../scenarios/garmin_tests.py and calls one method here per step; the
// Wear OS ScenarioTest.kt and the Apple Watch ScenarioTests.swift replay the
// same files. The first failing step is kept and reported by passed().
class ScenarioRunner {
    private var _host as FakeHost;
    private var _navigator as AppNavigator;
    // Sample time, apart from the scheduler's clock, as on the other watches.
    private var _t as Number;
    private var _step as Number;
    private var _failure as String?;

    public function initialize(epochSeconds as Number) {
        _host = new FakeHost(epochSeconds);
        _navigator = new AppNavigator(_host, AppNavigator.ENABLE_RECORDING_MODE);
        _t = 0;
        _step = 0;
        _failure = null;
    }

    // Whether every step passed; logs the first failure.
    public function passed(logger as Logger) as Boolean {
        if (_failure != null) {
            logger.error(_failure);
            return false;
        }
        return true;
    }

    private function begin() as Boolean {
        _step += 1;
        return _failure == null;
    }

    private function fail(message as String) as Void {
        if (_failure == null) {
            _failure = "step " + _step + ": " + message;
        }
    }

    // ── Steps ─────────────────────────────────────────────────────────

    public function press(button as String) as Void {
        if (!begin()) { return; }
        if (button.equals("up")) {
            _navigator.onUp();
        } else if (button.equals("down")) {
            _navigator.onDown();
        } else if (button.equals("start")) {
            _navigator.onStart();
        } else if (button.equals("back")) {
            // A BACK nobody uses reaches the system, which closes the app.
            if (!_navigator.onBack()) {
                _host.effects.exits += 1;
            }
        } else {
            fail("unknown button " + button);
        }
    }

    public function select(itemId as String) as Void {
        if (!begin()) { return; }
        var juggle = _navigator.tracker;
        var rec = _navigator.recording;
        if (_navigator.screen == AppNavigator.SCREEN_TRACKER && juggle != null) {
            juggle.onMenuSelect(itemId);
        } else if (_navigator.screen == AppNavigator.SCREEN_RECORDING && rec != null) {
            rec.onMenuSelect(itemId);
        } else {
            fail("no menu on " + screenName());
        }
    }

    public function warmup() as Void {
        if (!begin()) { return; }
        feed(new Feed().still(55));
    }

    public function catches(n as Number) as Void {
        if (!begin()) { return; }
        feed(new Feed().catches(n));
    }

    public function burst(n as Number) as Void {
        if (!begin()) { return; }
        for (var i = 0; i < n; i++) {
            feed(new Feed().burst());
        }
    }

    public function idle(n as Number) as Void {
        if (!begin()) { return; }
        feed(new Feed().still(n));
    }

    public function wait(ms as Number) as Void {
        if (!begin()) { return; }
        _host.scheduler.advance(ms);
    }

    public function epochAdvance(seconds as Number) as Void {
        if (!begin()) { return; }
        _host.clock.epoch += seconds;
    }

    public function deliver(delivered as Boolean) as Void {
        if (!begin()) { return; }
        _host.link.complete(delivered);
    }

    public function deliverAt(delivered as Boolean, index as Number) as Void {
        if (!begin()) { return; }
        _host.link.completeAt(delivered, index);
    }

    // Lets every message through: this one and all later ones report
    // delivered, and time runs on in small steps until the run's rec_end
    // (or the session) has gone out.
    public function deliverAll() as Void {
        if (!begin()) { return; }
        var link = _host.link;
        link.autoResult = true;
        link.complete(true);
        for (var steps = 0; steps < 400; steps++) {
            var last = link.last();
            if (last != null && ("rec_end".equals(last["type"]) || "session".equals(last["type"]))) {
                break;
            }
            _host.scheduler.advance(50);
        }
    }

    public function phone(data as Dictionary) as Void {
        if (!begin()) { return; }
        _host.link.phone(data);
    }

    // Checks the state; every key given must match.
    public function expect(want as Dictionary) as Void {
        if (!begin()) { return; }
        var keys = want.keys();
        for (var i = 0; i < keys.size(); i++) {
            var key = keys[i] as String;
            var expected = want[key];
            if (key.equals("firstSent") || key.equals("lastSent")) {
                var sent = _host.link.sent;
                var message = sent.size() == 0 ? null : (key.equals("firstSent") ? sent[0] : sent[sent.size() - 1]);
                checkSubset(key, expected as Dictionary, message);
            } else {
                var actual = actualFor(key);
                if (_failure == null && !sameValue(expected, actual)) {
                    fail(key + " expected " + showValue(expected) + " but was " + showValue(actual));
                }
            }
            if (_failure != null) {
                return;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    // Feeds samples in batches of 25, about one a second as the sensor does.
    private function feed(samples as Feed) as Void {
        var n = samples.size();
        for (var from = 0; from < n; from += 25) {
            var to = from + 25 < n ? from + 25 : n;
            var lastMs = _t + (to - 1) * 40;
            var juggle = _navigator.tracker;
            var rec = _navigator.recording;
            if (_navigator.screen == AppNavigator.SCREEN_TRACKER && juggle != null) {
                juggle.onBatch(samples.xs.slice(from, to), samples.ys.slice(from, to), samples.zs.slice(from, to), lastMs);
            } else if (_navigator.screen == AppNavigator.SCREEN_RECORDING && rec != null) {
                rec.onBatch(samples.xs.slice(from, to), samples.ys.slice(from, to), samples.zs.slice(from, to), lastMs);
            }
        }
        _t += n * 40;
    }

    private function screenName() as String {
        var screen = _navigator.screen;
        if (screen == AppNavigator.SCREEN_MODE_SELECT) { return "modeSelect"; }
        if (screen == AppNavigator.SCREEN_BALL_SELECT) { return "ballSelect"; }
        if (screen == AppNavigator.SCREEN_TRACKER) { return "tracker"; }
        return "recording";
    }

    private function phaseName(phase as Number) as String {
        if (phase == RecordingSession.PHASE_IDLE) { return "idle"; }
        if (phase == RecordingSession.PHASE_RECORDING) { return "recording"; }
        if (phase == RecordingSession.PHASE_LABELING) { return "labeling"; }
        return "syncing";
    }

    private function actualFor(key as String) as Object? {
        var nav = _navigator;
        var screen = nav.screen;
        var juggle = screen == AppNavigator.SCREEN_TRACKER ? nav.tracker : null;
        var rec = screen == AppNavigator.SCREEN_RECORDING ? nav.recording : null;
        var menu = null;
        if (juggle != null) { menu = juggle.menu(); }
        if (rec != null) { menu = rec.menu(); }
        var effects = _host.effects;

        if (key.equals("screen")) { return screenName(); }
        if (key.equals("recordMode")) { return screen == AppNavigator.SCREEN_MODE_SELECT ? nav.isRecordMode : null; }
        if (key.equals("mode")) {
            if (screen != AppNavigator.SCREEN_BALL_SELECT) { return null; }
            return nav.mode == :record ? "record" : "juggle";
        }
        if (key.equals("balls")) {
            if (screen == AppNavigator.SCREEN_BALL_SELECT) { return nav.ballCount; }
            if (juggle != null) { return juggle.detector().ballCount; }
            if (rec != null) { return rec.ballCount(); }
            return null;
        }
        if (key.equals("menu")) { return menu == null ? null : menu.kind; }
        if (key.equals("menuTitle")) { return menu == null ? null : menu.title; }
        if (key.equals("menuItems") || key.equals("menuLabels") || key.equals("menuSubLabels")) {
            if (menu == null) { return null; }
            var out = [];
            for (var i = 0; i < menu.items.size(); i++) {
                var item = menu.items[i];
                out.add(key.equals("menuItems") ? item.id : (key.equals("menuLabels") ? item.label : item.subLabel));
            }
            return out;
        }
        if (key.equals("count")) {
            if (juggle != null) { return juggle.detector().currentCount; }
            if (rec != null) { return rec.liveCount(); }
            return null;
        }
        if (key.equals("error")) {
            if (juggle != null) { return juggle.errorMessage(); }
            if (rec != null) { return rec.errorMessage(); }
            return null;
        }
        if (juggle != null) {
            var d = juggle.detector();
            if (key.equals("runActive")) { return d.isRunActive(); }
            if (key.equals("previous")) { return d.previousCount; }
            if (key.equals("runs")) { return d.sessionRuns(); }
            if (key.equals("max")) { return d.sessionMax; }
            if (key.equals("sending")) { return juggle.isSending(); }
        }
        if (rec != null) {
            if (key.equals("phase")) { return phaseName(rec.phase()); }
            if (key.equals("runsCompleted")) { return rec.runsCompleted(); }
            if (key.equals("detected")) { return rec.detectedCount(); }
            if (key.equals("label")) { return rec.labelCount(); }
            if (key.equals("recordedSamples")) { return rec.recordedSamples(); }
        }
        if (key.equals("exits")) { return effects.exits; }
        if (key.equals("vibrations")) { return effects.vibrations; }
        if (key.equals("sentTypes")) { return collapsed(_host.link.types()); }
        if (key.equals("sentCount")) { return _host.link.sent.size(); }
        if (isKnownKey(key)) {
            return null;   // a screen-specific key while another screen shows
        }
        fail("unknown expectation " + key);
        return null;
    }

    private function isKnownKey(key as String) as Boolean {
        var known = ["runActive", "previous", "runs", "max", "sending",
            "phase", "runsCompleted", "detected", "label", "recordedSamples"];
        for (var i = 0; i < known.size(); i++) {
            if (known[i].equals(key)) {
                return true;
            }
        }
        return false;
    }

    private function checkSubset(name as String, want as Dictionary, message as Dictionary?) as Void {
        if (message == null) {
            fail(name + ": nothing was sent");
            return;
        }
        var keys = want.keys();
        for (var i = 0; i < keys.size(); i++) {
            var key = keys[i];
            var expected = want[key];
            if (expected == null) {
                if (message.hasKey(key)) {
                    fail(name + "." + key + " should be absent");
                    return;
                }
            } else if (!sameValue(expected, message[key])) {
                fail(name + "." + key + " expected " + showValue(expected) + " but was " + showValue(message[key]));
                return;
            }
        }
    }

    // Consecutive repeats folded into one, so chunk counts do not matter.
    private function collapsed(types as Array) as Array {
        var out = [];
        for (var i = 0; i < types.size(); i++) {
            if (out.size() == 0 || !sameValue(out[out.size() - 1], types[i])) {
                out.add(types[i]);
            }
        }
        return out;
    }
}

// Whether two JSON-like values are equal: null, booleans, numbers, strings
// and arrays of them.
function sameValue(a as Object?, b as Object?) as Boolean {
    if (a == null || b == null) {
        return a == null && b == null;
    }
    if (a instanceof String) {
        return b instanceof String && a.equals(b);
    }
    if (a instanceof Boolean) {
        return b instanceof Boolean && a == b;
    }
    if (a instanceof Array) {
        if (!(b instanceof Array) || a.size() != b.size()) {
            return false;
        }
        for (var i = 0; i < a.size(); i++) {
            if (!sameValue(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }
    if ((a instanceof Number || a instanceof Long) && (b instanceof Number || b instanceof Long)) {
        return a.toLong() == b.toLong();
    }
    return a.equals(b);
}

function showValue(value as Object?) as String {
    if (value == null) {
        return "null";
    }
    if (value instanceof String) {
        return "\"" + value + "\"";
    }
    if (value instanceof Array) {
        var out = "[";
        for (var i = 0; i < value.size(); i++) {
            out += (i > 0 ? ", " : "") + showValue(value[i]);
        }
        return out + "]";
    }
    return value.toString();
}
