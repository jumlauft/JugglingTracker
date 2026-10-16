#!/bin/zsh
# Films the Apple Watch app counting a real 5-ball run in the simulator.
# Needs the watch app built with the replay mode (apple/Watch/ReplaySource.swift).
# Usage: capture_watch.sh <samples to replay>   (run from the build folder)
set -e
REPO=${0:A:h:h}
BUILD=$PWD
SAMPLES=${1:-182}
[ -x frames ] || swiftc -O $REPO/promo/frames.swift -o frames
python3 $REPO/promo/make_replay.py $REPO/connectiq/data/20260924_170352.csv $SAMPLES replay.csv
W=$(xcrun simctl list devices | grep "Promo Watch" | grep -o "[0-9A-F-]\{36\}" | head -1 || true)
[ -z "$W" ] && W=$(xcrun simctl create "Promo Watch" "Apple Watch Series 12 (46mm)" com.apple.CoreSimulator.SimRuntime.watchOS-27-0)
(cd $REPO/apple && xcodegen generate -q && xcodebuild -project JugglingTracker.xcodeproj -scheme JugglingTrackerWatch \
  -destination "id=$W" -derivedDataPath $BUILD/dd CODE_SIGNING_ALLOWED=NO build > /dev/null)
APP=$BUILD/dd/Build/Products/Debug-watchsimulator/JugglingTrackerWatch.app
xcrun simctl boot $W 2>/dev/null || true
xcrun simctl bootstatus $W -b > /dev/null
sleep 20
xcrun simctl install $W $APP
# A first launch after install is slow; warm up once.
xcrun simctl launch $W com.juggling.tracker.watchkitapp -screenshot mode > /dev/null
sleep 25
xcrun simctl terminate $W com.juggling.tracker.watchkitapp || true
sleep 2
xcrun simctl io $W recordVideo --codec h264 -f --force watch.mov > /dev/null 2>&1 &
RP=$!
sleep 2
xcrun simctl launch $W com.juggling.tracker.watchkitapp -replay $PWD/replay.csv -replayBalls 5 > /dev/null
sleep 26
kill -INT $RP
wait $RP || true
xcrun simctl shutdown $W
./frames watch.mov fr_watch 30
python3 $REPO/promo/count_steps.py fr_watch
