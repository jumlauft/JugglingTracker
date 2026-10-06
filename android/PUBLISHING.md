# Juggling Tracker Publishing Checklist

This document outlines the steps and assets needed to publish **Juggling Tracker** to the Google Play Store.

## 1. Technical Requirements

- [ ] **Release Signing**: You must generate a upload keystore to sign your App Bundle.
    - Run: `keytool -genkey -v -keystore main.keystore -alias main -keyalg RSA -keysize 2048 -validity 10000`
    - **Note**: Never commit the keystore file to git.
- [ ] **Build App Bundle**: Generate the `.aab` file for upload.
    - Run: `cd android && ./gradlew bundleRelease`
- [ ] **Versioning**: Nothing to edit by hand. `versionName` comes from the release tag and `versionCode` from the git commit count (see section 6).

## 2. Store Listing Assets

- **App Icon**: 512x512 PNG. Render the `PlayStoreIconPreview` Compose preview in `app/src/main/java/com/juggling/tracker/ui/IconPreview.kt` and export it from Android Studio.
- **Feature Graphic**: 1024x500 PNG. Render the `FeatureGraphicPreview` preview in `app/src/main/java/com/juggling/tracker/ui/FeatureGraphicPreview.kt` the same way.
- **Screenshots**: At least 2 phone screenshots (portrait).
    - *Tip*: Take screenshots of the Tracker screen and the Graph view.
- **Short Description**: (Max 80 chars)
    Counts your juggling catches with a Garmin or Wear OS watch, or your phone.
- **Full Description**: (Max 4000 chars)
    Juggling Tracker counts your catches while you juggle, so you can stop counting in your head and see how your practice adds up over time.

    How it works
    Wear a Garmin or Wear OS watch on one wrist. The watch detects each catch made by that hand from its motion sensor and groups the catches into runs automatically. Because it counts one hand, a 20-catch cascade shows about 10. When you finish, the session goes to this phone app, which keeps your history.

    Key features
    * Automatic run and catch detection on the watch, for 3 to 9 balls
    * Live count, best run and session average on your wrist
    * Regularity score: how consistent your throws are from catch to catch
    * History charts, best and average per number of balls
    * Garmin watches (Forerunner, Fenix, Venu, Vivoactive, Instinct, Epix and more) via the Connect IQ app, and Wear OS watches via the companion watch app
    * No watch? Hold the phone in your counting hand and track a session with the phone's sensor
    * CSV export of your sessions

    Accuracy
    The count is often off by a catch or two per run, so use it for trends rather than to prove a new record. On our test set of 121 runs and about 3,000 catches from three jugglers, it is 92.7% accurate, with over- and undercounting roughly balanced.

    Help improve it
    Record mode lets you label runs with the number you counted yourself and email the recordings to the developer. Every recording makes detection better for everyone.

    Privacy
    Your history stays on your phone. The app uses Firebase Analytics and Crashlytics for usage stats and crash reports; you can turn this off in Settings.
    Source code: https://github.com/jumlauft/JugglingTracker

- *Note*: Keep the accuracy figure in step with the "All" row of the
  per-ball-count table in the root README.

## 3. Privacy Policy

Google Play requires a Privacy Policy hosted on a public URL.

The policy lives in `privacy_policy.md` at the repository root and covers the
phone app and both watch apps. Its GitHub link,
https://github.com/jumlauft/JugglingTracker/blob/main/privacy_policy.md, is the
one the Connect IQ listing uses. Update it in the same change whenever an app
starts collecting or sending something new.

## 4. Data Safety Form

In the Play Console, you will need to declare:
- **Location**: Used for Bluetooth (on Android < 12).
- **Device or other IDs**: The Firebase Analytics app instance ID (Analytics).
- **Advertising ID** (App content): "No". The manifest removes the AD_ID
  permissions that Firebase Analytics adds and turns off ad ID collection.
- **Crash logs** and **Diagnostics**: Collected by Firebase Crashlytics.
- **App interactions**: Firebase Analytics usage events.
- **Fitness info**: Session results and recordings.
- **Name**: Collected, optional, for app functionality. The juggler name set
  under Raw Data Recording is written into recordings, and "Send recordings by
  email" sends them to the developer address.

## 5. Deployment

1. Create a developer account at [play.google.com/console](https://play.google.com/console).
2. Create a new app and follow the "Initial setup" tasks.
3. Upload the `.aab` file to the "Internal Testing" or "Production" track.
4. Complete the Store Listing and Content Rating questionnaires.

## 6. Automated Release (CI)

`.github/workflows/release-android.yml` builds a signed App Bundle and uploads
it to the Play **internal testing** track whenever a tag matching `v*` is pushed.
To move that build to closed testing, run the **Promote Play release** workflow
(`.github/workflows/promote-play.yml`) from the Actions tab; it defaults to the
`ClosedTestingTrack` closed track. Promote to production by hand in the Play
Console.

### Cutting a release

```sh
git tag v1.2        # versionName is taken from the tag (the leading "v" is stripped)
git push origin v1.2
```

`versionCode` is derived automatically from the git commit count, so it always
increases. Nothing else needs editing in `build.gradle` for a release.

### One-time setup

**a. Upload keystore.** Generate it once (see section 1) and keep the file
safe outside the repo. Base64-encode it for the CI secret:

```sh
base64 -i main.keystore | tr -d '\n' | pbcopy   # macOS; paste into the secret
```

**b. First upload must be manual.** Google requires the very first release of a
new app to be uploaded through the Play Console by hand. The API — and therefore
this workflow — only works once the app already has one release on the track.

**c. Play service account.** In the Google Play Console under *Setup → API
access*, link a Google Cloud project and create a service account with the
*Release manager* role, then download its JSON key. This is the
`PLAY_SERVICE_ACCOUNT_JSON` secret.

### Required repository secrets

Add these under *Settings → Secrets and variables → Actions*:

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the upload keystore (step a). |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password. |
| `ANDROID_KEY_ALIAS` | Key alias (e.g. `main`). |
| `ANDROID_KEY_PASSWORD` | Key password. |
| `PLAY_SERVICE_ACCOUNT_JSON` | Service-account JSON key contents (step c). |

### Wear OS

The Wear OS watch app in `wearos/` is published in the same Play listing as
the phone app (same package, `com.juggling.tracker`, and the same upload key,
which the Data Layer needs). The release workflow uploads it to the Wear OS
internal testing track, `wear:internal`, in the same run. Its `versionCode` is
the phone's plus 1,000,000, so the two never collide.

One-time setup in the Play Console, before the first tag that includes it:

1. *Test and release → Advanced settings → Form factors*: add **Wear OS**.
   This creates the Wear OS tracks.
2. In the new Wear OS section, upload the first Wear OS bundle by hand to
   *Internal testing*, as for the phone app (step b). Build it with the
   signing env vars set: `cd wearos && ./gradlew bundleRelease`.
3. Add Wear OS screenshots (at least one, 384×384 or larger, round) to the
   store listing and accept the Wear OS review terms.
4. Testers get the watch app from the Play Store on the watch, or from the
   phone listing's "Available on more devices" section, once they are on
   the internal testing list.

### Garmin

The Connect IQ store has no publishing API, so the watch app is **not** part of
this pipeline. Build and submit it by hand following `connectiq/PUBLISHING.md`.
