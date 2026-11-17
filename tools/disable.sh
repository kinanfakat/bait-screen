#!/bin/bash
# Disables (never uninstalls) the given packages, logs each one for restore.sh, then checks the TV still answers.
# Usage: tools/disable.sh <tv-ip>[:port] <backup-folder> <package>...
set -e
. "$(dirname "$0")/env.sh"; tv_addr "$1"; OUT="$2"; shift 2
for p in "$@"; do
  if adb -s "$D" shell pm disable-user --user 0 "$p" | grep -q "disabled-user"; then
    echo "$p" >> "$OUT/disabled-by-us.txt"; echo "✅ $p"
  else
    echo "⚠️ $p (not changed)"
  fi
done
sleep 2
if [ "$(adb -s "$D" shell echo ok 2>/dev/null | tr -d '\r')" = "ok" ]; then
  echo "TV is answering"
else
  echo "⛔ TV stopped answering - run $OUT/restore.sh"
fi
