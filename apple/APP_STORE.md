# App Store listing for Juggling Tracker (iPhone and Apple Watch)

Text and answers to paste into App Store Connect. They follow the Play
listing (`android/PUBLISHING.md`), adjusted for Garmin and Apple Watch. Keep
the accuracy figure in step with the "All" row of the per-ball-count table in
the root README, and the privacy answers in step with
`JugglingTracker/Resources/PrivacyInfo.xcprivacy`.

## App information

| Field | Value |
| --- | --- |
| Name (max 30) | Juggling Tracker |
| Subtitle (max 30) | Count catches with your watch |
| Bundle ID | com.juggling.tracker (Apple Watch app: com.juggling.tracker.watchkitapp) |
| SKU | juggling-tracker-ios |
| Primary category | Sports |
| Secondary category | Health & Fitness |
| Age rating | 4+ (answer "None" to every question) |
| Copyright | 2026 Jonas Umlauft |
| Privacy policy URL | https://github.com/jumlauft/JugglingTracker/blob/main/privacy_policy.md |
| Support URL | https://github.com/jumlauft/JugglingTracker/issues |
| Price | Same as on Google Play (one-time purchase); Jonas's call |

## Promotional text (max 170)

Stop counting in your head. Your Garmin or Apple Watch counts your juggling catches and your iPhone keeps the history.

## Keywords (max 100, comma separated, no spaces)

juggling,juggle,balls,catches,counter,cascade,practice,garmin,tracker,circus,skill,training

## Description (max 4000)

Juggling Tracker counts your catches while you juggle, so you can stop counting in your head and see how your practice adds up over time.

How it works
Wear a Garmin watch or an Apple Watch on one wrist. The watch detects each catch made by that hand from its motion sensor and groups the catches into runs automatically. Because it counts one hand, a 20-catch cascade shows about 10. When you finish, the session goes to this iPhone app, which keeps your history.

Key features
* Automatic run and catch detection on the watch, for 3 to 9 balls
* Live count, best run and session average on your wrist
* Regularity score: how consistent your throws are from catch to catch
* History charts, best and average per number of balls
* Apple Watch app included, and Garmin watches (Forerunner, Fenix, Venu, Vivoactive, Instinct, Epix and more) through the free Juggling Tracker app from the Garmin Connect IQ Store
* No watch? Strap the iPhone to your counting arm and track a session with its motion sensor
* Weekly backup of your history to a file you choose

Accuracy
The count is often off by a catch or two per run, so use it for trends rather than to prove a new record. On our test set of 129 runs and about 3,200 catches from five jugglers, it is 92.5% accurate, with slightly more undercounting than overcounting.

Help improve it
Record mode lets you label runs with the number you counted yourself and email the recordings to the developer. Every recording makes detection better for everyone.

Privacy
Your history stays on your iPhone. The app uses Firebase Analytics and Crashlytics for usage stats and crash reports; you can turn this off in Settings.
Source code: https://github.com/jumlauft/JugglingTracker

## What's New (first release)

First release on the App Store.

## App Review information

Notes for the reviewer:

> Juggling Tracker counts juggling catches from a watch's motion sensor. To
> try it without a watch, open the app, tap "Start Session with Phone",
> choose a number of balls, press Start, and hold the iPhone against one
> wrist or forearm while juggling (or move that hand down and up as if
> catching a ball); each catch adds to the count. The Apple Watch app is
> embedded and works the same way on the wrist. The Garmin link needs the
> Garmin Connect app and a Garmin watch with the Juggling Tracker Connect IQ
> app. The Apple Watch app runs a workout session to keep the motion sensor
> running with the wrist down, and saves each juggling session to Health as
> an "Other" workout with the ball count and average catches per run in its
> metadata. It reads nothing from Health.

No sign-in is needed, so leave the demo account empty.

## Screenshots

Required sizes (App Store Connect scales the rest down):

| Device | Size (portrait) | Count |
| --- | --- | --- |
| iPhone with Dynamic Island, medium display (iPhone 16/17 Pro) | 1206 x 2622 (or 1179 x 2556) | 3 to 10 |
| Apple Watch (Series 10 46 mm) | 416 x 496 | 1 to 10 |

The screenshots are in `store_assets/iphone` and `store_assets/watch`. They
are taken in the simulators by `.github/workflows/apple-screenshots.yml`
(add the `apple-screenshots` label to a pull request, or run it from the
Actions tab), with a made-up history of 3, 4 and 5 balls so the trend graph
shows progress. The iPhone shots are the 3-ball history, a session's details,
the Apple Watch card and Settings; the watch shots are the live count, the
session stats, the ball selection and the mode selection. App Store Connect
refuses the Pro Max size (1320 x 2868) in the required iPhone slot.

## App Privacy answers

In App Store Connect > App Privacy, answer "Yes, we collect data" and declare
exactly these. None is used for tracking.

| Data type | Linked to the user | Purpose | Why |
| --- | --- | --- | --- |
| Diagnostics > Crash Data | No | App Functionality | Firebase Crashlytics |
| Usage Data > Product Interaction | No | Analytics | Firebase Analytics events (session imported, phone session started, ball count, runs, catches) |
| Identifiers > Device ID | No | Analytics | Firebase app instance id |
| Contact Info > Name | Yes | App Functionality | Juggler name in Record mode recordings, sent only if the user emails them |
| Health & Fitness > Fitness | Yes | App Functionality | Record mode recordings, sent only if the user emails them |

Firebase collection is on by default and can be turned off under Settings >
Privacy > "Share app activity and crash reports". Everything else (session
history, settings, backups) stays on the device. The Apple Watch app collects
nothing on its own; it sends sessions and recordings only to the user's
iPhone, and the workouts it saves to Health stay in Health on the user's
devices, so they are not "collected" data either.

## Export compliance

`ITSAppUsesNonExemptEncryption` is `false` in both Info.plists: the app uses
only Apple's and Firebase's standard HTTPS. App Store Connect then asks no
export questions per build.
