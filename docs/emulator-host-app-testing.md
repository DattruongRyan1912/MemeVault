# Test MemeVault với Android Emulator

## Mức bằng chứng

Emulator kiểm tra tốt logic Android của MemeVault: Room migration, process recreation, FileProvider,
URI grant, Sharesheet, clipboard, keyboard `commitContent()`, crop và codec mà image ảo cung cấp.

Messenger, Zalo và TikTok có thể cài từ Play Store trên AVD Google Play ARM64 nếu listing cho phép.
Tuy nhiên kết quả này chỉ là `EMULATOR VERIFIED`, không thay thế hoàn toàn máy thật vì app đích có
thể phát hiện máy ảo, giới hạn đăng nhập, thiếu camera/SIM/Play Integrity hoặc dùng codec/OEM editor
khác. Riêng giả thuyết Zalo đọc private `photo_sticker/recent_sticker` không thể chứng minh bằng SAF
nếu Zalo không expose thư mục đó, và ADB không được quyền đọc sandbox app production không debuggable.

## Thiết lập

```bash
tools/setup_emulator.sh
tools/start_emulator.sh
tools/install_and_test_emulator.sh
tools/check_host_apps_emulator.sh
```

AVD mặc định dùng Android 15/API 35, Google Play và ARM64 để khớp Mac Apple Silicon và có Play Store.
Đăng nhập một tài khoản test riêng trong Play Store; không dùng tài khoản/chat cá nhân cho evidence.

## Cài app đích

Mở Play Store trong emulator và thử cài:

1. Messenger (`com.facebook.orca`)
2. Zalo (`com.zing.zalo`)
3. TikTok (`com.zhiliaoapp.musically`, tùy vùng có thể dùng package khác)

Nếu Play Store báo không tương thích, không sideload APK từ website không rõ nguồn. Có thể lấy APK
từ một thiết bị/tài khoản hợp pháp của chính người dùng, nhưng kết quả sideload phải ghi rõ nguồn và
không được gọi là Play Store-compatible.

Script kiểm tra chỉ báo package/version, không đọc tài khoản hoặc nội dung chat. Có thể mở thẳng
listing chính thức, ví dụ `tools/check_host_apps_emulator.sh --open zalo`; thao tác đăng nhập/cài đặt
vẫn thực hiện trong Play Store bằng một tài khoản test. Sau khi cài, chạy lại script để ghi đúng
package và version trước khi test.

Ở lần kiểm tra ngày 2026-09-01, AVD `MemeVault_API_35_Play` có Play Store ARM64 nhưng chưa đăng nhập,
vì vậy cả ba app chưa được cài và Play Store dừng ở màn hình Sign in. Đây là blocker tài khoản test,
không phải bằng chứng app không tương thích với emulator.

## Ma trận acceptance

Với mỗi host ghi: AVD/API, phiên bản host, ABI, media, MIME, đường gửi và kết quả thực tế.

- Sharesheet: PNG trong suốt, PNG profile Zalo/TikTok, GIF và MP4.
- MemeVault keyboard: host quảng cáo MIME thì `commitContent`; nếu không thì clipboard fallback.
- Đổi bàn phím: quay lại Gboard hoặc mở system picker.
- Rotation/process death: dùng `adb shell am kill com.memevault.app`, mở lại và kiểm tra draft.
- FileProvider: recipient đọc được URI trong grant, nhưng không đọc lại khi không còn grant hợp lệ.
- Zalo SAF: chỉ test thư mục mà system picker thực sự cho chọn; không tuyên bố native sticker nếu
  Zalo chỉ hiển thị ảnh thường.

## Giới hạn kết luận

- Emulator pass giúp loại phần lớn bug code/lifecycle trước khi cài lên điện thoại.
- Ít nhất một lượt trên thiết bị thật vẫn cần cho Zalo/TikTok rendering, keyboard OEM, GIF codec và
  native/private sticker behavior. Nếu chưa có, ghi `Blocked pending physical-device acceptance`.
