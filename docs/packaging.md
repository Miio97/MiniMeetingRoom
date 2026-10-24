# Đóng gói Windows

Chạy từ thư mục gốc:

```powershell
./scripts/package-portable.ps1 -Roles Client -Offline
```

Bỏ `-Offline` nếu cần tải dependency Maven. `-Roles Client,Server` tạo thêm gói
server mẫu không chứa bí mật. Gói trong `target/portable-<timestamp>` chứa JAR,
dependency, JavaFX Windows, runtime Java và launcher. Máy nhận không cần Maven,
Java hoặc NetBeans.

Giải nén toàn bộ ZIP vào nơi có quyền ghi rồi chạy `Start-Client.cmd`.
Giữ cùng thư mục `app`, `lib`, `javafx`, `runtime`, `config`.
Máy chủ phải chạy trước; client Radmin hiện kết nối `26.10.177.30`.

Script lấy chứng chỉ từ `auth.certificate`, chỉ chép file công khai `.cer`
trong `config`. Không đóng gói server properties, keystore, private properties,
SMTP secrets hoặc phiên đăng nhập vào gói client.

Runtime cần các module JavaFX, TLS, WebRTC và HikariCP/JDBC được liệt kê trong
`build-client-runtime.ps1`. Không thay bằng một runtime java.base tối thiểu.
Client nạp WebRTC native từ dependency, không phải SFU.
Server package có SFU prebuilt nhưng người quản trị phải tự bố trí private
config/keystore/storage; không phát tán chúng cho người dùng.

Bài startup check đã chuyển ra archive cùng công cụ QA. Kết quả kiểm chứng
bản dọn source được ghi tại [verification.md](verification.md).
`target` là output sinh lại được; sao chép ZIP cần phân phối ra nơi lưu trước
khi Maven clean. Không đưa cả thư mục target vào source bàn giao.

Để tạo ZIP source trình bày/bàn giao:

```powershell
./scripts/package-source.ps1
```

Gói `target/source-<timestamp>/MiniMeetingRoom-Source.zip` gồm source production,
SQL, script chạy/đóng gói, tài liệu và cấu hình mẫu. Không kèm test, output build,
private config, chứng chỉ/keystore, storage hoặc công cụ phát triển cũ. Người
nhận cần tự bố trí cấu hình và chứng chỉ phù hợp theo [running.md](running.md).
