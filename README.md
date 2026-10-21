# MiniMeetingRoom

Cập nhật 21/10/2026: [cleanup ngay khi đóng phòng, chat pending, sảnh push và giới hạn media](docs/runtime-performance-cleanup.md). Không cần migration mới cho đợt thay đổi này.

Thống kê media lấy cục bộ mỗi 500 ms, giữ tối đa 40 mẫu trong RAM; không gửi telemetry định kỳ hoặc lưu database. Chất lượng chỉ AUTO, khởi đầu LOW và tối đa 720p/30 FPS; giảm video trước, chỉ tạm dừng khi thoại bị ảnh hưởng kéo dài. Database hiện có dùng [migration 004 xóa bảng thống kê và lịch sử](database/migrations/004_drop_network_statistics.sql), chạy thủ công sau khi nâng cấp server. Xem [kiến trúc, cấu hình, kiểm thử và backup/rollback](docs/client-stats-migration.md). Đã sửa giới hạn hiển thị 8–10 FPS, cách chọn mode camera và điều kiện nâng HD: [bằng chứng trước/sau](docs/video-quality-fix.md).

Mất kết nối signaling sẽ giữ thành viên và vai trò chủ phòng tối đa **2 phút** để client tự kết nối lại cùng phiên. Hết hạn, giao diện tự thoát cuộc họp; kết nối lại thành công giữ quyền gửi tệp. Camera/mic/chia sẻ cần bật lại bằng tay sau mất kết nối. Cần build và khởi động lại cả server lẫn client, không thêm migration. Xem [khôi phục kết nối phòng](docs/meeting-recovery.md).

Cập nhật điều khiển phòng: sảnh tự cập nhật khoảng 2 giây/lần; quản lý phòng nằm trong Cài đặt; tab Chờ duyệt bên phải dành cho host, bật khi phòng yêu cầu duyệt, yêu cầu hết hạn sau 5 phút; host chọn người kế nhiệm hoặc đóng phòng khi rời; icon mắt ẩn/hiện thanh công cụ. Không cần migration mới. Xem [luồng và kiểm thử](docs/room-controls.md).

Ứng dụng desktop Java 21/JavaFX. Xác thực, tạo/tham gia/rời phòng, danh sách thành viên, video call và chia sẻ tệp đã kết nối backend. Form tạo/tham gia có sẵn dùng signaling TCP/TLS cho phòng mới; dữ liệu tệp dùng dịch vụ TCP/TLS riêng với cùng membership. Chat văn bản thời gian thực, duyệt thành viên, khóa phòng, mời ra và chuyển chủ dùng cùng phòng với video và tệp. Khi chủ phòng chủ động rời, giao diện yêu cầu chọn người kế nhiệm hoặc đóng cả phòng; mất kết nối đột ngột vẫn dùng cơ chế chuyển chủ dự phòng. Phòng mới có voice chat Opus và chia sẻ màn hình/cửa sổ VP8 độc lập camera.

## Truyền tệp qua TCP/TLS

Tab **Tệp** hỗ trợ chọn tệp, tải lên/tải xuống theo luồng, kiểm tra SHA-256, tiến độ, tạm dừng, tiếp tục và hủy. Server kiểm tra phiên cùng quyền thành viên phòng; client cùng phòng cập nhật metadata mỗi 3 giây. `server.AuthServer` hiện khởi động cả auth trên cổng **8443** và dịch vụ tệp riêng trên cổng **9001**.

Với database đã tồn tại, áp dụng **một lần** [migration 001](database/migrations/001_file_transfer.sql) trước khi bật dịch vụ tệp; server không tự sửa database. Database mới dùng [schema.sql](database/schema.sql), không chạy thêm migration này. Xem [báo cáo, cấu hình, hướng dẫn hai client và giới hạn hiện tại](docs/file-transfer.md). Phần hướng dẫn auth bên dưới vẫn áp dụng; khi dùng tệp qua LAN cần mở thêm cổng TCP 9001 và cấu hình `file.transfer.bind`.

