# Garmin watch app — behaviour specification

What the Connect IQ app in `source/` actually does, written down so it can be
changed deliberately rather than by accident.

Every requirement has an ID and names the test that secures it. Two kinds of
test appear:

| Kind | Where | What it does |
|---|---|---|
| **Unit** | `test/*.mc` | Compiles and runs the real Monkey C. Run with `./run_tests.sh`. |
| **Corpus / pinning** | `../simulation/test_detection.py` | Replays 102 real recordings through the reference port, and pins source-level wiring that cannot be executed off-device. Run with `pytest`. |

UI wiring, sensor lifecycle and the phone transfer are covered by the pinning
tests rather than unit tests: instantiating those views needs a device context,
a live sensor and a radio. Where a requirement is **not** covered by an
automated test, it says so plainly rather than implying coverage that does not
exist.

---

## APP — startup and navigation

**APP-1.** The app opens on a mode screen offering **Juggle** and **Record**,
defaulting to Juggle. Record is a developer path for capturing raw sensor data;
it is reachable only by deliberately toggling. Setting
`JugglingTrackerApp.ENABLE_RECORDING_MODE` to `false` skips this screen and
starts customers directly on ball selection.

> **Release gap.** The flag is currently `true` for data collection and must be
> `false` before any customer release. The test below pins its *current* value,
> so flipping it forces a deliberate test update — but nothing fails while it is
> on, so nothing blocks shipping Record mode to customers today.

*Verified by:* `app1_modeSelectionDefaultsToJuggle`, `test_watch_startup_offers_recording_mode`

**APP-2.** On the mode screen, UP and DOWN both toggle between the two modes;
START confirms and moves to ball selection.
*Verified by:* `app2_toggleFlipsBetweenTheTwoModes`

**APP-3.** Ball selection covers **3 to 9**, opens on 3, and wraps in both
directions: incrementing from 9 gives 3, decrementing from 3 gives 9. UP
increments, DOWN decrements.
*Verified by:* `app3_ballSelectionStartsAtThree`, `app3_incrementWalksUpAndWrapsAtNine`, `app3_decrementWrapsAtThree`

**APP-4.** START on ball selection opens the tracker for the mode chosen
earlier — `MainView` for Juggle, `RecordingView` for Record — carrying the
selected ball count.
*Verified by:* `app4_ballSelectionRemembersTheChosenMode`

---

## DET — catch detection

The detector is a single pipeline over the accelerometer stream. It is ported
to Python (`../simulation/eval_new_watch.py`) and Kotlin
(`../android/.../PhoneJugglingDetector.kt`); **all three must stay in sync.**

**DET-1.** The accelerometer delivers samples in **milli-g including gravity**
at **25 Hz**. The detector converts to m/s² (× 9.80665 / 1000) on entry, so all
thresholds below are in m/s².
*Verified by:* `test_watch_params_match_detector_constants`

**DET-2.** Gravity is estimated with a low-pass filter, **seeded from the first
sample** so it matches the watch's real orientation rather than an assumed
"down". Its coefficient slows from `0.95` to `0.99` while a run is active, so
sustained arm motion is not absorbed into the gravity estimate and the signal
is not attenuated mid-run.
*Verified by:* `test_detection_count_per_run` (behavioural, over the corpus)

**DET-3.** Gravity is subtracted and the **magnitude** of what remains is taken
— catches are counted in any direction, so cascade, shower and columns all
work. That magnitude passes through a 2nd-order Butterworth highpass
(0.7 Hz at fs = 25 Hz) to remove gravity-estimate drift.
*Verified by:* `test_watch_params_match_detector_constants`

**DET-4.** The first **25 samples** are a warmup during which nothing is
counted, so a watch sitting still at startup never registers a phantom run
while the gravity estimate settles.
*Verified by:* `det4_noCountDuringWarmup`

**DET-5.** A **candidate** is emitted on a complete above→below cycle: the
filtered signal must rise above the threshold and then fall below
`threshold × 0.3`. It is kept only if the refractory period has elapsed **and**
the peak raw magnitude exceeds the gate — the gate is what rejects noise the
highpass would otherwise amplify.
*Verified by:* `det5_rawMagnitudeGateRejectsWeakMotion`

**DET-6.** Detection parameters are per ball count, selected once at startup:

| Balls | HP threshold | Refractory | Raw gate | Merge window |
|---|---|---|---|---|
| ≤3 | 2.0 | 80 ms | 7.0 | 160 ms |
| 4 | 3.0 | 80 ms | 11.0 | 160 ms |
| 5 | 3.0 | 40 ms | 13.0 | 160 ms |
| 6 | 5.0 | 40 ms | 17.0 | 120 ms |
| 7+ | 3.0 | 160 ms | 7.0 | 80 ms |

*Verified by:* `det6_gateIsPerBallCount`, `test_watch_params_match_detector_constants`

