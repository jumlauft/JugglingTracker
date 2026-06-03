# Project Guidelines

## Architecture

Two-platform juggling throw tracker: a Garmin watch app detects throws from accelerometer data and transmits finished sessions to an Android companion app for storage and visualization.

- `connectiq/source/JugglingTrackerApp.mc` — Monkey C watch app. Contains `JugglingDetector` (throw detection algorithm), `MainView` (sensor listener, sync logic, UI), `BallSelectView` (startup screen), and menu delegates.
- `android/` — Kotlin/Compose Android app.
  - `MainActivity.kt` — Garmin Connect IQ SDK integration, permissions, message routing.
  - `logic/JugglingViewModel.kt` — State management, session import, CSV export.
  - `data/SessionRepository.kt` — Persistence layer (in-memory cache + SharedPreferences with manual JSON).
  - `model/` — Data classes (`JugglingRun`, `SessionSummary`).
  - `ui/` — Compose screens and components.

### Communication flow

Watch accelerometer (25 Hz) → `JugglingDetector` (throw detection) → `MainView` (buffering) → `Communications.transmit()` → `MainActivity` (Garmin SDK) → `JugglingViewModel` → `SessionRepository`. The phone sends an `ack` back; the watch only exits after receiving it.

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
monkeyc -f connectiq/monkey.jungle -d fr245 -o connectiq/build/JugglingTracker.prg -y developer_key.der -r
```

Requires the Garmin Connect IQ SDK. The `developer_key.der` is gitignored and must never be committed.

## Conventions

- Android: Kotlin, Jetpack Compose (Material 3), `ViewModelProvider.Factory` pattern, `mutableStateOf`/`mutableStateListOf` for reactive state.
- Watch: Monkey C, `WatchUi.View`/`BehaviorDelegate` pattern, `Sensor.registerSensorDataListener` for accelerometer.
- Error handling: Always log exceptions. Android uses `Log.e(TAG, message, exception)` or `Log.w(TAG, message, exception)`. Watch uses `System.println()`. Never swallow exceptions silently.
- Persistence: `SharedPreferences` with manual JSON serialization via `org.json.JSONArray`/`JSONObject` and `buildSessionJson()`. No ORM or serialization library.
- The watch is the sole session controller. The phone only listens and records.
- Session deduplication is by timestamp.
