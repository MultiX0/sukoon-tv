#!/usr/bin/env bash
# Cleans an Android TV box over ADB and makes Sukoon the home screen.
# Nothing is uninstalled: every package is disabled for user 0 and restore.sh turns it back on.
#
#   ./debloat.sh                 # back up, install Sukoon, disable packages.txt (+ devices/<model>.txt), faster animations
#   ./debloat.sh --dry-run       # print what would change
#   ADB=/path/to/adb ./debloat.sh
set -euo pipefail

cd "$(dirname "$0")"
ADB="${ADB:-adb}"
APK="${APK:-../launcher/dist/sukoon-launcher.apk}"
HOME_ACTIVITY="${HOME_ACTIVITY:-dev.multix.tvhome/.HomeActivity}"
LISTENER="${LISTENER:-dev.multix.tvhome/dev.multix.tvhome.media.NowPlayingListener}"
ANIM="${ANIM:-0.5}"
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

# adb shell reads stdin, so every call gets /dev/null (otherwise it eats the rest of a list being read)
sh_() { "$ADB" shell "$@" </dev/null | tr -d '\r'; }
run() { if [ $DRY = 1 ]; then echo "  would run: $*"; else "$@" </dev/null; fi; }
HOME_Q=(cmd package query-activities --brief -c android.intent.category.HOME -a android.intent.action.MAIN)
HOME_R=(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN)

if [ -n "${ANDROID_SERIAL:-}" ]; then
  "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -qx "$ANDROID_SERIAL" || { echo "$ANDROID_SERIAL is not connected."; exit 1; }
else
  n=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l)
  [ "$n" = 1 ] || { echo "Connect exactly one TV with debugging on (found $n), or pick one: ANDROID_SERIAL=<id> ./debloat.sh"; exit 1; }
fi
serial=$("$ADB" get-serialno | tr -d '\r')
echo "Device: $(sh_ getprop ro.product.model) ($(sh_ getprop ro.build.version.release))"

mem() { sh_ cat /proc/meminfo | awk '/MemAvailable/ {printf "%d MB", $2/1024}'; }
procs() { sh_ ps -A | wc -l; }
echo "Before: $(mem) free, $(procs) processes"

# 1. Backup, once per device: what was enabled, the home screen and the animation speeds.
dir="backup/$serial"
if [ ! -f "$dir/enabled.txt" ]; then
  mkdir -p "$dir"
  sh_ pm list packages -e | sed 's/package://' | sort > "$dir/enabled.txt"
  sh_ pm list packages -d | sed 's/package://' | sort > "$dir/disabled.txt"
  sh_ cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1 > "$dir/home.txt"
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    echo "$k=$(sh_ settings get global $k)"
  done > "$dir/settings.txt"
  sh_ settings get secure default_input_method > "$dir/ime.txt"
  echo "Backup written to scripts/$dir"
fi

# 2. Sukoon first, so the box always has a home screen. Without a local build, the latest release is downloaded.
if [ ! -f "$APK" ] && command -v curl >/dev/null; then
  APK="$(mktemp -d)/sukoon-launcher.apk"
  echo "Downloading the latest Sukoon release"
  curl -fsSL -o "$APK" "https://github.com/MultiX0/sukoon-tv/releases/latest/download/sukoon-launcher.apk" || rm -f "$APK"
