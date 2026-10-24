import Toybox.Lang;

// Juggle mode: the detector, the session-end and discard menus, and the sync
// to the phone, with no WatchUi in it. MainView draws it and feeds it the
// accelerometer; AppDelegate routes the buttons to it through AppNavigator.
// The Wear OS TrackerSession.kt and the Apple Watch TrackerSession.swift are
// the same logic. Requirement ids refer to REQUIREMENTS.md.
class TrackerSession {
    // Give up waiting for the phone's ack after this long. The full round trip
    // (watch -> phone transport -> app -> ack -> watch) can take several seconds
    // over the Garmin link, so keep this generous to avoid false "sync failed".
    public static const SYNC_TIMEOUT_MS = 10000;
    public static const STATUS_TICK_MS = 1000;
    public static const VIBRATE_EVERY = 10;
    public static const SAMPLE_PERIOD_MS = 40; // 25 Hz

    public static const MENU_SESSION_END = "session_end";
    public static const MENU_DISCARD = "discard_run";
    public static const MENU_RESUME = "resume_session";

    public static const ITEM_SYNC_QUIT = "sync_quit";
    public static const ITEM_SYNC_RETRY = "sync_retry";
    public static const ITEM_NOSYNC_QUIT = "nosync_quit";
    public static const ITEM_CONTINUE = "continue_session";
    public static const ITEM_CONTINUE_LATER = "continue_later";
    public static const ITEM_DISCARD_SESSION = "discard_session";
    public static const ITEM_DISCARD_YES = "discard_yes";
    public static const ITEM_DISCARD_NO = "discard_no";

    public static const SYNC_FAILED = "Sync failed";

    private var _link as PhoneLink;
    private var _scheduler as Scheduler;
    private var _clock as Clock;
    private var _effects as WatchEffects;
    private var _recorder as ActivityRecorder;
    private var _store as SessionStore;

    private var _detector as JugglingDetector;
    private var _sessionStartMs as Number;
    // When the user ended the session; the clock stands still from here.
    private var _sessionEndMs as Number?;
    // Time spent in the session-end menu before picking Continue. The session
    // clock stands still while that menu is up, so this is left out of the
    // elapsed time shown and sent to the phone.
    private var _pausedMs as Number;
    // The session's id on the phone, fixed by the first sync. Ending again
    // after Continue resends the whole session under the same id, so the
    // phone replaces its first copy instead of storing the runs twice (SYNC-5).
    private var _sessionTimestamp as Number?;
    // Session time from before the app was last closed with Continue later.
    private var _priorSeconds as Number;
    // The watch's activity for this stretch of juggling (ACT-1, ACT-2): one
    // per time the app is open, since Connect IQ cannot reopen an activity
    // once the app has closed.
    private var _activityStarted as Boolean;
    private var _activityEnded as Boolean;
    // Runs completed before this stretch began; the activity's stats cover
    // only the runs after them.
    private var _stretchFirstRun as Number;
    // Told when the Previous session is discarded, so the navigator can show
    // the first screen (LATER-4).
    private var _discardListener as Method?;
    // Garmin only: a menu opened before the view is on screen (the Previous
    // session menu at launch) is pushed once the view shows.
    private var _menuPending as Boolean;

    private var _sending as Boolean;
    private var _errorMsg as String?;
    // Payload kept so a sync can be retried after a failure or timeout.
    private var _pendingPayload as Dictionary?;
    // Bumped on every transmit attempt. A timed-out or abandoned attempt can
    // still report back late; without checking this, its callback would land
    // on whichever attempt is current by then instead of being ignored (SYNC-4).
    private var _syncGeneration as Number;
    private var _syncTimer as Cancellable?;
    private var _statusTimer as Cancellable?;
    private var _syncDots as Number;
    // Last watch-hand catch count at which we vibrated.
    private var _lastVibrateCount as Number;
    private var _menu as MenuSpec?;
    private var _exited as Boolean;
    // True when the accelerometer could not be registered (SENS-4).
    private var _sensorError as Boolean;

