#!/usr/bin/env bash
set -euo pipefail

emulator_serial="${ANDROID_SERIAL:-}"
if [[ -z "$emulator_serial" ]]; then
  emulator_serials="$(adb devices | awk '$2 == "device" && $1 ~ /^emulator-/ { print $1 }')"
  emulator_count="$(printf '%s\n' "$emulator_serials" | awk 'NF { count++ } END { print count+0 }')"
  [[ "$emulator_count" -eq 1 ]] || {
    echo "Cần đúng một emulator đang chạy; hoặc đặt ANDROID_SERIAL=emulator-xxxx."
    exit 1
  }
  emulator_serial="$emulator_serials"
fi
[[ "$emulator_serial" == emulator-* ]] || {
  echo "Không tìm thấy emulator. Chạy tools/start_emulator.sh trước."
  exit 1
}
adb -s "$emulator_serial" get-state >/dev/null

describe_package() {
  label="$1"
  package_name="$2"
  if adb -s "$emulator_serial" shell pm path "$package_name" >/dev/null 2>&1; then
    version_name="$(adb -s "$emulator_serial" shell dumpsys package "$package_name" | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')"
    echo "$label: INSTALLED (${version_name:-không đọc được version}, $package_name)"
  else
    echo "$label: NOT_INSTALLED ($package_name)"
  fi
}

describe_package "Messenger" "com.facebook.orca"
describe_package "Zalo" "com.zing.zalo"
describe_package "TikTok" "com.zhiliaoapp.musically"

if [[ "${1:-}" == "--open" ]]; then
  case "${2:-}" in
    messenger) package_name="com.facebook.orca" ;;
    zalo) package_name="com.zing.zalo" ;;
    tiktok) package_name="com.zhiliaoapp.musically" ;;
    *) echo "Dùng: $0 --open messenger|zalo|tiktok"; exit 2 ;;
  esac
  adb -s "$emulator_serial" shell am start \
    -a android.intent.action.VIEW \
    -d "market://details?id=$package_name" \
    com.android.vending >/dev/null
  echo "Đã mở listing Play Store chính thức cho $package_name trên $emulator_serial."
  echo "Nếu Play Store yêu cầu đăng nhập, hãy dùng một tài khoản test riêng."
fi