**DET-7.** Candidates closer together than the merge window are lobes of one
catch motion: they cluster into a single **burst**, keeping the strongest peak.
A burst commits only once no further candidate has arrived within the window.
*Verified by:* `test_detection_count_per_run`

**DET-8.** Hands alternate, so **only every other committed burst is a catch by
the watch hand** — odd-numbered bursts count, even ones are the other hand.
Three impulses are therefore two catches. Every count the app shows and syncs
is a watch-hand count.
*Verified by:* `det8_everyOtherBurstIsAWatchHandCatch`, `test_watch_counts_alternating_bursts_as_watch_hand_catches`

**DET-9.** A run ends by itself **2000 ms** after the last *committed burst*.
The timer deliberately keys on committed bursts, not on raw motion, so
low-level wrist movement after a drop cannot hold a finished run open.
*Verified by:* `det9_runAutoFinishesAfterIdleDelay`, `test_auto_finish_timer_uses_committed_bursts_not_low_level_motion`

**DET-10.** A completed run's duration is the span from its **first to its last
counted watch-hand catch**, not the wall time the screen was open.
*Verified by:* `det10_runDurationSpansFirstToLastCatch`, `test_watch_transfers_run_durations`

**DET-11.** Detection accuracy over the recorded corpus must not regress:
total absolute error ≤ 189 and positive overcount error ≤ 69 across 102 runs
(2459 real watch-hand catches), with every recording covered and each run's
count pinned individually.
*Verified by:* `test_detection_count_per_run`, `test_total_absolute_error`, `test_total_overcount_error`, `test_every_recording_is_covered`

---

## RUN — runs and session statistics

**RUN-1.** A run of **fewer than 3 catches is a false start** and is discarded:
`previousCount`, run count, total, max and the run list are all left exactly as
they were. 3 catches is the first length that counts. The rule lives in
`recordRun()`, so a manual stop and an idle timeout are treated identically.
The live count still resets, ready for the next attempt.
*Verified by:* `run1_falseStartsAreNotRecorded`, `run1_threeCatchesIsARealRun`, `test_short_runs_are_ignored_as_false_starts`

**RUN-2.** Recording a run updates, in order: previous count, run count,
session total, session max, the ordered run list and the run-duration list.
*Verified by:* `run6_finishCurrentRunRecordsTheRunInProgress`

**RUN-3.** The session average is the mean over **completed** runs, and is
`0.0` before any run completes.
*Verified by:* `run3_sessionAverageIsOverCompletedRuns`

**RUN-4.** Discarding removes one run and nothing else:
- with a run **in progress**, that run is dropped without ever being recorded;
- otherwise the **last completed** run is removed retroactively, and run count,
  total, max and previous are **recomputed from the runs that remain** — max in
  particular must fall back rather than stay stale;
- it takes the *last* run even when an earlier run has the same catch count.

*Verified by:* `run4_discardDropsTheRunInProgress`, `run4_discardRemovesLastCompletedRunAndRecomputes`, `run4_discardTakesTheLastRunNotAMatchingEarlierOne`

**RUN-5.** Discarding reports failure when there is nothing to discard; the
caller uses that to suppress the prompt entirely (see JUG-4).
*Verified by:* `run5_discardReportsFailureWhenThereIsNothingToDiscard`

**RUN-6.** Finishing by hand commits any pending burst and folds the run in,
identically to an idle timeout. Finishing with no run in progress records
nothing, so repeated stops cannot inflate the run count.
*Verified by:* `run6_finishCurrentRunRecordsTheRunInProgress`, `run6_finishWithNoRunInProgressRecordsNothing`

---

## JUG — Juggle mode screen

**JUG-1.** The screen shows: run state (**RUN ACTIVE** green / **WAITING**
yellow), the live watch-hand count, and then Prev, Runs, Avg, Max and elapsed
session time. A failed transfer adds a red banner.
*Verified by:* `test_main_view_displays_run_state`, `test_main_view_displays_and_transfers_session_duration`

**JUG-2.** **START/STOP** opens the session-end menu: *Sync and quit*, *Quit
without sync*, *Continue*. This is the only way to end a session.
*Verified by:* not automatically tested — needs a device context.

**JUG-3.** **BACK** never ends the session and never exits. It offers to throw
away one run, after a confirmation:
- run in progress → *Discard this run?* with its catch count;
- otherwise → *Discard last run?* with that run's catch count.

Answering *Keep*, or backing out of the prompt, changes nothing.
*Verified by:* `test_back_button_confirms_discarding_a_run_instead_of_exiting`

**JUG-4.** With nothing recorded yet, BACK is **swallowed**: no prompt, and
critically no exit — letting it reach the system would close the app and lose
the session, which is the failure this whole path exists to prevent.
*Verified by:* `test_back_button_confirms_discarding_a_run_instead_of_exiting`

