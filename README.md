# Kayji-LockEnd

Plugin Minecraft (Spigot) khóa/mở chiều **The End** — chặn người chơi vào End theo lịch, tự động mở khóa vào thời điểm đã định và đếm ngược thông báo cho cả server.

> **Tác giả:** Kayji_tizi · **Phiên bản:** 1.0 · **API:** 1.16+ · **Java:** 17

## Tính năng

- **Khóa / mở The End** bằng lệnh `/lockend`, trạng thái được lưu vào `config.yml` và giữ nguyên sau khi restart.
- **Chặn mọi đường vào End**: portal (`PlayerPortalEvent`), teleport (`PlayerTeleportEvent`), entity chở người chơi (`EntityPortalEvent`) — kể cả khi plugin được load giữa chừng.
- **Đuôi người chơi đang ở trong End** về spawn Overworld khi khóa.
- **Mở khóa tự động theo lịch** (`auto-unlock`): giờ, thứ trong tuần, ngày trong tháng, timezone — chống chạy trùng nhờ cờ `last-run`.
- **Đếm ngược toàn server** ở các mốc: 1 ngày, 12 giờ, 6 giờ, 1 giờ, 30 phút, 10 phút, 5 phút, 1 phút.
- **Thông báo thời gian mở End** khi người chơi join và sau mỗi lệnh `/lockend`.
- Tab-complete và kiểm tra quyền `lockend.admin`.

## Bảng lệnh

Gõ trong game với dấu `/`, có tab-complete:

| Lệnh | Quyền | Mô tả |
| --- | --- | --- |
| `/lockend lock` | `lockend.admin` | Khóa The End, đẩy người chơi đang ở trong End về Overworld |
| `/lockend unlock` | `lockend.admin` | Mở khóa The End |
| `/lockend reload` | `lockend.admin` | Tải lại `config.yml` và lên lịch auto-unlock |

> Quyền `lockend.admin` — mặc định chỉ `op`.

## Cấu hình

```yaml
lock-end: true                  # true = đang khóa, false = đang mở
kick-message: "&cThe End chua duoc mo khoa!"

auto-unlock:
  enabled: true
  day-of-week: SATURDAY         # MONDAY..SUNDAY, bỏ trống nếu không dùng
  day-of-month: 11              # 1-31, đặt -1 nếu không kiểm tra
  time: "17:50"                 # giờ mở khóa (định dạng 24h)
  timezone: "Asia/Ho_Chi_Minh"
  last-run: ""                  # tự cập nhật, không sửa tay
```

Nếu `day-of-week` và `day-of-month` cùng được đặt thì cả hai phải khớp mới mở khóa.

## Cài đặt

```bash
mvn clean package
```

Copy `target/Kayji-LockEnd-1.0-SNAPSHOT.jar` vào thư mục `plugins/` rồi restart server.

> Maven Shade Plugin được dùng để đóng gói JDA vào jar (loại trừ `spigot-api`).

## Cấu trúc dự án

```
├── pom.xml                          Maven + Shade (Java 17)
└── src/main
    ├── java/com/example/lockend
    │   └── LockEndPlugin.java       Logic khóa/mở End + auto-unlock + đếm ngược
    └── resources
        ├── plugin.yml               Lệnh, quyền, metadata
        └── config.yml               Trạng thái khóa + lịch mở tự động
```

## Giấy phép

[GNU General Public License v3.0](LICENSE)
