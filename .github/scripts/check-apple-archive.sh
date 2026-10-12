#!/usr/bin/env bash
# Checks an .xcarchive of the iPhone app for what App Store Connect rejects an
# upload over: both privacy manifests, both app icons, the embedded Apple
# Watch app, the export compliance flag and the version numbers.
#
#   .github/scripts/check-apple-archive.sh path/to/JugglingTracker.xcarchive
set -euo pipefail

archive="$1"
app="$archive/Products/Applications/JugglingTracker.app"
watch="$app/Watch/JugglingTrackerWatch.app"
failed=0

plist() { /usr/libexec/PlistBuddy -c "Print :$2" "$1/Info.plist" 2>/dev/null || true; }
# The asset catalog compiler writes the icon name in one of two places.
icon() { plist "$1" CFBundleIconName; plist "$1" CFBundleIcons:CFBundlePrimaryIcon:CFBundleIconName; }

need() {
  if [ -n "$2" ]; then echo "ok    $1: $2"; else echo "MISSING $1"; failed=1; fi
}

need "iPhone app" "$( [ -d "$app" ] && echo "$app" )"
need "Apple Watch app inside it" "$( [ -d "$watch" ] && echo "$watch" )"
need "iPhone privacy manifest" "$( [ -f "$app/PrivacyInfo.xcprivacy" ] && echo yes )"
need "Watch privacy manifest" "$( [ -f "$watch/PrivacyInfo.xcprivacy" ] && echo yes )"
need "iPhone icon" "$(icon "$app" | grep -m1 . || true)"
need "Watch icon" "$(icon "$watch" | grep -m1 . || true)"
need "iPhone version" "$(plist "$app" CFBundleShortVersionString)"
need "iPhone build" "$(plist "$app" CFBundleVersion)"
need "Watch build" "$(plist "$watch" CFBundleVersion)"
need "Export compliance flag" "$( [ "$(plist "$app" ITSAppUsesNonExemptEncryption)" = "false" ] && echo false )"

if [ "$(plist "$app" CFBundleVersion)" != "$(plist "$watch" CFBundleVersion)" ]; then
  echo "MISMATCH iPhone and Watch build numbers"; failed=1
fi
if [ "$(plist "$app" CFBundleShortVersionString)" != "$(plist "$watch" CFBundleShortVersionString)" ]; then
  echo "MISMATCH iPhone and Watch versions"; failed=1
fi

exit "$failed"
