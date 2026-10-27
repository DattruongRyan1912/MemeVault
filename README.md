# MemeVault MVP

MemeVault là app Android local-first để lưu meme, sticker, GIF và video rồi đưa chúng ra app
khác qua các điểm tích hợp ổn định của Android.

## Luồng đang có

- Nhập từ system picker hoặc bấm **Chia sẻ → MemeVault** trong Messenger/Zalo/Files.
- Lưu bản gốc vào vùng app-private, ghi metadata Room và chống trùng bằng SHA-256.
- Duyệt grid, tìm theo tên/MIME, lọc media động/GIF/sticker/meme, đánh dấu yêu thích và xóa.
- Media động có thể nhập bằng **Nhập media → Dán link Tenor/Giphy** (trang `tenor.com/view/...`,
  `giphy.com/stickers/...` hoặc media trực tiếp) mà không cần lưu qua Downloads; HTTPS/host được
  kiểm tra, Giphy ưu tiên WebP động, Tenor ưu tiên GIF/ảnh, fallback MP4, tải có giới hạn vào
  vùng tạm rồi lưu đúng MIME.
  GIF/video không đi qua Sticker Studio nên không bị mất animation. GIF có thêm **Gửi sticker
  (thử nghiệm)** — tạo animated WebP vuông, fallback GIF nếu encode lỗi; app chat vẫn quyết định
  bubble. Bộ lọc **Động** và tab cùng tên trên keyboard giúp tìm lại nhanh.
- **Tạo sticker** chọn ảnh tĩnh, crop nhanh vùng cần giữ trước khi tách nền on-device, chỉnh ngưỡng alpha, crop/padding, chọn màu
  viền (palette hoặc mã HEX) và kéo độ dày viền; lưu PNG nền trong suốt, bản gốc vẫn được giữ riêng.
- Sticker lưu theo mô hình storage-first: Room v3 liên kết asset nguồn, recipe renderer, vùng crop và mask
  grayscale 8-bit trong thư mục app-private. Mỗi lần lưu là một asset bất biến; chỉnh sticker cũ
  tạo phiên bản mới, còn xóa ảnh nguồn sẽ giữ sticker và báo “Nguồn đã xóa”.
- Nút **Tạo sticker** có ở Library, card media và MemeVault keyboard. Picker có tìm kiếm, lọc
  Sticker, nhập ảnh mới; Studio copy tag nguồn làm mặc định và cho chỉnh tag trước khi lưu.
- Tag được chuẩn hóa Unicode NFKC, tìm được bằng tên/MIME/tag, có gợi ý theo tần suất và lưu
  attribution MANUAL/SYSTEM/AI để dành chỗ cho semantic search về sau.
- **Chia sẻ** dùng `content://` URI + Android Sharesheet; **Copy** dùng clipboard URI.
- Bàn phím MemeVault có tab **Động** để lọc GIF/video đã nhập cùng với Gần đây, Yêu thích, Sticker
  và Tất cả. Đây là capability seam: editor quảng cáo MIME phù hợp sẽ nhận
  `InputConnection.commitContent()`, còn lại có clipboard fallback. Nút **Đổi bàn phím** trong
  thanh đầu bàn phím sẽ quay lại bàn phím dùng trước; nếu Android không nhớ được bàn phím đó,
  app mở bộ chọn bàn phím hệ thống.
- Nút **TikTok/Zalo** cho chọn profile riêng khi gửi sticker PNG/WebP: profile PNG
  trong suốt 320×320 mô phỏng raster iPhone, hoặc profile tương thích PNG nền trắng 512×512 có
  đệm quanh chủ thể để giảm việc app đích crop alpha rồi phóng to. Messenger và đường hệ thống
  vẫn nhận PNG trong suốt. GIF trên TikTok thử tạo biến thể MP4/H.264 bounded trước khi share.
- Cả hai profile chỉ gửi một ảnh thường qua Android Sharesheet; MemeVault chỉ dùng MIME công khai
  (`image/png` hoặc MIME editor quảng cáo), `commitContent()` và clipboard URI fallback. TikTok/Zalo vẫn quyết định bubble
  và không có contract để MemeVault ép hiển thị sticker native. Profile 320×320 không sao chép
  chunk `iDOT`; chunk đó chỉ là hint giải mã PNG của Apple, không phải metadata sticker.
- Khi MemeVault keyboard đang ở ô nhập của TikTok/Zalo, nó tự dùng cùng profile tương thích ở
  background; nếu đổi app/ô nhập giữa chừng, kết quả cũ sẽ không bị chèn nhầm.
- Trong menu **⋮ → Thử đồng bộ Zalo**, có thể chọn thư mục `photo_sticker`/`recent_sticker` mà
  Android cho phép truy cập để trao đổi PNG sticker hai chiều. Đây là cầu nối SAF thử nghiệm:
  không ghi trực tiếp vào sandbox Zalo, không xóa/ghi đè file và không cam kết native sticker.

