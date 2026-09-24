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
- `connectiq/data/` — Labeled accelerometer CSVs used for detection tuning and regression tests. One run per file, named `YYYYMMDD_HHMMSS.csv` after its run id.
- `simulation/` — Python detector simulation, tuning/plotting scripts, and pytest regression tests.

### Communication flow

Watch accelerometer (25 Hz) → `JugglingDetector` (burst-clustered watch-hand catch detection) → `MainView` (run/session state) → `Communications.transmit()` → `MainActivity` (Garmin SDK) → `JugglingViewModel` → `SessionRepository`. The phone sends an `ack` back; the watch only exits after receiving it.

Recording mode uses `RecordingView` to capture raw accelerometer samples. Start begins and ends each run, Back quits or discards; the user corrects the detected count to the true watch-hand count, then the run transfers as `rec_start` / `rec_chunk` × N / `rec_end` and the Android app writes it through `RecordingRepository` in the same CSV format as `connectiq/data/`.

`ENABLE_RECORDING_MODE` in `JugglingTrackerApp.mc` is currently `true` for data collection and must be `false` before release. The failing `test_customer_watch_startup_hides_recording_mode` is the standing reminder, and is the one expected failure in the Python suite.

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

`test_detection.py` pins a per-run expected count for every recording in `connectiq/data/`, plus corpus totals (currently 157 absolute error and 58 overcount over 77 runs). Adding recordings means adding their entries; changing the detector means re-deriving the expectations and justifying the new totals.

## Conventions

- Android: Kotlin, Jetpack Compose (Material 3), `ViewModelProvider.Factory` pattern, `mutableStateOf`/`mutableStateListOf` for reactive state.
- Watch: Monkey C, `WatchUi.View`/`BehaviorDelegate` pattern, `Sensor.registerSensorDataListener` for accelerometer.
- Sensor batches can contain `null` samples when a stream comes up short for the period. Guard every batch before doing arithmetic on it — an unguarded null throws inside the detector and kills the app with no on-screen trace.
- Detection: threshold crossings are candidates, not direct counts. Nearby candidates are delayed and merged into one catch-motion burst; odd-numbered committed bursts increment the watch-hand catch count by 1 and alternating other-hand bursts are skipped.
- Error handling: Always log exceptions. Android uses `Log.e(TAG, message, exception)` or `Log.w(TAG, message, exception)`. Watch uses `System.println()`. Never swallow exceptions silently.
- Persistence: `SharedPreferences` with manual JSON serialization via `org.json.JSONArray`/`JSONObject` and `buildSessionJson()`. No ORM or serialization library.
- The watch is the sole session controller. The phone only listens and records.
- Session deduplication is by timestamp.