    // A new session for `ballCount` balls, or with `saved` the session kept
    // with Continue later, reopened with its Previous session menu up
    // (LATER-1); its ball count then comes from `saved`.
    public function initialize(ballCount as Number, saved as Dictionary?, link as PhoneLink,
            scheduler as Scheduler, clock as Clock, effects as WatchEffects,
            recorder as ActivityRecorder, store as SessionStore) {
        _link = link;
        _scheduler = scheduler;
        _clock = clock;
        _effects = effects;
        _recorder = recorder;
        _store = store;
        var balls = ballCount;
        if (saved != null) {
            var keptBalls = saved["balls"];
            if (keptBalls instanceof Number) {
                balls = keptBalls;
            }
        }
        _detector = new JugglingDetector(balls);
        _sessionStartMs = clock.nowMs();
        _sessionEndMs = null;
        _pausedMs = 0;
        _sessionTimestamp = null;
        _priorSeconds = 0;
        _activityStarted = false;
        _activityEnded = false;
        _stretchFirstRun = 0;
        _discardListener = null;
        _menuPending = false;
        _sending = false;
        _errorMsg = null;
        _pendingPayload = null;
        _syncGeneration = 0;
        _syncTimer = null;
        _statusTimer = null;
        _syncDots = 0;
        _lastVibrateCount = 0;
        _menu = null;
        _exited = false;
        _sensorError = false;

        // Listen for the phone's acknowledgement that a session was stored.
        _link.setMessageListener(method(:onPhoneMessage));

        if (saved != null) {
            restore(saved);
        } else {
            startActivity();
        }
    }

    // ── What the screen shows (JUG-1) ─────────────────────────────────

    public function detector() as JugglingDetector {
        return _detector;
    }

    public function errorMessage() as String? {
        return _errorMsg;
    }

    public function isSending() as Boolean {
        return _sending;
    }

    public function syncDots() as Number {
        return _syncDots;
    }

    public function menu() as MenuSpec? {
        return _menu;
    }

    public function isExited() as Boolean {
        return _exited;
    }

    public function sensorError() as Boolean {
        return _sensorError;
    }

    public function setSensorError(failed as Boolean) as Void {
        _sensorError = failed;
        _effects.update();
    }

    public function nowMs() as Number {
        return _clock.nowMs();
    }

    public function elapsedSeconds() as Number {
        var endMs = _sessionEndMs;
        if (endMs == null) {
            endMs = _clock.nowMs();
        }
        var elapsedMs = endMs - _sessionStartMs - _pausedMs;
        if (elapsedMs < 0) {
            elapsedMs = 0;
        }
        return _priorSeconds + elapsedMs / 1000;
    }

    // The navigator's callback for LATER-4, called with no arguments.
    public function setDiscardListener(listener as Method?) as Void {
        _discardListener = listener;
    }

    // The view is on screen: push a menu that was opened before it was.
    public function onShown() as Void {
        var spec = _menu;
        if (_menuPending && spec != null) {
            _menuPending = false;
            _effects.showMenu(spec, method(:onMenuSelect), method(:onMenuBack));
        }
    }

    // ── Samples ───────────────────────────────────────────────────────

    // One accelerometer batch, its last sample taken at `nowMs`. Samples in a
    // batch are 40 ms apart, so the refractory period works inside a batch.
    // A menu over the screen hides it, and on the watch that drops the
    // sensor listener, so samples are ignored while one is up.
    public function onBatch(xs as Array, ys as Array, zs as Array, nowMs as Number) as Void {
        if (_exited || _menu != null) {
            return;
        }
        var n = batchLength(xs, ys, zs);
        if (n == 0) {
            return;
        }
        var batchStartMs = nowMs - (n - 1) * SAMPLE_PERIOD_MS;
        for (var i = 0; i < n; i++) {
            // SENS-3: a stream that came up short is padded with nulls, and
            // arithmetic on one throws inside the detector, killing the app.
            var x = xs[i];
            var y = ys[i];
            var z = zs[i];
            if (x == null || y == null || z == null) {
                continue;
            }
            _detector.processSample(x.toNumber(), y.toNumber(), z.toNumber(), batchStartMs + i * SAMPLE_PERIOD_MS);
        }

        // JUG-7: vibrate every 10 watch-hand catches as tactile feedback.
        var count = _detector.currentCount;
        if (count > 0 && count / VIBRATE_EVERY > _lastVibrateCount / VIBRATE_EVERY) {
            _effects.vibrate();
            _lastVibrateCount = count;
        }

        var finished = _detector.checkAutoFinish(nowMs);
        if (finished > 0) {
            _lastVibrateCount = 0;
        }
        _effects.update();
    }

