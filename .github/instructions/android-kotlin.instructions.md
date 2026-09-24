---
description: "Use when writing or modifying Kotlin files in the Android app. Covers Compose patterns, ViewModel conventions, repository layer, and Garmin SDK integration."
applyTo: "android/**/*.kt"
---
# Android App Conventions

## Language & UI
- Kotlin with Jetpack Compose (Material 3). No XML layouts.
- Target/compile SDK 34, min SDK 26.

## State Management
- Use `mutableStateOf` and `mutableStateListOf` for observable state in ViewModels.
- Create ViewModels via `ViewModelProvider.Factory` — never inject the repository directly into constructors without the factory pattern.
- UI state that comes from the Garmin SDK (e.g. `garminStatus`, `isWatchAppRunning`) currently lives in `MainActivity`. All other state belongs in `JugglingViewModel`.

## Persistence
- `SessionRepository` uses `SharedPreferences` with manual JSON via `org.json.JSONArray`/`JSONObject`.
- `buildSessionJson()` builds JSON strings by template interpolation — keep this pattern consistent.
- Always call `saveSessionsToStorage()` after mutating `sessionsCache`.
- Use `apply()` (async) not `commit()` for SharedPreferences writes.
- `RecordingRepository` stores raw accelerometer recordings under the app's `recordings/` files directory, one run per file named `YYYYMMDD_HHMMSS.csv` in the same format as `connectiq/data/`, so files can be copied straight into the corpus. It can also export all recordings as one merged CSV string.

## Garmin Connect IQ SDK
- `ConnectIQ.getInstance()` with `IQConnectType.WIRELESS`.
- Register for app events with the watch app ID `A1B2C3D4E5F60718293A4B5C6D7E8F90`.
- The phone never starts or controls sessions — it only receives finished session payloads and sends back an `ack`.
- Incoming message shape: `{ "type": "session", "countMode": "watch_hand", "balls": Int, "timestamp": Long (epoch seconds), "durationSeconds": Long, "runDurationsMillis": List<Number>, "runs": List<Number> }`. `runs` contains watch-hand catch counts. `runDurationsMillis` is one first-to-last-watch-hand-catch duration per run and is used for session-detail frequency/spacing display; `durationSeconds` is stored/exported but not displayed in the Android UI.
- Recording messages arrive chunked: `rec_start` carries the metadata (`balls`, `catches`, `detected`, `sampleRate`, `samples`, `chunks`, `id`), then one `rec_chunk` per 50 samples (`i`, `x`, `y`, `z`), then `rec_end`. `catches` and `detected` are watch-hand counts. Ack only `session` and `rec_end`; chunks are not acked. A repeated chunk index is ignored because the transport can deliver a message twice, but a gap discards the run rather than writing corrupt training data.
- ACK shape: `{ "type": "ack", "timestamp": Long? }`. The timestamp is included when available so the watch can match it to the pending send.

## Error Handling
- Always log exceptions: `Log.e(TAG, "message", exception)` for errors, `Log.w(TAG, "message", exception)` for warnings.
- Never swallow exceptions in empty catch blocks.
- Each class should have a `companion object` with `private const val TAG = "ClassName"`.
- User-facing errors (e.g. export failure) should also show a `Toast`.

## Imports
- Prefer specific imports over wildcards, except for `androidx.compose.*` where wildcards are acceptable.