Video qua WebRTC/Pion chạy ngay từ **Tạo phòng / Tham gia phòng → trước khi vào phòng → màn hình họp**, không cần nút SFU riêng. Camera xem trước chỉ hiển thị cục bộ; vào phòng mới gửi video nếu bạn đã bật. Các bài native integration đã kiểm thử 2/3 client với nguồn hình tạo sẵn, gồm hai JVM cùng máy, giao diện chính và gửi/tải tệp trong cùng phòng; camera thật cần người dùng chủ động nghiệm thu. Xem [cách chạy video call](docs/video-call.md), [kiến trúc](docs/sfu-architecture.md) và [kiểm thử](docs/sfu-testing.md).

Camera chỉ hỏi quyền khi nhấn **Bật camera** nếu chưa được cho phép; quyền đã chấp nhận được ghi nhớ trên máy. Từ chối hoặc đóng hộp thoại sẽ không cho vào giao diện họp; có thể nhấn Bật camera để thử lại. Không còn nút Quyền camera/Tìm camera. Phòng chờ bỏ các dòng đếm camera, mô tả kỹ thuật và thông báo tính năng chưa triển khai; chỉ hiện lỗi khi cần. Nút **Ẩn/Hiện thanh công cụ** trong cuộc họp giúp tăng vùng video mà không dừng media. Quyền camera của Windows vẫn cần cho phép ứng dụng desktop truy cập.

Chọn thiết bị ở **Cài đặt** trong cuộc họp; danh sách ẩn các camera ảo OBS/ByteCast và chỉ đếm camera còn lại. Nút **Camera** nhận nhấn liên tục, áp dụng trạng thái cuối cùng thay vì xếp hàng nhiều lần mở thiết bị; thời gian capture/WebRTC vẫn phụ thuộc thiết bị và kết nối. Driver camera đọc chậm được tách khỏi worker nhận video; định dạng có timeout trong Java helper riêng và được tái sử dụng trong phiên để bật lại nhanh hơn. **Sáng/Tối** trong Cài đặt đổi đồng thời giao diện cuộc họp và dialog. Thử hai cửa sổ với một webcam: bật camera ở một cửa sổ, để camera cửa sổ kia tắt để kiểm tra nhận hình. Không cần migration database cho bản sửa này.

## Chạy trên máy phát triển

Trong NetBeans, **Run File** `src/main/java/server/Main.java` để khởi động toàn bộ server; chờ `MMR_MEETING_SERVER_READY`, sau đó **Run File** `src/main/java/client/Main.java`. `server.Main` dùng cùng bộ khởi động với script dưới đây, bao gồm auth, phòng, signaling, truyền tệp (theo cấu hình) và media video.

Cấu hình sử dụng hiện tại: **server Java và client JavaFX chạy trên máy local; database MySQL vẫn ở Railway**. Cần JDK 21 trở lên, Maven (hoặc Maven đi kèm NetBeans), kết nối tới Railway và các bảng cần thiết trong database hiện có. Không cần khởi động MySQL local. Nếu database đã có đủ bảng thì không chạy lại schema.

1. Giữ thông tin Railway hiện có trong các khóa `db.url`, `db.user`, `db.password` của `config/auth-server.properties`. File chỉ nằm trên máy chạy server và được Git bỏ qua. `db.url` dùng dạng `jdbc:mysql://<host-public>:<port>/<database>?sslMode=REQUIRED` với host, port và tên database thực tế của Railway. Bản mẫu `config/auth-server.properties.example` dùng placeholder để không chứa thông tin đăng nhập thật; không chép đè cấu hình đang hoạt động bằng placeholder. Client kết nối `localhost`; server lắng nghe `127.0.0.1` trên các cổng 8443 và 9001. Client không chứa thông tin đăng nhập database.
2. Mở PowerShell tại thư mục dự án và chạy server:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\start-meeting-server.ps1
   ```

   Script tự tạo chứng chỉ TLS phát triển nếu chưa có, rồi khởi động `server.AuthServer` tại `127.0.0.1:8443`. Giữ terminal này mở. Server kiểm tra bảng bằng truy vấn chỉ đọc, không tự tạo hay sửa database.

3. Mở terminal thứ hai để chạy giao diện:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\start-client.ps1
   ```

   Trong NetBeans, **Run Project** vẫn chạy `client.Main`. Hãy khởi động server trước.