    // ── START/STOP: the session-end menu (JUG-2) ──────────────────────

    public function onStartStop() as Void {
        showSessionEndMenu(false);
    }

    private function showSessionEndMenu(isRetry as Boolean) as Void {
        if (_menu != null || _exited) {
            return;
        }
        if (!isRetry && _sessionEndMs == null) {
            _sessionEndMs = _clock.nowMs();
            // ACT-2: the activity's timer stands still with the session clock.
            if (_activityStarted && !_activityEnded) {
                _recorder.pause();
            }
        }
        var items = [] as Array<MenuItemSpec>;
        if (isRetry) {
            items.add(new MenuItemSpec(ITEM_SYNC_RETRY, "Retry sync", null));
        } else {
            items.add(new MenuItemSpec(ITEM_SYNC_QUIT, "Sync and quit", null));
        }
        items.add(new MenuItemSpec(ITEM_NOSYNC_QUIT, "Quit without sync", null));
        items.add(new MenuItemSpec(ITEM_CONTINUE_LATER, "Continue later", null));
        items.add(new MenuItemSpec(ITEM_CONTINUE, "Continue", null));
        openMenu(new MenuSpec(MENU_SESSION_END, isRetry ? SYNC_FAILED : "End session?", items));
    }

    // ── BACK: discard one run, never exit (JUG-3, JUG-4) ──────────────

    // BACK never ends the session: it offers to throw away the run in
    // progress, or else the last completed one, after a confirmation.
    public function onBack() as Void {
        if (_menu != null || _exited) {
            return;
        }
        var title;
        var detail;
        if (_detector.isRunActive()) {
            title = "Discard this run?";
            detail = _detector.currentCount.toString() + " catches";
        } else if (_detector.sessionRuns() > 0) {
            title = "Discard last run?";
            detail = _detector.previousCount.toString() + " catches";
        } else {
            // Nothing recorded yet: swallow the press. Letting it reach the
            // system would close the app, which is what this exists to stop.
            return;
        }
        // A Menu2 rather than WatchUi.Confirmation, whose yes/no come in the
        // watch's language (JUG-5).
        openMenu(new MenuSpec(MENU_DISCARD, title, [
            new MenuItemSpec(ITEM_DISCARD_YES, "Discard", detail),
            new MenuItemSpec(ITEM_DISCARD_NO, "Keep", null)
        ] as Array<MenuItemSpec>));
    }

    // ── Menu answers ──────────────────────────────────────────────────

    private function openMenu(spec as MenuSpec) as Void {
        _menu = spec;
        _effects.showMenu(spec, method(:onMenuSelect), method(:onMenuBack));
        _effects.update();
    }

    public function onMenuSelect(itemId as Object) as Void {
        var open = _menu;
        if (open == null) {
            return;
        }
        _menu = null;
        _menuPending = false;
        if (open.kind.equals(MENU_DISCARD)) {
            onDiscardResponse(ITEM_DISCARD_YES.equals(itemId));
        } else if (ITEM_SYNC_QUIT.equals(itemId)) {
            doSync();
        } else if (ITEM_SYNC_RETRY.equals(itemId)) {
            _errorMsg = null;
            attemptSync();
        } else if (ITEM_NOSYNC_QUIT.equals(itemId)) {
            cancelSyncTimer();
            exit();
        } else if (ITEM_CONTINUE_LATER.equals(itemId)) {
            continueLater();
        } else if (ITEM_DISCARD_SESSION.equals(itemId)) {
            discardSession();
        } else if (ITEM_CONTINUE.equals(itemId)) {
            onContinueSession();
        }
        _effects.update();
    }

