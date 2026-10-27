# Thử nghiệm Zalo Android: `photo_sticker`

MemeVault Android không được phép tự ý ghi vào thư mục riêng của Zalo. Android scoped storage
và quyền sandbox của ứng dụng chặn việc ghi trực tiếp vào `/data/user/0/com.zing.zalo/...` trên
điện thoại production không root.

APK hiện có nút **⋮ → Thử đồng bộ Zalo**. Nút này dùng Android Storage Access Framework: người
dùng tự chọn một thư mục mà Android cho phép (thường là `photo_sticker`/`recent_sticker` nếu
provider hiển thị), sau đó app trao đổi PNG hai chiều qua URI được cấp quyền. Đây vẫn là thử
nghiệm; app không tự tìm private path, không xóa/ghi đè file và không cam kết Zalo index file như
sticker native. Nếu Android không cho chọn thư mục, dùng tool ADB bên dưới trên Mac.

Phần dưới là **tool lab chạy trên Mac qua ADB**, nằm ngoài APK. Tool có thể đọc/ghi những candidate
path mà ADB được cấp quyền; nó không thay thế quyền sandbox của Zalo.

## Tool

```bash
cd /Users/ryantruong/Project/Orther/MemeVault

# Kiểm tra thiết bị, package và các candidate photo_sticker (chỉ đọc)
python3 tools/zalo_android_bridge.py scan

# Xem JSON để gửi lại evidence
python3 tools/zalo_android_bridge.py scan --json

# Kéo sticker đọc được từ điện thoại về bridge local
python3 tools/zalo_android_bridge.py pull

# Kiểm tra trước khi ghi (không tạo thư mục, không ghi thiết bị)
python3 tools/zalo_android_bridge.py push "/path/to/pngs" --dry-run
```

Nếu `scan` tìm thấy đúng thư mục `photo_sticker/recent_sticker`, mới thử ghi sau khi đóng Zalo
trên điện thoại và sao lưu dữ liệu:

```bash
python3 tools/zalo_android_bridge.py push "/path/to/pngs" \
  --remote-dir "/sdcard/Android/data/com.zing.zalo/files/photo_sticker/recent_sticker" \
  --apply --yes
```

Nếu thư mục chưa tồn tại nhưng đã xác định đúng path và ADB có quyền tạo:

```bash
python3 tools/zalo_android_bridge.py push "/path/to/pngs" \
  --remote-dir "/sdcard/Android/data/com.zing.zalo/files/photo_sticker/recent_sticker" \
  --create-remote --apply --yes
```

`sync` thực hiện pull trước rồi push thư mục local:

```bash
python3 tools/zalo_android_bridge.py sync "/path/to/pngs" \
  --remote-dir "/sdcard/Android/data/com.zing.zalo/files/photo_sticker/recent_sticker" \
  --apply --yes
```

Khi có nhiều máy, đặt `--serial SERIAL` trước subcommand. Tool chỉ xử lý PNG hợp lệ, đặt tên
remote theo SHA-256, kiểm tra hash sau `adb push`, publish qua file tạm và `mv -i` để tránh ghi
đè ngoài ý muốn. Không xóa file Zalo, không sửa database/index, không tự động bấm UI. File tạm
remote có thể còn lại nếu ADB bị ngắt; chỉ dọn file `.part` do bridge tạo sau khi chắc chắn bridge
đã dừng.

## Giới hạn và tiêu chí xác nhận

- `FOUND` chỉ có nghĩa là ADB đọc được thư mục; không chứng minh Zalo sẽ index file như sticker.
- Nếu chỉ có `/data/user/0/...` hoặc `/data/data/...`, ADB thường trả permission denied trên máy
  không root; đây là `BLOCKED`, không phải lỗi PNG.
- Tên file/hash và thư mục cô lập là lựa chọn thử nghiệm; schema/index riêng của Zalo chưa được
  biết và không được giả lập.
- Sau khi push, mở Zalo và kiểm tra khay sticker/recent bằng tay, ghi lại model, Android version,
  phiên bản Zalo, path đã dùng, số file và kết quả hiển thị. Kết quả native sticker vẫn là
  `UNKNOWN` cho tới khi có evidence trên đúng thiết bị.
- Nếu không có thiết bị ADB, dùng Sharesheet/clipboard của APK như fallback chính thức; không có
  đường tự động từ app Android sang sandbox của Zalo.
