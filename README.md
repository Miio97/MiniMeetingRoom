# MiniMeetingRoom

Ứng dụng desktop Java 21/JavaFX. Đăng ký, đăng nhập và đăng xuất đã kết nối MySQL qua server TCP/TLS riêng. Tạo/tham gia/rời phòng và chia sẻ tệp đã được nối với backend; chat văn bản, danh sách người tham gia, thiết bị và media vẫn có phần mô phỏng.

## Truyền tệp qua TCP/TLS

Tab **Tệp** hỗ trợ chọn tệp, tải lên/tải xuống theo luồng, kiểm tra SHA-256, tiến độ, tạm dừng, tiếp tục và hủy. Server kiểm tra phiên cùng quyền thành viên phòng; client cùng phòng cập nhật metadata mỗi 3 giây. `server.AuthServer` hiện khởi động cả auth trên cổng **8443** và dịch vụ tệp riêng trên cổng **9001**.

Với database đã tồn tại, áp dụng **một lần** [migration 001](database/migrations/001_file_transfer.sql) trước khi bật dịch vụ tệp; server không tự sửa database. Database mới dùng [schema.sql](database/schema.sql), không chạy thêm migration này. Xem [báo cáo, cấu hình, hướng dẫn hai client và giới hạn hiện tại](docs/file-transfer.md). Phần hướng dẫn auth bên dưới vẫn áp dụng; khi dùng tệp qua LAN cần mở thêm cổng TCP 9001 và cấu hình `file.transfer.bind`.

## Chạy trên máy phát triển

Cấu hình sử dụng hiện tại: **server Java và client JavaFX chạy trên máy local; database MySQL vẫn ở Railway**. Cần JDK 21 trở lên, Maven (hoặc Maven đi kèm NetBeans), kết nối tới Railway và các bảng cần thiết trong database hiện có. Không cần khởi động MySQL local. Nếu database đã có đủ bảng thì không chạy lại schema.

1. Giữ thông tin Railway hiện có trong các khóa `db.url`, `db.user`, `db.password` của `config/auth-server.properties`. File chỉ nằm trên máy chạy server và được Git bỏ qua. `db.url` dùng dạng `jdbc:mysql://<host-public>:<port>/<database>?sslMode=REQUIRED` với host, port và tên database thực tế của Railway. Bản mẫu `config/auth-server.properties.example` dùng placeholder để không chứa thông tin đăng nhập thật; không chép đè cấu hình đang hoạt động bằng placeholder. Client kết nối `localhost`; server lắng nghe `127.0.0.1` trên các cổng 8443 và 9001. Client không chứa thông tin đăng nhập database.
2. Mở PowerShell tại thư mục dự án và chạy server:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\start-server.ps1
   ```

   Script tự tạo chứng chỉ TLS phát triển nếu chưa có, rồi khởi động `server.AuthServer` tại `127.0.0.1:8443`. Giữ terminal này mở. Server kiểm tra bảng bằng truy vấn chỉ đọc, không tự tạo hay sửa database.

3. Mở terminal thứ hai để chạy giao diện:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\start-client.ps1
   ```

   Trong NetBeans, **Run Project** vẫn chạy `client.Main`. Hãy khởi động server trước.

4. Chọn **Đăng ký**, nhập tên đăng nhập không chứa khoảng trắng, tên hiển thị, mật khẩu từ 8 đến 128 ký tự có chữ thường, chữ hoa, chữ số và ký tự đặc biệt, rồi xác nhận. Email có thể bỏ trống. Khi tạo thành công, màn hình chuyển về đăng nhập và điền sẵn tên tài khoản. Đăng nhập phải nhập đúng chữ hoa/thường của tên đã đăng ký: `TranKhoa` khác `trankhoa`. Đăng nhập đúng sẽ mở Lobby với tên hiển thị lấy từ database.
5. **Đăng xuất** thu hồi phiên trên server trước khi trở lại màn hình đăng nhập. Nếu mất kết nối, giao diện báo lỗi để thử lại. Khi đóng ứng dụng, client cố gắng thu hồi phiên trong thời gian giới hạn; phiên không thu hồi được sẽ hết hạn theo cấu hình server.

Thông tin tài khoản hiện chỉ đọc; chỉnh sửa tên và đổi mật khẩu sẽ được triển khai ở bước riêng.

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
- `client/auth/AuthClient.java`: gọi đăng ký, đăng nhập, đăng xuất qua TLS 1.2/1.3.
- `common/auth`: DTO và protocol gồm độ dài 4 byte + JSON UTF-8, tối đa 16 KiB/thông điệp.
- `server/AuthServer.java`: nhận kết nối, giới hạn worker, timeout và gọi service.
- `server/auth`: xác thực, băm mật khẩu và truy vấn bằng `PreparedStatement` trên `Nguoi_Dung`/`Phien_Dang_Nhap`.

Mật khẩu được băm PBKDF2-HMAC-SHA256 với salt ngẫu nhiên và 600.000 vòng theo [hướng dẫn OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). Mật khẩu gốc không lưu trong database hoặc trạng thái phiên giao diện. Token ngẫu nhiên chỉ giữ trong bộ nhớ client; database lưu SHA-256 của token và hạn dùng. TLS kiểm tra danh tính máy chủ theo [hướng dẫn JSSE của Java](https://docs.oracle.com/en/java/javase/21/security/java-secure-socket-extension-jsse-reference-guide.html).

Tài khoản `BI_KHOA` hoặc `VO_HIEU_HOA` không được đăng nhập. Tài khoản có sẵn cần có mật khẩu theo định dạng băm service hỗ trợ; ứng dụng không chấp nhận mật khẩu văn bản thuần trong DB.

## Kiểm thử

```powershell
mvn test
```

Các test dùng H2 in-memory và TLS server cục bộ để kiểm tra đăng ký, trùng tài khoản/email, đăng nhập, trạng thái tài khoản, phiên, thu hồi token, framing và xác minh chứng chỉ. Test không ghi dữ liệu lên MySQL đang dùng.
