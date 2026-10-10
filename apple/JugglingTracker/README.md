# Juggling Tracker for iPhone

The iPhone counterpart of the Android app in `android/`: the same screens and
features, working with a Garmin watch or an Apple Watch instead of a Garmin or
Wear OS watch. It is a side development stream: no CI on every push and no
releases (see the project instructions).

## Build

The Xcode project is generated from `apple/project.yml` with
[XcodeGen](https://github.com/yonaskolb/XcodeGen) and is not checked in:

```sh
brew install xcodegen
cd apple
xcodegen generate
open JugglingTracker.xcodeproj
```

Pick an iPhone simulator and run, or run the tests with Cmd-U. Without a paid
Apple developer account the app installs on your own iPhone from Xcode for
seven days at a time.

CI: add the label `run-apple-ci` to the pull request, and the "iPhone app" job
in `.github/workflows/apple.yml` builds the app and runs its tests on a
simulator.

## Layout

| Folder | Holds | Android counterpart |
| --- | --- | --- |
| `App/` | App entry, the services wired together | `JugglingTrackerApplication`, `MainActivity` |
| `Data/` | Session history (JSON Lines), raw recordings (CSV), settings, weekly backup | `data/`, `backup/` |
| `Logic/` | `TrackerModel` (phone sessions, raw recording, watch card), `WatchInbox`, accelerometer, voice, Firebase | `logic/`, `sensor/` |
| `UI/` | SwiftUI screens | `ui/` |

Counting, Regularity, the watch messages and the history CSV come from the
`JugglingCore` package next to this folder, so they match the Android app.

Files keep the Android formats, so a history backup or a raw recording moves
between the two phones unchanged. The iPhone's accelerometer is converted to
Android's units and axis signs (m/s², +1 g up when lying face up) before it
reaches the detector or a recording.

## Garmin watch

As on Android, the app has to be running (it may be in the background) to
receive from the Garmin watch. Unlike Android, the iOS SDK does not find the
watch by itself: tap the watch card, then "Choose Watch in Garmin Connect".
Garmin Connect opens, you pick the watch, and it sends the choice back through
the app's `jugglingtracker-ciq://` URL scheme. The app keeps the choice, so
this is needed once per watch. Messages go to `WatchInbox` and are acked to the
watch app they came from, exactly as on Android.

## Not done yet

- **Apple Watch link.** WatchConnectivity is still a stub: with Apple Watch
  picked in Settings, the watch card shows the setup checklist.
- **Garmin link on a real watch.** `Logic/GarminLink.swift` talks to the
  Garmin app through Garmin's Connect IQ Mobile SDK
  (`Logic/ConnectIQSDKClient.swift`, a Swift package in `project.yml`) and is
  unit tested against a fake SDK, but it has not yet run with a real iPhone
  and Garmin watch.
- **Firebase.** The app includes Firebase Analytics and Crashlytics behind the
  same "Share app activity and crash reports" switch as Android, but they stay
  off until the app is registered in the Firebase console: add an iOS app with
  bundle ID `com.juggling.tracker` to the project the Android app uses,
  download its `GoogleService-Info.plist` and put it in `Resources/`. Crash
  reports will also need Crashlytics' dSYM upload script once there are builds
  outside Xcode.
