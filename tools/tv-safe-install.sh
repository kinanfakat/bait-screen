#!/bin/bash
# Installs one Noor flavor on the TV only when that will not cut something the family is doing:
# an update of the main app (kahf) closes any open Noor game and stops a playing adhan.
# Usage: tools/tv-safe-install.sh <tv-ip> <flavor> [--force]
set -e
. "$(dirname "$0")/env.sh"; tv_addr "$1"; F="$2"
[ -n "$1" ] && [ -n "$2" ] || { echo "usage: $0 <tv-ip> <flavor> [--force]"; exit 1; }

front=$(adb -s "$D" shell "dumpsys activity activities | grep mResumedActivity" | tr -d '\r')
awake=$(adb -s "$D" shell "dumpsys power | grep mWakefulness=" | tr -d '\r ')
if [ "$3" != "--force" ] && [ "$awake" = "mWakefulness=Awake" ]; then
  case "$front" in
    *app.noortv.*Quiz*|*app.noortv.*Teams*|*app.noortv.*Who*|*app.noortv.*Charades*|*app.noortv.*Memory*|*app.noortv.*Games*|*AdhanScreen*)
      echo "The TV is in use ($front). Not installing; pass --force to install anyway."; exit 2;;
  esac
fi
cd "$(dirname "$0")/../noor-app"
adb -s "$D" install -r "app/build/outputs/apk/$F/release/app-$F-release.apk"
if [ "$F" = "kahf" ]; then
  sleep 6   # Android restarts the app right after an update; the adhan is scheduled again once that has settled
  adb -s "$D" shell am broadcast -n app.noortv.kahf/app.noortv.Reminder >/dev/null
  echo "adhan rescheduled"
fi