4. Chọn **Đăng ký**, nhập tên đăng nhập không chứa khoảng trắng, tên hiển thị, **email bắt buộc**, mật khẩu từ 8 đến 128 ký tự có chữ thường, chữ hoa, chữ số và ký tự đặc biệt, rồi xác nhận. Nhập OTP 6 số được gửi tới email; chỉ khi xác minh đúng server mới tạo tài khoản. Khi tạo thành công, màn hình chuyển về đăng nhập và điền sẵn tên tài khoản. Đăng nhập phải nhập đúng chữ hoa/thường của tên đã đăng ký: `TranKhoa` khác `trankhoa`. Đăng nhập đúng sẽ mở Lobby với tên hiển thị lấy từ database.
5. **Đăng xuất** thu hồi phiên trên server trước khi trở lại màn hình đăng nhập. Nếu mất kết nối, giao diện báo lỗi để thử lại. Khi đóng ứng dụng, client cố gắng thu hồi phiên trong thời gian giới hạn; phiên không thu hồi được sẽ hết hạn theo cấu hình server.

Tại đăng nhập, chọn **Quên mật khẩu?** để nhận OTP qua email đã đăng ký, nhập mã và mật khẩu mới. Đặt lại thành công thu hồi các phiên đăng nhập cũ. OTP hết hạn sau 5 phút, tối đa 5 lần nhập sai; gửi lại cách nhau 60 giây, tối đa 5 lần mỗi giờ cho một email/mục đích.

Trước khi dùng OTP, chạy [script MySQL 002-email-otp.sql](database/migrations/002-email-otp.sql) trên database hiện có; script chỉ thêm hai bảng OTP, không sửa tài khoản/phòng. Database mới cũng cần migration này sau `schema.sql`. SMTP chỉ cấu hình trên server trong `config/mail.private.properties` (Git bỏ qua), theo [hướng dẫn email OTP](docs/email-otp.md).

Trong **Cài đặt → Tài khoản**, bạn có thể sửa tên đăng nhập, tên hiển thị và email rồi chọn **Lưu thay đổi**. Tên đăng nhập/email phải chưa được tài khoản khác sử dụng; email hồ sơ vẫn có thể bỏ trống. Tên đăng nhập mới được dùng cho lần đăng nhập tiếp theo. Với tài khoản chưa xác thực, lưu email rồi chọn **Xác thực email (OTP)** → **Gửi mã OTP** → nhập mã → **Xác thực**. **Đổi mật khẩu** yêu cầu email đã xác thực, mật khẩu hiện tại, mật khẩu mới đủ độ phức tạp và nhập lại khớp nhau; đổi thành công giữ phiên hiện tại và thu hồi các phiên khác. Sửa sang email khác sẽ vô hiệu hóa nút đổi mật khẩu cho đến khi xác thực địa chỉ mới. **Quên mật khẩu** tại cài đặt mở hộp thoại OTP ngay trong tài khoản, không cần mật khẩu cũ và không đăng xuất phiên hiện tại; đặt lại thành công thu hồi các phiên khác. Luồng quên mật khẩu ở màn hình đăng nhập vẫn thu hồi toàn bộ phiên cũ. Các thay đổi dùng cùng TCP/TLS và hai bảng OTP hiện có, không thêm migration. Xem [chi tiết chức năng tài khoản](docs/account-settings.md).

## Tạo và tham gia phòng

