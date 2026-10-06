#!/usr/bin/env bash
# Waits for a freshly booted emulator to settle before instrumented tests run.
#
# sys.boot_completed turns 1 well before a cold-booted Wear OS image is quiet:
# for a while after it, system windows (the keyguard, the home screen starting
# up, "isn't responding" dialogs from slow system apps) come and go and take
# window focus. Tests that need their activity to hold focus (Espresso's
# pressBack) then time out. Waits until window focus has stayed on the same
# window for several polls in a row. Never fails the job: if the emulator does
# not settle in time, the tests run anyway and report what they find.
set -u

stable_polls_needed=5   # 5 polls x 2 s = 10 s without a focus change
deadline=$((SECONDS + 180))

adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

last=""
stable=0
while [ "$SECONDS" -lt "$deadline" ]; do
  focus=$(adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' | tr -d '\r' | sed 's/^ *//')
  case "$focus" in
    ''|*=null*|*'Not Responding'*|*'isn'"'"'t responding'*|*Error*|*Keyguard*|*NotificationShade*)
      # Nothing focused yet, or a system window has it: close system dialogs
      # and keep waiting.
      echo "Waiting for the emulator to settle: ${focus:-no focus}"
      adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
      adb shell wm dismiss-keyguard >/dev/null 2>&1
      stable=0
      ;;
    "$last")
      stable=$((stable + 1))
      ;;
    *)
      echo "Focus: $focus"
      stable=0
      ;;
  esac
  last="$focus"
  if [ "$stable" -ge "$stable_polls_needed" ]; then
    echo "Emulator settled after ${SECONDS}s: $focus"
    exit 0
  fi
  sleep 2
done

echo "::warning::Emulator did not settle within 180 s (last focus: ${last:-none}); running the tests anyway."
exit 0
