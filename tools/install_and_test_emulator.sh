#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

emulator_serial="${ANDROID_SERIAL:-}"
if [[ -z "$emulator_serial" ]]; then
  mapfile_output="$(adb devices | awk '$2 == "device" && $1 ~ /^emulator-/ { print $1 }')"
  emulator_count="$(printf '%s\n' "$mapfile_output" | awk 'NF { count++ } END { print count+0 }')"
  [[ "$emulator_count" -eq 1 ]] || {
    echo "Cần đúng một emulator đang chạy; hoặc đặt ANDROID_SERIAL=emulator-xxxx."
    exit 1
  }
  emulator_serial="$mapfile_output"
fi
[[ "$emulator_serial" == emulator-* ]] || { echo "ANDROID_SERIAL phải là emulator-*; không chạy test này trên điện thoại thật."; exit 1; }
adb -s "$emulator_serial" get-state >/dev/null
export ANDROID_SERIAL="$emulator_serial"

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
adb -s "$emulator_serial" install -r -t app/build/outputs/apk/debug/app-debug.apk

adb -s "$emulator_serial" logcat -c
launch_output="$(adb -s "$emulator_serial" shell am start -W -n com.memevault.app/.MainActivity)"
printf '%s\n' "$launch_output"
grep -Fq "Status: ok" <<<"$launch_output" || { echo "MemeVault không cold-start thành công."; exit 1; }
adb -s "$emulator_serial" shell pidof com.memevault.app >/dev/null || { echo "Process MemeVault không còn chạy sau cold start."; exit 1; }
if adb -s "$emulator_serial" logcat -d -b crash -t 200 | grep -Fq "com.memevault.app"; then
  echo "Phát hiện crash MemeVault trong crash buffer."
  exit 1
fi

echo "Đã chạy instrumentation, cài lại và smoke-test MemeVault trên $emulator_serial."
echo "Cài Messenger/Zalo/TikTok từ Play Store trong emulator rồi chạy checklist docs/emulator-host-app-testing.md."
