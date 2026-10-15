# Phòng/signaling SFU — Mốc 1 sau khi ghép code

Luồng SFU dùng `server.meeting.RoomService`, `server.signalingapi` và `client.meeting.MeetingSession`. Server xác thực token opaque, kiểm tra membership/quyền và phát sự kiện qua TCP/TLS lâu dài. Giao diện dùng `RoomSnapshot`/`ParticipantView` thật. **Video đã được tích hợp sau Mốc 1; xem [video-call.md](video-call.md) để chạy và chọn camera.** Các kết quả/giới hạn Mốc 1 dưới đây là lịch sử của bước phòng/signaling.

Tại Mốc 1, luồng auth và signaling có lối vào riêng. Bản hiện tại đã nối các form tạo/join chính với signaling, PreJoinScreen và MeetingScreen; RoomSnapshot mang RoomInfo thật để tab Tệp dùng cùng phòng/membership. Kênh auth vẫn không được join/get/leave phòng signaling; danh sách phòng gần đây đọc cả hai loại. Chat và lệnh quản trị dùng cầu nối `RoomRequest`/`ChatRequest` trên auth TLS, xác minh cùng token và participant ID của phiên signaling; không tạo thêm membership. Xem `video-call.md` cho hướng dẫn hiện tại.

## Chạy trên cùng máy

Migration `database/migrations/001-meeting-runtime.sql` đã được người dùng báo áp dụng thành công. Server chỉ kiểm tra schema, không tự áp dụng migration. Nếu bật dịch vụ tệp, schema/migration tệp của đồng đội cũng cần sẵn có theo `docs/file-transfer.md`.

Giữ cấu hình Railway hiện tại trong file server được Git bỏ qua. Settings công khai có mẫu `config/meeting.properties.example`; mặc định signaling bind `127.0.0.1`, client hostname `localhost`, cổng `8444`, frame `131072` byte, tối đa 64 kết nối và 20 request/giây/kết nối. Giới hạn phòng SFU mặc định 10 là chính sách MVP, chưa phải năng lực media đã đo. `signaling.maxConnections` và `signaling.requestsPerSecond` có thể cấu hình cùng `signaling.maxBytes`.

1. Dừng server cũ đang dùng cổng 8443/9001 bằng Ctrl+C ở terminal của nó.
2. Trong terminal server chạy:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\start-meeting-server.ps1
   ```

   Entry point `server.MeetingServerMain` khởi động auth/phòng cũ trên 8443, signaling trên 8444 và dịch vụ tệp trên cổng cấu hình (mặc định 9001) nếu được bật. Cả ba dùng TLS hiện có và cùng một diagnostics owner. Không chạy đồng thời với `start-server.ps1` vì trùng cổng. Dừng bằng Ctrl+C.

3. Hai terminal client, mỗi terminal chạy:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\start-client.ps1
   ```

4. Đăng nhập bằng hai tài khoản khác nhau. Từ sảnh dùng **Tạo phòng / Tham gia phòng** có sẵn. Client đầu tạo phòng với PIN; client thứ hai nhập mã phòng và PIN. Hai màn hình thấy cùng thành viên. Chủ phòng có khóa/mở khóa và mời thành viên ra; rời phòng cập nhật màn hình còn lại. Chủ phòng rời thì quyền chủ tự chuyển cho thành viên còn lại; chọn Đóng phòng mới kết thúc cho mọi người. Một tài khoản chỉ có một membership đang sống trong cùng phòng. Chọn camera trên màn hình trước khi vào phòng hoặc trong màn hình họp theo `video-call.md`.

Chuyển view chỉ gỡ listener của view; `SceneManager` sở hữu `MeetingSession`. Rời phòng đóng signaling; logout/đóng ứng dụng hủy session và các yêu cầu chờ. Mất kết nối không tự bật lại thiết bị. Certificate được kiểm tra trust và hostname, không dùng trust-all.

## Đã kiểm thử và giới hạn

`MeetingSignalingTest` có 11 bài integration: hai TLS client đồng bộ thành viên/trạng thái; retry create/join; sai PIN/capacity/cross-room/quyền host; token sai/hết hạn/thu hồi/tài khoản vô hiệu; logout qua auth server làm periodic validation dừng signaling; identity cũ; authentication có frame limit sai không cấp phiên; cách ly kênh phòng cũ; hai màn hình JavaFX tạo/join/leave; từ chối certificate/hostname sai; shutdown với 8 worker đang chạy và một yêu cầu chờ, cleanup đúng một lần.

