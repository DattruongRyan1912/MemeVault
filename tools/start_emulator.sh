#!/usr/bin/env bash
set -euo pipefail

avd_name="${1:-MemeVault_API_35_Play}"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
emulator_bin="$(command -v emulator || true)"
if [[ -z "$emulator_bin" && -n "$sdk_root" && -x "$sdk_root/emulator/emulator" ]]; then
  emulator_bin="$sdk_root/emulator/emulator"
fi
[[ -x "$emulator_bin" ]] || { echo "Không tìm thấy emulator."; exit 1; }
command -v adb >/dev/null || { echo "Không tìm thấy adb."; exit 1; }

running_serial="$(adb devices | awk '$2 == "device" && $1 ~ /^emulator-/ { print $1; exit }')"
if [[ -n "$running_serial" ]]; then
  echo "Đã có emulator đang chạy: $running_serial"
  exit 0
fi

"$emulator_bin" -avd "$avd_name" \
  -no-snapshot-save \
  -no-boot-anim \
  -gpu host \
  >"${TMPDIR:-/tmp}/memevault-emulator.log" 2>&1 &

emulator_serial=""
for _ in $(seq 1 60); do
  emulator_serial="$(adb devices | awk '$2 == "device" && $1 ~ /^emulator-/ { print $1; exit }')"
  [[ -n "$emulator_serial" ]] && break
  sleep 2
done
[[ -n "$emulator_serial" ]] || { echo "Emulator không xuất hiện trong adb sau 120 giây."; exit 1; }

until [[ "$(adb -s "$emulator_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 2
done

adb -s "$emulator_serial" shell input keyevent 82 >/dev/null 2>&1 || true
echo "Emulator $avd_name đã sẵn sàng: $emulator_serial"
