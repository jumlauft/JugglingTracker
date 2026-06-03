---
description: "Use when writing or modifying Monkey C files for the Garmin ConnectIQ watch app. Covers sensor patterns, UI conventions, and communication protocol."
applyTo: "connectiq/**/*.mc"
---
# ConnectIQ Watch App Conventions

## Language
- Monkey C targeting Connect IQ SDK 4.3+, min API 3.3.0.
- Target devices: FR245, FR245M.

## App Structure
- All classes live in a single file: `JugglingTrackerApp.mc`.
- `JugglingDetector` — Pure algorithm class. Stateless between sessions. Processes raw accelerometer samples and detects throws via peak detection with adaptive thresholds.
- `BallSelectView` / `BallSelectDelegate` — Startup screen for ball count selection (3–9).
- `MainView` / `MainDelegate` — Main tracking screen. Owns the sensor listener, detector, sync logic, and all UI drawing.
- `CommListener` — Thin wrapper forwarding `onComplete`/`onError` to `MainView`.
- `SessionEndDelegate` / `QuitConfirmationDelegate` — Menu delegates for session end flow.

## Accelerometer & Detection
- Sensor registered at 25 Hz, 1-second batches via `Sensor.registerSensorDataListener`.
- Samples arrive in milli-g. Converted to m/s² via `MILLI_G_TO_MS2 = 9.80665 / 1000`.
- Gravity estimated with low-pass filter (α = 0.95 idle, 0.99 during active juggling to prevent drift).
- Linear acceleration magnitude (`sqrt(lx² + ly² + lz²)`) used for detection — captures throw energy in all directions.
- 3-point moving average smooths noise before peak detection.
- True peak detection: local maxima in the smoothed signal above threshold, with hysteresis (signal must drop below `threshold × hysteresisFactor()` before re-arming).
- Each detected peak increments `currentCount` by 2 (one wrist sensor sees one arm, doubled for the other hand). The count sent to the phone in `runs` is this already-doubled total.
- 2nd-order Butterworth IIR highpass filter (0.7 Hz cutoff) applied to the magnitude signal after gravity removal. Removes slow drift from gravity estimation, giving clean peaks that are consistent across ball counts. Filter is causal (real-time) — 5 multiplies + 4 additions per sample.
- Universal detection thresholds on the filtered signal: `HP_THRESHOLD = 1.0`, `HP_HYSTERESIS = 0.3`, `REFRACTORY_MS = 200`. No per-ball-count scaling needed because the highpass filter normalises the signal.
- True peak detection: local maxima in the filtered signal above threshold, with hysteresis (signal must drop below `threshold × 0.3` before re-arming).
- Auto-finish after 2s idle.
- 25-sample warmup before detection begins.
- Debug logging behind `DEBUG_LOG` constant (default false); prints per-sample magnitude, threshold, and detection state.

## Communication Protocol
- Session payload sent via `Communications.transmit()`: `{ "type": "session", "balls": N, "timestamp": epochSeconds, "runs": [throwCounts...] }`.
- Wait for phone ACK (`{ "type": "ack", "timestamp": Long? }`) via `Communications.registerForPhoneAppMessages`. The watch only checks `type == "ack"`; the optional timestamp is for future matching.
- 10-second sync timeout. On failure: prompt retry/quit/continue menu.
- Never exit the app silently on sync failure — always prompt the user.

## UI Drawing
- All rendering in `onUpdate(dc)` using `Graphics` primitives — no Compose, no layouts.
- Use `Graphics.FONT_NUMBER_THAI_HOT` for the main throw count, `FONT_TINY`/`FONT_XTINY` for labels.
- Call `WatchUi.requestUpdate()` to trigger redraws.

## Error Handling
- Log errors with `System.println("context: " + ex.getErrorMessage())`.
- Never swallow exceptions in empty catch blocks.

## State Management
- Session end flow uses `_sending`, `_awaitingDecision`, `_pendingPayload` flags.
- Only one sync attempt or confirmation dialog at a time.