Sau khi đăng nhập, chọn **Tạo phòng**, nhập tên phòng 1–100 ký tự, PIN gồm 4–6 chữ số và sức chứa. Sức chứa mặc định là 10; phòng video cho phép 2–16 người theo chính sách mặc định của server (`meeting.maxParticipants`), phòng auth cũ vẫn có giới hạn 250 người bao gồm chủ phòng; đây là giới hạn quản lý thành viên, chưa phải năng lực truyền media. Server sinh `room_id`, mã `MMR-` cùng 16 ký tự ngẫu nhiên, xác định `host_id` từ phiên đăng nhập và lưu `created_at` theo UTC. Server chỉ lưu hash của PIN và không trả lại PIN/hash. Giao diện hiển thị ngày giờ phòng, tệp và tin nhắn theo Asia/Ho_Chi_Minh (UTC+7), độc lập múi giờ máy. PIN người dùng vừa nhập chỉ giữ trong bộ nhớ phiên phòng để chủ phòng chủ động sao chép lời mời; không ghi ra file hay log và xóa khi rời phòng. Chủ phòng được thêm vào phòng ngay khi tạo thành công.

Chia sẻ **mã phòng cùng PIN** cho người khác. Tại sảnh, nhập mã phòng và PIN rồi chọn **Tham gia**. Server kiểm tra phiên đăng nhập, phòng có tồn tại, PIN, trạng thái phòng, khóa phòng và số chỗ còn lại. Chỉ khi thành công client mới chuyển sang màn hình trước khi vào phòng. Mã phòng không phân biệt hoa/thường; PIN được kiểm tra nguyên vẹn, kể cả chữ số 0 ở đầu. Giao diện không hiển thị ID phòng và server từ chối dùng ID để tham gia.

Danh sách **Phòng gần đây của bạn** lấy từ database, tối đa 20 phòng đã tạo hoặc tham gia. Nút **Nhập PIN** điền lại mã phòng, vẫn yêu cầu PIN và xác nhận từ server. Màn hình họp nhận thay đổi thành viên qua signaling và cập nhật danh sách chờ khoảng 1 giây một lần. Tab **Tệp** truyền dữ liệu thật qua TCP/TLS; tab **Chat** nhận tin nhắn đẩy qua TLS, lưu lịch sử trong database. Video dùng camera thật khi được người dùng cho phép. Trong **Cài đặt**, chủ phòng có khóa/mở khóa và bật/tắt duyệt thành viên; tab **Chờ duyệt** hiển thị các yêu cầu tham gia. Menu thành viên hỗ trợ chuyển chủ, mời ra, dừng mic/camera/chia sẻ màn hình. Trong phòng cũ từ luồng auth, camera/microphone/share vẫn là demo có nhãn mô phỏng; tạo phòng mới qua signaling để dùng WebRTC.

Quay về sảnh từ màn hình trước khi vào phòng hoặc chọn rời cuộc họp sẽ cập nhật `Thanh_Vien_Phong`. Chủ phòng rời sẽ tự chuyển quyền cho thành viên còn lại; chọn Đóng phòng trong quản lý phòng mới kết thúc cho tất cả. Khi người cuối cùng rời hoặc chủ phòng kết thúc, server đổi trạng thái thành `DA_DONG`. Chủ phòng hiện tại có thể mở lại phòng đã đóng từ sảnh hoặc màn hình kết thúc, giữ nguyên mã, PIN và lịch sử; thành viên phải tham gia lại. Đăng xuất hoặc đóng ứng dụng cố gắng gửi thao tác rời phòng trước khi thu hồi phiên. Phòng video dùng heartbeat của kết nối signaling để dọn thành viên mất kết nối và chuyển chủ nếu cần; phòng auth cũ vẫn có hạn chế dọn thành viên khi tiến trình bị tắt đột ngột.

Dùng **`server.Main`** hoặc `scripts/start-meeting-server.ps1` để khởi động đồng thời auth/chat TLS, signaling, dịch vụ tệp và media. Các thành phần dùng cùng `Phong_Hop` và `Thanh_Vien_Phong`; người chờ duyệt chưa có membership để truy cập chat/tệp/video. Database cũ cần các migration của main theo hướng dẫn SFU và truyền tệp; bản ghép này không thêm migration mới và không tự sửa database. Chạy một server và hai client bằng hai tài khoản khác nhau để thử tạo và tham gia cùng phòng.

