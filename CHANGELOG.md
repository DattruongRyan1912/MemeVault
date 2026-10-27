# Changelog

## 0.1.18 — 2026-09-02

- Cải thiện preview thư viện: giải mã thumbnail có giới hạn cho danh sách, phát GIF/animated WebP
  ở màn hình chi tiết và hiển thị poster frame cho video để tránh giải mã ảnh gốc toàn kích thước.
- Bổ sung kiểm thử phân biệt media động cần phát inline với ảnh tĩnh/video.
- Tăng `versionCode` lên 19 để bản test không bị trùng với 0.1.17.

## 0.1.17 — 2026-09-02

- Đóng gói cập nhật mới cho bộ nhập Tenor/Giphy: chuẩn hóa URL media Tenor `/m/` về CDN ổn
  định và ưu tiên thứ tự MIME phù hợp cho từng CDN.
- Bổ sung kiểm thử cho canonical URL và thứ tự `Accept` của media GIF/WebP.
- Tăng `versionCode` lên 18 để bản test không bị trùng với 0.1.16.

## 0.1.16 — 2026-09-02

- Đóng gói source hiện tại với phân loại media từ link Tenor/Giphy an toàn hơn: sticker WebP/GIF
  từ URL được giữ đúng MIME và tên gợi nhớ, không ép qua đường nhập sticker PNG của Studio/Zalo.
- Tăng `versionCode` lên 17 để bản test không bị trùng với 0.1.15.

## 0.1.15 — 2026-09-02

- Đóng gói source hiện tại sau các cập nhật mới cho bộ nhập link Tenor/Giphy và kiểm tra chọn
  media/fallback theo MIME.
- Tăng `versionCode` lên 16 để bản test không bị trùng với 0.1.14.

## 0.1.14 — 2026-09-02

- Đóng gói source hiện tại với luồng **Dán link** hỗ trợ Giphy: ưu tiên `giphy.webp` động cho
  sticker, giữ fallback GIF/MP4 và tiếp tục kiểm tra host/MIME trước khi lưu.
- Tăng `versionCode` lên 15 để bản test không bị trùng với 0.1.13.

## 0.1.13 — 2026-09-02

- **Dán link** nhận cả Giphy (`giphy.com`, `media*.giphy.com`, `i.giphy.com`). Sticker Giphy ưu
  tiên tải `giphy.webp` động có alpha, GIF/MP4 là fallback; Tenor vẫn ưu tiên GIF vì đuôi
  `.webp` của Tenor không đáng tin. Request file media không gửi `Accept: text/html` trước —
  Giphy CDN coi đó là trang HTML và trả về “link không trả về file media”. WebP/GIF từ trang
  `/stickers/` không ép `typeOverride=STICKER` (cờ đó chỉ dành PNG Studio/Zalo).
- Đóng gói các thay đổi mới nhất trong source hiện tại, gồm luồng thử nghiệm tạo và gửi sticker
  animated WebP cho GIF với fallback GIF khi encoder không tạo được biến thể.
- Bàn phím chỉ chọn WebP khi editor quảng cáo đúng `image/webp`; các host không hỗ trợ vẫn dùng
  đường gửi ảnh/GIF hiện có.

## 0.1.12 — 2026-09-02

- Thêm **Gửi sticker (thử nghiệm)** cho GIF: giữ file gốc, tạo variant animated WebP vuông
  512 (fallback 320) rồi share URI `image/webp` với intent `image/*` để Messenger/Zalo hiện
  trong Sharesheet. Encode lỗi thì gửi GIF gốc và hiện lý do.
- Đóng gói lại source hiện tại sau các thay đổi mới nhất để tạo bản test riêng trên Drive.

## 0.1.11 — 2026-09-02

- Đóng gói các thay đổi mới nhất trong source hiện tại và tăng version để phân biệt bản test.

## 0.1.10 — 2026-09-02

