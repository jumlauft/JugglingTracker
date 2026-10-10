# Apple Watch app

A watchOS port of the Garmin Connect IQ app in `../../connectiq`, built the
same way as the Wear OS port in `../../wearos`. It has the same screens, the
same catch detector, the same session and recording rules, and sends the same
messages. `../../connectiq/REQUIREMENTS.md` is the specification.

Side development stream: no CI on every push (the `run-apple-ci` label runs
it) and no releases. watchOS 10 or later.

## Layout

- `../JugglingCore/` - the detector, the Regularity score and the message
  codec, shared with the iPhone app.
- `../WatchLogic/` - everything with behaviour, with no UI, sensor or radio
  types, so `swift test` covers it:
  - `TrackerSession.swift` - Juggle mode (`MainView.mc`): runs, menus, sync.
  - `RecordingSession.swift` - Record mode (`RecordingView.mc`).
  - `AppNavigator.swift` - mode and ball selection, and button routing.
  - `SampleThrottle.swift` - thins the sensor stream to 25 Hz on a 40 ms grid.
  - `Format.swift` - display strings, identical to what the Garmin draws.
  - `Platform.swift` - the timer, clock, phone link and vibration interfaces.
  - `ConnectivityPhoneLink.swift` - the phone link over WatchConnectivity.
- `Watch/` (this folder) - the app target:
  - `WatchRuntime.swift` - wires it together.
  - `MotionSource.swift` - CoreMotion accelerometer, 50 Hz thinned to 25 Hz,
    in milli-g including gravity, handed over in one-second batches.
  - `WorkoutKeeper.swift` - the workout session that keeps counting with the
    wrist down.
  - `Platform.swift` - main-queue timers and haptics.
  - `PhoneConnectivity.swift` - `WCSession`, forwarded to the phone link.
  - `Screens.swift` - SwiftUI screens.

The Xcode project is generated from `../project.yml`:

    brew install xcodegen
    cd apple && xcodegen generate && open JugglingTracker.xcodeproj

Pick the JugglingTrackerWatch scheme and an Apple Watch simulator.

## Buttons

| Garmin | Apple Watch |
|---|---|
| UP / DOWN | turn the Digital Crown, or tap the arrows |
| START / STOP | the green button on screen (Start, Stop, Confirm); End on the Juggle screen's controls page |
| BACK | the button in the top-left corner; Discard run on the Juggle screen's controls page |

The Juggle screen has three pages, as a workout in the Workout app does: the
count in the middle, where it opens; the controls (End, Discard run, Lock
screen) a swipe to the right; the session stats a swipe to the left. Nothing on
the count page reacts to a touch, because a stray one mid-juggle would open a
menu, and counting pauses while a menu is up. The controls page goes back to the
count after a few seconds untouched, and so does every page when the wrist goes
down. Lock screen turns on Water Lock, which ignores the screen until the
Digital Crown is turned; watchOS allows it only while the workout session runs.

The first screen has no back button: pressing the Digital Crown leaves the
app, as on any watch app. Where the Garmin app quits (after a sync, or on
Quit), this one ends the session and returns to the first screen, because a
watchOS app cannot close itself.

## The wrist down

watchOS suspends an app soon after the screen goes dark. While a juggling or
record session is open the app runs an `HKWorkoutSession`, which keeps it and
the accelerometer going. That needs Health permission, asked the first time a
session opens; nothing is read from or saved to Health. Without it the app
still counts while it is on screen. In the dimmed always-on display the
buttons are hidden and the count keeps updating.

## Talking to the phone

Sessions and recorded runs go to the iPhone app over WatchConnectivity, as
the messages every watch sends: `session`, `rec_start`, `rec_chunk` (1000
samples, as on Wear OS) and `rec_end`. Each one is a `sendMessage`, which wakes
the iPhone app in the background if it is not open. The phone answers every
message, with its `ack` once it has stored a session or a whole run and an
empty reply otherwise; only the ack closes the session. With no iPhone in
reach a send fails, so ending a session offers Retry sync, Quit without sync
and Continue, as a Garmin out of reach of its phone does.

## Tests

`../WatchLogic/Tests` ports the Wear OS logic tests one for one; each test
name carries its requirement id.

| Requirements | Tests |
|---|---|
| APP-1 to APP-5, REC-11 | `AppNavigatorTests` |
| DET-1 (25 Hz) | `SampleThrottleTests` |
| DET-2 to DET-11, RUN, SHAPE-1 to SHAPE-3 | `../JugglingCore/Tests` |
| SHAPE-4, JUG-1 formats | `FormatTests` |
| JUG-1 to JUG-8, SYNC-1 to SYNC-5 | `TrackerSessionTests` |
| REC-1 to REC-10 | `RecordingSessionTests` |

Not covered by an automated test: drawing, the Digital Crown, haptics
(JUG-7 is covered as a call), the accelerometer itself (SENS-1 to SENS-4)
and the workout session. There is no Apple Watch to try them on yet, so they
have only run in the simulator.