    // JUG-6: backing out of any menu clears it. Out of the discard prompt it
    // means Keep, out of the session-end menu it means Continue.
    public function onMenuBack() as Void {
        var open = _menu;
        if (open == null) {
            return;
        }
        _menu = null;
        _menuPending = false;
        if (open.kind.equals(MENU_DISCARD)) {
            onDiscardResponse(false);
        } else if (open.kind.equals(MENU_RESUME)) {
            // LATER-5: the session stays kept, as it was, and the app closes.
            cancelSyncTimer();
            exitKeeping();
        } else {
            onContinueSession();
        }
        _effects.update();
    }

    private function onDiscardResponse(confirmed as Boolean) as Void {
        if (confirmed) {
            _detector.discardLastRun();
            // The next run's 10-catch buzz counts from zero again.
            _lastVibrateCount = 0;
        }
    }

    private function onContinueSession() as Void {
        // Resume the session clock from where the menu stopped it.
        var endMs = _sessionEndMs;
        if (endMs != null) {
            _pausedMs += _clock.nowMs() - endMs;
        }
        _sessionEndMs = null;
        // ACT-2: the activity runs on, or starts for a Previous session.
        if (!_activityStarted) {
            startActivity();
        } else if (!_activityEnded) {
            _recorder.resume();
        }
        // JUG-8: nothing is failing any more once the user is juggling again.
        _errorMsg = null;
        // JUG-8: abandon the sync. Its ack can still arrive late, and with the
        // payload kept it would close the app mid-juggle. Ending again sends
        // the whole session afresh under the same timestamp.
        _sending = false;
        cancelSyncTimer();
        cancelStatusTimer();
        _pendingPayload = null;
        _syncGeneration += 1;
    }

    // ── Sync (SYNC-1..5) ──────────────────────────────────────────────

    private function doSync() as Void {
        if (_sending || _menu != null) {
            return;
        }

        // Fold any run still in progress into the session.
        _detector.finishCurrentRun();
        if (_sessionEndMs == null) {
            _sessionEndMs = _clock.nowMs();
        }

        var runs = _detector.runCatches();
        if (runs.size() == 0) {
            // SYNC-3: nothing worth sending, just close.
            exit();
            return;
        }

        if (_sessionTimestamp == null) {
            _sessionTimestamp = _clock.epochSeconds();
        }

        var payload = {
            "type" => "session",
            "countMode" => "watch_hand",
            "balls" => _detector.ballCount,
            "timestamp" => _sessionTimestamp,
            "durationSeconds" => elapsedSeconds(),
            "runDurationsMillis" => _detector.runDurationsMillis(),
            "runs" => runs
        };
        // Left out until a run has been long enough to score, so the phone
        // shows no value rather than a made-up one (SYNC-1).
        var shape = _detector.shapeConsistencyPercent();
        if (shape >= 0) {
            payload["shapeConsistency"] = shape;
        }
        _pendingPayload = payload;
        attemptSync();
    }

    // Start (or retry) a transmit attempt and arm the timeout.
    private function attemptSync() as Void {
        var payload = _pendingPayload;
        if (payload == null) {
            return;
        }
        _errorMsg = null;
        // Each attempt gets its own id, so a late callback from an attempt
        // that already timed out cannot be mistaken for this one.
        _syncGeneration += 1;

        _sending = true;
        startSyncTimer();
        startStatusTimer();
        try {
            _link.send(payload, method(:onTransmitResult), _syncGeneration);
        } catch (ex) {
            _effects.log("Transmission error: " + ex.getErrorMessage());
            _sending = false;
            cancelSyncTimer();
            cancelStatusTimer();
            promptRetryOrQuit();
        }
        _effects.update();
    }

    // Delivery alone closes nothing: only the phone's ack does (SYNC-2).
    public function onTransmitResult(generation as Number, delivered as Boolean) as Void {
        if (delivered) {
            return;
        }
        if (generation != _syncGeneration) {
            return;   // late report from an abandoned attempt
        }
        if (!_sending) {
            return;
        }
        _sending = false;
        cancelSyncTimer();
        cancelStatusTimer();
        promptRetryOrQuit();
        _effects.update();
    }

