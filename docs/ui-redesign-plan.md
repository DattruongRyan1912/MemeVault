# MemeVault — kế hoạch redesign UI/UX

**Ngày:** 2026-08-30  
**Phạm vi:** Android, dùng cá nhân, local-first  
**Mục tiêu:** biến MVP đang chạy được thành một app media vault nhanh, rõ và dễ dùng hằng ngày;
không mở rộng sang social feed, cloud hay public Play release.

## 1. Kết luận audit hiện tại

MVP đã có các capability quan trọng (import, lưu local, tìm/lọc, favorite, share/copy, keyboard
và Sticker Studio), nhưng UI hiện còn thiên về proof-of-concept:

| Khu vực | Vấn đề hiện tại | Hướng redesign |
|---|---|---|
| Library | Một `ScrollView` dài, header và nhiều nút hành động cạnh nhau | App bar rõ ràng, search cố định, filter chips, grid virtualized |
| Asset card | Card có nhiều nút chữ (`Chia sẻ`, `TikTok`, `Copy`, favorite, delete) làm loãng preview | Preview là trọng tâm; giữ favorite + overflow, gom action vào detail/bottom sheet |
| Điều hướng | Chưa có hierarchy giữa Library, Studio và cài đặt | 3 khu vực cố định: Library, Tạo sticker, Cài đặt/Trợ giúp |
| Tìm/lọc | EditText và nút phẳng, khó nhận biết trạng thái đang chọn | Search bar, chips có selected state, sort sheet |
| Empty/error | Chủ yếu là text status | Empty state có hướng dẫn; lỗi có retry và fallback cụ thể |
| Sticker Studio | Một form dọc; thiếu checkerboard, before/after, undo/redo và trạng thái tiến trình | Editor theo state/step, preview media-first, toolbar chỉnh sửa rõ |
| Keyboard | Nút media dạng text, không nhìn thấy thumbnail; switch có nhưng chưa nổi bật | Lưới thumbnail, Recent/Favorites, nút `Đổi bàn phím` luôn hiện |
| Visual system | Dùng theme platform cơ bản, ít token về spacing/radius/type | Bộ token thống nhất, touch target tối thiểu 48dp, tương phản và content description |

## 2. Nguyên tắc thiết kế

1. **Media-first:** người dùng nhận ra asset bằng thumbnail trước khi đọc tên.
2. **Một hành động chính mỗi màn hình:** Library ưu tiên tìm/chọn; Studio ưu tiên tạo và lưu.
3. **Ít bước, không giấu fallback:** share, copy và đổi keyboard luôn có đường quay lại.
4. **Local-first rõ ràng:** không có trạng thái “đang đồng bộ” giả; chỉ hiển thị thao tác thật.
5. **Native và nhỏ gọn:** giữ Kotlin + platform Views trong đợt đầu; chỉ thêm component/dependency
   khi giúp giải quyết một vấn đề UI đo được.
6. **Thiết kế theo trạng thái:** Loading, Empty, Content, Error, Unsupported, Success và Cancelled
   là các UI state chính, không phải text tạm đặt trong cùng một layout.
7. **Dùng cá nhân:** tối ưu tốc độ mở app, tìm asset thường dùng và thao tác bằng một tay;
   không đầu tư onboarding, feed, social graph hay cơ chế tăng trưởng.

## 3. Design system v1

### Token đề xuất

| Nhóm | Giá trị/qui ước |
|---|---|
| Spacing | 4dp base; dùng 8/12/16/20/24/32dp cho layout |
| Touch target | Tối thiểu 48 × 48dp; icon có padding, không dùng vùng chạm nhỏ theo kích thước glyph |
| Radius | 12dp cho card/input, 16dp cho sheet, 20dp cho pill/FAB |
| Surface | `mv_surface` cho nền nhẹ, white cho card, đường viền trung tính thay cho shadow nặng |
| Primary | Giữ `mv_blue` cho action chính; `mv_blue_dark` cho pressed/contrast |
| Semantic | Success xanh lá, warning amber, error đỏ; không chỉ dùng màu để truyền ý nghĩa |
| Typography | Title 22sp, section 16sp medium, body 14–16sp, metadata 12sp; tối đa 2 dòng tên asset |
| Motion | Fade/scale nhẹ cho sheet và save success; không animation liên tục trong grid |
| Accessibility | Content description cho icon, focus state, dynamic text size, contrast kiểm tra trên device |