## Chạy và kiểm tra

Yêu cầu JDK 17 và Android SDK 36:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

APK debug nằm tại `app/build/outputs/apk/debug/app-debug.apk`.

Có thể chạy phần lớn kiểm thử không cần điện thoại thật bằng AVD Google Play ARM64 qua
`tools/setup_emulator.sh`, `tools/start_emulator.sh` và `tools/install_and_test_emulator.sh`.
Sau đó dùng `tools/check_host_apps_emulator.sh` để kiểm tra version hoặc mở listing Play Store
chính thức của Messenger/Zalo/TikTok.
Chi tiết khả năng cài Messenger/Zalo/TikTok và giới hạn bằng chứng nằm tại
[`docs/emulator-host-app-testing.md`](docs/emulator-host-app-testing.md).

Để tạo APK release cá nhân có chữ ký ổn định, chạy một lần
`tools/setup_personal_signing.sh`, backup riêng thư mục được báo rồi chạy
`./gradlew :app:assembleRelease`. Không đưa file keystore hoặc signing properties lên Drive/repo.
APK release không thể cài đè bản debug vì hai chữ ký khác nhau; hãy giữ bản debug cho tới khi dữ
liệu đã được Android backup/device-transfer hoặc đã được nhập lại an toàn.

Database, originals và mask được đưa vào Android encrypted Auto Backup/device transfer. Variant
chia sẻ có thể tái tạo nên không backup; quyền SAF của thư mục Zalo phải chọn lại sau khi restore.

Để dùng bàn phím, mở app → **Bàn phím**, bật MemeVault trong phần Input method của Android rồi
chọn nó tại ô nhập. Đây là đường tùy chọn; Sharesheet và clipboard vẫn hoạt động khi chưa bật.

## Kế hoạch mở rộng

Thiết kế hệ thống, Sticker Studio, tách nền on-device, Room V2→V3 và roadmap triển khai được ghi tại
[`docs/system-design-plan.md`](docs/system-design-plan.md).
Lộ trình production cá nhân theo gate, device matrix và local release checklist nằm tại
[`docs/production-roadmap.md`](docs/production-roadmap.md).
Kế hoạch redesign UI/UX, information architecture, component và thứ tự implement nằm tại
[`docs/ui-redesign-plan.md`](docs/ui-redesign-plan.md).

## Bridge thử nghiệm cho Zalo Desktop trên Mac

Zalo Desktop lưu một cache sticker local dưới `~/Library/Application Support/ZaloData`. Công cụ
[`tools/zalo_desktop_bridge.py`](tools/zalo_desktop_bridge.py) hỗ trợ quét, kéo PNG từ cache về
bridge và đẩy PNG ra outbox theo hash. Ghi trực tiếp vào cache chỉ là thử nghiệm opt-in bằng
`--zalo-cache --apply --yes`; không sửa index/database, không xóa file và không cam kết Zalo sẽ
hiển thị file như native sticker. Xem [hướng dẫn bridge](docs/zalo-desktop-bridge.md).

Với Zalo Android, [`tools/zalo_android_bridge.py`](tools/zalo_android_bridge.py) là bridge ADB
thử nghiệm để probe và trao đổi PNG với candidate `photo_sticker/recent_sticker`. Android app
không tự ghi được sandbox của Zalo; tool yêu cầu thiết bị USB debugging, path remote rõ ràng và
`--apply --yes` cho thao tác ghi. Xem [hướng dẫn Zalo Android](docs/zalo-android-photo-sticker-bridge.md).

## Giới hạn cần xác nhận trên thiết bị thật

- Messenger, Zalo và từng phiên bản TikTok có thể quảng cáo MIME/nhận URI khác nhau; cần ghi
  nhận kết quả theo máy, OS và app version.
- Với Zalo/TikTok, việc ảnh còn nằm trong bubble chữ nhật hoặc kích thước hiển thị khác Messenger
  là giới hạn của host; cần ghi screenshot theo phiên bản thay vì coi đó là native-sticker PASS.
- Subject Segmentation là API ML Kit beta; model cần khởi tạo qua Google Play services. Nếu model
  chưa sẵn sàng hoặc thiết bị không tương thích, Sticker Studio báo lỗi và cho thử lại.
- Bản hiện tại chỉ tách nền ảnh tĩnh; GIF/video và cọ giữ/xóa nâng cao chưa triển khai.
- GIF-to-MP4 dùng adapter local best-effort; nếu codec/decoder của máy từ chối, app báo lỗi và
  không sửa bản GIF gốc.
- Chưa có iOS Share Extension, cloud sync, đăng nhập, public library hoặc TikTok Content
  Posting API.
