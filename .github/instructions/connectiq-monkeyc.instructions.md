---
description: "Use when writing or modifying Monkey C files for the Garmin ConnectIQ watch app. Covers sensor patterns, UI conventions, and communication protocol."
applyTo: "connectiq/**/*.mc"
---
# ConnectIQ Watch App Conventions

## Language
- Monkey C targeting Connect IQ SDK 4.3+, min API 3.3.0.
- Target devices: All devices with API Level 3.3 or higher (e.g., FR245, Fenix 6, Venu, etc.).

## App Structure
- Watch classes are split by feature under `connectiq/source/`.
- `JugglingTrackerApp` — App entry point. Starts the mode-selection flow.
- `ModeSelectView` / `ModeSelectDelegate` — Juggle or Record chooser at startup. `ENABLE_RECORDING_MODE` in `JugglingTrackerApp` is `true` in released builds on purpose (APP-1); setting it to `false` skips this screen and starts on ball selection.
- `JugglingDetector` — Stateful per-session algorithm class. Processes raw accelerometer samples and detects catches made by the hand wearing the watch with highpass-filtered candidate burst clustering.
- `BallSelectView` / `BallSelectDelegate` — Startup screen for ball count selection (3–9). BACK returns to the mode screen when that led here (APP-5).
- `ShapeConsistency` — The Regularity score (SHAPE-1..4): per-axis highpassed history, scored once a second against the previous hand cycle. Owned by `JugglingDetector`.
- `MainView` / `MainDelegate` — Main tracking screen. Owns the sensor listener, detector, sync logic, and all UI drawing.
- `RecordingView` / `RecordingDelegate` — Record mode: raw accelerometer recording and labeling workflow for detector tuning data. Start begins and ends each run and confirms the label. Back returns to ball selection while idle (REC-11), offers to discard while labeling and to quit while recording, both behind a confirmation, and is swallowed while syncing (REC-9). Runs are capped at 120 seconds (`MAX_RUN_SAMPLES`) and end early if `freeMemory` drops below `MIN_FREE_MEMORY`.
- `CommListener` / `RecordingCommListener` — Thin wrappers forwarding `onComplete`/`onError`, tagged with the sync generation, to `MainView` / `RecordingView`.
- `SessionEndDelegate` / `DiscardRunDelegate` — `Menu2` delegates for the session-end menu and the discard prompt over `MainView`. `RecordingSyncDelegate`, `RecordingQuitDelegate` and `RecordingDiscardDelegate` do the same over `RecordingView`. Every one overrides `onBack` to pop itself and clear the pending-decision flag (JUG-6, REC-10).

## Accelerometer & Detection
- Sensor registered at 25 Hz, 1-second batches via `Sensor.registerSensorDataListener`. 25 Hz is the FR245 ceiling, per `Sensor.getMaxSampleRate()`.
- A batch can contain `null` samples: when a stream comes up short for the period the device pads the arrays. Check each axis before using it — arithmetic on a null throws inside the detector and kills the app roughly a second into a run, since idle code reads the values without doing arithmetic on them.
- Do not request the gyroscope. The FR245 presents a non-null `gyroscopeData` object but delivers nothing behind it (verified over seven recordings: all gyro columns identically zero), and enabling it is what made the accelerometer stream start null-padding.
- Samples arrive in milli-g. Converted to m/s² via `MILLI_G_TO_MS2 = 9.80665 / 1000`.
- Gravity estimated with low-pass filter (α = 0.95 idle, 0.99 during active juggling to prevent drift).
- Linear acceleration magnitude (`sqrt(lx² + ly² + lz²)`) used for detection — captures catch energy in all directions.
- Threshold crossings are candidates, not immediate counts. Nearby candidates are delayed and merged into one catch-motion burst. Odd-numbered committed bursts increment `currentCount` by 1; alternating even-numbered bursts are treated as the other hand and do not change the displayed watch-hand count. Do not double the count.
- 2nd-order Butterworth IIR highpass filter (0.7 Hz cutoff) applied to the magnitude signal after gravity removal. Removes slow drift from gravity estimation, giving clean peaks that are consistent across ball counts. Filter is causal (real-time) — 5 multiplies + 4 additions per sample.
- Ball-count-adaptive burst clustering (tuned on the watch-hand labels in `connectiq/data`):
  - 3 balls: HP threshold 2.0, candidate refractory 80ms, raw gate 7.0 m/s², merge window 160ms
  - 4 balls: HP threshold 3.0, candidate refractory 80ms, raw gate 11.0 m/s², merge window 160ms
  - 5 balls: HP threshold 3.0, candidate refractory 40ms, raw gate 13.0 m/s², merge window 160ms
  - 6 balls: HP threshold 5.0, candidate refractory 40ms, raw gate 17.0 m/s², merge window 120ms
  - 7+ balls: HP threshold 3.0, candidate refractory 160ms, raw gate 7.0 m/s², merge window 80ms
