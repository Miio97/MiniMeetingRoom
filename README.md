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

Mặc định 20 người/phòng. Đây là giới hạn chính sách,
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
config/                Cấu hình cục bộ, không được Git theo dõi
database/              SQL cục bộ, không được Git theo dõi
scripts/               Khởi tạo công cụ, build, chạy và đóng gói
docs/                  Tài liệu cục bộ, không được Git theo dõi
```

Bộ test, probe, benchmark và nhật ký phát triển được lưu ngoài source bàn giao.
Chúng không phải thành phần cần chạy ứng dụng. `.tools`, `target`, `logs` và
`storage` là công cụ/dữ liệu cục bộ; không đưa chúng vào gói source công khai.

## Bảng đóng góp thành viên

Bảng được đối chiếu ngày **26/10/2026** sau khi chạy `git fetch origin --prune`,
theo lịch sử `origin/main` tại commit
[ c83f558 ](https://github.com/Miio97/MiniMeetingRoom/commit/c83f55881443b82dea175131938ad1219b12cb2a)
(PR #32, ngày 24/10/2026). MSSV, họ tên và tên Git do nhóm cung cấp;
công việc và PR được đối chiếu với commit và nhánh nguồn trong lịch sử Git.
Repository dùng GitHub nên cột Merge Requests ghi **Pull Request (PR)**.

| MSSV | Họ và Tên | Công việc đã thực hiện | Merge Requests |
| --- | --- | --- | --- |
| 2380600750 | Nguyễn Hiếu Học (`HieuHoc`) | Xây dựng giao diện client, kết nối database; SFU/signaling và video call realtime; chất lượng video; tối ưu chat, truy vấn, tạo/vào/thoát phòng, bật/tắt mic/camera; dọn source và tài liệu bàn giao. Minh chứng: [f548366](https://github.com/Miio97/MiniMeetingRoom/commit/f548366), [9d1e8a3](https://github.com/Miio97/MiniMeetingRoom/commit/9d1e8a3), [17aab3e](https://github.com/Miio97/MiniMeetingRoom/commit/17aab3e). | [#1](https://github.com/Miio97/MiniMeetingRoom/pull/1), [#6](https://github.com/Miio97/MiniMeetingRoom/pull/6), [#7](https://github.com/Miio97/MiniMeetingRoom/pull/7), [#15](https://github.com/Miio97/MiniMeetingRoom/pull/15), [#16](https://github.com/Miio97/MiniMeetingRoom/pull/16), [#20](https://github.com/Miio97/MiniMeetingRoom/pull/20), [#22](https://github.com/Miio97/MiniMeetingRoom/pull/22), [#24](https://github.com/Miio97/MiniMeetingRoom/pull/24), [#27](https://github.com/Miio97/MiniMeetingRoom/pull/27), [#29](https://github.com/Miio97/MiniMeetingRoom/pull/29), [#32](https://github.com/Miio97/MiniMeetingRoom/pull/32) |
| 2380601469 | Nguyễn Thị Trúc Ngọc (`nguyenngoc205917`) | Xây dựng và sửa truyền tệp; giao diện cài đặt người dùng; tối ưu gửi tệp. Minh chứng: [e53f917](https://github.com/Miio97/MiniMeetingRoom/commit/e53f917), [5e75b0e](https://github.com/Miio97/MiniMeetingRoom/commit/5e75b0e), [58407bc](https://github.com/Miio97/MiniMeetingRoom/commit/58407bc).
| 2380602267 | Lại Mỹ Toàn (`Miio97`) | Khởi tạo dự án Maven; bố cục PreJoin, theme và sidebar; chat realtime, thống kê mạng và giới hạn số người; sửa âm thanh phòng; lưu/khôi phục phiên đăng nhập theo thiết bị; chia sẻ âm thanh hệ thống; tích hợp các nhánh; dọn source và tài liệu bàn giao. Minh chứng: [151aaba](https://github.com/Miio97/MiniMeetingRoom/commit/151aaba), [1f9d03b](https://github.com/Miio97/MiniMeetingRoom/commit/1f9d03b), [45bab98](https://github.com/Miio97/MiniMeetingRoom/commit/45bab98). | [#3](https://github.com/Miio97/MiniMeetingRoom/pull/3), [#11](https://github.com/Miio97/MiniMeetingRoom/pull/11), [#13](https://github.com/Miio97/MiniMeetingRoom/pull/13), [#14](https://github.com/Miio97/MiniMeetingRoom/pull/14), [#19](https://github.com/Miio97/MiniMeetingRoom/pull/19), [#21](https://github.com/Miio97/MiniMeetingRoom/pull/21), [#23](https://github.com/Miio97/MiniMeetingRoom/pull/23), [#26](https://github.com/Miio97/MiniMeetingRoom/pull/26), [#28](https://github.com/Miio97/MiniMeetingRoom/pull/28), [#30](https://github.com/Miio97/MiniMeetingRoom/pull/30) |
| 2380601083 | Trần Văn Khoa (`VanKhoa2005`) | Xây dựng đăng nhập/đăng ký, tạo phòng; sửa logic phòng; xác thực email qua Gmail/OTP. Minh chứng: [5a678d3](https://github.com/Miio97/MiniMeetingRoom/commit/5a678d3), [7c4ee89](https://github.com/Miio97/MiniMeetingRoom/commit/7c4ee89), [4e27beb](https://github.com/Miio97/MiniMeetingRoom/commit/4e27beb), [c1890e1](https://github.com/Miio97/MiniMeetingRoom/commit/c1890e1). | [#4](https://github.com/Miio97/MiniMeetingRoom/pull/4), [#10](https://github.com/Miio97/MiniMeetingRoom/pull/10), [#18](https://github.com/Miio97/MiniMeetingRoom/pull/18), [#25](https://github.com/Miio97/MiniMeetingRoom/pull/25) |
| 2380600544 | Phạm Quý Gia Đức (tên nhóm cung cấp: `pduc2905`) | Quản lý phòng, chat realtime, theo dõi/thống kê mạng; tích hợp phòng, chat, video và truyền tệp trên nhánh `Duc`. Minh chứng: [94ac1a4](https://github.com/Miio97/MiniMeetingRoom/commit/94ac1a4), [85fff88](https://github.com/Miio97/MiniMeetingRoom/commit/85fff88), [33a7df0](https://github.com/Miio97/MiniMeetingRoom/commit/33a7df0). | [#9](https://github.com/Miio97/MiniMeetingRoom/pull/9) |
