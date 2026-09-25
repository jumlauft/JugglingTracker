import Toybox.Lang;
import Toybox.Test;

// Executable tests for the behaviour specified in REQUIREMENTS.md.
// Each test names the requirement it secures. Run them with ./run_tests.sh.
//
// The detector takes raw accelerometer samples in milli-g, so the helpers
// below work in milli-g and convert the amplitudes the requirements state in
// m/s². Baseline is a watch held still with gravity on Z.

const MILLI_G_PER_MS2 = 1000.0 / 9.80665;
const BASELINE_Z = 1000;        // milli-g, i.e. 1 g
const SAMPLE_PERIOD_MS = 40;    // 25 Hz

// Feeds `samples` still-watch samples, advancing time, and returns the new time.
function feedBaseline(detector as JugglingDetector, startMs as Number, samples as Number) as Number {
    var nowMs = startMs;
    for (var i = 0; i < samples; i++) {
        detector.processSample(0, 0, BASELINE_Z, nowMs);
        detector.checkAutoFinish(nowMs);
        nowMs += SAMPLE_PERIOD_MS;
    }
    return nowMs;
}

// One catch-like impulse: a short spike against gravity, then enough still
// samples for the merge window to close and the burst to commit.
function feedBurstAmp(detector as JugglingDetector, startMs as Number, amplitudeMs2 as Float) as Number {
    var nowMs = startMs;
    var spikeZ = BASELINE_Z - (amplitudeMs2 * MILLI_G_PER_MS2).toNumber();
    for (var i = 0; i < 3; i++) {
        detector.processSample(0, 0, spikeZ, nowMs);
        detector.checkAutoFinish(nowMs);
        nowMs += SAMPLE_PERIOD_MS;
    }
    for (var j = 0; j < 12; j++) {
        detector.processSample(0, 0, BASELINE_Z, nowMs);
        detector.checkAutoFinish(nowMs);
        nowMs += SAMPLE_PERIOD_MS;
    }
    return nowMs;
}

function feedBurst(detector as JugglingDetector, startMs as Number) as Number {
    return feedBurstAmp(detector, startMs, 50.0);
}

// Drives `catches` watch-hand catches into a fresh run. Because only every
// other burst counts (DET-8), that needs 2*catches - 1 bursts.
function feedCatches(detector as JugglingDetector, startMs as Number, catches as Number) as Number {
    var nowMs = startMs;
    var bursts = catches * 2 - 1;
    for (var i = 0; i < bursts; i++) {
        nowMs = feedBurst(detector, nowMs);
    }
    return nowMs;
}

function warmedUp(detector as JugglingDetector) as Number {
    return feedBaseline(detector, 0, 55);
}

// ── DET: detection algorithm ───────────────────────────────────────────

// DET-4: the warmup window suppresses detection so a stationary watch never
// registers a run at startup while the gravity estimate is still settling.
(:test)
function det4_noCountDuringWarmup(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = 0;
    // WARMUP_SAMPLES is 25; stay just inside it, spiking hard the whole way.
    for (var i = 0; i < 24; i++) {
        d.processSample(0, 0, -4000, nowMs);
        nowMs += SAMPLE_PERIOD_MS;
    }
    Test.assertEqualMessage(d.currentCount, 0, "warmup must not produce catches");
    Test.assertEqualMessage(d.sessionRuns(), 0, "warmup must not produce runs");
    return true;
}

// DET-8: bursts alternate between hands and only the odd ones are the watch
// hand, so three impulses are two catches, not three.
(:test)
function det8_everyOtherBurstIsAWatchHandCatch(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);

    nowMs = feedBurst(d, nowMs);
    Test.assertEqualMessage(d.currentCount, 1, "first burst is a watch-hand catch");

    nowMs = feedBurst(d, nowMs);
    Test.assertEqualMessage(d.currentCount, 1, "second burst is the other hand");

    feedBurst(d, nowMs);
    Test.assertEqualMessage(d.currentCount, 2, "third burst is the watch hand again");
    return true;
}

// DET-5/DET-6: an impulse below the ball-count's raw magnitude gate is noise,
// not a catch, however cleanly it crosses the highpass threshold.
(:test)
function det5_rawMagnitudeGateRejectsWeakMotion(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);

    // MIN_RAW_MAG_3 is 7.0 m/s².
    nowMs = feedBurstAmp(d, nowMs, 4.0);
    feedBaseline(d, nowMs, 20);

    Test.assertEqualMessage(d.currentCount, 0, "motion under the gate must not count");
    return true;
}

