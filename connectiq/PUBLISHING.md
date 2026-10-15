# Juggling Tracker Garmin Publishing Checklist

## 1. Technical Requirements

- [ ] **Developer Key**: Ensure you have `connectiq/developer_key.der`. 
    - *Note*: If you lose this, you cannot update your app in the store.
- [ ] **Build App Package**: Generate the `.iq` file for upload.
    - Run: `monkeyc -e -f connectiq/monkey.jungle -o connectiq/JugglingTracker.iq -y connectiq/developer_key.der`
- [ ] **Versioning**: Increment `version` in `connectiq/manifest.xml` for every new release.

## 2. Store Listing Assets


- **Store listing**: https://apps.garmin.com/apps/0096c65a-510f-440a-948c-d61267d10998
    - *Note*: that URL id is the store's own listing id and is **not** the
      `id` in `manifest.xml`. Keep the manifest id stable — changing it makes
      Garmin treat the upload as a brand new app rather than an update, which
      orphans this listing along with its downloads and reviews.
- **Title**: Juggling Tracker  (Max 50 chars)
- **Description**: (Max 4000 chars)
Track your juggling runs automatically based on the movements on your wrist. 

How it works:
Using advanced accelerometer analysis, the app detects each catch made by the hand wearing the watch. It automatically groups catches into runs and sessions, allowing you to focus entirely on your patterns.

Key Features:
* Automatic run detection: Once you started your session, the app will automatically detect the start and finish of each run
* Automatic catch detection: Catches in the hand wearing the watch will be detected automatically.
* Live Feedback: See your current run count, best in session and session average at a glance. Also provides a regularity score for the cleanliness of your pattern
* Optimized for performance: Low-power sensor monitoring ensures your battery lasts through even the longest practice sessions.
* Seamless Sync: Transmits finished sessions to the Juggling Tracker Android app for long-term visualization and history. (It can also be used standalone).  
* Record labeled data and send to developers (via Android App) to improve detection precision in future releases.

Disclaimer: The counting will often be off by 1 or 2 catches, so it is not suitable to document your newest high-score, but rather works based on statistics. On our test set of 129 runs and about 3,200 catches from five jugglers, the count is 92.5% accurate, with slightly more undercounting than overcounting, so it definitely will give you a trend on how fast you are improving.

Call-for-action: Help to improve precision by running a "Record" session. Label the runs with the manually counted number of catches and send the data to the developer. We will use it to improve detection accuracy in future releases.

E-Mail Address: juggling.tracker@gmail.com
Source Code URL (Optional): https://github.com/jumlauft/JugglingTracker


- **Privacy Policy URL**:
    https://github.com/jumlauft/JugglingTracker/blob/main/privacy_policy.md
- **Visual Assets**:
    * **Cover Image**: 500x500 PNG (`connectiq/resources/images/cover_image.png`)
    * **App Store Icon (64 Color)**: 128x128 PNG (`connectiq/resources/images/app_store_icon_128_64color.png`)
    * **App Store Icon (24-bit Color)**: 128x128 PNG (`connectiq/resources/images/app_store_icon_128_24bit.png`)
    * **Screenshots**: Found in `connectiq/resources/images/`



## 3. Release Notes ("What's New")

Paste the entry for the version being uploaded into the store's "What's New"
field. Newest first.

### 1.3.0
- Now available on 13 more watches, including Forerunner 645 and 935, fēnix 5, 5S, 5X and Chronos, D2 Delta, Descent Mk1 and D2 Air.
- The Juggle screen now shows Regularity above Time, so Time is the bottom line.
- The session clock now pauses while the end-session menu is open, so time spent in the menu no longer counts toward your session.
- Better counting for 7 balls and more: the wind-up before a run and gathering the balls after a drop no longer start or stretch a run, so back-to-back runs split cleanly.
- Juggle mode no longer closes early when the phone confirms an older session; it waits for the confirmation of the session it just sent.
- Record mode: a run you skip after a failed sync is no longer counted under Runs.
- Internal clean-up for reliability, including freeing memory when you leave Record mode.