- Cho phép dán link Tenor (link trang hoặc media trực tiếp) từ nút **Nhập media**; app tải có
  giới hạn vào vùng tạm rồi lưu GIF/video/ảnh đúng MIME vào kho riêng, không qua Downloads.
  Link dạng `tenor.com/view/...-gif-...` ưu tiên GIF/ảnh của đúng item, bỏ WebM và GIF liên quan,
  rồi mới fallback MP4 nếu ảnh không tải được.

## 0.1.9 — 2026-09-01

- Thêm bộ lọc **Động** trong Library và tab **Động** trên keyboard để tìm nhanh GIF/video đã tải
  từ các nguồn như Tenor; file gốc và MIME vẫn được giữ nguyên, không tự ép thành sticker PNG.
- Chặn stream import và batch trước khi vượt giới hạn; đọc đúng kích thước/rotation MP4.
- Canonical hóa migration tag về đúng key, giữ attribution ưu tiên MANUAL.
- Tách Share Receiver import khỏi lifecycle Activity, drain Picker trước khi đóng Room và đóng
  Zalo manager ngoài main thread.
- Checkpoint mask Sticker Studio để phục hồi draft/recipe/tag/viền sau rotation hoặc process death.
- Thêm deadline cho GIF encoder và đối chiếu hash variant thật khi đồng bộ file Zalo managed.
- Bật encrypted Android backup/device transfer cho Room, originals và mask; thêm personal signing
  ngoài repo cho APK sideload ổn định.
- Sửa SQL `LIKE ... ESCAPE` trên SQLite thật, đóng gói Room schema cho migration test và thêm
  FileProvider instrumentation test trên Android Emulator.
- Thêm AVD Google Play ARM64 tooling khóa đúng serial emulator, cài lại APK sau instrumentation,
  cold-start smoke test và kiểm tra package/version host app mà không chạm điện thoại thật.

## 0.1.8 — 2026-08-31

- Sửa đồng bộ Zalo dùng đúng URI mà DocumentProvider trả về sau khi publish và lưu hash của
  variant PNG 320×320 để lần sync sau không nhập trùng.
- Tương thích sticker cũ dùng `content_hash` làm `dedup_key`, đồng thời giữ MEME và STICKER cùng
  bytes là hai asset độc lập.
- Kiểm tra chữ ký PNG và decode bounds trước khi lưu sticker; import ảnh thường suy luận là sticker
  từ tên vẫn giữ WebP/JPEG hợp lệ.
- Đồng bộ Zalo dùng single-flight cấp process, giữ trạng thái qua rotation và cho phép chạy lại
  trên cùng màn hình sau khi hoàn tất.

## 0.1.7 — 2026-08-31

- Thêm màn hình **Thử đồng bộ Zalo** trong menu trợ giúp/cài đặt.
- Cho người dùng chọn thư mục `photo_sticker`/`recent_sticker` bằng Android Storage Access
  Framework, lưu quyền truy cập và đồng bộ PNG sticker hai chiều mà không ghi đè hoặc xóa file.
- PNG từ thư mục đã chọn được nhập thành sticker trong MemeVault; sticker hiện có được xuất bằng
  profile PNG trong suốt 320×320. Android/Zalo vẫn quyết định có hiển thị như sticker native hay không.
- Nếu Android không cho cấp quyền vào sandbox riêng của Zalo, màn hình báo rõ giới hạn và giữ
  bridge ADB trên Mac làm fallback.

## 0.1.6 — 2026-08-31

- Thêm crop nhanh ngay trong Sticker Studio trước bước tách nền, có kéo vùng chọn/góc, đặt lại
  toàn ảnh và thao tác hỗ trợ TalkBack.
- Lưu vùng crop cùng canvas decoded trong recipe Room v3; recipe cũ được migrate an toàn và không
  khôi phục mask nếu chính sách sampling/orientation làm canvas thay đổi.
