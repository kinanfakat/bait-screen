#!/bin/bash
# Builds شاشة البيت's apps and installs them on the TV. The main app (kahf) carries the adhan, the reminders, the
# phone page and the memory guard, so it gets the overlay, usage-access and settings permissions and is started once.
# Usage: tools/install-noor.sh <tv-ip>[:port]
set -e
. "$(dirname "$0")/env.sh"; tv_addr "$1"
cd "$(dirname "$0")/../noor-app"
sdk_props
[ -f app/src/kahf/res/raw/adhan.mp3 ] || echo "ℹ️ no adhan recording (tools/fetch-adhan.sh): the adhan will show without sound"
bash ./gradlew -q assembleRelease
failed=""
for f in kahf baqara sabah masa ruqya yasin mulk radio wird nawm week hifz hadith status apps matches deen house games control; do
  apk="app/build/outputs/apk/$f/release/app-$f-release.apk"
  [ -f "$apk" ] || continue
  if out=$(adb -s "$D" install -r "$apk" 2>&1) && echo "$out" | grep -q Success; then
    echo "✅ $f"
  else
    echo "❌ $f: $(echo "$out" | tail -1)"; failed="$failed $f"
  fi
done
case " $failed " in *" kahf "*) echo "⛔ the main app (kahf) did not install; nothing else will work until it does"; exit 1;; esac
for op in SYSTEM_ALERT_WINDOW GET_USAGE_STATS WRITE_SETTINGS REQUEST_INSTALL_PACKAGES; do
  adb -s "$D" shell appops set app.noortv.kahf $op allow >/dev/null 2>&1 || echo "⚠️ could not allow $op"
done
# Android 12+: the exact alarm for the adhan (USE_EXACT_ALARM is granted at install; this covers older builds)
adb -s "$D" shell appops set app.noortv.kahf SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1 || true
# Android 13+: notifications for the background service
adb -s "$D" shell pm grant app.noortv.kahf android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
sleep 8 # Android restarts the app right after an update, so start it once that has settled
adb -s "$D" shell am broadcast -n app.noortv.kahf/app.noortv.Reminder >/dev/null
[ -n "$failed" ] && echo "⚠️ not installed:$failed (often: not enough storage on the TV)"
echo "Phone page: http://$IP:8080  (the code to enter shows on the TV)"
