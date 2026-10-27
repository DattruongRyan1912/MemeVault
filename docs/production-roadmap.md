# MemeVault — Lộ trình production cá nhân

Ngày lập: 2026-08-30  
Phiên bản hiện tại: `0.1.5` (debug APK)
Mục tiêu: **APK ổn định để sideload và dùng hằng ngày**, không phải phát hành công khai.  
Ước tính: **3–4 tuần cho một developer**, nếu có thiết bị Android thật.

## 0. Profile production cá nhân

### Bắt buộc

- một máy Android chính và các app đích thực tế: Messenger, Zalo, TikTok, Gboard;
- dữ liệu không mất khi app restart/update; có đường backup/restore thủ công;
- import, tìm kiếm, favorite, xóa, sticker PNG, Sharesheet, clipboard và Đổi bàn phím hoạt động;
- release APK ký bằng keystore cá nhân, version/checksum rõ ràng và cài đè được bản trước;
- lỗi model, file hỏng, thiếu app nhận, hết bộ nhớ và cancel có thông báo dễ hiểu.

### Không làm trong profile này

- Play Console, Play App Signing, staged rollout, store listing và Data Safety form;
- backend, đăng nhập, cloud sync, telemetry hoặc multi-user;
- iOS, TikTok Content Posting API và cài sticker vào catalogue riêng của app khác.

Các mục public-release vẫn được giữ ở phần tham khảo cuối tài liệu, nhưng không chặn việc hoàn thiện
app để tự dùng.

## 1. Định nghĩa production cá nhân V1

Production V1 là một Android app local-first có thể:

- lưu meme, sticker, GIF và video trong vùng app-private;
- giữ nguyên bản gốc, chống trùng bằng SHA-256 và lưu metadata bền vững;
- tạo sticker từ ảnh tĩnh bằng tách nền on-device, có fallback thủ công;
- xuất bằng `content://`, MIME chính xác, Sharesheet, clipboard và IME tùy chọn;
- hoạt động an toàn khi model chưa sẵn sàng, file hỏng, thiếu app nhận hoặc người dùng hủy;
- không cần tài khoản, backend, cloud sync hoặc quyền truy cập nội dung chat.

MemeVault không cam kết cài sticker vào catalogue riêng của Messenger, Zalo hoặc TikTok. Các app
đó vẫn quyết định có nhận MIME/URI, rich content, bubble và kích thước hiển thị hay không. Route
TikTok/Zalo cho sticker có hai profile để A/B trên cùng host/version: PNG trong suốt 320×320 theo
raster iPhone và PNG vuông nền trắng 512×512 có đệm để giảm phóng to do crop alpha; cả hai vẫn là
ảnh thường, không phải sticker native.

## 2. Khoảng cách hiện tại

| Vùng | Đã có | Còn thiếu để production | Mức |
|---|---|---|---|
| Library | Import, Room v3, lineage, tag canonical, tìm kiếm, favorite, xóa, reconcile | Restore/reinstall test, MIME sniffing sâu hơn | P0 |
| Sticker Studio | Ảnh tĩnh → ML Kit mask → threshold/viền → PNG, recipe/mask immutable | Cọ giữ/xóa, undo/redo, quality dataset, engine fallback | P0 |
| Export | `content://`, Sharesheet, clipboard | Instrumented provider/grant test và ma trận host thật | P0 |
| IME | `commitContent`, clipboard fallback, Đổi bàn phím | Xác nhận trên Gboard/OEM/Messenger/Zalo, accessibility | P0 |
| Animation | GIF import, GIF → MP4 best-effort | Đo timing/transparency/size, xử lý codec theo máy | P1 |
| Release | Debug APK `0.1.5` | Keystore cá nhân, signed APK, cài đè/rollback, build script | P0 |
| Privacy/data | Local-first intent, backup đang exclude file/DB | Backup disclosure, permission/log audit và rights note cá nhân | P0 |
| Observability | Chưa có telemetry | Local bug notes, crash/ANR triage trên máy chính | P1 |

## 3. Các nguyên tắc khóa phạm vi

1. Không thêm backend, đăng nhập, cloud sync hoặc iOS trước khi local flow đạt độ ổn định.
2. Không tách nhiều Gradle module chỉ để “đẹp”; giữ package/interface rõ ràng cho đến khi build
   time hoặc ownership buộc phải tách.
