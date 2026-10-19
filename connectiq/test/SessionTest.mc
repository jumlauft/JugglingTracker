import Toybox.Lang;
import Toybox.Test;

// Executable tests for the Juggle and Record logic, per REQUIREMENTS.md. The
// sessions run here on the fakes in Fakes.mc; the shared scenarios in
// ScenarioTest.mc cover the workflows, and these cover what the scenarios
// leave out: timing, payload details, logging, memory and chunking, which
// differ between the watches or need a closer look.

function trackerOn(host as FakeHost) as TrackerSession {
    return host.newTracker(3) as TrackerSession;
}

function recordingOn(host as FakeHost) as RecordingSession {
    return host.newRecording(3) as RecordingSession;
}

// Feeds `samples` to a session in batches of 25 from `startMs` and returns
// the time after the last one.
function feedSession(session, samples as Feed, startMs as Number) as Number {
    var n = samples.size();
    for (var from = 0; from < n; from += 25) {
        var to = from + 25 < n ? from + 25 : n;
        session.onBatch(samples.xs.slice(from, to), samples.ys.slice(from, to), samples.zs.slice(from, to),
            startMs + (to - 1) * 40);
    }
    return startMs + n * 40;
}

// A session with one completed run of `catches`, ready to sync.
function trackerWithRun(host as FakeHost, catches as Number) as TrackerSession {
    var tracker = trackerOn(host);
    var t = feedSession(tracker, new Feed().still(55), 0);
    t = feedSession(tracker, new Feed().catches(catches), t);
    feedSession(tracker, new Feed().still(60), t);
    return tracker;
}

// A recording session in LABELING with `samples` recorded.
function recordingWithRun(host as FakeHost, samples as Number) as RecordingSession {
    var rec = recordingOn(host);
    rec.onStart();
    feedSession(rec, new Feed().still(samples), 0);
    rec.onStart();
    return rec;
}

// ── JUG ────────────────────────────────────────────────────────────────

// JUG-1: what the Juggle screen writes for its stats, run state and time.
(:test)
function jug1_screenTextFormats(logger as Logger) as Boolean {
    Test.assertEqualMessage(Format.runState(true), "RUN ACTIVE", "active run");
    Test.assertEqualMessage(Format.runState(false), "WAITING", "between runs");
    Test.assertEqualMessage(Format.countOrDash(0), "-", "no value yet");
    Test.assertEqualMessage(Format.countOrDash(12), "12", "a count");
    Test.assertEqualMessage(Format.averageOrDash(0.0f), "-", "no average yet");
    Test.assertEqualMessage(Format.averageOrDash(4.5f), "4.5", "one decimal");
    Test.assertEqualMessage(Format.percentOrDash(-1), "-", "no Regularity yet (SHAPE-4)");
    Test.assertEqualMessage(Format.percentOrDash(83), "83%", "Regularity in percent (SHAPE-4)");
    Test.assertEqualMessage(Format.elapsed(0), "0:00", "zero");
    Test.assertEqualMessage(Format.elapsed(65), "1:05", "minutes and seconds");
    Test.assertEqualMessage(Format.elapsed(3725), "1:02:05", "hours from one hour on");
    Test.assertEqualMessage(Format.syncing(2), "Sync to phone..", "one dot per tick");
    return true;
}

// JUG-2: the session clock stands still while the session-end menu is up,
// and Continue resumes it from there.
(:test)
function jug2_menuTimeIsLeftOutOfTheSession(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var tracker = trackerOn(host);
    host.scheduler.advance(5000);
    tracker.onStartStop();
    Test.assertEqualMessage((tracker.menu() as MenuSpec).kind, TrackerSession.MENU_SESSION_END, "START/STOP opens the menu");
    host.scheduler.advance(30000);
    Test.assertEqualMessage(tracker.elapsedSeconds(), 5, "the clock stops while the menu is up");
    tracker.onMenuSelect(TrackerSession.ITEM_CONTINUE);
    host.scheduler.advance(4000);
    Test.assertEqualMessage(tracker.elapsedSeconds(), 9, "Continue resumes where the menu stopped it");
    return true;
}

// ── SENS ───────────────────────────────────────────────────────────────