### Component dùng chung

- `MvAppBar`: title, navigation/up, search/import hoặc overflow theo ngữ cảnh.
- `MvSearchBar`: search theo tên/tag; có clear và empty query state.
- `MvFilterChip`: All, Favorite, Meme, Sticker, GIF; selected state nhìn thấy khi không có màu.
- `AssetCard`: thumbnail, type badge/duration, favorite icon, overflow; không chứa 5 nút chữ.
- `AssetGrid`: `RecyclerView` + `GridLayoutManager`, stable id, thumbnail cache, placeholder/error.
- `MvEmptyState`: icon/illustration nhẹ, lý do, một CTA chính.
- `MvStatusBanner`: progress, lỗi, retry, fallback; ưu tiên inline thay toast cho kết quả quan trọng.
- `MvBottomSheet`: share/copy/TikTok, rename/tag, tạo sticker, delete có xác nhận.
- `MvPrimaryButton` và `MvSecondaryButton`: primary action duy nhất mỗi state.
- `KeyboardMediaTile`: thumbnail, favorite/recent marker, disabled/unsupported state.

## 4. Information architecture

### 4.1 Library — màn hình mặc định

```text
┌────────────────────────────────────┐
│ MemeVault                 ＋  ⋮     │
│ [⌕  Tìm meme, sticker, GIF     × ]  │
│ [Tất cả] [★] [Meme] [Sticker] [GIF] │
│                         Sắp xếp ▾   │
│                                    │
│       [ thumbnail ] [ thumbnail ]   │
│       [ tên  ★   ⋮ ] [ tên       ]  │
│       [ thumbnail ] [ thumbnail ]   │
│       ...                          │
│                                    │
│                              ＋     │
└────────────────────────────────────┘
```

- App bar: tên app, import icon và overflow; bỏ nhóm 3 nút chữ ở header.
- Search nằm ngay dưới app bar và giữ vị trí khi danh sách cuộn.
- Filter là horizontal chips; sort mở bottom sheet gồm Mới nhất, Dùng nhiều, Tên A–Z.
- Grid 2 cột, tỷ lệ thumbnail ổn định; GIF hiển thị poster + badge thời lượng, không tự phát mọi ô.
- FAB/primary action `Tạo sticker` chỉ xuất hiện khi phù hợp, không che content.
- Long-press card bật selection mode để share/delete nhiều asset; không cần cho v1 nếu gây phức tạp.

**Trạng thái:**

- `Loading`: skeleton 4–6 ô, không nhấp nháy.
- `Empty`: “Chưa có media” + `Nhập từ máy` và `Mở hướng dẫn chia sẻ`.
- `Filtered empty`: “Không tìm thấy kết quả” + `Xóa bộ lọc`.
- `Content`: grid + count nhỏ ở section header.
- `Error`: banner lỗi DB/file + `Thử lại`; asset hỏng chỉ đánh dấu tại card, không làm hỏng cả grid.

### 4.2 Asset detail — preview và hành động

Mở bằng tap card. Có thể bắt đầu bằng `BottomSheet` toàn màn hình để giảm navigation code; chỉ
tách thành Activity khi preview GIF/video cần lifecycle riêng.

```text
[‹] Chi tiết                              ⋮
┌────────────────────────────────────────┐
│          preview lớn, nền checker      │
└────────────────────────────────────────┘
Tên asset                         ★
PNG · 512×512 · 128 KB
[ Chia sẻ ] [ Copy ] [ Tạo sticker ]
[ TikTok ]                 Xóa asset
```