3. Không đổi engine tách nền mà không chạy cùng một bộ ảnh đánh giá và so sánh output.
4. Không gọi một host app là “được hỗ trợ” nếu chưa có evidence theo OS, máy và phiên bản app.
5. Mọi thay đổi release phải có version, changelog, checksum và đường rollback.

## 4. Lộ trình theo phase

### Phase 0 — Chốt profile máy dùng chính (1 ngày)

**Làm:**

- chốt format cần dùng: PNG/JPEG, GIF, MP4; giới hạn kích thước, thời lượng và cạnh ảnh;
- ghi model máy, Android version và phiên bản Messenger/Zalo/TikTok/Gboard đang dùng;
- viết acceptance matrix cho import, sticker, share, copy, IME, cancel, lỗi model và xóa;
- phân loại P0/P1/P2; chỉ giữ những gì phục vụ luồng cá nhân.

**Gate:** có một bộ máy/app cụ thể để test; mỗi đường đi có expected result và fallback.

### Phase 1 — Data/media foundation (3–5 ngày)

**Làm:**

- Room V2: `asset_variants` có `source_hash`, `recipe_hash`, `engine_id/version`, dimensions,
  size, status và error code;
- migration `1 → 2`, test rollback/failure và process death;
- tách `FileStore`/repository: staging, atomic move, orphan cleanup, giới hạn bytes/dimensions;
- validate MIME bằng decoder/bytes khi có thể, không chỉ tin extension;
- quyết định backup: hiện file và database đều bị exclude, phải ghi rõ cho người dùng và test
  reinstall/device-transfer theo quyết định đó;
- lưu original bất biến; derived sticker/variant có thể xóa và tái sinh.

**Gate:** migration không mất metadata/original; duplicate import idempotent; file hỏng, quá lớn,
MIME sai và cancel đều dọn staging mà không xóa original.

### Phase 2 — Sticker Studio production quality (1–2 tuần)

**Làm:**

- hoàn thiện interface `BackgroundRemovalEngine` với `availability`, `prepare`, `segment`;
- lưu recipe/mask/job state; dùng WorkManager cho tác vụ cần sống qua rotation/process restart;
- UI nền caro before/after, zoom/pan, threshold/feather, cọ **Giữ/Xóa**, undo/redo;
- xuất master PNG và profile nhẹ; bỏ EXIF/GPS khỏi derived output;
- benchmark model download, cold/warm run, memory và cancel;
- chạy quality set tối thiểu 50 ảnh gồm người, thú, vật thể, tóc/viền, nền phức tạp;
- đánh giá MediaPipe/LiteRT bundled fallback nếu ML Kit unavailable, tải model thất bại hoặc
  quality không đạt.

**Gate đề xuất:**

- 0 crash/OOM trên bộ máy hỗ trợ;
- sau khi model sẵn sàng, p95 tách ảnh nằm trong ngưỡng UX đã chốt;
- tối thiểu 90% ảnh nội bộ được reviewer đánh giá “dùng được” mà không sửa nhiều;
- ảnh lỗi/model unavailable có thông báo và retry; không có output rỗng im lặng;
- original hash không đổi sau mọi thao tác.

### Phase 3 — Android interoperability (1 tuần)

**Làm:**

- instrumented test cho `FileProvider`, query metadata, read grant và read-only mode;
- test picker/share receiver với single/multiple URI, revoked grant và app nhận không tồn tại;
- test IME: MIME compatible → `commitContent`, incompatible → clipboard/Sharesheet, nút Đổi bàn
  phím → previous/next/system picker;
- test GIF → MP4 theo size, duration, transparency và codec;
- lập ma trận evidence tối thiểu: API 26, API 30, API 34/35, API 36; một Pixel và một OEM phổ biến;
  Messenger, Zalo, TikTok và Gboard theo phiên bản ghi lại tại thời điểm test.

**Gate:** mỗi ô matrix là `PASS`, `KNOWN LIMITATION` hoặc `BLOCKED` có log/screenshot; không dùng
fallback im lặng và không tuyên bố hỗ trợ khi chưa có evidence.

### Phase 4 — Local release và bảo vệ dữ liệu (2–3 ngày)

**Làm:**

