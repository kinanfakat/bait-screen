# Shared by the tools: the TV's adb address, Java 17 and the Android SDK, on Windows (Git Bash), macOS and Linux.
# Usage inside a tool: . "$(dirname "$0")/env.sh"; tv_addr "$1"
export MSYS_NO_PATHCONV=1   # Git Bash would otherwise turn /sdcard/... into C:/Program Files/Git/sdcard/...

# "192.168.1.20" → 192.168.1.20:5555; "192.168.1.20:37415" (wireless debugging's own port) is kept as is
tv_addr() {
  case "$1" in
    "") echo "usage: $0 <tv-ip>[:port] ..." >&2; exit 1 ;;
    *:*) D="$1" ;;
    *) D="$1:5555" ;;
  esac
  IP="${D%:*}"
  command -v adb >/dev/null || { echo "adb is not installed or not on PATH" >&2; exit 1; }
  adb connect "$D" >/dev/null 2>&1 || true
  [ "$(adb -s "$D" get-state 2>/dev/null)" = "device" ] || { echo "⛔ the TV at $D does not answer adb (is debugging allowed on the TV?)" >&2; exit 1; }
}

# Java 17 with jlink (the Android Gradle plugin needs it); Homebrew's openjdk@17 is keg-only
if [ -z "${JAVA_HOME:-}" ]; then
  JH="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  [ -n "$JH" ] && [ ! -x "$JH/bin/jlink" ] && JH=""
  for d in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17; do [ -z "$JH" ] && [ -d "$d" ] && JH="$d"; done
  [ -z "$JH" ] && JH="$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')"
  [ -n "$JH" ] && export JAVA_HOME="$JH"
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

# the Android SDK: Android Studio's place on Windows, macOS, Linux, then Homebrew's command-line tools
if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "${LOCALAPPDATA:-/nonexistent}/Android/Sdk" ~/Library/Android/sdk ~/Android/Sdk \
           /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
    [ -d "$d" ] && { export ANDROID_HOME="$d"; break; }
  done
fi

# writes local.properties for the Gradle project in the current folder (forward slashes: a backslash is an escape)
sdk_props() {
  [ -f local.properties ] && return
  [ -n "${ANDROID_HOME:-}" ] || { echo "⛔ Android SDK not found: install it (sdkmanager) or set ANDROID_HOME" >&2; exit 1; }
  echo "sdk.dir=$(printf '%s' "$ANDROID_HOME" | tr '\' '/')" > local.properties
}

PY="$(command -v python3 || command -v python || command -v py)"