- Action thường dùng hiển thị tối đa 3 nút; phần còn lại ở overflow.
- Delete luôn có confirm và nêu rõ bản gốc/variant sẽ bị xóa.
- Với MIME không tương thích, hiển thị fallback thực tế: Copy hoặc Sharesheet.

### 4.3 Sticker Studio — editor theo state

```text
┌────────────────────────────────────┐
│ ‹  Tạo sticker                 Hủy  │
│                                    │
│        checkerboard preview         │
│        [before] ⇄ [after]            │
│                                    │
│ [Giữ] [Xóa]  ↶  ↷  [So sánh]        │
│ Ngưỡng        ─────●─────           │
│ Feather       ───●────────           │
│ [Chọn ảnh]       [Lưu sticker]      │
└────────────────────────────────────┘
```

Luồng state đề xuất:

1. `NoImage`: CTA chọn ảnh tĩnh, giải thích GIF/video chưa hỗ trợ tách nền.
2. `Importing`: progress và nút hủy; không cho bấm save.
3. `PreparingModel`/`Segmenting`: status “Đang chuẩn bị model/tách nền”, có retry khi lỗi.
4. `Editing`: preview nền trong suốt, zoom/pan, threshold/feather; undo/redo chỉ bật khi có thay đổi.
5. `Saving`: khóa thao tác gây xung đột, hiển thị progress.
6. `Success`: preview kết quả + `Mở chi tiết`, `Chia sẻ`, `Tạo bản khác`.
7. `Error`/`Cancelled`: giữ ảnh gốc và cho phép retry/thoát an toàn.

Đợt UI đầu chỉ cần rehost flow tách nền hiện có, checkerboard, compare và toolbar. Cọ giữ/xóa
pixel-level là P1 sau khi flow cơ bản đã được kiểm tra trên máy thật.

### 4.4 Keyboard — chọn và chèn media

```text
┌────────────────────────────────────┐
│ MemeVault     Đổi bàn phím  ⚙      │
│ [⌕ Tìm]                             │
│ [Gần đây] [★ Yêu thích] [Tất cả]    │
│ [thumb] [thumb] [thumb] [thumb]     │
│ [thumb] [thumb] [thumb] [thumb]     │
│ Copy / Chia sẻ khi editor không hỗ trợ│
└────────────────────────────────────┘
```

- Nút `Đổi bàn phím` nằm ở toolbar cố định, có label rõ và tối thiểu 48dp.
- Tile dùng thumbnail; chạm tile sẽ commit content nếu editor quảng cáo MIME phù hợp.
- Khi không hỗ trợ rich content, tile vẫn cho `Copy`/mở Sharesheet, không biến thành lỗi mơ hồ.
- Không đặt ô text search trong IME v1: MemeVault không có bàn phím chữ riêng, nên ô đó sẽ không
  thể nhập khi chính MemeVault đang là IME. Người dùng tìm/lọc ở Library trước, keyboard tập trung
  vào Recent/Favorites/All.
- Không làm full text keyboard; MemeVault chỉ là media keyboard seam.

### 4.5 Settings/Help

Một màn hình nhỏ, không biến thành admin panel:

- Trạng thái bật MemeVault keyboard + nút mở Input method settings.
- Giải thích `Đổi bàn phím` và cách quay lại Gboard/bàn phím trước.
- Storage: dung lượng, `Xuất backup`, `Nhập backup`, xóa cache thumbnail.
- Model tách nền: ready/downloading/unavailable, retry.
- Phiên bản app, chẩn đoán tối thiểu (không thu thập nội dung chat).

## 5. Luồng người dùng ưu tiên