**JUG-5.** The prompt is a `Menu2` with the app's own **English** labels, not a
`WatchUi.Confirmation`. A Confirmation's yes/no come from the system in the
*watch's* language, which put German "Ja"/"Nein" into an otherwise English app.
*Verified by:* `test_back_button_confirms_discarding_a_run_instead_of_exiting`

**JUG-6.** Backing out of **any** menu over this screen clears the
pending-decision flag. `Menu2`'s default back pops without telling the view,
which left the flag set forever and silently turned every later START/STOP
press into a no-op — with no way left to end or sync the session.
*Verified by:* `test_menus_over_main_view_clear_the_pending_decision_flag_on_back`

**JUG-7.** The watch vibrates once every 10 watch-hand catches, and the
vibration counter resets when a run finishes.
*Verified by:* not automatically tested — needs device hardware.

---

## SENS — sensor lifecycle

**SENS-1.** Both tracking screens re-acquire the accelerometer in `onShow()`
and release it in `onHide()`. Pushing **any** view over them (a menu, a
confirmation) hides them and, on real hardware, drops the sensor listener with
it. Without re-acquisition the screen froze permanently at whatever was last
drawn while detection silently stopped — reachable by pressing START/STOP and
then Continue, or START/STOP mid-run.
*Verified by:* `test_main_view_reacquires_sensor_after_any_menu`

**SENS-2.** Sensor registration is **idempotent**, so `onShow()` firing right
after `initialize()` cannot double-register.
*Verified by:* `test_main_view_reacquires_sensor_after_any_menu`

**SENS-3.** A sample batch that arrives short is padded with nulls. Every null
sample is skipped, because arithmetic on one throws inside the detector and
kills the app.
*Verified by:* not automatically tested — needs a live sensor.

---

## SYNC — transfer to the phone

**SYNC-1.** A session is transmitted as one message: `type: "session"`,
`countMode: "watch_hand"`, `balls`, `timestamp` (epoch **seconds**),
`durationSeconds`, `runDurationsMillis` and `runs`.
*Verified by:* `test_main_view_displays_and_transfers_session_duration`, `test_watch_transfers_run_durations`

**SYNC-2.** The app closes **only** on the phone's `ack`, so data is never
assumed delivered. Failure or a 10 s timeout opens a retry menu instead.
*Verified by:* not automatically tested — needs a radio.

**SYNC-3.** Syncing a session with no completed runs is skipped and the app
just exits; there is nothing worth sending.
*Verified by:* not automatically tested — needs a radio.

---

## REC — Record mode (developer)

**REC-1.** Record mode cycles `IDLE → RECORDING → LABELING → SYNCING → IDLE`.
START drives every transition: it starts a run, stops it, and confirms the
label. BACK offers to discard while labelling, and to quit elsewhere; during
syncing it is ignored.
*Verified by:* not automatically tested — needs a device context.

**REC-2.** A run stops automatically at **3000 samples (120 s)**, or earlier if
free memory drops below 16 KB. The app gets 128 KB and each sample costs three
boxed array entries, so a long run would otherwise exhaust memory and crash.
*Verified by:* not automatically tested — needs device memory statistics.

**REC-3.** After a run the detector's own count is offered as the starting
label; UP and DOWN adjust it and it never goes below 0. The point is comparing
the algorithm against ground truth.
*Verified by:* not automatically tested — needs a device context.

**REC-4.** Each confirmed run is written to the log as
`RUN_DATA,...` / `S,x,y,z` per sample / `RUN_DATA_END`, which is the format the
offline tooling in `../simulation/` parses.
*Verified by:* not automatically tested — output is a console side effect.

**REC-5.** Runs transfer to the phone in chunks: `rec_start`, then one
`rec_chunk` per **50 samples**, then `rec_end`. Only `rec_end` is acknowledged,
after the phone has written the run. 50 is deliberate — per-message cost is
flat to ~150 integers then climbs steeply, so 100-sample chunks measured ~25%
slower overall.
*Verified by:* not automatically tested — needs a radio.

**REC-6.** Each transmit attempt carries a generation number. An abandoned
attempt still reports back later, and without this its failure would land on
whichever run is syncing by then and fail it instantly.
*Verified by:* not automatically tested — needs a radio.

**REC-7.** The next chunk is sent from a timer, never from inside the
connection callback: transmitting from that callback wedges the single
outstanding-transmit slot and the call never reports back.
*Verified by:* not automatically tested — needs a radio.

---

## Keeping this honest

The three detector implementations — `source/JugglingDetector.mc`,
`../android/.../PhoneJugglingDetector.kt` and `../simulation/eval_new_watch.py`
— must agree. `test_watch_params_match_detector_constants` pins the watch
constants against the Python reference, and
`PhoneDetectorCorpusTest` replays the same recordings through the Kotlin port.

When behaviour changes on purpose, change this document in the same commit as
the code, and move the requirement's test with it.
