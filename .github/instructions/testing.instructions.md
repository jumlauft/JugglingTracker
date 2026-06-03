---
description: "Use when writing, modifying, or discussing tests. Covers test structure, frameworks, and patterns for both Android and ConnectIQ."
applyTo: ["android/**/test/**/*.kt", "android/**/androidTest/**/*.kt"]
---
# Test Conventions

## Framework
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
- Throw detection algorithm: threshold behavior, refractory periods, auto-finish, warmup, adaptive thresholds.
- Message parsing: valid payloads, malformed payloads, missing fields, wrong types.

## Running Tests
```sh
cd android
./gradlew test
```
