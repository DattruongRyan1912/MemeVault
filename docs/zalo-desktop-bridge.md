# Experimental Zalo Desktop bridge

MemeVault là app Android; nó không thể tự ghi vào filesystem của MacBook. Bridge này là
công cụ Python chạy **trên Mac**, dùng để trao đổi PNG với cache sticker của Zalo Desktop.

## Dữ liệu đã xác nhận trên Mac này

- App: `/Applications/Zalo.app` (`com.vng.zalo`, bản 26.8.10).
- Root dữ liệu: `~/Library/Application Support/ZaloData`.
- Cache sticker đang dùng: `media/<account-id>/sticker/<pack-id>/<sticker-id>/<hash>.png`.
- Không thấy thư mục tên `photo_sticker` hoặc `recent_sticker` trong ZaloData Desktop ở lần quét hiện tại.

Đây là **cache nội bộ**, không phải API cài sticker được Zalo công bố. Việc file xuất hiện
trong cache không chứng minh Zalo sẽ hiện nó trong khay sticker.

## Cách dùng

Từ thư mục gốc MemeVault:

```bash
# Chỉ đọc, xem account và số lượng PNG
python3 tools/zalo_desktop_bridge.py scan

# Kéo các PNG đang có trong cache Zalo vào thư mục bridge
python3 tools/zalo_desktop_bridge.py pull

# Đưa một thư mục PNG vào outbox an toàn (không chạm ZaloData)
python3 tools/zalo_desktop_bridge.py push "/path/to/memevault-stickers"

# Hai chiều: pull cache trước, sau đó push thư mục nguồn ra outbox
python3 tools/zalo_desktop_bridge.py sync "/path/to/memevault-stickers"
```

`pull` ghi vào `~/Library/Application Support/MemeVaultZaloBridge/from_zalo`.
`push` mặc định ghi vào `~/Library/Application Support/MemeVaultZaloBridge/outbox`.
Mỗi file được đặt tên theo SHA-256, copy atomically và ghi vào
`~/Library/Application Support/MemeVaultZaloBridge/sync-manifest.json`. Không có thao tác
xóa và không ghi đè file khác hash. Bridge directory được tạo với mode `0700`; manifest chỉ
lưu hash, kích thước, chiều PNG và đường dẫn tương đối để đối chiếu. Khi không cần nữa, có thể
xóa toàn bộ `MemeVaultZaloBridge` bằng Finder; không đụng tới `ZaloData` nếu chưa dùng lệnh
cache thử nghiệm.

Nếu tiến trình bị dừng sau khi file đích đã được publish nhưng trước khi manifest được ghi,
file hash đó có thể tạm thời chưa có entry (orphan). Bridge không tự xóa file để tránh mất dữ
liệu; chạy lại đúng lệnh `pull` hoặc `push` sẽ nhận file là `existing` và ghi bù manifest. Các
file tạm `.part` còn lại chỉ nên dọn thủ công sau khi chắc chắn không có bridge process nào đang
chạy.

## Thử ghi vào cache Zalo (không đảm bảo Zalo nhận diện)

Chỉ dùng khi Zalo Desktop đã đóng và sau khi đã sao lưu `ZaloData`:

```bash
python3 tools/zalo_desktop_bridge.py \
  push "/path/to/memevault-stickers" \
  --zalo-cache --apply --yes
```

Lệnh này chỉ tạo `media/<account-id>/sticker/memevault-bridge/<sha256>.png`; nó không sửa
database/index của Zalo và không xóa sticker cũ. Đây là thử nghiệm `UNKNOWN`: Zalo có thể bỏ
qua các file vì thiếu metadata hoặc danh sách recent. Nếu không muốn ghi cache thật, luôn dùng
`--dry-run` hoặc bỏ `--zalo-cache`.

## Giới hạn hiện tại

- Không có kết nối tự động Android ↔ Mac; cần đưa PNG từ MemeVault sang một thư mục trên Mac.
- Chỉ xử lý PNG, tối đa 32 MB, 16384×16384 và 128 MB payload sau giải nén mỗi file; sprite
  strip của Zalo được giữ nguyên bytes.
- `pull`/`push` là đồng bộ byte ở mức file và manifest, không phải native-sticker sync.
- Không đọc nội dung chat, không sửa database, không dùng private API và không tự động thao tác UI.
