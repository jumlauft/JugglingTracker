# Instructions for agents working in this repo

Read `.github/copilot-instructions.md` for the architecture, build and test
commands and code conventions. This file adds the rules that decide **which
apps a change has to reach**.

## Keep the implementations in sync

This repo holds two phone apps and three watch apps. Each group is one product
built several times over, and the copies must not drift apart.

### Phones: Android and iPhone

- `android/` (Kotlin/Compose) and `apple/JugglingTracker` (SwiftUI) are both
  valid implementations of the phone app.
- They keep the **same user-facing functionality** and the **same user-facing
  design**, as close as the two platforms allow: the same screens, cards,
  settings, wording, colours and flows.
- The one intended difference is which watches they talk to: Android connects
  to Garmin and Wear OS watches, the iPhone to Garmin and Apple Watch.

### Watches: Garmin, Wear OS and Apple Watch

- `connectiq/` (Garmin, Monkey C), `wearos/` (Wear OS, Kotlin/Compose) and
  `apple/Watch` with `apple/WatchLogic` (Apple Watch, SwiftUI) are all valid
  implementations of the watch app. `connectiq/REQUIREMENTS.md` is the shared
  specification.
- They keep the **same user-facing functionality and workflow**: the same
  screens shown one after another, the same menus and menu items, the same
  BACK behaviour, the same labels and stats, the same recording and sync flow.
- Hardware-specific differences are allowed and expected. Note each one where
  it is made (code comment, the app's README, the PR). Current ones:
  - Input: Garmin has physical UP/DOWN/START/BACK buttons; Wear OS uses the
    rotary input, on-screen arrows and buttons and the edge swipe; Apple Watch
    uses the Digital Crown, on-screen arrows and buttons and a back button in
    the corner; its mode and ball-count screens are swipeable wheels, as in
    the Workout app, where the other watches step one value at a time.
  - Scrolling: the ball count wraps around (9 → 3 and 3 → 9) on the Garmin,
    which steps with buttons; on Wear OS and Apple Watch, which scroll with a
    crown or bezel, it stops at 3 and 9 (Jonas, 2026-10-12).
  - Juggle screen: the touch watches (Wear OS, Apple Watch) split it into
    pages (controls, count, stats) so a stray touch mid-juggle cannot open a
    menu; the Garmin, driven by buttons, shows it on one screen. Apple Watch
    alone offers Lock screen (Water Lock), which Wear OS has no API for.
  - A watchOS app cannot close itself, so where the Garmin and Wear OS apps
    quit, the Apple Watch app returns to its first screen.

### What every change must do

1. When you change one implementation, **port the change to the others in
   its group** in the same piece of work (phone: Android and iPhone; watch:
   Garmin, Wear OS and Apple Watch), keeping only hardware-specific
   differences.
2. **Tell the user** in your reply whenever a change was synced into another
   implementation: which app got it and what changed. Name any intentional
   hardware-specific difference too.
3. If a change cannot be ported (an API missing on one platform, for
   example), say so in the reply and in the PR instead of leaving it silent.

This replaces the older rule that only the Garmin and Wear OS watch apps had
to keep the same features: that rule now covers all three watch apps.

All five apps are main development streams. Their CI runs on every pull
request that touches them: `ci.yml` for Android, `shared/` and the detection
tests, `wearos.yml` for Wear OS, and `apple.yml` for the iPhone and Apple
Watch. The Garmin tests run only on the Mac (see below).

Releases:
- Android phone and Wear OS: every `v*` tag publishes both to Google Play
  (`release-android.yml`), the watch app to the `wear:internal` track.
- Garmin: built on the Mac and uploaded to the Connect IQ store by hand.
- iPhone with the Apple Watch app inside: released to the App Store, but only
  when started by hand (`release-apple.yml`, see `apple/RELEASE.md`). A `v*`
  tag does not ship it.

## Testing what you cannot run

Garmin code builds and runs its tests only on a Mac with the Connect IQ SDK
(`connectiq/run_tests.sh`), and Apple code only with Xcode or on a GitHub
macOS runner. When a synced change could not be built or tested, say which
app was left unverified.