| Luồng | Đích UX cần đạt |
|---|---|
| Lần đầu mở app | Empty state dẫn thẳng đến import; không có onboarding dài |
| Import từ Messenger/Zalo/Files | Sau khi nhận file, quay về Library và làm nổi asset mới |
| Tìm rồi share | Search → chip nếu cần → tap card → Share; không phải mở nhiều menu |
| Tạo sticker | Library → Tạo sticker → Chọn ảnh → Tách nền → Lưu; giữ bản gốc |
| Dùng trong chat | Mở keyboard → Recent/Favorites → chạm thumbnail; fallback hiển thị ngay |
| Đổi về keyboard cũ | Chạm `Đổi bàn phím` trên toolbar; nếu Android không nhớ IME thì mở picker |
| Asset lỗi/unsupported | Card/detail nêu lỗi cụ thể, có retry/fallback; không mất asset gốc |

## 6. Lộ trình triển khai UI

Ước lượng cho một người, làm ngoài giờ; mỗi phase nên tạo một checkpoint chạy được trên máy.

### Phase 0 — Visual contract (0.5–1 ngày)

- Chốt token màu/spacing/type và wireframe ở trên.
- Chụp baseline Library, Studio, Keyboard trên một máy Android chính.
- Chốt danh sách state và acceptance gate; không đổi behavior export trong phase này.

### Phase 1 — Foundation components (1–2 ngày)

- Tạo resource tokens và helper views: app bar, chip, button, banner, empty state, bottom sheet.
- Bổ sung content descriptions, touch target và pressed/disabled states.
- Giữ platform Views; chưa chuyển Compose để tránh tạo thêm migration risk.

### Phase 2 — Library redesign (3–4 ngày)

- Thay `GridLayout`/card phẳng bằng `RecyclerView` + `AssetAdapter`/`AssetCard`.
- App bar + search + filter chips + sort sheet + loading/empty/error states.
- Thumbnail sampling/cache; giữ nguyên repository, Room và share behavior.
- Test với 0, 1, 20 và 100+ asset; kiểm tra scroll và xoay màn hình.

### Phase 3 — Detail và action model (2–3 ngày)

- Thêm detail bottom sheet/full screen với preview lớn.
- Gom share/copy/TikTok/delete/tag/favorite theo mức độ dùng.
- Confirm destructive action, success/error banner và deep-link ngược về Library.

### Phase 4 — Sticker Studio redesign (4–6 ngày)

- Tách layout thành header, canvas, tool row, parameter row và action footer.
- Thêm checkerboard, before/after, state progress, cancel/retry và save success.
- Reuse pipeline tách nền hiện có; cọ giữ/xóa và crop nâng cao để P1.

### Phase 5 — Keyboard redesign (2–3 ngày)

- Thumbnail tile + Recent/Favorites/All; không thêm ô text search giả trong media-only IME.
- Làm nổi bật `Đổi bàn phím`; giữ MIME check và clipboard/Sharesheet fallback.
- Kiểm tra toolbar không che media trên màn hình nhỏ.

### Phase 6 — Accessibility, performance, device QA (2–3 ngày)

- TalkBack cơ bản, font lớn, dark/high-contrast sanity check.
- 100+ assets, file hỏng, model unavailable, rotate/background/low storage.
- Test thực tế với Messenger, Zalo, TikTok và Gboard trên máy chính.
- Cập nhật screenshot/baseline và ghi kết quả vào `docs/production-roadmap.md`.

## 7. Ưu tiên P0/P1/P2

### P0 — phải làm để app cá nhân dùng hằng ngày

- Library app bar/search/chips và `RecyclerView` thumbnail grid.
- Empty/loading/error/unsupported states.
- Detail với Share/Copy/Favorite/Delete rõ ràng.
- Sticker Studio state flow, checkerboard preview, cancel/retry/success.
- Keyboard thumbnail grid và nút đổi keyboard luôn nhìn thấy.
- Touch target, content description, device smoke test.

### P1 — tăng chất lượng sau khi P0 ổn

- Brush keep/erase, undo/redo nhiều bước, feather và crop nâng cao.
- Batch selection/share/delete, tags/collections và sort “dùng nhiều”.
- GIF preview controls và thumbnail generation tốt hơn.
- Dark mode hoàn chỉnh, tablet/foldable layout.

### P2 — chưa cần cho bản dùng cá nhân