// DET-6: the gate is per ball count, and 6 balls demands a much harder peak
// (17.0 m/s²) than 3 balls (7.0), so the same impulse counts for one and not
// the other. This is what stops a 6-ball session counting arm swing.
(:test)
function det6_gateIsPerBallCount(logger as Logger) as Boolean {
    var three = new JugglingDetector(3);
    var nowMs = warmedUp(three);
    feedBurstAmp(three, nowMs, 12.0);
    Test.assertEqualMessage(three.currentCount, 1, "12 m/s2 clears the 3-ball gate of 7.0");

    var six = new JugglingDetector(6);
    nowMs = warmedUp(six);
    feedBurstAmp(six, nowMs, 12.0);
    Test.assertEqualMessage(six.currentCount, 0, "12 m/s2 must not clear the 6-ball gate of 17.0");
    return true;
}

// DET-9: a run ends on its own once no catch has landed for 2 s, without the
// user touching anything.
(:test)
function det9_runAutoFinishesAfterIdleDelay(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);
    nowMs = feedCatches(d, nowMs, 3);
    Test.assertEqualMessage(d.currentCount, 3, "three catches before the pause");

    // AUTO_FINISH_DELAY_MS is 2000; 60 samples at 40 ms is 2.4 s.
    feedBaseline(d, nowMs, 60);

    Test.assertEqualMessage(d.currentCount, 0, "idle must end the run");
    Test.assertEqualMessage(d.previousCount, 3, "the finished run becomes the previous run");
    Test.assertEqualMessage(d.sessionRuns(), 1, "the finished run joins the session");
    return true;
}

// DET-10: a completed run reports the span from its first to its last counted
// catch, which is what the phone charts as run duration.
(:test)
function det10_runDurationSpansFirstToLastCatch(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);
    nowMs = feedCatches(d, nowMs, 3);
    d.finishCurrentRun();

    var durations = d.runDurationsMillis();
    Test.assertEqualMessage(durations.size(), 1, "one completed run has one duration");
    Test.assertMessage(durations[0] > 0, "a multi-catch run must have a positive duration");
    return true;
}

// ── RUN: run and session bookkeeping ───────────────────────────────────

// RUN-1: fewer than three catches is a false start and must leave the session
// exactly as it was -- this is what stops drops polluting Prev/Avg/Max.
(:test)
function run1_falseStartsAreNotRecorded(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);
    nowMs = feedCatches(d, nowMs, 2);
    Test.assertEqualMessage(d.currentCount, 2, "two catches are in progress");

    feedBaseline(d, nowMs, 60);   // let it auto-finish

    Test.assertEqualMessage(d.sessionRuns(), 0, "a 2-catch run must not be recorded");
    Test.assertEqualMessage(d.previousCount, 0, "a false start must not become Prev");
    Test.assertEqualMessage(d.sessionMax, 0, "a false start must not become Max");
    Test.assertEqualMessage(d.runCatches().size(), 0, "a false start must not be listed");
    Test.assertEqualMessage(d.currentCount, 0, "the count still resets for the next attempt");
    return true;
}

// RUN-1: three catches is the first length that counts, so the boundary is
// pinned from both sides.
(:test)
function run1_threeCatchesIsARealRun(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);
    nowMs = feedCatches(d, nowMs, 3);
    feedBaseline(d, nowMs, 60);

    Test.assertEqualMessage(d.sessionRuns(), 1, "a 3-catch run is a real run");
    Test.assertEqualMessage(d.previousCount, 3, "and becomes Prev");
    Test.assertEqualMessage(d.sessionMax, 3, "and becomes Max");
    return true;
}

// RUN-3: the session average is over completed runs, and is 0 before any.
(:test)
function run3_sessionAverageIsOverCompletedRuns(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    Test.assertEqualMessage(d.sessionAverage(), 0.0, "no runs means no average");

    var nowMs = warmedUp(d);
    nowMs = feedCatches(d, nowMs, 3);
    nowMs = feedBaseline(d, nowMs, 60);
    nowMs = feedCatches(d, nowMs, 5);
    feedBaseline(d, nowMs, 60);

    Test.assertEqualMessage(d.sessionRuns(), 2, "two completed runs");
    Test.assertEqualMessage(d.sessionAverage(), 4.0, "(3 + 5) / 2");
    Test.assertEqualMessage(d.sessionMax, 5, "max is the longer run");
    return true;
}

