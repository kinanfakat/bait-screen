#!/bin/bash
# Saves everything needed to undo: device info, the full app list, what is already disabled, and the settings.
# Usage: tools/backup.sh <tv-ip>[:port]     → ~/bait-screen-backup-<date>/
set -e
. "$(dirname "$0")/env.sh"; tv_addr "$1"
OUT=~/bait-screen-backup-$(date +%Y%m%d-%H%M); mkdir -p "$OUT"
adb -s "$D" shell getprop > "$OUT/props.txt"
adb -s "$D" shell pm list packages | sed 's/package://' | sort > "$OUT/packages-all.txt"
adb -s "$D" shell pm list packages -d | sed 's/package://' | sort > "$OUT/packages-disabled.txt"
for ns in system secure global; do adb -s "$D" shell settings list $ns > "$OUT/settings-$ns.txt"; done
: > "$OUT/disabled-by-us.txt"
cat > "$OUT/restore.sh" <<R
#!/bin/bash
# Turns back on every app this setup disabled.
adb connect "$D" >/dev/null 2>&1 || true
while read -r p; do p="\${p%\$'\r'}"; [ -n "\$p" ] && adb -s "$D" shell pm enable --user 0 "\$p"; done < "$OUT/disabled-by-us.txt"
R
chmod +x "$OUT/restore.sh"
echo "$OUT"
