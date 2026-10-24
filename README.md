# Mini Meeting Room

Ứng dụng họp trực tuyến cho Windows, gồm Java/JavaFX client, Java server,
signaling TCP/TLS, Go/Pion SFU và MySQL. Hai điểm chạy chính là `client.Main`
và `server.Main`.

## Chức năng

- Đăng ký/xác minh email bằng OTP, đăng nhập, khôi phục mật khẩu và quản lý tài khoản.
- Sảnh phòng realtime, tạo/join phòng, lịch sử, mở lại phòng, khóa phòng và duyệt thành viên.
- Chuyển host, mời thành viên ra khỏi phòng và đóng cuộc họp.
- Camera WebRTC, thoại Opus, chia sẻ màn hình và âm thanh hệ thống trên Windows.
- Chat realtime và truyền tệp TCP/TLS với SHA-256.
- Thống kê media tại client, chất lượng video AUTO tối đa 720p/30 FPS.
- Phục hồi signaling tối đa hai phút; dọn nội dung phòng đóng trước khi mở lại.

Mặc định 20 người/phòng, trần cấu hình 50. Đây là giới hạn chính sách,
không phải cam kết 50 người cùng phát media trên mọi máy server.

## Chạy nhanh

1. Giữ cấu hình server riêng, chứng chỉ và database đã hoạt động; không chép mẫu đè lên chúng.
2. Trong NetBeans, Run File `src/main/java/server/Main.java`; chờ `MMR_MEETING_SERVER_READY`.
3. Run File `src/main/java/client/Main.java`, hoặc Run Project.

PowerShell, từ thư mục dự án:

```powershell
./scripts/start-meeting-server.ps1
# Terminal khác:
./scripts/start-client.ps1
```

Cấu hình máy hiện tại dùng server Radmin `26.10.177.30`, cổng auth 8443,
signaling 8444 và tệp 9001. Máy khác cần cùng mạng Radmin và đúng chứng chỉ
công khai của server. Không đưa mật khẩu database hoặc keystore cho client.

## Cấu trúc

```text
src/main/java/client/   Giao diện, session và media client
src/main/java/server/   Auth, phòng, signaling, tệp và điều khiển SFU
src/main/java/common/   DTO, giao thức và validation dùng chung
src/main/resources/    CSS và helper âm thanh hệ thống Windows
media/video/           Go/Pion SFU production
config/                Mẫu cấu hình; file riêng trên máy được Git bỏ qua
database/              Schema nền, migration, procedure và rollback
scripts/               Khởi tạo công cụ, build, chạy và đóng gói
docs/                  Tài liệu chính thức
```

Bộ test, probe, benchmark và nhật ký phát triển được lưu ngoài source bàn giao.
Chúng không phải thành phần cần chạy ứng dụng. `.tools`, `target`, `logs` và
`storage` là công cụ/dữ liệu cục bộ; không đưa chúng vào gói source công khai.

## Tài liệu

- [Kiến trúc và luồng hoạt động](docs/architecture.md)
- [Cài đặt, NetBeans, local và Radmin](docs/running.md)
- [Database và stored procedure](docs/database.md)
- [Đóng gói client/server](docs/packaging.md)
- [Hướng dẫn đọc source](docs/source-guide.md)
- [Kiểm chứng và giới hạn bàn giao](docs/verification.md)

Các script SQL phải được người quản trị xem xét và chạy thủ công. Server không
tự áp dụng migration. Dọn source không đồng nghĩa xóa dữ liệu database/storage.