// RUN-4: BACK during a live run throws that run away without ever recording
// it, so the session looks as though the run never happened.
(:test)
function run4_discardDropsTheRunInProgress(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);
    feedCatches(d, nowMs, 4);
    Test.assertMessage(d.isRunActive(), "a run is in progress");

    Test.assertMessage(d.discardLastRun(), "discarding an active run reports success");

    Test.assertEqualMessage(d.currentCount, 0, "the in-progress count is gone");
    Test.assertEqualMessage(d.sessionRuns(), 0, "it was never recorded");
    Test.assertEqualMessage(d.previousCount, 0, "and never became Prev");
    return true;
}

// RUN-4: with no run in progress, BACK removes the last completed run and the
// session statistics are recomputed from what is left -- Max in particular
// must fall back to the next-best run rather than stay stale.
(:test)
function run4_discardRemovesLastCompletedRunAndRecomputes(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);
    nowMs = feedCatches(d, nowMs, 5);
    nowMs = feedBaseline(d, nowMs, 60);
    nowMs = feedCatches(d, nowMs, 3);
    nowMs = feedBaseline(d, nowMs, 60);
    nowMs = feedCatches(d, nowMs, 7);
    feedBaseline(d, nowMs, 60);

    Test.assertEqualMessage(d.sessionRuns(), 3, "three runs recorded");
    Test.assertEqualMessage(d.sessionMax, 7, "the last run is the best");
    Test.assertEqualMessage(d.sessionAverage(), 5.0, "(5 + 3 + 7) / 3");

    Test.assertMessage(d.discardLastRun(), "discarding a completed run reports success");

    Test.assertEqualMessage(d.sessionRuns(), 2, "one run removed");
    Test.assertEqualMessage(d.previousCount, 3, "Prev falls back to the run before it");
    Test.assertEqualMessage(d.sessionMax, 5, "Max is recomputed, not left at 7");
    Test.assertEqualMessage(d.sessionAverage(), 4.0, "(5 + 3) / 2");
    Test.assertEqualMessage(d.runCatches().size(), 2, "the run list shrank");
    return true;
}

// RUN-4: discarding takes the *last* run even when an earlier run has the same
// catch count. Monkey C arrays only remove by value, which would take the
// wrong one; this pins that the implementation does not regress to that.
(:test)
function run4_discardTakesTheLastRunNotAMatchingEarlierOne(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);
    nowMs = feedCatches(d, nowMs, 4);
    nowMs = feedBaseline(d, nowMs, 60);
    nowMs = feedCatches(d, nowMs, 6);
    nowMs = feedBaseline(d, nowMs, 60);
    nowMs = feedCatches(d, nowMs, 4);
    feedBaseline(d, nowMs, 60);

    d.discardLastRun();

    var remaining = d.runCatches();
    Test.assertEqualMessage(remaining.size(), 2, "two runs left");
    Test.assertEqualMessage(remaining[0], 4, "the first run survives");
    Test.assertEqualMessage(remaining[1], 6, "the middle run survives");
    Test.assertEqualMessage(d.sessionMax, 6, "Max reflects the survivors");
    return true;
}

// RUN-5: with nothing juggled yet there is nothing to throw away, and the
// caller uses this to decide not to prompt at all.
(:test)
function run5_discardReportsFailureWhenThereIsNothingToDiscard(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    Test.assertMessage(!d.discardLastRun(), "an untouched session has nothing to discard");

    var nowMs = warmedUp(d);
    feedBaseline(d, nowMs, 20);
    Test.assertMessage(!d.discardLastRun(), "a still watch still has nothing to discard");
    return true;
}

// RUN-6: stopping by hand commits whatever is pending and folds the run in,
// so a run ended manually is treated the same as one that timed out.
(:test)
function run6_finishCurrentRunRecordsTheRunInProgress(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    var nowMs = warmedUp(d);
    feedCatches(d, nowMs, 4);

    var finished = d.finishCurrentRun();

    Test.assertEqualMessage(finished, 4, "the finished run reports its catches");
    Test.assertEqualMessage(d.currentCount, 0, "the live count resets");
    Test.assertEqualMessage(d.sessionRuns(), 1, "it joins the session");
    Test.assertEqualMessage(d.previousCount, 4, "and becomes Prev");
    return true;
}

// RUN-6: finishing with nothing in progress is a no-op rather than an empty
// run, so repeatedly pressing stop cannot inflate the run count.
(:test)
function run6_finishWithNoRunInProgressRecordsNothing(logger as Logger) as Boolean {
    var d = new JugglingDetector(3);
    warmedUp(d);

    Test.assertEqualMessage(d.finishCurrentRun(), 0, "nothing to finish");
    Test.assertEqualMessage(d.sessionRuns(), 0, "no phantom run recorded");
    return true;
}
