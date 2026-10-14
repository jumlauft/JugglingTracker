# Project Guidelines

## Architecture

Juggling tracker with three watch apps and two phone apps: a Garmin watch app, a Wear OS watch app and an Apple Watch app detect catches made by the hand wearing the watch and transmit finished sessions to a companion phone app (Android, or iPhone in `apple/`) for storage and visualization. The phone apps can also count from their own accelerometer. Counts are watch-hand catches, not both-hands totals.

**Keep the implementations in sync.** The three watch apps keep the same user-facing functionality and workflow, and the Android and iPhone apps the same user-facing functionality and design. Port every change to the other implementations in its group and tell the user which app got what. The full rules, and the allowed hardware-specific differences, are in `CLAUDE.md` at the repo root.

- `connectiq/` — Monkey C watch app. Its behaviour is specified in `connectiq/REQUIREMENTS.md`.
  - `JugglingTrackerApp.mc` — app entry point.
  - `AppNavigator.mc` — startup flow for Juggle vs Record and ball-count selection, and button routing (`AppDelegate.mc`); `ModeSelectView.mc` / `BallSelectView.mc` draw it.
  - `JugglingDetector.mc` — watch-hand catch detection algorithm.
  - `ShapeConsistency.mc` — the Regularity score (SHAPE-1..4), fed by the detector.
  - `TrackerSession.mc` — Juggle mode: runs, menus, sync logic. `MainView.mc` draws it and feeds it the sensor.
  - `RecordingSession.mc` — Record mode: raw accelerometer capture and labeling for tuning data. `RecordingView.mc` draws it.
  - `Platform.mc` / `GarminPlatform.mc` — the plain timer, clock, phone link and effects interfaces the sessions and navigator use, and their Connect IQ versions. The sessions match the Wear OS `logic/` and Apple `WatchLogic` classes of the same names.
