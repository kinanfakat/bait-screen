#!/bin/bash
# Builds شاشة البيت's home screen: CleanYourTV's launcher at a known commit, with this project's changes on top,
# and installs it on the TV with CleanYourTV's own installer (which also makes it the home screen).
# Usage: tools/install-launcher.sh <tv-ip>[:port] [cleanyourtv folder, default ~/cleanyourtv]
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
. "$HERE/tools/env.sh"; tv_addr "$1"
C="${2:-$HOME/cleanyourtv}"
BASE="$(tr -d '\r\n ' < "$HERE/launcher/BASE_COMMIT")"
[ -d "$C/.git" ] || git clone https://github.com/Gathin23/cleanyourtv "$C"
cd "$C"
if ! grep -q "themePlace" launcher/app/src/main/java/com/gathin/tvhome/NoorPrefs.java 2>/dev/null; then
  if [ -n "$(git status --porcelain)" ]; then
    echo "⛔ $C has local changes. Move it aside (or pass another folder as the second argument) and run again."; exit 1
  fi
  git cat-file -e "$BASE^{commit}" 2>/dev/null || git fetch -q origin
  git checkout -q "$BASE"
  git apply --whitespace=nowarn "$HERE/launcher/cleanyourtv.patch"
  echo "✅ home screen changes applied"
fi
[ -n "$PY" ] || { echo "⛔ Python is not installed"; exit 1; }
if [ ! -d tools/venv ]; then
  "$PY" -m venv tools/venv
  VPIP=tools/venv/bin/pip; [ -x "$VPIP" ] || VPIP=tools/venv/Scripts/pip.exe
  "$VPIP" -q install -r tools/requirements.txt
fi
cd launcher
sdk_props
TV_HOST="$D" bash build-and-install.sh
echo "✅ home screen installed"
