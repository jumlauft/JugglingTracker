# Juggling Tracker Publishing Checklist

This document outlines the steps and assets needed to publish **Juggling Tracker** to the Google Play Store.

## 1. Technical Requirements

- [ ] **Release Signing**: You must generate a upload keystore to sign your App Bundle.
    - Run: `keytool -genkey -v -keystore main.keystore -alias main -keyalg RSA -keysize 2048 -validity 10000`
    - **Note**: Never commit the keystore file to git.
- [ ] **Build App Bundle**: Generate the `.aab` file for upload.
    - Run: `cd android && ./gradlew bundleRelease`
- [ ] **Versioning**: Ensure `versionCode` and `versionName` in `android/app/build.gradle` are incremented for every new release.

## 2. Store Listing Assets

- **App Icon**: 512x512 PNG (Generated: `android/play_store_icon.png`).
- **Feature Graphic**: 1024x500 PNG (Generated: `android/play_store_feature_graphic.png`).
- **Screenshots**: At least 2 phone screenshots (portrait).
    - *Tip*: Take screenshots of the Tracker screen and the Graph view.
- **Short Description**: (Max 80 chars)
    Track your juggling progress with automatic catch detection.
- **Full Description**: (Max 4000 chars)
    Juggling Tracker is the ultimate companion for jugglers looking to quantify their practice.

    Key Features:
    * Automated Detection: Use your phone's sensors to track catches and runs automatically. 
    * Visualization: View your progress over time with history graphs.
    * Detailed Analytics: Track average throws, best runs, and consistency across different ball counts.
    * Garmin Integration: If you don't want to wear the phone on your wrist, use a garmin watch and sync your sessions seamlessly. 

    Whether you're working on your first 3-ball cascade or pushing for a new 7-ball record, Juggling Tracker helps you stay motivated and see your improvement.



## 3. Privacy Policy

Google Play requires a Privacy Policy hosted on a public URL.

### Template
> **Privacy Policy for Juggling Tracker**
> 
> Juggling Tracker ("the App") is provided as-is. 
> 
> **Data Collection:**
> - **Sensor Data**: The App processes accelerometer data to detect juggling catches. This data is processed locally on your device.
> - **Usage Data**: We use Firebase Analytics and Crashlytics to monitor app performance and improve the user experience. This may include anonymized device information and crash reports.
> - **Bluetooth/Location**: The App requires Bluetooth permissions to communicate with Garmin watches. On some Android versions, this requires Location access, but the App does NOT track or store your physical location.
> 
> **Data Storage:**
> Your juggling sessions are stored locally on your device. We do not upload your personal juggling history to our servers.
> 
> **Third Parties:**
> The App uses Google Firebase services. You can find their privacy policy here: [https://policies.google.com/privacy](https://policies.google.com/privacy)

## 4. Data Safety Form

In the Play Console, you will need to declare:
- **Location**: Used for Bluetooth (on Android < 12).
- **Device Identifiers**: Used by Firebase for analytics.
- **Crash logs**: Collected by Firebase Crashlytics.

## 5. Deployment

1. Create a developer account at [play.google.com/console](https://play.google.com/console).
2. Create a new app and follow the "Initial setup" tasks.
3. Upload the `.aab` file to the "Internal Testing" or "Production" track.
4. Complete the Store Listing and Content Rating questionnaires.

## 6. Automated Release (CI)

`.github/workflows/release-android.yml` builds a signed App Bundle and uploads
it to the Play **internal testing** track whenever a tag matching `v*` is pushed.
Promote a build from internal to production by hand in the Play Console.

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
