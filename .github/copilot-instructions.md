# Project Guidelines

## Architecture

Juggling tracker with two watch apps and a phone app: a Garmin watch app and a Wear OS watch app detect catches made by the hand wearing the watch and transmit finished sessions to an Android companion app for storage and visualization. The phone app can also count from its own accelerometer. Counts are watch-hand catches, not both-hands totals. The two watch apps keep the same features and behaviour.

- `connectiq/` — Monkey C watch app. Its behaviour is specified in `connectiq/REQUIREMENTS.md`.
  - `JugglingTrackerApp.mc` — app entry point.
  - `ModeSelectView.mc` / `BallSelectView.mc` — startup flow for Juggle vs Record and ball-count selection.
  - `JugglingDetector.mc` — watch-hand catch detection algorithm.
  - `ShapeConsistency.mc` — the Regularity score (SHAPE-1..4), fed by the detector.
  - `MainView.mc` — Juggle mode: tracking UI, sensor listener, sync logic.
  - `RecordingView.mc` — Record mode: raw accelerometer capture and labeling for tuning data.
- `wearos/` — Wear OS watch app, a Kotlin/Compose port of the Garmin app. Behaviour lives in plain-Kotlin `logic/` (detector, shape consistency, Juggle and Record sessions, navigation) and follows `connectiq/REQUIREMENTS.md`; it talks to the phone over the Wear OS Data Layer with the same payloads as the Garmin app. Keep its `JugglingDetector.kt` and `ShapeConsistency.kt` in sync with the other ports. See `wearos/README.md`.
- `android/` — Kotlin/Compose Android app.
  - `MainActivity.kt` — Garmin Connect IQ SDK integration, the Wear OS Data Layer listener, permissions, the phone accelerometer listener, message routing.
  - `logic/JugglingViewModel.kt` — State management, session import, CSV export.
  - `data/SessionRepository.kt` — Persistence layer (in-memory cache + SharedPreferences with manual JSON).
  - `data/RecordingRepository.kt` — Raw recording CSV storage and zip export.
  - `data/WearMessageCodec.kt` — Wear OS message paths and JSON codec.
  - `data/SettingsManager.kt` — Settings (watch type, voice, analytics, export details).
  - `model/` — Data classes (`JugglingRun`, `SessionSummary`).
  - `ui/` — Compose screens and components.
- `connectiq/data/` — Labeled accelerometer CSVs used for detection tuning and regression tests. One run per file, named `YYYYMMDD_HHMMSS.csv` after its run id.
- `simulation/` — Python detector simulation, tuning/plotting scripts, and pytest regression tests.

### Communication flow

Garmin: watch accelerometer (25 Hz) → `JugglingDetector` (burst-clustered watch-hand catch detection) → `MainView` (run/session state) → `Communications.transmit()` → `MainActivity` (Garmin SDK) → `JugglingViewModel` → `SessionRepository`. The phone sends an `ack` back; the watch only exits after receiving it.

Wear OS: accelerometer thinned to 25 Hz → `JugglingDetector.kt` → `TrackerSession` → `DataLayerPhoneLink` (JSON over the Data Layer) → `MainActivity` → the same import path. The `ack` goes back over the Data Layer to the sending watch.

Recording mode uses `RecordingView` (Wear OS: `RecordingSession`) to capture raw accelerometer samples. Start begins and ends each run; Back returns to ball selection while idle, offers to quit while recording, offers to discard while labeling, and is ignored while syncing. The user corrects the detected count to the true watch-hand count, then the run transfers as `rec_start` / `rec_chunk` × N / `rec_end` and the Android app writes it through `RecordingRepository` in the same CSV format as `connectiq/data/`.

`ENABLE_RECORDING_MODE` in `JugglingTrackerApp.mc` (and `AppNavigator.ENABLE_RECORDING_MODE` on Wear OS) is `true` in released builds on purpose: Record mode is how the labeled corpus grows, and the Connect IQ store listing advertises it. `test_watch_startup_offers_recording_mode` and the Wear OS `AppNavigatorTest` pin the values, so turning either off has to be deliberate (APP-1).

## Build and Test

### Android

```sh
cd android
./gradlew assembleDebug       # build
./gradlew test                # unit tests
./gradlew installDebug        # install on device
```

Requires JDK 17+. Android Studio's bundled JBR works.

### Wear OS watch app

```sh
cd wearos
./gradlew assembleDebug             # build
./gradlew testDebugUnitTest         # logic tests + Compose UI tests (Robolectric)
./gradlew connectedDebugAndroidTest # on a Wear OS emulator or watch
```

### ConnectIQ watch app

```sh
monkeyc -f connectiq/monkey.jungle -d fr245 -o connectiq/build/JugglingTracker.prg -y connectiq/developer_key.der -r
cd connectiq && ./run_tests.sh   # unit tests in the simulator (macOS)
```

Requires the Garmin Connect IQ SDK. The `connectiq/developer_key.der` is gitignored and must never be committed. The Garmin tests are not in CI.

### Detection simulation

```sh
cd simulation
python -m pytest test_detection.py -v
python eval_new_watch.py
```

`simulation/eval_new_watch.py` mirrors `connectiq/source/JugglingDetector.mc`. Keep detector parameters synchronized across the watch code, the Wear OS and phone Kotlin ports, the Python simulation, regression tests, and ConnectIQ instruction file.

`test_detection.py` pins a per-run expected count for every recording in `connectiq/data/`, plus corpus totals (currently at most 203 absolute error and 83 overcount over 108 runs). Adding recordings means adding their entries; changing the detector means re-deriving the expectations and justifying the new totals.

## Conventions

- Android: Kotlin, Jetpack Compose (Material 3), `ViewModelProvider.Factory` pattern, `mutableStateOf`/`mutableStateListOf` for reactive state.
- Watch: Monkey C, `WatchUi.View`/`BehaviorDelegate` pattern, `Sensor.registerSensorDataListener` for accelerometer.
- Sensor batches can contain `null` samples when a stream comes up short for the period. Guard every batch before doing arithmetic on it — an unguarded null throws inside the detector and kills the app with no on-screen trace.
- Detection: threshold crossings are candidates, not direct counts. Nearby candidates are delayed and merged into one catch-motion burst; odd-numbered committed bursts increment the watch-hand catch count by 1 and alternating other-hand bursts are skipped.
- Error handling: Always log exceptions. Android uses `Log.e(TAG, message, exception)` or `Log.w(TAG, message, exception)`. Watch uses `System.println()`. Never swallow exceptions silently.
- Persistence: `SharedPreferences` with manual JSON serialization via `org.json.JSONArray`/`JSONObject` and `buildSessionJson()`. No ORM or serialization library.
- The watch is the sole controller of a watch session; the phone only listens and records. Phone IMU sessions are the exception: the phone app runs those itself.
- Session deduplication is by timestamp.
