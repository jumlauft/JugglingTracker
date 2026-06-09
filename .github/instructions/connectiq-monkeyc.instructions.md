---
description: "Use when writing or modifying Monkey C files for the Garmin ConnectIQ watch app. Covers sensor patterns, UI conventions, and communication protocol."
applyTo: "connectiq/**/*.mc"
---
# ConnectIQ Watch App Conventions

## Language
- Monkey C targeting Connect IQ SDK 4.3+, min API 3.3.0.
- Target devices: FR245, FR245M.

## App Structure
- Watch classes are split by feature under `connectiq/source/`.
- `JugglingTrackerApp` — App entry point. Starts the mode-selection flow.
- `ModeSelectView` / `ModeSelectDelegate` — Developer-only chooser for normal tracking vs recording mode. Customer builds bypass it while `ENABLE_RECORDING_MODE` is `false` in `JugglingTrackerApp`.
- `JugglingDetector` — Stateful per-session algorithm class. Processes raw accelerometer samples and detects catches made by the hand wearing the watch with highpass-filtered candidate burst clustering.
- `BallSelectView` / `BallSelectDelegate` — Startup screen for ball count selection (3–9).
- `MainView` / `MainDelegate` — Main tracking screen. Owns the sensor listener, detector, sync logic, and all UI drawing.
- `RecordingView` / `RecordingDelegate` — Developer-only raw accelerometer recording and labeling workflow for detector tuning data. The Back button starts and stops each recording run.
- `CommListener` — Thin wrapper forwarding `onComplete`/`onError` to `MainView`.
- `SessionEndDelegate` / `QuitConfirmationDelegate` — Menu delegates for session end flow.

## Accelerometer & Detection
- Sensor registered at 25 Hz, 1-second batches via `Sensor.registerSensorDataListener`.
- Samples arrive in milli-g. Converted to m/s² via `MILLI_G_TO_MS2 = 9.80665 / 1000`.
- Gravity estimated with low-pass filter (α = 0.95 idle, 0.99 during active juggling to prevent drift).
- Linear acceleration magnitude (`sqrt(lx² + ly² + lz²)`) used for detection — captures catch energy in all directions.
- Threshold crossings are candidates, not immediate counts. Nearby candidates are delayed and merged into one catch-motion burst. Odd-numbered committed bursts increment `currentCount` by 1; alternating even-numbered bursts are treated as the other hand and do not change the displayed watch-hand count. Do not double the count.
- 2nd-order Butterworth IIR highpass filter (0.7 Hz cutoff) applied to the magnitude signal after gravity removal. Removes slow drift from gravity estimation, giving clean peaks that are consistent across ball counts. Filter is causal (real-time) — 5 multiplies + 4 additions per sample.
- Ball-count-adaptive burst clustering (data-driven from watch-hand labels over 36 runs):
  - 3 balls: HP threshold 2.0, candidate refractory 80ms, raw gate 7.0 m/s², merge window 160ms
  - 4 balls: HP threshold 4.0, candidate refractory 40ms, no raw gate, merge window 80ms
  - 5+ balls: HP threshold 0.8, candidate refractory 320ms, raw gate 13.0 m/s², merge window 160ms
- Hysteresis factor is 0.3. Raw magnitude gate: a candidate only counts if the pre-highpass magnitude also exceeds the per-ball-count floor.
- True peak detection: threshold-crossing on the filtered signal with hysteresis (signal must drop below `threshold × 0.3` before re-arming). The strongest filtered peak in a burst represents the catch motion.
- Auto-finish after 2s without a committed candidate burst. Do not refresh the finish timer from low-level filtered motion; otherwise the user has to hold the watch hand too still after a run.
- 25-sample warmup before detection begins.
- `simulation/eval_new_watch.py` must mirror `JugglingDetector.mc`; update both plus `simulation/test_detection.py` whenever parameters or detection semantics change.
- Delayed burst clustering must only flush a pending candidate when the filtered signal is no longer above threshold. Flushing while `_above` is true can split one physical catch motion into multiple counts.

## Communication Protocol
- Session payload sent via `Communications.transmit()`: `{ "type": "session", "countMode": "watch_hand", "balls": N, "timestamp": epochSeconds, "durationSeconds": totalSessionSeconds, "runDurationsMillis": [firstToLastWatchHandCatchMs...], "runs": [watchHandCatchCounts...] }`.
- Recording payload sent via `RecordingView`: `{ "type": "recording", "countMode": "watch_hand", "balls": N, "catches": watchHandLabel, "detected": detectorCount, "sampleRate": 25, "accelX": [...], "accelY": [...], "accelZ": [...], "timestamp": epochSeconds }`.
- Wait for phone ACK (`{ "type": "ack", "timestamp": Long? }`) via `Communications.registerForPhoneAppMessages`. The watch only checks `type == "ack"`; the optional timestamp is for future matching.
- 10-second sync timeout. On failure: prompt retry/quit/continue menu.
- Never exit the app silently on sync failure — always prompt the user.

## UI Drawing
- All rendering in `onUpdate(dc)` using `Graphics` primitives — no Compose, no layouts.
- Use `Graphics.FONT_NUMBER_THAI_HOT` for the main watch-hand catch count, `FONT_TINY`/`FONT_XTINY` for labels, including the run-state label and total session timer.
- Call `WatchUi.requestUpdate()` to trigger redraws.

## Error Handling
- Log errors with `System.println("context: " + ex.getErrorMessage())`.
- Never swallow exceptions in empty catch blocks.

## State Management
- Session end flow uses `_sending`, `_awaitingDecision`, `_pendingPayload` flags.
- Only one sync attempt or confirmation dialog at a time.