// SENS-3: a short batch padded with nulls is read up to its shortest axis,
// and every null sample is skipped instead of reaching the detector.
(:test)
function sens3_nullSamplesAreSkipped(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var tracker = trackerOn(host);
    var t = feedSession(tracker, new Feed().still(55), 0);
    var padded = new Feed().catches(3);
    padded.add(null, 0, BASELINE_Z);
    padded.add(0, null, BASELINE_Z);
    padded.add(0, 0, null);
    feedSession(tracker, padded, t);
    Test.assertEqualMessage(tracker.detector().currentCount, 3, "the real samples still count");

    // Axes of different lengths: only as many samples as the shortest.
    tracker.onBatch([0, 0, 0], [0, 0], [BASELINE_Z], 99999);

    var rec = recordingOn(host);
    rec.onStart();
    var gappy = new Feed().still(10);
    gappy.add(null, null, null);
    gappy.add(0, 0, null);
    feedSession(rec, gappy, 0);
    Test.assertEqualMessage(rec.recordedSamples(), 10, "null samples are not recorded");
    return true;
}

// SENS-4: with no accelerometer, START on the idle record screen does nothing,
// and a later successful registration clears the error.
(:test)
function sens4_recordStartDoesNothingWithoutTheSensor(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var rec = recordingOn(host);
    rec.setSensorError(true);
    Test.assertMessage(rec.onStart(), "START is still consumed");
    Test.assertEqualMessage(rec.phase(), RecordingSession.PHASE_IDLE, "no run starts");
    rec.setSensorError(false);
    rec.onStart();
    Test.assertEqualMessage(rec.phase(), RecordingSession.PHASE_RECORDING, "a run starts once the sensor is back");

    var tracker = trackerOn(host);
    tracker.setSensorError(true);
    Test.assertMessage(tracker.sensorError(), "the Juggle screen knows to show the error");
    return true;
}

// ── SYNC ───────────────────────────────────────────────────────────────

// SYNC-1: the session message carries the duration and one duration per
// run, and leaves shapeConsistency out until a run has been scored.
(:test)
function sync1_sessionCarriesDurationsAndShapeOnlyOnceScored(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var tracker = trackerWithRun(host, 3);
    host.scheduler.advance(7000);
    tracker.onStartStop();
    tracker.onMenuSelect(TrackerSession.ITEM_SYNC_QUIT);
    var payload = host.link.last() as Dictionary;
    Test.assertEqualMessage(payload["type"], "session", "a session message");
    Test.assertEqualMessage(payload["durationSeconds"], 7, "the session's elapsed time");
    Test.assertEqualMessage((payload["runDurationsMillis"] as Array).size(), (payload["runs"] as Array).size(),
        "one duration per run");
    var scored = tracker.detector().shapeConsistencyPercent() >= 0;
    Test.assertEqualMessage(payload.hasKey("shapeConsistency"), scored, "Regularity only once scored");
    return true;
}

// SYNC-2: a transmit failure or a 10 s timeout opens the retry menu; the
// app does not close on delivery alone.
(:test)
function sync2_failureOrTimeoutOpensTheRetryMenu(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var tracker = trackerWithRun(host, 3);
    tracker.onStartStop();
    tracker.onMenuSelect(TrackerSession.ITEM_SYNC_QUIT);
    host.link.complete(true);
    host.scheduler.advance(9999);
    Test.assertMessage(tracker.isSending() && tracker.menu() == null, "still waiting for the ack");
    host.scheduler.advance(1);
    Test.assertEqualMessage((tracker.menu() as MenuSpec).title, "Sync failed", "the timeout opens the retry menu");
    Test.assertEqualMessage(host.effects.exits, 0, "delivery alone never closes the app");

    tracker.onMenuSelect(TrackerSession.ITEM_SYNC_RETRY);
    host.link.complete(false);
    Test.assertMessage(tracker.isSending() && tracker.menu() == null, "a failed send keeps trying (SYNC-6)");
    host.scheduler.advance(TrackerSession.SYNC_TIMEOUT_MS);
    Test.assertEqualMessage((tracker.menu() as MenuSpec).title, "Sync failed", "until the timeout opens the menu");
    return true;
}

