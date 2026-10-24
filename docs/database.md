# Database

Ứng dụng dùng MySQL; máy hiện tại kết nối Railway bằng cấu hình server riêng.
Không đặt credentials trong client hoặc source công khai. Server không tự
chạy schema/migration và không tự tạo procedure.

## Các file SQL cần giữ

| File | Vai trò |
|---|---|
| `database/schema.sql` | Sáu bảng nền: người dùng, phiên, phòng, thành viên, tin nhắn, tệp |
| `migrations/001_file_transfer.sql` | Bổ sung truyền tệp cho schema cũ; không chạy nếu cấu trúc đã có |
| `migrations/001-meeting-runtime.sql` | Ownership/runtime/receipt phòng |
| `migrations/002-email-otp.sql` | Bảng OTP và giới hạn gửi |
| `migrations/002_lich_hen.sql` | Tạo bảng lịch hẹn nếu chưa có |
| `migrations/003_simplify_schedule.sql` | Chỉ dành cho bảng lịch hẹn còn các cột cũ |
| `migrations/004_drop_network_statistics.sql` | Loại bảng thống kê cũ; thao tác phá hủy cần xem xét riêng |
| `procedures/005-install-all.sql` | Installer procedure create/join/exit, có preflight và truy vấn kiểm tra |
| `manual/`, `rollback/` | Công cụ quản trị và phương án khôi phục cần đọc trước khi chạy |

Tên migration là lịch sử từ các nhánh phát triển, không phải danh sách để
chạy máy móc theo thứ tự số. `schema.sql` hiện chưa gồm OTP, runtime và lịch
hẹn; một số comment cũ trong migration lịch hẹn nói đã có trong schema nền
không còn chính xác với file đang bàn giao. Hãy đối chiếu cấu trúc thật.

Database mới: dựng schema nền, kiểm tra các bổ sung cần thiết rồi cài runtime,
OTP, lịch hẹn và procedure tương ứng. Database hiện có: chỉ áp dụng phần thiếu
sau khi sao lưu/kiểm tra. Không chạy lại DDL đang tồn tại, không chạy rollback
hoặc DROP chỉ vì nằm trong thư mục database. Đợt dọn source không đổi SQL.

## Stored procedure

`005-install-all.sql` chứa định nghĩa từ `005-room-operations-v1.sql`, không
chạy cả hai để cài trùng. Dùng công cụ hỗ trợ DELIMITER, chọn đúng database,
dừng ở lỗi đầu tiên. Installer không có nghĩa mọi phiên bản MySQL/schema
đều tương thích; phải kiểm tra kết quả và quyền CREATE/EXECUTE.

Java hiện gọi `mmr_create_room_v1`, `mmr_join_room_v1`, `mmr_exit_room_v1`
qua `RoomRoutineClient`. Procedure hỗ trợ kiểm tra/transaction và receipt;
Java vẫn quản lý quyền live, admission, generation, media và tệp vật lý.
Mở lại phòng còn phụ thuộc `RoomReopenGuard`/`ImmediateRoomCleanup`, không
chỉ phụ thuộc bảng rỗng. Không gộp transaction MySQL với xóa tệp như thể có
thể rollback cả hai.

Bộ `005-room-preflight.sql`, định nghĩa riêng, installer và rollback được giữ
để quản trị/đối chiếu. Generator và bài thử procedure đã chuyển ra archive;
không cần Python để chạy ứng dụng.
