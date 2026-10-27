#!/usr/bin/env bash
set -euo pipefail

config_root="${XDG_CONFIG_HOME:-${HOME:?}/.config}/memevault"
properties_file="$config_root/signing.properties"
keystore_file="$config_root/memevault-release.jks"

umask 077
mkdir -p "$config_root"

if [[ -s "$properties_file" && -s "$keystore_file" ]]; then
  echo "Personal signing đã tồn tại tại $config_root"
  exit 0
fi

command -v keytool >/dev/null || {
  echo "Không tìm thấy keytool; hãy cài JDK 17."
  exit 1
}
command -v openssl >/dev/null || {
  echo "Không tìm thấy openssl để tạo mật khẩu ngẫu nhiên."
  exit 1
}

signing_password="$(openssl rand -hex 32)"
keytool -genkeypair \
  -keystore "$keystore_file" \
  -storepass "$signing_password" \
  -keypass "$signing_password" \
  -alias "memevault" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=MemeVault Personal, OU=Personal, O=MemeVault, L=Ho Chi Minh City, C=VN" \
  >/dev/null

temporary_properties="$(mktemp "$config_root/signing.properties.XXXXXX")"
{
  printf 'storeFile=%s\n' "$keystore_file"
  printf 'storePassword=%s\n' "$signing_password"
  printf 'keyAlias=memevault\n'
  printf 'keyPassword=%s\n' "$signing_password"
} > "$temporary_properties"
mv "$temporary_properties" "$properties_file"
chmod 600 "$properties_file" "$keystore_file"
unset signing_password

echo "Đã tạo personal signing tại $config_root"
echo "Hãy backup cả thư mục này ở nơi riêng tư; mất keystore sẽ không thể cập nhật APK đã cài."
