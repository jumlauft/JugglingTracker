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

## Garmin Connect IQ SDK
- `ConnectIQ.getInstance()` with `IQConnectType.WIRELESS`.
- Register for app events with the watch app ID `A1B2C3D4E5F60718293A4B5C6D7E8F90`.
- The phone never starts or controls sessions — it only receives finished session payloads and sends back an `ack`.
- Incoming message shape: `{ "type": "session", "balls": Int, "timestamp": Long (epoch seconds), "runs": List<Number> }`.
- ACK shape: `{ "type": "ack", "timestamp": Long? }`. The timestamp is included when available so the watch can match it to the pending send.

## Error Handling
- Always log exceptions: `Log.e(TAG, "message", exception)` for errors, `Log.w(TAG, "message", exception)` for warnings.
- Never swallow exceptions in empty catch blocks.
- Each class should have a `companion object` with `private const val TAG = "ClassName"`.
- User-facing errors (e.g. export failure) should also show a `Toast`.

## Imports
- Prefer specific imports over wildcards, except for `androidx.compose.*` where wildcards are acceptable.
