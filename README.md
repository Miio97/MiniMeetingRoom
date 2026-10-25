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

## Bảng đóng góp thành viên

Bảng được đối chiếu ngày **26/10/2026** sau khi chạy `git fetch origin --prune`,
theo lịch sử `origin/main` tại commit
[ c83f558 ](https://github.com/Miio97/MiniMeetingRoom/commit/c83f55881443b82dea175131938ad1219b12cb2a)
(PR #32, ngày 24/10/2026). MSSV, họ tên và tên Git do nhóm cung cấp;
công việc và PR được đối chiếu với commit và nhánh nguồn trong lịch sử Git.
Repository dùng GitHub nên cột Merge Requests ghi **Pull Request (PR)**.

| MSSV | Họ và Tên | Công việc đã thực hiện | Merge Requests |
| --- | --- | --- | --- |
| 2380600750 | Nguyễn Hiếu Học (`HieuHoc`) | Xây dựng giao diện client, kết nối database, ghi log phần cứng; SFU/signaling và video call realtime; chất lượng video; tối ưu chat, truy vấn, tạo/vào/thoát phòng, bật/tắt mic/camera; dọn source và tài liệu bàn giao. Minh chứng: [f548366](https://github.com/Miio97/MiniMeetingRoom/commit/f548366), [9d1e8a3](https://github.com/Miio97/MiniMeetingRoom/commit/9d1e8a3), [17aab3e](https://github.com/Miio97/MiniMeetingRoom/commit/17aab3e). | [#1](https://github.com/Miio97/MiniMeetingRoom/pull/1), [#6](https://github.com/Miio97/MiniMeetingRoom/pull/6), [#7](https://github.com/Miio97/MiniMeetingRoom/pull/7), [#15](https://github.com/Miio97/MiniMeetingRoom/pull/15), [#16](https://github.com/Miio97/MiniMeetingRoom/pull/16), [#20](https://github.com/Miio97/MiniMeetingRoom/pull/20), [#22](https://github.com/Miio97/MiniMeetingRoom/pull/22), [#24](https://github.com/Miio97/MiniMeetingRoom/pull/24), [#27](https://github.com/Miio97/MiniMeetingRoom/pull/27), [#29](https://github.com/Miio97/MiniMeetingRoom/pull/29), [#32](https://github.com/Miio97/MiniMeetingRoom/pull/32) |
| 2380601469 | Nguyễn Thị Trúc Ngọc (`nguyenngoc205917`) | Xây dựng và sửa truyền tệp; giao diện cài đặt người dùng; tối ưu gửi tệp. Minh chứng: [e53f917](https://github.com/Miio97/MiniMeetingRoom/commit/e53f917), [5e75b0e](https://github.com/Miio97/MiniMeetingRoom/commit/5e75b0e), [58407bc](https://github.com/Miio97/MiniMeetingRoom/commit/58407bc). Phần thay đổi của PR #31 đã bị hoàn nguyên sau khi merge, xem ghi chú bên dưới. | [#5](https://github.com/Miio97/MiniMeetingRoom/pull/5), [#12](https://github.com/Miio97/MiniMeetingRoom/pull/12), [#17](https://github.com/Miio97/MiniMeetingRoom/pull/17), [#31](https://github.com/Miio97/MiniMeetingRoom/pull/31) |
| 2380602267 | Lại Mỹ Toàn (`Miio97`) | Khởi tạo dự án Maven; bố cục PreJoin, theme và sidebar; chat realtime, thống kê mạng và giới hạn số người; quản lý lịch hẹn; sửa âm thanh phòng; lưu/khôi phục phiên đăng nhập theo thiết bị; chia sẻ âm thanh hệ thống; tích hợp các nhánh. Minh chứng: [151aaba](https://github.com/Miio97/MiniMeetingRoom/commit/151aaba), [1f9d03b](https://github.com/Miio97/MiniMeetingRoom/commit/1f9d03b), [45bab98](https://github.com/Miio97/MiniMeetingRoom/commit/45bab98). | [#3](https://github.com/Miio97/MiniMeetingRoom/pull/3), [#11](https://github.com/Miio97/MiniMeetingRoom/pull/11), [#13](https://github.com/Miio97/MiniMeetingRoom/pull/13), [#14](https://github.com/Miio97/MiniMeetingRoom/pull/14), [#19](https://github.com/Miio97/MiniMeetingRoom/pull/19), [#21](https://github.com/Miio97/MiniMeetingRoom/pull/21), [#23](https://github.com/Miio97/MiniMeetingRoom/pull/23), [#26](https://github.com/Miio97/MiniMeetingRoom/pull/26), [#28](https://github.com/Miio97/MiniMeetingRoom/pull/28), [#30](https://github.com/Miio97/MiniMeetingRoom/pull/30) |
| 2380601083 | Trần Văn Khoa (`VanKhoa2005`) | Xây dựng đăng nhập/đăng ký, tạo phòng; sửa logic phòng; xác thực email qua Gmail/OTP. Minh chứng: [5a678d3](https://github.com/Miio97/MiniMeetingRoom/commit/5a678d3), [7c4ee89](https://github.com/Miio97/MiniMeetingRoom/commit/7c4ee89), [4e27beb](https://github.com/Miio97/MiniMeetingRoom/commit/4e27beb), [c1890e1](https://github.com/Miio97/MiniMeetingRoom/commit/c1890e1). | [#4](https://github.com/Miio97/MiniMeetingRoom/pull/4), [#10](https://github.com/Miio97/MiniMeetingRoom/pull/10), [#18](https://github.com/Miio97/MiniMeetingRoom/pull/18), [#25](https://github.com/Miio97/MiniMeetingRoom/pull/25) |
| 2380600544 | Phạm Quý Gia Đức (tên nhóm cung cấp: `pduc2905`) | Quản lý phòng, chat realtime, theo dõi/thống kê mạng; tích hợp phòng, chat, video và truyền tệp trên nhánh `Duc`. Minh chứng Git ghi tác giả `PhamQuyGiaDuc` và người merge `pduc2950`: [94ac1a4](https://github.com/Miio97/MiniMeetingRoom/commit/94ac1a4), [85fff88](https://github.com/Miio97/MiniMeetingRoom/commit/85fff88), [33a7df0](https://github.com/Miio97/MiniMeetingRoom/commit/33a7df0). | [#9](https://github.com/Miio97/MiniMeetingRoom/pull/9) |

### Cách đối chiếu và giới hạn thống kê

- Gán PR theo **nhánh nguồn chứa công việc** (`Hoc`, `Ngoc`, `Miio`,
  `Khoa`, `Duc`), không theo người bấm merge. Các bí danh tác giả xuất hiện
  trong Git gồm `HieuHoc`/`NguyenHieuHoc`, `Ngoc`/`nguyenngoc205917`,
  `Miio97`/`Tone` (email GitHub chứa `Miio97`),
  `TranVanKhoa_1083`/`VanKhoa2005` và `PhamQuyGiaDuc`/`pduc2950`.
- Tên `pduc2905` do nhóm cung cấp khác `pduc2950` trong lịch sử Git.
  Dòng của Đức được đối chiếu theo tác giả `PhamQuyGiaDuc`, email commit
  và nhánh `Duc`; chưa xác minh hai tên tài khoản GitHub là cùng một người.
- [PR #2](https://github.com/Miio97/MiniMeetingRoom/pull/2) là merge từ `main` vào `Miio`,
  được ghi nhận là đồng bộ nhánh, không cộng thành PR tính năng riêng.
  Không suy diễn PR #8 vì không có merge tương ứng trong lịch sử đang kiểm tra.
- PR #31 có trong lịch sử nhưng đã được hoàn nguyên bởi
  [348ca5b](https://github.com/Miio97/MiniMeetingRoom/commit/348ca5b) để khôi phục nội dung về [fe88a48](https://github.com/Miio97/MiniMeetingRoom/commit/fe88a48).
  Bảng ghi nhận công việc từng thực hiện, không khẳng định mọi tính năng lịch sử
  (ví dụ lịch hẹn, thống kê) còn nguyên trong phiên bản bàn giao.
- Git lưu commit và quan hệ nhánh, **không lưu đầy đủ số lần/lệnh push hay người
  thực hiện push**. Commit author, người merge PR và người push có thể khác nhau.
  Không dùng số commit hoặc số PR để suy ra số lần push hay tỷ lệ đóng góp.
  Bảng này dựa trên lịch sử Git đã fetch; chưa đối chiếu GitHub Events/audit log
  hoặc trạng thái PR trên API GitHub.

## Vệ sinh kho mã nguồn

Dự án dùng Java/JavaFX với Maven và Go/Pion. `.gitignore` loại trừ thư mục
build (`target/`, `build/`, `dist/`, `out/`, `bin/`, `node_modules/`),
cấu hình cá nhân (`.idea/`, `.vscode/`, NetBeans private), file biên dịch,
gói nhị phân (`.jar`, `.zip`, `.exe`, v.v.), công cụ cục bộ,
log, storage và cấu hình server riêng. Giữ lại `pom.xml`, `go.mod`,
`go.sum`, mã nguồn và các file cấu hình `.example`.

Lần kiểm tra ngày 26/10/2026 phát hiện `.vscode/extensions.json` đang được
Git theo dõi; file này được bỏ khỏi index và giữ trên máy. Không phát hiện
thư mục build hoặc file `.jar`/`.zip` đang được theo dõi trong snapshot
đã kiểm tra; không có blob từ 10 MiB trở lên trong lịch sử `origin/main`.
Bỏ theo dõi file không xóa nó khỏi các commit cũ; lịch sử Git không được viết lại.

## Tài liệu

- [Kiến trúc và luồng hoạt động](docs/architecture.md)
- [Cài đặt, NetBeans, local và Radmin](docs/running.md)
- [Database và stored procedure](docs/database.md)
- [Đóng gói client/server](docs/packaging.md)
- [Hướng dẫn đọc source](docs/source-guide.md)
- [Kiểm chứng và giới hạn bàn giao](docs/verification.md)

Các script SQL phải được người quản trị xem xét và chạy thủ công. Server không
tự áp dụng migration. Dọn source không đồng nghĩa xóa dữ liệu database/storage.