- `wearos/` — Wear OS watch app, a Kotlin/Compose port of the Garmin app. Behaviour lives in plain-Kotlin `logic/` (Juggle and Record sessions, navigation) on top of `shared/` and follows `connectiq/REQUIREMENTS.md`; it talks to the phone over the Wear OS Data Layer with the same payloads as the Garmin app. See `wearos/README.md`.
- `shared/` — plain-Kotlin module both `android/` and `wearos/` include as `:shared`, the Kotlin twin of `apple/JugglingCore/`: `JugglingDetector.kt` and `ShapeConsistency.kt` (keep them in sync with the Monkey C and Python versions), `WatchProtocol.kt` (Data Layer paths, capability names and the JSON codec), `SampleThrottle.kt` (thins a sensor to 25 Hz), `SessionSummary.kt` (the stored session and reading a watch's `session` payload), `SessionCsv.kt` (history CSV for export, the weekly backup and restore), `RecordingHeader.kt` (the recording CSV header and `Juggler`) and `RecordingTransfer.kt` (puts a run sent as `rec_start` / `rec_chunk` / `rec_end` back together). Anything without Android in it goes here, not in `android/`. Test with `./gradlew :shared:test` from either app.
- `android/` — Kotlin/Compose Android app.
  - `MainActivity.kt` — Permissions, the phone accelerometer listener, the watch cards' status.
  - `JugglingTrackerApplication.kt` — One repository, `WatchInbox` and `GarminLink` per process, shared by the screens and `WatchMessageService`.
  - `GarminLink.kt` — Garmin Connect IQ SDK integration, process-scoped so a recreated screen cannot drop a transfer.
  - `WatchMessageService.kt` — `WearableListenerService` for the Wear OS watch's messages; it receives with the app closed.
  - `logic/WatchInbox.kt` — Stores every watch message and decides the `ack`: only what was stored is acked.
  - `logic/JugglingViewModel.kt` — State management, session import, CSV export.
  - `data/SessionRepository.kt` — Session history: in-memory cache plus `sessions.jsonl`, one JSON object per line, written on a background executor.
  - `data/RecordingRepository.kt` — Raw recording CSV storage and zip export.
  - `data/SettingsManager.kt` — Settings (watch type, voice, analytics, juggler, backup file).
  - `ui/` — Compose screens and components.
- `apple/` — the iPhone app (`JugglingTracker/`) and the Apple Watch app (`Watch/`, behaviour in `WatchLogic/`), sharing `JugglingCore/`, the counterpart of `shared/` with the same files: the Swift detector, Regularity score, watch protocol, sample throttle, session summary, history CSV, recording header and recording transfer. `WatchLogic` is a line-for-line port of the Wear OS `logic/` package. See `apple/Watch/README.md` and `apple/JugglingTracker/README.md`.
- `connectiq/data/` — Labeled accelerometer CSVs used for detection tuning and regression tests. One run per file, named `YYYYMMDD_HHMMSS.csv` after its run id.
- `simulation/` — Python detector simulation, tuning/plotting scripts, and pytest regression tests.

### Communication flow

Garmin: watch accelerometer (25 Hz) → `JugglingDetector` (burst-clustered watch-hand catch detection) → `TrackerSession` (run/session state) → `GarminPhoneLink` (`Communications.transmit()`) → `GarminLink` (Garmin SDK) → `WatchInbox` → `SessionRepository`, with `JugglingViewModel` following the inbox's events. The phone sends an `ack` back only once the session is stored; the watch only exits after receiving it.

Wear OS: accelerometer thinned to 25 Hz → `JugglingDetector.kt` (shared) → `TrackerSession` → `DataLayerPhoneLink` (JSON over the Data Layer) → `WatchMessageService` → the same `WatchInbox`. The `ack` goes back over the Data Layer to the sending watch.

Apple Watch: CoreMotion thinned to 25 Hz → `JugglingDetector.swift` (`JugglingCore`) → `TrackerSession` (`WatchLogic`) → `ConnectivityPhoneLink` (WatchConnectivity) → the iPhone's `AppleWatchLink` → its `WatchInbox` → `SessionStore`. The `ack` comes back in the message's reply. A Garmin watch reaches the iPhone through the Connect IQ iOS SDK in `GarminLink.swift`.

Recording mode uses `RecordingSession` (on all three watches) to capture raw accelerometer samples. Start begins and ends each run; Back returns to ball selection while idle, offers to quit while recording, offers to discard while labeling, and is ignored while syncing. The user corrects the detected count to the true watch-hand count, then the run transfers as `rec_start` / `rec_chunk` × N / `rec_end` and the Android app writes it through `RecordingRepository` in the same CSV format as `connectiq/data/`.

`AppNavigator.ENABLE_RECORDING_MODE` (Garmin and Wear OS; `AppNavigator.enableRecordingMode` on Apple Watch) is `true` in released builds on purpose: Record mode is how the labeled corpus grows, and the Connect IQ store listing advertises it. The Garmin `app1_modeSelectionDefaultsToJuggle` and the Wear OS `AppNavigatorTest` pin the values, so turning either off has to be deliberate (APP-1).

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

### iPhone and Apple Watch apps

```sh
cd apple && xcodegen generate && open JugglingTracker.xcodeproj   # Mac with Xcode
cd apple/JugglingCore && swift test
cd apple/WatchLogic && swift test
```

CI: `.github/workflows/apple.yml` runs on a macOS runner on every pull request that touches `apple/`, the recordings or the pinned counts, and on `main` after merge.

### Detection simulation

```sh
cd simulation
python -m pytest test_detection.py -v
python eval_new_watch.py
```

`simulation/eval_new_watch.py` mirrors `connectiq/source/JugglingDetector.mc`. Keep detector parameters synchronized across the watch code, the Kotlin port in `shared/`, the Swift port in `apple/JugglingCore/`, the Python simulation, regression tests, and ConnectIQ instruction file.

`test_detection.py` pins a per-run expected count for every recording in `connectiq/data/`, plus corpus totals (currently at most 245 absolute error and 99 overcount over 129 runs). Adding recordings means adding their entries; changing the detector means re-deriving the expectations and justifying the new totals.

## Conventions

- Android: Kotlin, Jetpack Compose (Material 3), `ViewModelProvider.Factory` pattern, `mutableStateOf`/`mutableStateListOf` for reactive state.
- Watch: Monkey C, `WatchUi.View`/`BehaviorDelegate` pattern, `Sensor.registerSensorDataListener` for accelerometer.
- Sensor batches can contain `null` samples when a stream comes up short for the period. Guard every batch before doing arithmetic on it — an unguarded null throws inside the detector and kills the app with no on-screen trace.
- Detection: threshold crossings are candidates, not direct counts. Nearby candidates are delayed and merged into one catch-motion burst; odd-numbered committed bursts increment the watch-hand catch count by 1 and alternating other-hand bursts are skipped.
- Error handling: Always log exceptions. Android uses `Log.e(TAG, message, exception)` or `Log.w(TAG, message, exception)`. Watch uses `System.println()`. Never swallow exceptions silently.
- Persistence: session history as JSON Lines (`sessions.jsonl`) built with `org.json.JSONObject` in `buildSessionJson()`, settings in `SharedPreferences`, recordings as one CSV per run. No ORM or serialization library. The iPhone app writes the same formats, so files move between the phones unchanged.
- The watch is the sole controller of a watch session; the phone only listens and records. Phone IMU sessions are the exception: the phone app runs those itself.
- A session's timestamp is its identity. The watch keeps one per session (SYNC-5), and a resend under the same timestamp replaces the stored copy.