Quy tắc đăng ký được dùng chung ở giao diện và server. Server kiểm tra tên đăng nhập khớp chính xác với tên lưu trong database, kể cả khi [collation MySQL không phân biệt hoa/thường](https://dev.mysql.com/doc/refman/8.4/en/case-sensitivity.html). Không cần thay đổi schema hoặc dữ liệu Railway. Ràng buộc unique hiện tại vẫn ngăn đăng ký một tên chỉ khác chữ hoa/thường với tài khoản đã có. Quy tắc độ phức tạp mật khẩu áp dụng cho đăng ký mới; tài khoản có sẵn vẫn đăng nhập bằng mật khẩu cũ.

## Khi khởi động server gặp lỗi

`BindException` ở `127.0.0.1:8443` thường có nghĩa là auth server đã chạy trong terminal hoặc NetBeans khác. Mỗi địa chỉ/cổng chỉ dùng cho một auth server đang lắng nghe. Nếu server đã sẵn sàng, chỉ chạy client; nếu cần khởi động lại, dừng server cũ bằng **Stop** trong NetBeans hoặc **Ctrl+C** ở terminal server trước. Nếu đã cấu hình `auth.bind` khác, kiểm tra địa chỉ đó thuộc máy đang chạy server.

`DatabaseConnection` của server local kết nối tới MySQL Railway theo cấu hình trong `config/auth-server.properties`. `TestDatabase` chỉ kiểm tra kết nối và các bảng. Client gọi server local qua TCP/TLS; chỉ server sử dụng JDBC để truy vấn Railway. Thông báo lỗi khởi động phân biệt bước MySQL, chứng chỉ TLS và mở cổng. Việc không có listener MySQL ở `localhost:3306` không ảnh hưởng mô hình này.

## Cấu hình kết nối

Server đọc `config/auth-server.properties`; đổi đường dẫn bằng JVM property `-Dmmr.server.config=...`. Client đọc `config/auth-client.properties` nếu có; bản mẫu là `config/auth-client.properties.example`, JVM property là `-Dmmr.client.config=...`. Đường dẫn tương đối tính từ thư mục chạy dự án. File `.env` không được tự động nạp. Các biến `MMR_DB_*` nếu đã đặt sẽ ưu tiên hơn file; khởi động lại server sau khi chỉnh cấu hình vì file được nạp một lần khi tiến trình khởi tạo.

| Biến môi trường | Ý nghĩa |
|---|---|
| `MMR_DB_URL` | JDBC URL MySQL |
| `MMR_DB_USER`, `MMR_DB_PASSWORD` | Tài khoản database, chỉ đặt ở server |
| `MMR_AUTH_BIND`, `MMR_AUTH_PORT` | Địa chỉ lắng nghe và cổng server |
| `MMR_AUTH_KEYSTORE`, `MMR_AUTH_KEYSTORE_PASSWORD` | Keystore PKCS12 và mật khẩu của server |
| `MMR_AUTH_HOST` | Tên máy chủ client kết nối, mặc định `localhost` |
| `MMR_AUTH_CERTIFICATE` | Đường dẫn chứng chỉ công khai client tin cậy |
| `MMR_AUTH_TIMEOUT_MILLIS` | Thời gian chờ client, mặc định 15000 ms |

Để chạy client trên máy khác trong LAN: cho server lắng nghe IP LAN/`0.0.0.0`, dùng chứng chỉ có SAN khớp tên máy chủ hoặc IP kết nối, mở cổng đã cấu hình và đặt `auth.host` ở client. Script `scripts/init-local-tls.ps1 -CertificateHost ...` hỗ trợ cả tên máy và địa chỉ IP. Script chỉ tạo keystore khi file chưa tồn tại; khi đổi từ localhost sang LAN, dùng một tên keystore mới để cấp đúng chứng chỉ. Chỉ sao chép `auth-server.cer` sang client; giữ keystore `.p12` và cấu hình database trên server. Client kiểm tra cả chứng chỉ và tên máy chủ. Khi triển khai thực tế, cấp chứng chỉ phù hợp thay cho chứng chỉ phát triển.

Ví dụ server có IP LAN `192.168.1.18`: trong `config/auth-server.properties`, sửa `auth.bind=0.0.0.0`, `auth.port=8443`, `auth.keystore=config/auth-lan.p12` (tên file mới). Giữ cấu hình Railway và mật khẩu keystore hiện có. Dừng server rồi chạy:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\init-local-tls.ps1 -CertificateHost 192.168.1.18
```

Trên từng máy client, sao chép `config/auth-client.properties.example` thành `config/auth-client.properties`, đặt `auth.host=192.168.1.18`, `auth.port=8443`, `auth.certificate=config/auth-server.cer` và sao chép chứng chỉ vừa xuất từ server vào đúng đường dẫn đó. Cho phép kết nối TCP vào cổng 8443 của máy server trong Windows Firewall trên mạng Private. Hai máy phải liên lạc được trong cùng LAN/VPN. Chạy một `server.AuthServer` trên máy server và `client.Main` trên từng máy client. Khi IP server thay đổi, cập nhật cấu hình client và cấp lại chứng chỉ chứa IP mới bằng một keystore mới. Biến môi trường `MMR_AUTH_HOST`, `MMR_AUTH_PORT`, `MMR_AUTH_BIND`, `MMR_AUTH_KEYSTORE` nếu đã đặt sẽ ưu tiên hơn file cấu hình.

## Cấu trúc xác thực

- `client/UI/screens/AuthScreen.java`: biểu mẫu sẵn có, kiểm tra dữ liệu và hiển thị kết quả. Tác vụ mạng chạy nền.
- `client/auth/AuthClient.java`: gọi xác thực và các thao tác phòng qua TLS 1.2/1.3.
- `common/auth`, `common/room`: DTO và protocol gồm độ dài 4 byte + JSON UTF-8, tối đa 256 KiB/thông điệp để chứa snapshot tối đa 250 thành viên.
- `server/AuthServer.java`: nhận kết nối, giới hạn worker, timeout và gọi service.
- `server/auth`: xác thực, băm mật khẩu và truy vấn bằng `PreparedStatement` trên `Nguoi_Dung`/`Phien_Dang_Nhap`.
- `server/ApplicationService.java`, `server/rooms/RoomService.java`: điều phối yêu cầu, kiểm tra phiên, tạo/tham gia/rời phòng và đọc danh sách thành viên. Thao tác thay đổi phòng dùng transaction và khóa dòng phòng; mức READ COMMITTED tránh đếm số chỗ theo snapshot cũ khi nhiều người vào đồng thời. Xem [cơ chế khóa dòng](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html) và [mức cô lập của MySQL](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html).

Mật khẩu được băm PBKDF2-HMAC-SHA256 với salt ngẫu nhiên và 600.000 vòng theo [hướng dẫn OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). Mật khẩu gốc không lưu trong database hoặc trạng thái phiên giao diện. Token ngẫu nhiên chỉ giữ trong bộ nhớ client; database lưu SHA-256 của token và hạn dùng. TLS kiểm tra danh tính máy chủ theo [hướng dẫn JSSE của Java](https://docs.oracle.com/en/java/javase/21/security/java-secure-socket-extension-jsse-reference-guide.html).

Tài khoản `BI_KHOA` hoặc `VO_HIEU_HOA` không được đăng nhập. Tài khoản có sẵn cần có mật khẩu theo định dạng băm service hỗ trợ; ứng dụng không chấp nhận mật khẩu văn bản thuần trong DB.

## Kiểm thử

Server và client tự ghi nhật ký kiểm thử `.txt` trong `logs/diagnostics/`, gồm tài nguyên JVM, thời gian TCP/TLS/xác thực, hàng đợi và lỗi được lọc thông tin nhạy cảm. Xem [cách chạy và đọc nhật ký](docs/diagnostics.md). Thông số media hiện vẫn là demo; nhật ký chỉ nhận số liệu WebRTC/SFU thực tế khi phần media được triển khai.

```powershell
mvn test
```

Các test dùng H2 in-memory, TLS server cục bộ và cửa sổ JavaFX ẩn để kiểm tra xác thực, tạo phòng, mã phòng, từ chối tham gia bằng ID, PIN, phòng đóng/khóa/đầy, quyền truy cập, phiên hết hạn, nhiều người tranh chỗ cuối, rời phòng, framing, xác minh chứng chỉ và streaming upload/download. Test không ghi dữ liệu lên MySQL đang dùng.

## Voice chat và chia sẻ màn hình

Trong phòng mới, Bật mic hỏi quyền lần đầu và ghi nhớ trên máy; Tắt mic dừng publication và nguồn thu. Chọn microphone/loa thật trong Cài đặt. Vào phòng chờ không tự mở mic; vào họp cho phép phát âm thanh từ người khác. Kiểm tra Windows Settings → Privacy & security → Microphone nếu thiết bị bị chặn; nên dùng tai nghe.

Nhấn Chia sẻ, chọn màn hình/cửa sổ và kiểm tra preview rồi xác nhận. Camera và screen chạy độc lập. Mỗi phòng chỉ một người chia sẻ; host có thể Dừng mic/Dừng chia sẻ. Không tự bật lại sau mất kết nối. Cấu hình mục tiêu screen mặc định 1280×720, 10 fps, 1.8 Mbit/s; đây không phải số đo đạt được. Xem config/media-client.vmoptions.example và docs/video-call.md.

Giữ cách chạy NetBeans: Run File server/Main.java rồi client/Main.java. Không thêm schema/migration cho hai tính năng này. Test tự động dùng H2/TLS + PCM/I420 tạo sẵn; thiết bị mic/loa/màn hình thật, LAN và tải dài hạn cần người dùng nghiệm thu. Chạy scripts/test-video-call.ps1 cho cả audio/video/screen, mvn -B test cho hồi quy. Bằng chứng và phần chưa chạy ở docs/sfu-testing.md.


## Tinh gọn Cài đặt & Trải nghiệm Sảnh chính

### 1. Tinh gọn Cài đặt (`SettingsScreen`)
- Màn hình giữ 2 tab, không còn các mục thiết bị demo:
  1. **Thiết bị**:
     - Bộ chọn giao diện Sáng / Tối `ThemeManager.selector()` (chuyển đổi ngay tại chỗ, giữ nguyên Scene).
     - Camera trên máy (`CameraControls` chế độ `selectionOnly`) và chất lượng video tối đa (`360p`, `720p`, `Tự động`, lưu khóa `quality`).
     - Chọn microphone/loa thật, kiểm tra mic và phát âm thử cục bộ; giữ kiểm tra tín hiệu và dọn thiết bị khi rời màn hình.
     - Giữ các tùy chọn thông báo, tự tắt mic/camera khi vào phòng từ nhánh đồng đội, chuyển vào tab Thiết bị.
  2. **Tài khoản**: tích hợp `AccountSettingsPane` để xem/sửa hồ sơ, xác thực email OTP, đổi và khôi phục mật khẩu. Chi tiết tại [docs/account-settings.md](docs/account-settings.md).

### 2. Sảnh chính (`LobbyScreen`)
- Thẻ ngày tháng theo múi giờ Việt Nam (`Asia/Ho_Chi_Minh`) dạng chuẩn `Thứ Bảy, 17 thg 10` (đầy đủ tên thứ).
- Giữ nguyên các khối chức năng chính:
  - Chip trạng thái "Sẵn sàng kết nối" với hoạt ảnh nhấp nháy.
  - Thẻ "Tạo phòng mới" và thẻ "Tham gia phòng".
  - Danh sách "Phòng gần đây của bạn" kèm phân trang và nút "Xem thêm".

### 3. Dọn dẹp Database (nếu đã tạo bảng Lich_Hen trước đó)
- Nếu database của bạn trên Railway đã chạy script tạo bảng `Lich_Hen`, bạn có thể thực thi script [database/migrations/005_drop_lich_hen.sql](database/migrations/005_drop_lich_hen.sql) để dọn sạch bảng này.
