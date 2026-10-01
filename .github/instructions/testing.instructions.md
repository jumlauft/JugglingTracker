---
description: "Use when writing, modifying, or discussing tests. Covers test structure, frameworks, and patterns for Android, Wear OS, ConnectIQ, the detector simulation, and repository tests."
applyTo: ["android/**/test/**/*.kt", "android/**/androidTest/**/*.kt", "wearos/**/test/**/*.kt", "wearos/**/androidTest/**/*.kt", "connectiq/test/**/*.mc", "simulation/test_*.py"]
---
# Test Conventions

## Android Framework
- JUnit 4 with `kotlinx-coroutines-test` for coroutine testing.
- Use the shared `MainDispatcherRule` (in `src/test/.../MainDispatcherRule.kt`) via `@get:Rule` instead of manual `Dispatchers.setMain()` / `resetMain()`. This avoids boilerplate and prevents dispatcher leaks across tests.
- Use `runTest { }` for coroutine test bodies.

## File Placement
- Unit tests: `android/app/src/test/java/com/juggling/tracker/` mirroring the main source tree, so `logic/`, `data/` and `ui/` subpackages, with shared fakes (`MainDispatcherRule`, `FakeSharedPreferences`) at the package root.
- Test class names: `<ClassUnderTest>Test.kt`.
- Instrumented tests: `android/app/src/androidTest/java/com/juggling/tracker/` — see "Compose UI Tests" below for when something belongs there rather than in `test/`.

## Compose UI Tests
- Compose UI tests live in the **unit test** source set and run under Robolectric (`@RunWith(RobolectricTestRunner::class)` plus `@Config(sdk = [34])`), so `./gradlew testDebugUnitTest` and therefore CI runs them without an emulator. Put a UI test here unless it genuinely cannot work against Robolectric's shadows.
- Pin `@Config(sdk = ...)` explicitly. Robolectric does not support `compileSdk 36`, and a test whose behaviour depends on the API level should name the level it means — `StatusBarAppearanceTest` runs the same assertion at 34 and 35 because the correct answer inverts between them.
- Pass `dynamicColor = false` to `JugglingTrackerTheme` in any test that asserts on colour. With it on, the wallpaper picks the palette and expectations become host-dependent.
- `androidTest/` is for what needs a real framework instead of shadows. CI cannot run it (no emulator on the runner) but does compile it, so it will not rot silently. Run it by hand with `./gradlew connectedDebugAndroidTest`. Keep it small — anything assertable on the JVM belongs in `test/`, where it runs on every change.
- To assert on a `DisposableEffect` or `SideEffect` that writes to the view or window, capture `LocalView.current` in `setContent` and read the property back afterwards (`KeepScreenOnTest`), or read it off `createAndroidComposeRule<ComponentActivity>().activity.window` (`StatusBarAppearanceTest`).

## Patterns
- `JugglingViewModel` can be instantiated without a repository for unit tests (constructor accepts `null`).
- Use `advanceUntilIdle()` after operations that emit to `SharedFlow`.
- Test method names use backtick syntax: `` `descriptive name of what is tested` ``.

## What to Test
- ViewModel: session import (happy path, missing fields, duplicate timestamps, empty runs, edge cases).
- Repository: save/load cycle, JSON round-trip, deduplication, corruption recovery.
- Recording repository: single-run CSV save, zip export with the juggler/hand/firstThrow header fields, empty export, delete behavior.
- Watch-hand catch detection algorithm: threshold behavior, candidate refractory periods, delayed burst clustering, alternating watch-hand burst counting, auto-finish, warmup, adaptive thresholds, watch/Python parameter parity, and overcount regression.
- Message parsing: valid payloads, malformed payloads, missing fields, wrong types.

## Running Android Tests
```sh
cd android
./gradlew test                      # JVM tests, Compose UI tests included
./gradlew connectedDebugAndroidTest # instrumented; needs a device or emulator
```

The first Robolectric run downloads a ~170 MB framework jar per SDK level into
`~/.m2/repository/org/robolectric`, so it is slow once and fast after. CI caches
that directory.

## Wear OS Tests
- `wearos/app/src/test/.../logic/` holds plain JVM tests for everything with behaviour (detector, shape consistency, Juggle and Record sessions, navigation, protocol), driven by fake clocks, schedulers and links. `DetectorCorpusTest` replays every recording in `connectiq/data` and requires the counts `simulation/test_detection.py` pins, read out of that file.
- `wearos/app/src/test/.../ui/` holds the Robolectric Compose tests (`WearAppTest`, `TextFitTest`).
- `wearos/app/src/androidTest/` runs on a Wear OS emulator in CI (API 30, large round) and saves the screenshots CI uploads.
- Name tests after the requirement they cover in `connectiq/REQUIREMENTS.md` where there is one; `wearos/README.md` maps each requirement to its test.

```sh
cd wearos
./gradlew testDebugUnitTest         # logic + Robolectric UI tests
./gradlew connectedDebugAndroidTest # on a Wear OS emulator or watch
```

## Garmin Watch Tests
- `connectiq/test/*.mc` are `(:test)` functions compiled with the real sources through `test.jungle` and run in the Connect IQ simulator by `./connectiq/run_tests.sh` (macOS, needs the SDK and `developer_key.der`). They are not in CI.
- Every test must back a requirement in `connectiq/REQUIREMENTS.md`, and every requirement must name its tests; `test_detection.py` fails otherwise.

## Running Detection Tests
```sh
cd simulation
python -m pytest test_detection.py -v
```

`simulation/test_detection.py` uses `simulation/eval_new_watch.py` as the watch-parity detector simulation and the labeled CSVs in `connectiq/data/`, one run per file keyed by run id. Every recording on disk must have an `EXPECTED_RUNS` entry. The `catches=` labels are watch-hand catches, not both-hands totals. Keep expected counts and performance limits synchronized with intentional detector changes only.