    // Fired after SYNC_TIMEOUT_MS. No ack by now counts as a failure.
    public function onSyncTimeout() as Void {
        _syncTimer = null;
        if (_sending) {
            _sending = false;
            cancelStatusTimer();
            promptRetryOrQuit();
            _effects.update();
        }
    }

    // SYNC-2: only the phone's ack for this session closes the app, so the
    // data is never assumed delivered.
    public function onPhoneMessage(data as Dictionary) as Void {
        var type = data["type"];
        if (type == null || !type.equals("ack")) {
            return;
        }
        if (_pendingPayload == null) {
            return;
        }
        // The phone echoes the timestamp it stored; a late ack for a session
        // sent earlier must not close the app before this one is stored.
        if (data["timestamp"] != _sessionTimestamp) {
            return;
        }
        _sending = false;
        cancelSyncTimer();
        cancelStatusTimer();
        _pendingPayload = null;
        if (_menu != null) {
            _menu = null;
            _effects.closeMenu();
        }
        exit();
        _effects.update();
    }

    private function promptRetryOrQuit() as Void {
        _errorMsg = SYNC_FAILED;
        showSessionEndMenu(true);
    }

    private function startSyncTimer() as Void {
        cancelSyncTimer();
        _syncTimer = _scheduler.schedule(SYNC_TIMEOUT_MS, method(:onSyncTimeout), false);
    }

    private function cancelSyncTimer() as Void {
        var timer = _syncTimer;
        if (timer != null) {
            timer.cancel();
            _syncTimer = null;
        }
    }

    // Animates "Sync to phone..." with one more dot each second.
    private function startStatusTimer() as Void {
        cancelStatusTimer();
        _syncDots = 1;
        _statusTimer = _scheduler.schedule(STATUS_TICK_MS, method(:onStatusTick), true);
    }

    private function cancelStatusTimer() as Void {
        var timer = _statusTimer;
        if (timer != null) {
            timer.cancel();
            _statusTimer = null;
        }
        _syncDots = 0;
    }

    // Cycles the dots 1 -> 2 -> 3 -> 1.
    public function onStatusTick() as Void {
        _syncDots = (_syncDots % 3) + 1;
        _effects.update();
    }

    // ── Continue later (LATER-1..6) and the activity (ACT-1, ACT-2) ───

    // JUG-9: keeps the session on the watch and closes the app. With no run
    // completed there is nothing worth keeping, and it just closes.
    private function continueLater() as Void {
        cancelSyncTimer();
        if (keepForLater()) {
            exitKeeping();
        } else {
            exit();
        }
    }

    // LATER-6: the system is closing the app (AppBase.onStop on the Garmin)
    // with the session still open. It is kept as with Continue later, and its
    // activity saved, so neither is lost. A Previous session nobody answered
    // stays kept as it was.
    public function onAppStop() as Void {
        if (_exited) {
            return;
        }
        var open = _menu;
        if (open != null && open.kind.equals(MENU_RESUME)) {
            exitQuietly();
            return;
        }
        if (!keepForLater()) {
            _store.clear();
        }
        exitQuietly();
    }

    // Writes the session to the store; false when it has no completed run.
    private function keepForLater() as Boolean {
        _detector.finishCurrentRun();
        if (_sessionEndMs == null) {
            _sessionEndMs = _clock.nowMs();
        }
        var runs = _detector.runCatches();
        if (runs.size() == 0) {
            return false;
        }
        var shape = _detector.shapeRuns();
        var saved = {
            "balls" => _detector.ballCount,
            "elapsedSeconds" => elapsedSeconds(),
            "runs" => runs,
            "runDurationsMillis" => _detector.runDurationsMillis(),
            "shapeSums" => shape[0],
            "shapeCounts" => shape[1]
        };
        if (_sessionTimestamp != null) {
            saved["timestamp"] = _sessionTimestamp;
        }
        _store.save(saved);
        return true;
    }

