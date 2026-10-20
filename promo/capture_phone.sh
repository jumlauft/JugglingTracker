#!/bin/zsh
# Takes the iPhone screenshots (5-ball history, and its newest session's
# details) with the app's screenshot mode. Run from the build folder.
set -e
REPO=${0:A:h:h}
BUILD=$PWD
P=$(xcrun simctl list devices | grep "Promo iPhone" | grep -o "[0-9A-F-]\{36\}" | head -1 || true)
[ -z "$P" ] && P=$(xcrun simctl create "Promo iPhone" "iPhone 18 Pro" com.apple.CoreSimulator.SimRuntime.iOS-27-0)
(cd $REPO/apple && xcodegen generate -q && xcodebuild -project JugglingTracker.xcodeproj -scheme JugglingTracker \
  -destination "id=$P" -derivedDataPath $BUILD/dd CODE_SIGNING_ALLOWED=NO build > /dev/null)
xcrun simctl boot $P 2>/dev/null || true
xcrun simctl bootstatus $P -b > /dev/null
xcrun simctl status_bar $P override --time 9:41 --batteryLevel 100 --batteryState charged --wifiBars 3 --cellularBars 4
xcrun simctl install $P $BUILD/dd/Build/Products/Debug-iphonesimulator/JugglingTracker.app
B=com.juggling.tracker
# The app takes long to draw its first screen in the simulator: wait 50 s.
shot() { name=$1; shift; xcrun simctl terminate $P $B 2>/dev/null || true; xcrun simctl launch $P $B "$@" > /dev/null; sleep 50; xcrun simctl io $P screenshot shot_$name.png > /dev/null 2>&1; }
shot warmup -screenshots
shot overview5 -screenshots -screenshotBalls 5
shot details -screenshots -screenshotBalls 5 -screenshotDetails
rm -f shot_warmup.png
xcrun simctl shutdown $P
