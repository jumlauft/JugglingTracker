# Juggling Tracker Garmin Publishing Checklist

## 1. Technical Requirements

- [ ] **Developer Key**: Ensure you have `connectiq/developer_key.der`. 
    - *Note*: If you lose this, you cannot update your app in the store.
- [ ] **Build App Package**: Generate the `.iq` file for upload.
    - Run: `monkeyc -e -f connectiq/monkey.jungle -o connectiq/JugglingTracker.iq -y connectiq/developer_key.der`
- [ ] **Versioning**: Increment `version` in `connectiq/manifest.xml` for every new release.

## 2. Store Listing Assets


- **Ttitle**: (Max 50 chars)
- **Description**: (Max 4000 chars)
Track your juggling runs automatically based on the movements on your wrist. 

How it works:
Using advanced accelerometer analysis, the app detects each catch made by the hand wearing the watch. It automatically groups catches into runs and sessions, allowing you to focus entirely on your patterns.

Key Features:
* Automatic run detection: Once you started your session, the app will automatically detect the start and finish of each run
* Automatic catch detection: Catches in the hand wearing the watch will be detected automatically.
* Live Feedback: See your current run count and session best at a glance.
* Optimized for performance: Low-power sensor monitoring ensures your battery lasts through even the longest practice sessions.
* Seamless Sync: Transmits finished sessions to the Juggling Tracker Android app for long-term visualization and history. (It can also be used standalone).  
* Record labeled data and send to developers (via Android App) to detection precision in future releases.

E-Mail Address: juggling.tracker@gmail.com
Source Code URL (Optional): https://github.com/jumlauft/JugglingTracker


- **Privacy Policy URL**:
    https://github.com/jumlauft/JugglingTracker/blob/main/privacy_policy.md
- **Visual Assets**:
    * **Cover Image**: 500x500 PNG (`connectiq/resources/images/cover_image.png`)
    * **App Store Icon (64 Color)**: 128x128 PNG (`connectiq/resources/images/app_store_icon_128_64color.png`)
    * **App Store Icon (24-bit Color)**: 128x128 PNG (`connectiq/resources/images/app_store_icon_128_24bit.png`)
    * **Screenshots**: Found in `connectiq/resources/images/`


