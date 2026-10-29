#!/usr/bin/env bash
# Undoes debloat.sh: turns every package in packages.txt and devices/<model>.txt back on (except ones that were off before),
# puts the original home screen and animation speeds back. Add --remove-sukoon to uninstall Sukoon too.
set -euo pipefail

cd "$(dirname "$0")"
ADB="${ADB:-adb}"
sh_() { "$ADB" shell "$@" | tr -d '\r'; }

serial=$("$ADB" get-serialno | tr -d '\r')
dir="backup/$serial"
[ -f "$dir/enabled.txt" ] || echo "No backup for $serial; enabling the whole list and using defaults."

model=$("$ADB" shell getprop ro.product.model </dev/null | tr -d '' | tr ' ' '_')
for p in $(cat packages.txt "devices/$model.txt" 2>/dev/null | grep -v '^\s*#' | sed 's/#.*//' | awk 'NF {print $1}'); do
  if [ -f "$dir/disabled.txt" ] && grep -qx "$p" "$dir/disabled.txt"; then continue; fi
  echo "  enable $p"
  "$ADB" shell pm enable --user 0 "$p" >/dev/null 2>&1 || true
done

ime=$(cat "$dir/ime.txt" 2>/dev/null || echo com.android.inputmethod.latin/.LatinIME)
"$ADB" shell ime enable "$ime" >/dev/null 2>&1 || true
"$ADB" shell ime set "$ime" >/dev/null 2>&1 || true

home=$(cat "$dir/home.txt" 2>/dev/null || echo com.google.android.tvlauncher/.MainActivity)
"$ADB" shell cmd package set-home-activity "$home" >/dev/null || true

if [ -f "$dir/settings.txt" ]; then
  while IFS='=' read -r k v; do
    if [ "$v" = null ]; then "$ADB" shell settings delete global "$k" >/dev/null; else "$ADB" shell settings put global "$k" "$v"; fi
  done < "$dir/settings.txt"
else
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do "$ADB" shell settings put global "$k" 1.0; done
fi

for perm in DUMP WRITE_SECURE_SETTINGS RECORD_AUDIO READ_EXTERNAL_STORAGE; do
  "$ADB" shell pm revoke dev.multix.tvhome android.permission.$perm >/dev/null 2>&1 || true
done
"$ADB" shell appops set dev.multix.tvhome GET_USAGE_STATS default >/dev/null 2>&1 || true
"$ADB" shell appops set dev.multix.tvhome REQUEST_INSTALL_PACKAGES default >/dev/null 2>&1 || true
"$ADB" shell appops set dev.multix.tvhome SYSTEM_ALERT_WINDOW default >/dev/null 2>&1 || true
"$ADB" shell settings delete secure screensaver_components >/dev/null 2>&1 || true
[ "${1:-}" = "--remove-sukoon" ] && "$ADB" uninstall dev.multix.tvhome >/dev/null || true
"$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Restored. Reboot the box to finish: adb reboot"