fi
[ -f "$APK" ] && { echo "Installing Sukoon"; run "$ADB" install -r "$APK" >/dev/null; }
if sh_ pm list packages "${HOME_ACTIVITY%%/*}" | grep -q .; then
  run "$ADB" shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null
  run "$ADB" shell cmd notification allow_listener "$LISTENER" >/dev/null || true
  # Permissions ADB can grant without root (skip them with GRANTS=0; restore.sh takes them back):
  #   DUMP: low-RAM builds ignore notification listeners, so Sukoon reads "dumpsys media_session" for now playing
  #   WRITE_SECURE_SETTINGS: lets Sukoon set itself as the screensaver and change animation speed from its settings
  #   GET_USAGE_STATS: orders apps by recent use
  if [ "${GRANTS:-1}" = 1 ]; then
    pkg="${HOME_ACTIVITY%%/*}"
    run "$ADB" shell pm grant "$pkg" android.permission.DUMP || true
    run "$ADB" shell pm grant "$pkg" android.permission.WRITE_SECURE_SETTINGS || true
    run "$ADB" shell appops set "$pkg" GET_USAGE_STATS allow || true
    # RECORD_AUDIO lets the lyrics view pulse with the music (Visualizer); it never records anything.
    run "$ADB" shell pm grant "$pkg" android.permission.RECORD_AUDIO || true
    # Photos in /sdcard/Wallpapers become wallpaper sets.
    run "$ADB" shell pm grant "$pkg" android.permission.READ_EXTERNAL_STORAGE || true
    # Settings > About > Check for updates installs new releases (Android still asks before installing).
    run "$ADB" shell appops set "$pkg" REQUEST_INSTALL_PACKAGES allow || true
    # After such an update, lets the new version bring the home screen back by itself (Android 10).
    run "$ADB" shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow || true
  fi
else
  echo "Sukoon is not installed (no APK at $APK); the stock home screen stays on."
fi

# The TV keyboard takes over before the phone keyboard is turned off.
TV_IME=com.google.android.leanback.ime/com.google.leanback.ime.LeanbackImeService
if sh_ pm list packages com.google.android.leanback.ime | grep -q ime; then
  run "$ADB" shell ime enable "$TV_IME" >/dev/null
  run "$ADB" shell ime set "$TV_IME" >/dev/null
fi

# 3. Disable the list: packages.txt for every TV, plus devices/<model>.txt for this model when there is one.
#    Packages this TV does not have are skipped.
model=$(sh_ getprop ro.product.model | tr ' ' '_')
LIST=$(cat packages.txt "devices/$model.txt" 2>/dev/null | grep -v '^\s*#' | sed 's/#.*//' | awk 'NF {print $1}')
[ -f "devices/$model.txt" ] && echo "Using the extra list for $model"
is_home_pkg() { [[ "$1" == *launcher* || "$1" == *setupwraith* ]]; }
installed=$(sh_ pm list packages | sed 's/package://')
# Sukoon's lyrics screensaver takes over before the ambient screensavers are turned off.
if sh_ pm list packages "${HOME_ACTIVITY%%/*}" | grep -q .; then
  run "$ADB" shell settings put secure screensaver_components "${HOME_ACTIVITY%%/*}/.LyricsDream" >/dev/null
fi
# The stock launchers declare a higher priority than any app, so set-home-activity alone does not win:
# they are disabled, but only when Sukoon is installed as a home screen.
home_ok=$(sh_ "${HOME_Q[@]}" | grep -c "^ *${HOME_ACTIVITY%%/*}/" || true)
for p in $LIST; do
  echo "$installed" | grep -qx "$p" || continue
  if [ "$home_ok" = 0 ] && is_home_pkg "$p"; then echo "  keep $p (no other home screen)"; continue; fi
  echo "  disable $p"
  run "$ADB" shell pm disable-user --user 0 "$p" >/dev/null
done

# Home must now open Sukoon. If anything else still wins, the stock launchers come back so the box keeps a home.
if [ $DRY = 0 ] && [ "$home_ok" != 0 ]; then
  run "$ADB" shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null
  now=$(sh_ "${HOME_R[@]}" | tail -1)
  if [ "${now%%/*}" = "${HOME_ACTIVITY%%/*}" ]; then
    echo "Home button now opens $now"
  else
    echo "Home still resolves to $now; turning the stock launchers back on."
    for p in $LIST; do
      is_home_pkg "$p" && "$ADB" shell pm enable --user 0 "$p" </dev/null >/dev/null 2>&1 || true
    done
  fi
fi

# 4. Animations at half length: the box feels twice as quick.
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  run "$ADB" shell settings put global "$k" "$ANIM"
done

[ $DRY = 1 ] && exit 0
"$ADB" shell input keyevent KEYCODE_HOME </dev/null
echo "After: $(mem) free, $(procs) processes (numbers settle after a reboot)"
