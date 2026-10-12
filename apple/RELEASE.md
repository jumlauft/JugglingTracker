# Releasing the iPhone app (with the Apple Watch app)

The iPhone app embeds the Apple Watch app, so one upload ships both. Uploads
go through `.github/workflows/release-apple.yml`, started by hand. The store
text, screenshots and privacy answers are in `APP_STORE.md`.

The project is release ready as far as it can be without a paid account:

- App icons for the iPhone and the Apple Watch (`Resources/Assets.xcassets`,
  `Watch/Assets.xcassets`, one 1024 px image each, no transparency).
- Privacy manifests (`PrivacyInfo.xcprivacy` in both apps), matching the App
  Privacy answers.
- `ITSAppUsesNonExemptEncryption = false`, so no export questions per build.
- Version from the workflow input, build number from the git commit count,
  the same scheme as the Android release.
- The Apple CI's "App Store archive" job archives the app unsigned and checks
  the archive with `.github/scripts/check-apple-archive.sh`.

## One-time setup (Jonas)

1. Join the Apple Developer Program (developer.apple.com/programs, 99 USD a
   year). Note the Team ID under Membership details.
2. In App Store Connect > Apps, create the app: platform iOS, name
   "Juggling Tracker", bundle ID com.juggling.tracker (register it under
   Certificates, Identifiers & Profiles first if it is not offered, plus
   com.juggling.tracker.watchkitapp with the HealthKit capability), SKU
   `juggling-tracker-ios`.
3. In App Store Connect > Users and Access > Integrations > App Store Connect
   API, create a team key with the App Manager role. Download the .p8 file
   (only possible once) and note the Key ID and Issuer ID.
4. Add these repository secrets (GitHub > Settings > Secrets and variables >
   Actions):

   | Secret | Value |
   | --- | --- |
   | `APPLE_TEAM_ID` | Team ID from step 1 |
   | `APP_STORE_CONNECT_KEY_ID` | Key ID from step 3 |
   | `APP_STORE_CONNECT_ISSUER_ID` | Issuer ID from step 3 |
   | `APP_STORE_CONNECT_KEY_P8` | Full text of the .p8 file |
   | `GOOGLE_SERVICE_INFO_PLIST` | Optional: `base64 -i GoogleService-Info.plist` |

   Without the Firebase secret the app ships with crash reporting off.
5. Fill in the listing, App Privacy and age rating from `APP_STORE.md`.

## Each release

1. Actions > "Release iPhone app to TestFlight" > Run workflow, with the
   version (for example `1.0`).
2. When the build has been processed it shows up in TestFlight. Test it on a
   real iPhone with a Garmin watch and with an Apple Watch.
3. In App Store Connect, add the build to the version, add screenshots, and
   submit for review.

## Building on the Mac instead

Xcode > Product > Archive with your team selected under Signing &
Capabilities for both targets, then Distribute App > App Store Connect.