- tạo release keystore cá nhân ngoài repo; build signed APK để sideload;
- kiểm tra `apksigner`, versionCode tăng đơn điệu, cài đè và rollback về bản trước;
- rà permissions, backup rules, exported component, URI grants, log redaction và dependency audit;
- thêm nút/export script backup thư viện nếu quyết định giữ dữ liệu khi reinstall;
- chỉ bật R8/minify sau khi có keep rules và smoke test release.

**Gate:** `assembleRelease` reproducible; APK cài được trên máy chính, không có secret/media/chat
text trong log hoặc artifact; người dùng biết rõ dữ liệu local có được backup hay không.

### Phase 5 — Kiểm tra lặp lại và phát hành cá nhân (3–5 ngày)

**Làm:**

- một script local/CI chạy unit test, lint, assembleRelease và migration test;
- thêm `StrictMode`, macrobenchmark cho startup/grid/import, giới hạn bitmap và disk;
- dùng log local đã redacted, bug notes và Vitals trên máy nếu có; không thêm telemetry bắt buộc;
- tạo release checklist, rollback note, artifact checksum và changelog;
- ghi lại các lỗi import, segmentation, share, model unavailable và storage full bằng test case.

**Gate:** build từ checkout sạch tạo ra APK cùng loại; không còn P0; lỗi còn lại có cách tái hiện và
được ghi lại.

### Phase 6 — Dogfood trên máy cá nhân (3–5 ngày)

**Làm:**

- cài APK release lên máy chính và dùng luồng thật trong vài ngày;
- dùng ảnh thật đa dạng; kiểm tra “tạo → lấy ra → dán/chia sẻ”;
- kiểm tra upgrade từ `0.1.0/0.1.1`, process death, thiếu mạng, thiếu Play services, đầy bộ nhớ và
  đổi bàn phím;
- khóa release candidate, không thêm feature mới trong đợt dogfood cuối.

**Gate:** 0 P0, không có data loss; import/share/sticker happy path dùng được liên tục; có rollback
artifact đã kiểm tra.

### Phase 7 — Vận hành cá nhân (liên tục)

**Làm:**

- phát hành hotfix theo semver, không overwrite artifact cũ; giữ changelog/checksum/rollback;
- chỉ mở rộng sang collections nâng cao, GIF/video matting, sync hoặc iOS khi phát sinh nhu cầu thật.

## 5. Lịch triển khai gợi ý

| Tuần | Kết quả cần có |
|---|---|
| 1 | Profile máy chính, Room/storage/backup decision, device matrix |
| 2 | Sticker recipe/mask, brush fallback, quality dataset |
| 3 | Provider/IME/Sharesheet/GIF device evidence, release APK |
| 4 | Dogfood, sửa blocker, checksum/changelog và Drive archive |

## 6. Checklist Go/No-Go

- [ ] Signed release APK, keystore ngoài repo, version/changelog/checksum.
- [ ] Room migration + reinstall/backup behavior được kiểm chứng.
- [ ] Original/variant atomicity, dedupe, cleanup và storage limits đạt.
- [ ] Sticker quality set đạt ngưỡng; model unavailable/cancel/retry có đường rõ ràng.
- [ ] FileProvider/URI grant/Sharesheet/clipboard/IME có device evidence.
- [ ] Messenger/Zalo/TikTok limitation được ghi theo app/OS version.
- [ ] Rights note, backup disclosure, permission và log review hoàn tất.
- [ ] Local test script green; không P0; lỗi còn lại có cách tái hiện.
- [ ] Rollback artifact và backup/restore note đã thử.

## 7. Chưa làm trong production V1

- backend/login/cloud sync;
- iOS Share Extension;
- tự động đăng TikTok hoặc chèn vào catalogue riêng của app khác;
- scraping nguồn meme/GIF hoặc bypass access control;
- biến MemeVault thành bàn phím nhập chữ đầy đủ;
- tách nền temporal cho GIF/video khi chưa có quality benchmark.

## Tài liệu tham khảo nếu chuyển sang phát hành công khai

- [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Upload an app bundle to Play Console](https://developer.android.com/studio/publish/upload-bundle)
- [App signing và Play App Signing](https://developer.android.com/studio/publish/app-signing)
- [Google Play Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Android app quality guidelines](https://developer.android.com/docs/quality-guidelines/core-app-quality)