- Thêm tool lab ADB `tools/zalo_android_bridge.py` để probe/pull/push/sync PNG với candidate
  `photo_sticker/recent_sticker`; tool ngoài APK, yêu cầu `--apply --yes` khi ghi và không cam kết
  Zalo index file thành native sticker.

## 0.1.5 — 2026-08-31

- Thêm storage-first Sticker Vault với Room v2: lineage nguồn, recipe renderer/export, mask
  grayscale PNG app-private, dedup theo nguồn + recipe + render hash và reconcile file mồ côi.
- Thêm tag canonical NFKC (tối đa 20 tag/asset), attribution MANUAL/SYSTEM/AI, tìm kiếm tag và
  gợi ý theo tần suất; migration v1→v2 giữ quan hệ tag cũ ở dạng MANUAL.
- Thêm AssetPickerActivity không exported, quick create từ Library/card/keyboard, lọc Sticker,
  chỉnh recipe cũ thành asset mới và cảnh báo “Nguồn đã xóa” khi không thể re-edit.
- Bỏ đường giả lập `com.apple.sticker`; production chỉ dùng MIME công khai, `commitContent()` và
  clipboard URI fallback. Host Zalo/TikTok vẫn được ghi nhận là ảnh thường nếu không có native API.

## 0.1.4 — 2026-08-31

- Thêm lựa chọn profile chia sẻ riêng cho sticker PNG/WebP trên TikTok/Zalo: PNG trong suốt
  320×320 thử nghiệm theo raster iPhone hoặc PNG nền trắng 512×512 có đệm 72% tương thích hiện tại.
- Profile 320×320 được render lại bằng ARGB_8888, giữ alpha/viền, bỏ EXIF và không sao chép chunk
  `iDOT`; profile `opaque_share_v3` cũ vẫn giữ nguyên làm fallback.
- Chỉ route Intent tới TikTok dùng `image/*`; Zalo, FileProvider, clipboard và keyboard vẫn giữ
  MIME `image/png` chính xác. Đường Messenger/hệ thống vẫn dùng ảnh PNG trong suốt.
- Thêm dialog A/B để người dùng cá nhân đối chiếu hai profile trên cùng host/version; `ACTION_SEND`
  chỉ gửi ảnh, không thể ép TikTok/Zalo hiển thị sticker native.

## 0.1.3 — 2026-08-31

- Sửa crash khởi động trên Android 11+ do đọc `WindowInsetsController` trước khi decor view được khởi tạo.
- Kiểm tra lại cold start và mở Sticker Studio trên Android 15 emulator.

## 0.1.2 — 2026-08-31

- Sticker Studio hỗ trợ viền màu (palette hoặc mã HEX) và điều chỉnh độ dày theo ảnh.
- Viền được render lại từ confidence mask, giữ nguyên bản gốc và PNG nền trong suốt.
- Tối ưu bộ nhớ/cancel khi render ảnh lớn và mở rộng canvas để không cắt viền ở mép.

## 0.1.1 — 2026-08-30

- Thêm Sticker Studio: chọn ảnh tĩnh, tách nền on-device bằng ML Kit Subject Segmentation.
- Thêm chỉnh ngưỡng confidence, crop/padding và xuất PNG nền trong suốt.
- Giữ bản gốc, chống trùng bằng SHA-256, hỗ trợ lưu/chia sẻ/copy sticker mới.
- Bổ sung trạng thái model chưa sẵn sàng, hủy thao tác và xử lý retry.
- Cải thiện nút Đổi bàn phím với previous/next IME và system picker fallback.
- Chưa kiểm chứng tích hợp thực tế với Messenger, Zalo và TikTok trên thiết bị thật.

## 0.1.0 — 2026-08-30

- Bản MVP ban đầu: thư viện media local-first, import, tìm kiếm, yêu thích, xóa,
  Sharesheet/clipboard và MemeVault media keyboard.
