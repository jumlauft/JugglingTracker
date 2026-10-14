# Shared watch scenarios

Each JSON file here is one workflow from `../REQUIREMENTS.md`, written once
and replayed by the logic tests of every watch app:

- Wear OS: `wearos/app/src/test/.../logic/ScenarioTest.kt`
- Apple Watch: `apple/WatchLogic/Tests/WatchLogicTests/ScenarioTests.swift`
- Garmin: `connectiq/test/ScenarioTest.mc`, generated from these files by
  `garmin_tests.py` (Monkey C cannot read them in a test build). Run
  `python3 connectiq/scenarios/garmin_tests.py` after changing a scenario;
  `test_garmin_scenario_tests_are_up_to_date` in CI fails until you do.

A scenario starts the app on its first screen with a fake phone, a fake
clock and fake buzz/quit effects, then runs its `steps` in order. A drift
in workflow between the watches makes one of the replays fail.

```json
{
  "name": "What the scenario shows",
  "requirements": ["SYNC-1", "SYNC-2"],
  "epochSeconds": 1700000000,
  "platforms": ["wearos", "apple"],
  "steps": [{ "press": "start" }, { "expect": { "screen": "tracker" } }]
}
```

`epochSeconds` is the watch's wall clock (default 1700000000); it names the
session and the recorded run. `platforms` limits a scenario to the watches
listed (`garmin`, `wearos`, `apple`); leave it out for all three. Use it only
for the hardware differences listed in `CLAUDE.md`.

## Steps

Each step is an object with one key.

| Step | What it does |
|---|---|
| `{"press": "up" \| "down" \| "start" \| "back"}` | One button: UP and DOWN are the crown or arrows on the touch watches, BACK is the swipe or corner button. |
| `{"select": "<item id>"}` | Picks a menu item by id, such as `sync_quit`. |
| `{"warmup": true}` | 55 still samples, enough for the detector to settle. |
| `{"catches": n}` | `n` watch-hand catches (2n − 1 impulses). |
| `{"burst": n}` | `n` single impulses. |
| `{"idle": n}` | `n` still samples; 60 ends a run. |
| `{"wait": ms}` | Lets the clock run, firing timers that fall due. |
| `{"epochAdvance": s}` | Moves the wall clock on. |
| `{"deliver": true \| false}` | Reports the latest message as delivered or failed (`"index": i` for an earlier one). |
| `{"deliverAll": true}` | Delivers this and every later message, and lets time run until the session or the run's `rec_end` is out. |
| `{"phone": {...}}` | A message from the phone, usually `{"type": "ack", "timestamp": ...}`. |
| `{"expect": {...}}` | Checks the state; every key given must match. |

Samples are milli-g at 25 Hz with gravity on Z, as the sensor delivers them.

## Expectations

| Key | Meaning |
|---|---|
| `screen` | `modeSelect`, `ballSelect`, `tracker` (Juggle) or `recording` (Record). |
| `recordMode` | On the mode screen: whether Record is highlighted. |
| `mode`, `balls` | On ball selection: `juggle` or `record`, and the count. `balls` also reads a session's ball count. |
| `menu`, `menuTitle`, `menuItems`, `menuLabels`, `menuSubLabels` | The open menu's kind, title, item ids, labels and sub-labels; `menu: null` means none is open. |
| `runActive`, `count`, `previous`, `runs`, `max`, `sending` | Juggle screen state. `count` is the live count on either screen. |
| `phase`, `runsCompleted`, `detected`, `label`, `recordedSamples` | Record screen state; `phase` is `idle`, `recording`, `labeling` or `syncing`. |
| `error` | The error banner, or `null`. |
| `exits`, `vibrations` | How often the app closed itself and buzzed. |
| `sentTypes` | The `type` of every message sent, with repeats in a row folded into one, so chunk sizes do not matter. |
| `sentCount` | How many messages were sent. Avoid it where chunk counts differ between watches. |
| `firstSent`, `lastSent` | Fields the first or last message sent must carry; `null` means the field must be absent. |