- Compose migration, multi-module, cloud sync, public library, social feed, iOS extension,
  TikTok Content Posting API và analytics.

## 8. Acceptance gates

- **Clarity:** mở app là biết cách import, tìm và tạo sticker; không còn nhóm nút chữ dày ở header/card.
- **Library:** grid cuộn ổn với 100+ asset; thumbnail lỗi không làm crash màn hình; source bytes không đổi.
- **Studio:** người dùng luôn biết đang ở bước nào; cancel/error không làm mất bản gốc; save thành công mở được asset mới.
- **Keyboard:** thumbnail có thể chọn; `Đổi bàn phím` luôn có; MIME mismatch chuyển sang fallback cụ thể.
- **Accessibility:** icon có label, vùng chạm ≥48dp, text scale lớn không cắt action chính.
- **Interop:** share/copy thực tế được kiểm tra trên Messenger, Zalo, TikTok theo matrix trong roadmap cá nhân.
- **Regression:** các test repository/adapter hiện có vẫn pass; build debug tạo APK cài được.

## 9. Trạng thái triển khai checkpoint hiện tại

Đã triển khai P0 UI ở mức compile/build:

- Library dùng `RecyclerView` + `GridLayoutManager` + `ListAdapter/DiffUtil`, search debounce,
  filter chips, sort sheet, empty/error states và detail action surface.
- Thumbnail được downsample theo target pixel và cache bounded; grid dùng poster tĩnh cho GIF/video.
- Sticker Studio có app bar, checkerboard preview, compare ảnh gốc/kết quả, state progress,
  cancel/retry/success và đường mở từ một asset trong Library.
- Keyboard có thumbnail grid, Recent/Favorites/All, vùng chạm 48dp và nút đổi bàn phím cố định;
  không có ô tìm kiếm không thể nhập trong chính media-only IME.
- Callback UI/DB/share đã có lifecycle guard; Studio có bookkeeping cancel an toàn cho cả task
  đang chạy và task bị hủy trước khi bắt đầu, đồng thời trì hoãn đóng repository tới khi worker kết thúc.
- Detail có shortcut TikTok/Zalo; sticker PNG/WebP mở dialog chọn profile PNG trong suốt 320px
  thử nghiệm hoặc opaque compatibility variant canvas vuông 512px với đệm 72% để đối chiếu việc
  app đích crop alpha và phóng to chủ thể, trong khi Share/keyboard ngoài route host vẫn giữ đường
  PNG trong suốt. Đây là ảnh thường qua Sharesheet, không phải cơ chế chèn sticker native vào
  catalogue của host.
- Keyboard nhận diện host package TikTok/Zalo để chọn profile tương thích ở background và bỏ kết
  quả nếu input session đã đổi.
- Sticker Studio cho phép chọn viền không/màu palette/mã HEX và điều chỉnh độ dày; thay đổi viền
  chỉ render lại từ confidence mask, không chạy lại model tách nền.

Chưa gọi là production/runtime pass cho tới khi có thiết bị thật: GIF detail playback, rotate/process
recreation của Studio, IME composition, Messenger/Zalo/TikTok interop, malformed media và TalkBack.

## 10. Thứ tự implement đề xuất

Bắt đầu bằng **Phase 1 → Phase 2**: làm design tokens và Library mới trước. Đây là phần người dùng
nhìn thấy mỗi ngày, đồng thời tạo các component (`AssetCard`, `StatusBanner`, `EmptyState`) để tái
sử dụng cho Detail, Studio và Keyboard. Sau khi Library ổn định mới thay layout Studio và keyboard;
không sửa pipeline lưu/share cùng lúc với redesign UI.

### Không làm trong đợt redesign này

- Không đưa media vào catalog riêng của Messenger/Zalo/TikTok.
- Không thêm backend, đăng nhập, cloud sync hay thu thập nội dung chat.
- Không thay đổi MIME/URI contract chỉ vì đổi giao diện.
- Không tuyên bố interop ổn định nếu chưa có bằng chứng trên thiết bị và phiên bản app cụ thể.
