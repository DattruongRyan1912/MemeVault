#!/usr/bin/env bash
set -euo pipefail

avd_name="MemeVault_API_35_Play"
system_image="system-images;android-35;google_apis_playstore;arm64-v8a"
device_profile="pixel_7"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
emulator_bin="$(command -v emulator || true)"
if [[ -z "$emulator_bin" && -n "$sdk_root" && -x "$sdk_root/emulator/emulator" ]]; then
  emulator_bin="$sdk_root/emulator/emulator"
fi

command -v sdkmanager >/dev/null || { echo "Không tìm thấy sdkmanager."; exit 1; }
command -v avdmanager >/dev/null || { echo "Không tìm thấy avdmanager."; exit 1; }
[[ -x "$emulator_bin" ]] || { echo "Không tìm thấy Android Emulator binary."; exit 1; }

if ! sdkmanager --list_installed | grep -Fq "$system_image"; then
  set +o pipefail
  yes | sdkmanager "$system_image"
  install_status="${PIPESTATUS[1]}"
  set -o pipefail
  [[ "$install_status" -eq 0 ]] || exit "$install_status"
fi

if "$emulator_bin" -list-avds | grep -Fxq "$avd_name"; then
  echo "AVD $avd_name đã tồn tại."
  exit 0
fi

printf 'no\n' | avdmanager create avd \
  --force \
  --name "$avd_name" \
  --package "$system_image" \
  --device "$device_profile"

echo "Đã tạo $avd_name. Chạy tools/start_emulator.sh để khởi động."