- Hysteresis factor is 0.3. Raw magnitude gate: a candidate only counts if the pre-highpass magnitude also exceeds the per-ball-count floor.
- True peak detection: threshold-crossing on the filtered signal with hysteresis (signal must drop below `threshold × 0.3` before re-arming). The strongest filtered peak in a burst represents the catch motion.
- Auto-finish after 2s without a committed candidate burst. Do not refresh the finish timer from low-level filtered motion; otherwise the user has to hold the watch hand too still after a run.
- 25-sample warmup before detection begins.
- `simulation/eval_new_watch.py` must mirror `JugglingDetector.mc`; update both plus `simulation/test_detection.py`, `wearos/.../logic/JugglingDetector.kt` and `android/.../PhoneJugglingDetector.kt` whenever parameters or detection semantics change.
- Delayed burst clustering must only flush a pending candidate when the filtered signal is no longer above threshold. Flushing while `_above` is true can split one physical catch motion into multiple counts.

## Communication Protocol
- Session payload sent via `Communications.transmit()`: `{ "type": "session", "countMode": "watch_hand", "balls": N, "timestamp": epochSeconds, "durationSeconds": totalSessionSeconds, "runDurationsMillis": [firstToLastWatchHandCatchMs...], "runs": [watchHandCatchCounts...], "shapeConsistency": wholePercent }`. `shapeConsistency` is left out until a run has been scored (SYNC-1).
- Recording transfer from `RecordingView` is chunked, because a whole run does not fit one message: `{ "type": "rec_start", "id": epochSeconds, "countMode": "watch_hand", "balls": N, "catches": watchHandLabel, "detected": detectorCount, "sampleRate": 25, "samples": sampleCount, "chunks": chunkCount, "timestamp": epochSeconds }`, then one `{ "type": "rec_chunk", "id": ..., "i": index, "x": [...], "y": [...], "z": [...] }` per 50 samples, then `{ "type": "rec_end", "id": ... }`. Only `rec_end` is acknowledged; the watch advances each chunk on delivery.
- Never call `Communications.transmit()` from inside a `ConnectionListener` callback. It wedges the single outstanding-transmit slot and the call never reports back — hop through a short timer so the next send runs on a clean stack.
- Each transmit attempt is tagged with a generation counter. An abandoned attempt still reports back later, and without the tag its `onError()` lands on whichever run is syncing by then and fails it instantly.
- 50 samples per chunk is measured, not arbitrary: per-message cost is flat to about 150 integers then climbs steeply, so larger chunks transfer the run more slowly overall.
- Wait for phone ACK (`{ "type": "ack", "timestamp": Long? }`) via `Communications.registerForPhoneAppMessages`. `MainView` only checks `type == "ack"`; `RecordingView` also requires `timestamp` to equal the id of the run it is syncing, so a late ack for an earlier run is ignored (REC-8).
- 10-second sync timeout. On failure `MainView` offers Retry sync / Quit without sync / Continue, and `RecordingView` offers Retry sync / Skip (lose data) / Quit.
- Never exit the app silently on sync failure — always prompt the user.

## UI Drawing
- All rendering in `onUpdate(dc)` using `Graphics` primitives — no Compose, no layouts.
- Use `Graphics.FONT_NUMBER_THAI_HOT` for the main watch-hand catch count, `FONT_TINY`/`FONT_XTINY` for labels, including the run-state label, the "Catches per hand" label, the stats, the session timer and Regularity.
- Call `WatchUi.requestUpdate()` to trigger redraws.

## Error Handling
- Log errors with `System.println("context: " + ex.getErrorMessage())`.
- Never swallow exceptions in empty catch blocks.
- `System.println()` only reaches the simulator console. To diagnose a crash on real hardware, read `GARMIN/Apps/LOGS/CIQ_LOG.YML` from the watch's USB volume and resolve its program counters against the `pcToLineNum` entries in the `.prg.debug.xml` of the exact build the watch was running — verify that build by md5 against the `.prg` on the device.
- The simulator delivers no data to `registerSensorDataListener` without a GUI-loaded data source, so sensor-path faults cannot be reproduced there.

## State Management
- Session end flow uses `_sending`, `_awaitingDecision`, `_pendingPayload` flags.
- Only one sync attempt or confirmation dialog at a time.
