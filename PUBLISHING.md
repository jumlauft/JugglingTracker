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

- [x] **App Icon**: 512x512 PNG (Generated: `android/play_store_icon.png`).
- [x] **Feature Graphic**: 1024x500 PNG (Generated: `android/play_store_feature_graphic.png`).
- [ ] **Screenshots**: At least 2 phone screenshots (portrait).
    - *Tip*: Take screenshots of the Tracker screen and the Graph view.
- [ ] **Short Description**: (Max 80 chars)
    > Track your juggling progress with Garmin and phone sensors. Master your flow!
- [ ] **Full Description**: (Max 4000 chars)
    > Juggling Tracker is the ultimate companion for jugglers looking to quantify their practice. 
    > 
    > **Key Features:**
    > - **Garmin Integration**: Sync catches automatically from your Garmin watch.
    > - **Phone Tracking**: Use your phone's sensors to track runs when you're not wearing a watch.
    > - **Visualization**: View your progress over time with beautiful history graphs.
    > - **Voice Feedback**: Get real-time catch count announcements so you can keep your eyes on the balls.
    > - **Detailed Analytics**: Track average throws, best runs, and consistency across different ball counts.
    > 
    > Whether you're working on your first 3-ball cascade or pushing for a new 7-ball record, Juggling Tracker helps you stay motivated and see your improvement.

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
