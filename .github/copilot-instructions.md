# Project Guidelines

## Architecture

Two-platform juggling tracker: a Garmin watch app detects catches made by the hand wearing the watch and transmits finished sessions to an Android companion app for storage and visualization. Counts are watch-hand catches, not both-hands totals.

- `connectiq/` — Monkey C watch app.
  - `JugglingTrackerApp.mc` — app entry point.
  - `ModeSelectView.mc` / `BallSelectView.mc` — startup flow for normal tracking vs recording and ball-count selection.
  - `JugglingDetector.mc` — watch-hand catch detection algorithm.
  - `MainView.mc` — normal tracking UI, sensor listener, sync logic.
  - `RecordingView.mc` — raw accelerometer capture and labeling mode for tuning data.
- `android/` — Kotlin/Compose Android app.
  - `MainActivity.kt` — Garmin Connect IQ SDK integration, permissions, message routing.
  - `logic/JugglingViewModel.kt` — State management, session import, CSV export.
  - `data/SessionRepository.kt` — Persistence layer (in-memory cache + SharedPreferences with manual JSON).
  - `data/RecordingRepository.kt` — Raw recording CSV storage and export.
  - `model/` — Data classes (`JugglingRun`, `SessionSummary`).
  - `ui/` — Compose screens and components.
- `data/` — Labeled accelerometer CSVs used for detection tuning and regression tests.
- `simulation/` — Python detector simulation, tuning/plotting scripts, and pytest regression tests.

### Communication flow

Watch accelerometer (25 Hz) → `JugglingDetector` (burst-clustered watch-hand catch detection) → `MainView` (run/session state) → `Communications.transmit()` → `MainActivity` (Garmin SDK) → `JugglingViewModel` → `SessionRepository`. The phone sends an `ack` back; the watch only exits after receiving it.

Recording mode uses `RecordingView` to capture raw accelerometer samples. The user starts and stops each run with the watch Back button, provides the watch-hand catch label, then the watch transmits a `recording` payload that the Android app stores through `RecordingRepository`.

## Build and Test

### Android

```sh
cd android
./gradlew assembleDebug       # build
./gradlew test                # unit tests
./gradlew installDebug        # install on device
```

Requires JDK 17+. Android Studio's bundled JBR works.

### ConnectIQ watch app

```sh
monkeyc -f connectiq/monkey.jungle -d fr245 -o connectiq/build/JugglingTracker.prg -y connectiq/developer_key.der -r
```

Requires the Garmin Connect IQ SDK. The `connectiq/developer_key.der` is gitignored and must never be committed.

### Detection simulation

```sh
cd simulation
python -m pytest test_detection.py -v
python eval_new_watch.py
```

`simulation/eval_new_watch.py` mirrors `connectiq/source/JugglingDetector.mc`. Keep detector parameters synchronized across the watch code, Python simulation, regression tests, and ConnectIQ instruction file.

## Conventions

- Android: Kotlin, Jetpack Compose (Material 3), `ViewModelProvider.Factory` pattern, `mutableStateOf`/`mutableStateListOf` for reactive state.
- Watch: Monkey C, `WatchUi.View`/`BehaviorDelegate` pattern, `Sensor.registerSensorDataListener` for accelerometer.
- Detection: threshold crossings are candidates, not direct counts. Nearby candidates are delayed and merged into one catch-motion burst; odd-numbered committed bursts increment the watch-hand catch count by 1 and alternating other-hand bursts are skipped.
- Error handling: Always log exceptions. Android uses `Log.e(TAG, message, exception)` or `Log.w(TAG, message, exception)`. Watch uses `System.println()`. Never swallow exceptions silently.
- Persistence: `SharedPreferences` with manual JSON serialization via `org.json.JSONArray`/`JSONObject` and `buildSessionJson()`. No ORM or serialization library.
- The watch is the sole session controller. The phone only listens and records.
- Session deduplication is by timestamp.