    // Reopens a kept session (LATER-1): its runs, time and phone timestamp,
    // with the clock stopped and the Previous session menu up until answered.
    private function restore(saved as Dictionary) as Void {
        var runs = numbers(saved["runs"]);
        var durations = numbers(saved["runDurationsMillis"]);
        var sums = saved["shapeSums"];
        var counts = numbers(saved["shapeCounts"]);
        if (!(sums instanceof Array) || (sums as Array).size() != runs.size() || counts.size() != runs.size()) {
            sums = [] as Array<Float>;
            counts = [] as Array<Number>;
            for (var i = 0; i < runs.size(); i++) {
                sums.add(0.0f);
                counts.add(0);
            }
        }
        while (durations.size() < runs.size()) {
            durations.add(0);
        }
        _detector.restoreRuns(runs, durations, sums as Array<Float>, counts);
        _stretchFirstRun = runs.size();
        var prior = saved["elapsedSeconds"];
        _priorSeconds = prior instanceof Number ? prior : 0;
        var timestamp = saved["timestamp"];
        _sessionTimestamp = timestamp instanceof Number ? timestamp : null;
        _sessionEndMs = _clock.nowMs();

        var n = runs.size();
        var detail = _detector.ballCount.toString() + " balls, " + n.toString() + (n == 1 ? " run" : " runs");
        _menu = new MenuSpec(MENU_RESUME, "Previous session", [
            new MenuItemSpec(ITEM_CONTINUE, "Continue", detail),
            new MenuItemSpec(ITEM_SYNC_QUIT, "Sync and quit", null),
            new MenuItemSpec(ITEM_DISCARD_SESSION, "Discard", null)
        ] as Array<MenuItemSpec>);
        _menuPending = true;
    }

    // LATER-4: throws the kept session away and goes to the first screen.
    private function discardSession() as Void {
        _store.clear();
        exitQuietly();
        var listener = _discardListener;
        if (listener != null) {
            listener.invoke();
        }
    }

    private function startActivity() as Void {
        _activityStarted = true;
        _stretchFirstRun = _detector.sessionRuns();
        _recorder.start();
    }

    // ACT-2: saved with this stretch's stats when it has a run, else
    // discarded so an empty activity never reaches Garmin Connect.
    private function endActivity() as Void {
        if (!_activityStarted || _activityEnded) {
            return;
        }
        _activityEnded = true;
        var runs = _detector.runCatches();
        var first = _stretchFirstRun < runs.size() ? _stretchFirstRun : runs.size();
        var count = runs.size() - first;
        if (count == 0) {
            _recorder.discard();
            return;
        }
        var total = 0;
        for (var i = first; i < runs.size(); i++) {
            total += runs[i];
        }
        _recorder.save(_detector.ballCount, total.toFloat() / count);
    }

    // Closes the app with the session finished: nothing stays kept.
    private function exit() as Void {
        if (_exited) {
            return;
        }
        _store.clear();
        exitKeeping();
    }

    // Closes the app, leaving the store as it is.
    private function exitKeeping() as Void {
        if (_exited) {
            return;
        }
        exitQuietly();
        _effects.exit();
    }

    // Ends the session without closing the app.
    private function exitQuietly() as Void {
        if (_exited) {
            return;
        }
        _exited = true;
        cancelSyncTimer();
        cancelStatusTimer();
        _link.setMessageListener(null);
        endActivity();
    }
}

// The whole numbers of a stored array, or an empty array.
function numbers(value as Object?) as Array<Number> {
    var out = [] as Array<Number>;
    if (value instanceof Array) {
        for (var i = 0; i < value.size(); i++) {
            var v = value[i];
            if (v instanceof Number) {
                out.add(v);
            } else if (v instanceof Long || v instanceof Float || v instanceof Double) {
                out.add(v.toNumber());
            }
        }
    }
    return out;
}

// How many samples of a batch can be read: the shortest of the three axes.
function batchLength(xs as Array?, ys as Array?, zs as Array?) as Number {
    if (xs == null || ys == null || zs == null) {
        return 0;
    }
    var n = xs.size();
    if (ys.size() < n) { n = ys.size(); }
    if (zs.size() < n) { n = zs.size(); }
    return n;
}
