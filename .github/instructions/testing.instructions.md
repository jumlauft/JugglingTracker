---
description: "Use when writing, modifying, or discussing tests. Covers test structure, frameworks, and patterns for Android, ConnectIQ detector simulation, and repository tests."
applyTo: ["android/**/test/**/*.kt", "android/**/androidTest/**/*.kt", "simulation/test_*.py"]
---
# Test Conventions

## Android Framework
- JUnit 4 with `kotlinx-coroutines-test` for coroutine testing.
- Use the shared `MainDispatcherRule` (in `src/test/.../MainDispatcherRule.kt`) via `@get:Rule` instead of manual `Dispatchers.setMain()` / `resetMain()`. This avoids boilerplate and prevents dispatcher leaks across tests.
- Use `runTest { }` for coroutine test bodies.

## File Placement
- Unit tests: `android/app/src/test/java/com/jugglingtracker/imu/` mirroring the main source tree.
- Test class names: `<ClassUnderTest>Test.kt`.

## Patterns
- `JugglingViewModel` can be instantiated without a repository for unit tests (constructor accepts `null`).
- Use `advanceUntilIdle()` after operations that emit to `SharedFlow`.
- Test method names use backtick syntax: `` `descriptive name of what is tested` ``.

## What to Test
- ViewModel: session import (happy path, missing fields, duplicate timestamps, empty runs, edge cases).
- Repository: save/load cycle, JSON round-trip, deduplication, corruption recovery.
- Recording repository: single-run CSV save, merged CSV export, empty export, delete behavior.
- Watch-hand catch detection algorithm: threshold behavior, candidate refractory periods, delayed burst clustering, alternating watch-hand burst counting, auto-finish, warmup, adaptive thresholds, watch/Python parameter parity, and overcount regression.
- Message parsing: valid payloads, malformed payloads, missing fields, wrong types.

## Running Android Tests
```sh
cd android
./gradlew test
```

## Running Detection Tests
```sh
cd simulation
python -m pytest test_detection.py -v
```

`simulation/test_detection.py` uses `simulation/eval_new_watch.py` as the watch-parity detector simulation and the labeled CSVs in `connectiq/data/`, one run per file keyed by run id. Every recording on disk must have an `EXPECTED_RUNS` entry. The `catches=` labels are watch-hand catches, not both-hands totals. Keep expected counts and performance limits synchronized with intentional detector changes only.
