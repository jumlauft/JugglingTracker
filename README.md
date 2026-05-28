# JugglingTracker IMU Stream

This workspace contains a Garmin Connect IQ watch application and an Android companion skeleton for streaming IMU data from a Garmin Forerunner 245 to an Android phone.

## Project structure

- `connectiq/`
  - `project.xml` — Connect IQ project definition for the watch app
  - `source/Main.mc` — Monkey C app implementation for the Forerunner 245
  - `resources/strings.xml` — localized app strings

- `android/`
  - Android app skeleton for receiving the Garmin Connect IQ app messages
  - `android/app/src/main/java/com/jugglingtracker/imu/MainActivity.kt`
  - `android/app/src/main/AndroidManifest.xml`

## Watch app behavior

The Forerunner 245 app:

- registers accelerometer and gyroscope listeners
- batches IMU samples every 250 ms
- sends payloads to the phone using Connect IQ `sendAppMessage`
- listens for simple `start` / `stop` commands from the phone

## Android companion app

The Android side is a skeleton that should be completed with Garmin Connect IQ Mobile SDK integration.

### Next steps

1. Install Garmin Connect IQ SDK and the Android SDK.
2. Open `android/` in Android Studio.
3. Add Garmin Connect IQ Android SDK dependency in `android/app/build.gradle`.
4. Update `MainActivity.kt` to initialize `ConnectIQ`, discover the paired Forerunner 245, and register a message listener.
5. Pair the watch with the phone and run the watch app from the Connect IQ simulator or device.

## Notes

- The Android app uses placeholder Connect IQ code patterns. Replace the commented `TODO` blocks with the Garmin SDK APIs for your installed version.
- The watch app source uses generic `Sensors` and `Communications` APIs, which may require minor adjustment depending on your SDK version.