// SYNC-4: a late failure report from an attempt that already timed out does
// not abort the retry that is in flight.
(:test)
function sync4_lateFailureFromAnAbandonedAttemptIsIgnored(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var tracker = trackerWithRun(host, 3);
    tracker.onStartStop();
    tracker.onMenuSelect(TrackerSession.ITEM_SYNC_QUIT);
    host.scheduler.advance(TrackerSession.SYNC_TIMEOUT_MS);
    tracker.onMenuSelect(TrackerSession.ITEM_SYNC_RETRY);
    host.link.completeAt(false, 0);
    Test.assertMessage(tracker.isSending(), "the retry is still in flight");
    Test.assertMessage(tracker.menu() == null, "no retry menu over it");
    host.link.completeAt(false, 1);
    Test.assertMessage(tracker.isSending() && tracker.menu() == null, "the retry's own failure sends again");
    host.scheduler.advance(TrackerSession.STATUS_TICK_MS);
    Test.assertEqualMessage(host.link.sent.size(), 3, "on the next tick");
    return true;
}

// SYNC-6: with the phone out of reach every send fails at once. That must not
// bounce straight back to the menu, which made Retry look dead on the
// Forerunner: the sync screen stays up, the session is sent again each tick
// until the timeout, and Retry starts a real attempt.
(:test)
function sync6_failedSendIsSentAgainUntilTheTimeout(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var tracker = trackerWithRun(host, 3);
    tracker.onStartStop();
    host.link.autoResult = false;
    tracker.onMenuSelect(TrackerSession.ITEM_SYNC_QUIT);
    Test.assertMessage(tracker.isSending() && tracker.menu() == null, "the sync screen stays up");
    Test.assertEqualMessage(host.link.sent.size(), 1, "one send so far");

    host.scheduler.advance(TrackerSession.STATUS_TICK_MS);
    Test.assertEqualMessage(host.link.sent.size(), 2, "sent again on the next tick");
    Test.assertMessage(tracker.menu() == null, "still no menu");

    host.scheduler.advance(TrackerSession.SYNC_TIMEOUT_MS - TrackerSession.STATUS_TICK_MS);
    Test.assertMessage(!tracker.isSending(), "the attempt ends at the timeout");
    Test.assertEqualMessage((tracker.menu() as MenuSpec).title, "Sync failed", "and the retry menu opens");
    var sentBeforeRetry = host.link.sent.size();

    tracker.onMenuSelect(TrackerSession.ITEM_SYNC_RETRY);
    Test.assertMessage(tracker.isSending() && tracker.menu() == null, "Retry shows the sync, not the menu again");
    Test.assertEqualMessage(host.link.sent.size(), sentBeforeRetry + 1, "Retry sends");

    host.link.autoResult = null;
    host.scheduler.advance(TrackerSession.STATUS_TICK_MS);
    host.link.ack(1700000000);
    Test.assertEqualMessage(host.effects.exits, 1, "the ack closes the app");
    return true;
}

// ── REC ────────────────────────────────────────────────────────────────

// REC-2: a run stops on its own when free memory drops below 16 KB, and at
// 3000 samples.
(:test)
function rec2_runStopsAtTheMemoryFloorAndTheCap(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var rec = recordingOn(host);
    rec.onStart();
    host.effects.free = RecordingSession.MIN_FREE_MEMORY + 1;
    feedSession(rec, new Feed().still(30), 0);
    Test.assertEqualMessage(rec.phase(), RecordingSession.PHASE_RECORDING, "above the floor it records");
    host.effects.free = RecordingSession.MIN_FREE_MEMORY - 1;
    feedSession(rec, new Feed().still(30), 1200);
    Test.assertEqualMessage(rec.phase(), RecordingSession.PHASE_LABELING, "below the floor the run ends");
    Test.assertEqualMessage(rec.recordedSamples(), 50, "at the next memory check");
    Test.assertMessage(host.effects.logs[0].find("Low memory") != null, "and says why in the log");

    var full = recordingOn(new FakeHost(1700000000));
    full.onStart();
    var t = 0;
    for (var i = 0; i < 121; i++) {
        t = feedSession(full, new Feed().still(25), t);
    }
    Test.assertEqualMessage(full.phase(), RecordingSession.PHASE_LABELING, "the run ends at the cap");
    Test.assertEqualMessage(full.recordedSamples(), RecordingSession.MAX_RUN_SAMPLES, "with 3000 samples");
    return true;
}

// REC-4: confirming a run logs one RUN_DATA summary line, not the samples.
(:test)
function rec4_confirmLogsOneSummaryLine(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var rec = recordingWithRun(host, 80);
    rec.onUp();
    rec.onStart();
    Test.assertEqualMessage(host.effects.logs.size(), 1, "one line per run");
    Test.assertEqualMessage(host.effects.logs[0],
        "RUN_DATA,balls=3,catches=1,detected=0,rate=25,countMode=watch_hand,samples=80", "the summary");
    return true;
}

