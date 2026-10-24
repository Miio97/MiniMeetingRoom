# Cấu hình và chạy

## Máy phát triển

- Windows x64; JDK 21 trở lên (máy phát triển hiện dùng JDK 22).
- Maven hoặc Maven đi kèm NetBeans.
- Go/Pion được build bằng toolchain cục bộ do script quản lý.
- MySQL đã cài đúng bảng/procedure và cấu hình SMTP khi dùng OTP thật.

Khởi chạy từ thư mục gốc để các đường dẫn tương đối được giải quyết đúng.
Trong NetBeans: Run File `server.Main`, chờ `MMR_MEETING_SERVER_READY`, rồi
Run File `client.Main`. Không chạy hai server cùng cổng.

```powershell
./scripts/start-meeting-server.ps1
./scripts/start-client.ps1
```

`start-server.ps1` là lệnh tương thích gọi cùng server hoàn chỉnh.
`server.AuthServer` và `MeetingServerMain` vẫn là thành phần triển khai;
không cần chạy thêm khi đã chạy `server.Main`.

## Cấu hình

| File | Phạm vi |
|---|---|
| `config/auth-client.properties` | Host/cổng client và đường dẫn chứng chỉ công khai |
| `config/auth-server.properties` | Database, TLS, listener, file service và pool |
| `config/meeting-server.private.properties` | Instance ownership và khóa receipt |
| `config/video-server.private.properties` | Khóa control SFU |
| `config/mail.private.properties` | SMTP nếu sử dụng file này thay cho biến môi trường |

Các file `*.example` chỉ là mẫu, không ghi đè cấu hình đang hoạt động.
Biến môi trường có thể ưu tiên hơn file cấu hình; xem `ServerConfig` và
các lớp settings client để đối chiếu. Giữ nguyên instance/khóa khi restart.

## Radmin

Máy server hiện tại có IP `26.10.177.30`. Các bind auth/signaling/tệp và
host client dùng IP này. Server dùng keystore `config/auth-radmin.p12`;
client dùng chứng chỉ công khai `config/auth-radmin.cer`.

Máy đồng đội chỉ cần cấu hình client và `.cer` đúng server. Không gửi `.p12`,
private properties, thông tin database hoặc phiên đăng nhập. Các chứng chỉ
được Git bỏ qua nên không tự đến máy khác qua git pull.

| Kênh | Cổng mặc định |
|---|---:|
| Auth TCP/TLS | 8443 |
| Signaling TCP/TLS | 8444 |
| Tệp TCP/TLS | 9001 |
| Control Java–Go, chỉ nội bộ | 8766 |
| RTP WebRTC | UDP/ICE động |

Firewall cần cho Java nhận các TCP tương ứng và SFU nhận UDP trên adapter
đang dùng. Không mở control 8766 ra Radmin. Chat hoạt động chưa chứng minh
media đã thông. Nếu đổi IP phải cập nhật cấu hình và SAN chứng chỉ phù hợp;
không tắt xác minh TLS để bỏ qua lỗi.

## Runtime và dữ liệu trên máy

`.tools/bin/mmr-video.exe`, Go toolchain/cache được giữ để khởi chạy/build.
Không xóa toàn bộ `.tools` trong khi server phụ thuộc các file này.
`storage` thuộc server. Lỗi vận hành vẫn được báo trên console; bộ thu thập
diagnostics phục vụ phát triển đã gỡ. Dữ liệu tải về
thư mục cá nhân và private config không nằm trong phạm vi dọn source.