`SignalWireTest` có 3 bài với nhiều input: frame lớn hơn auth, giới hạn byte/depth, UTF-8 sai, frame thiếu, số bị ép kiểu/tràn, field lạ và JSON thừa. Tests chạy H2 riêng, TLS certificate tạm và JavaFX window không hiển thị, không tạo dữ liệu test trên Railway. SQL migration được dùng trong H2 sau khi bỏ comment, MySQL charset/collation và ENGINE; đây không phải kiểm thử MySQL collation.

```powershell
. .\scripts\common.ps1
$taskMaven = Resolve-Maven
& $taskMaven -B '-Dtest=MeetingSignalingTest,SignalWireTest' test
& $taskMaven -B test
```

Tại thời điểm Mốc 1, SDP/ICE/publish/subscribe trả `MEDIA_NOT_READY` sau kiểm tra membership. Bản video hiện dùng `VIDEO_PUBLISH`/`VIDEO_SUBSCRIBE`/`VIDEO_STOP`/`VIDEO_STATE` và Go/Pion thật; không có số đo media ngẫu nhiên. Media thiết bị thật, hai máy LAN, screenshare và cuộc gọi 30 phút chưa nghiệm thu; xem hướng dẫn video mới.

## Sửa mất kết nối khi Railway phản hồi chậm

Log thực tế ngày 2026-10-14 cho thấy authenticate mất 3.7–4.7 giây và create room mất 11.4–12.3 giây. Chính sách cũ đóng signaling khi lần xác thực gần nhất quá 5 giây, dù DB revalidation đang chạy. Không gọi đây là lỗi PIN hoặc thiếu migration.

`DatabaseConnection` hiện dùng pool HikariCP 7.0.2: mặc định tối đa 8 kết nối vật lý, chờ mượn tối đa 5000 ms; close Connection trả về pool và reset transaction. Server thiết lập một kết nối trước khi sẵn sàng phục vụ, đóng pool sau các service. `slf4j-nop` 2.0.17 giữ cấu hình pool/bí mật JDBC khỏi console; diagnostics vẫn ghi mã/loại lỗi an toàn. Cấu hình mẫu có `db.pool.maxConnections` (1–32) và `db.pool.waitMillis` (250–10000).

Revalidation chạy sau 3 giây từ lần xác nhận gần nhất, với deadline tối đa 10 giây từ lúc xếp lần kiểm tra vào executor. Hết hạn theo timestamp đã biết đóng trong vòng một tick (1 giây), không chờ DB. Logout trên server kết hợp invalidates ngay; thu hồi từ DB bên ngoài có thể cần tới khoảng 14 giây để kiểm tra xong hoặc fail closed. Mọi thao tác phòng vẫn xác thực DB mới, không dùng cache để cấp quyền. Chỉ số này là giới hạn theo scheduler, chưa phải đo SLA media; Mốc 1 chưa có media.

Thêm test DB chậm 6 giây không làm đóng phiên hợp lệ, test pool rollback/reset/reuse/giới hạn chờ, và lặp bài shutdown 3 lần. Bộ regression sau sửa: 181 bài, không failure/error, 2 skip. `signaling.connection.closed` ghi reason từ allowlist để phân biệt timeout, expiry và lỗi mạng. Các thay đổi cần restart cả server/client; Run File không cập nhật JVM đã chạy.

[Mã nguồn phiên bản HikariCP được cố định](https://github.com/brettwooldridge/HikariCP/tree/HikariCP-7.0.2) và [cấu hình chính thức](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby). Công cụ `server.database.MeasureDatabaseLatency` chỉ thực hiện `SELECT 1` ở chế độ read-only và in thời gian, không tạo dữ liệu thử Railway.

Probe chỉ đọc Railway sau sửa đã PASS: lần khởi tạo/mượn đầu 3803 ms; ba lần mượn lại 0.012–0.017 ms; đặt read-only và truy vấn `SELECT 1` mất 583–666 ms. Đây là thời gian mượn kết nối/truy vấn đơn, không phải latency tạo/join phòng. Kết quả ở `target/db-latency-check.txt`; test pool sau thay đổi khởi tạo cũng PASS (2 bài). Cần người dùng restart và thử lại thao tác tạo/join trên ứng dụng.

Sau lần người dùng xác nhận chạy ổn, đã tối ưu push snapshot, polling, danh sách sảnh và truy vấn chỉ đọc; xem [ui-responsiveness.md](ui-responsiveness.md). Các phép đo tạo/join trước tối ưu vẫn là baseline, chưa coi là thời gian sau tối ưu.