// REC-5: rec_start, one rec_chunk per 50 samples, then rec_end.
(:test)
function rec5_runsGoOverInChunksOfFifty(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var rec = recordingWithRun(host, 120);
    rec.onStart();
    host.link.autoResult = true;
    host.link.complete(true);
    for (var i = 0; i < 10; i++) {
        host.scheduler.advance(RecordingSession.NEXT_PART_DELAY_MS);
    }
    var sent = host.link.sent;
    Test.assertEqualMessage(sent.size(), 5, "header, three chunks, end marker");
    Test.assertEqualMessage(sent[0]["type"], "rec_start", "header first");
    Test.assertEqualMessage(sent[0]["chunks"], 3, "announcing three chunks");
    Test.assertEqualMessage(sent[0]["samples"], 120, "and the sample count");
    Test.assertEqualMessage((sent[1]["x"] as Array).size(), 50, "50 samples per chunk");
    Test.assertEqualMessage(sent[2]["i"], 1, "chunks are numbered");
    Test.assertEqualMessage((sent[3]["z"] as Array).size(), 20, "the last chunk holds the rest");
    Test.assertEqualMessage(sent[4]["type"], "rec_end", "end marker last");
    Test.assertEqualMessage(rec.phase(), RecordingSession.PHASE_SYNCING, "waiting for the ack");
    return true;
}

// REC-6: a failure reported late by an abandoned attempt (here: one the user
// skipped) does not fail the run that is syncing now.
(:test)
function rec6_lateFailureFromAnAbandonedAttemptIsIgnored(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var rec = recordingWithRun(host, 30);
    rec.onStart();
    host.scheduler.advance(RecordingSession.SYNC_TIMEOUT_MS);
    rec.onMenuSelect(RecordingSession.ITEM_SYNC_SKIP);
    rec.onStart();
    feedSession(rec, new Feed().still(30), 0);
    rec.onStart();
    rec.onStart();
    host.link.completeAt(false, 0);
    Test.assertEqualMessage(rec.phase(), RecordingSession.PHASE_SYNCING, "the new run keeps syncing");
    Test.assertMessage(rec.menu() == null, "with no failure menu");
    return true;
}

// REC-7: the next part goes out from a timer, never from inside the
// delivery callback.
(:test)
function rec7_nextPartGoesOutFromATimer(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var rec = recordingWithRun(host, 30);
    rec.onStart();
    host.link.complete(true);
    Test.assertEqualMessage(host.link.sent.size(), 1, "nothing is sent from the callback");
    host.scheduler.advance(RecordingSession.NEXT_PART_DELAY_MS);
    Test.assertEqualMessage(host.link.sent.size(), 2, "the chunk follows from the timer");
    return true;
}

// REC-10: backing out of the sync-failure menu retries, the only choice that
// keeps the run.
(:test)
function rec10_backingOutOfTheSyncMenuRetries(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var rec = recordingWithRun(host, 30);
    rec.onStart();
    host.link.complete(false);
    Test.assertEqualMessage((rec.menu() as MenuSpec).kind, RecordingSession.MENU_SYNC, "the sync menu is up");
    rec.onMenuBack();
    Test.assertMessage(rec.menu() == null, "backing out closes it");
    host.scheduler.advance(RecordingSession.NEXT_PART_DELAY_MS);
    Test.assertEqualMessage(host.link.sent.size(), 2, "and the run is sent again");
    Test.assertEqualMessage(host.link.sent[1]["type"], "rec_start", "from its header");
    return true;
}

// REC-11: stepping back from the idle record screen releases the phone
// listener and every timer, so the session (and its sample buffer) is not
// kept alive.
(:test)
function rec11_backFromIdleReleasesTheSession(logger as Logger) as Boolean {
    var host = new FakeHost(1700000000);
    var rec = recordingOn(host);
    Test.assertMessage(host.link.listener != null, "the session listens for acks");
    Test.assertMessage(rec.onBack(), "BACK on the idle screen leaves it");
    Test.assertMessage(host.link.listener == null, "the listener is released");
    Test.assertEqualMessage(host.scheduler.pending(), 0, "no timer is left");
    return true;
}
