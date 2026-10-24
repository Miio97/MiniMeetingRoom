# Kiểm chứng source bàn giao

Ngày kiểm chứng: 24/10/2026. Source hiện có 165 file Java production.
Không chạy migration hoặc thao tác dữ liệu Railway; không commit/push.

## Gỡ bộ thu thập diagnostics

Client, server Java và SFU Go không còn bộ ghi diagnostics, lịch lấy mẫu
CPU/heap/thread hoặc ghi thời gian xử lý để phân tích phát triển. JAR không còn
package `common.diagnostics` hoặc helper `RequestTiming`. SFU không còn
`diagnostics.go` và bộ đếm theo loại media chỉ phục vụ recorder cũ.
Lỗi vận hành vẫn được báo trên console. Dữ liệu WebRTC dùng cho giao diện,
chất lượng video AUTO và điều khiển media được giữ nguyên.

| Kiểm tra sau khi gỡ | Kết quả |
|---|---|
| Maven build sau khi bỏ output class cũ | BUILD SUCCESS; 318 class, không có diagnostics/test |
| Hồi quy Java bằng H2/TLS | 575 ca: 566 đạt, 9 bỏ qua; không lỗi |
| WebRTC native và SFU thật | 28/28 ca đạt |
| Go SFU | `go test ./...` và build Windows đạt |
| Client ZIP với runtime đi kèm | Khởi động UI/native media thành công; không có class/thread/file diagnostics |
| SFU độc lập | Health/startup/shutdown đạt; không ghi diagnostics dù biến môi trường bật cũ còn tồn tại |

Trong 9 ca bỏ qua: 5 ca cần MySQL riêng, 2 ca truyền tệp có điều kiện và 2 ca
chỉ kiểm tra logger đã gỡ. Hai lớp test diagnostics và benchmark phụ thuộc
recorder được loại khỏi lượt hồi quy này. Helper diagnostics lịch sử chỉ ở
test scope của harness bên ngoài để biên dịch bài thử cũ; không có trong JAR.
Native harness đã bỏ lệnh khởi động recorder, giữ các kiểm tra media.

Snapshot trước gỡ, module nguyên bản, 159 log cũ, manifest và SHA256 nằm tại
`C:/MMT/MiniMeetingRoom-Archive/20261024-142609-diagnostics`.
Log kiểm thử: `qa-unit.log`, `qa-native.log`, `qa-go.log`;
startup check: `release-verification/startup.log` và `sfu-startup-check/result.log`.
Cấu hình/chứng chỉ/storage, SQL/resources và các lớp adaptive/network/native
được đối chiếu giữ nguyên. Không kiểm thử trực tiếp Railway.

## Đợt dọn source trước khi gỡ diagnostics

Source trước khi dọn, thay đổi chưa commit, bộ test và công cụ được lưu ngoài
project tại `C:/MMT/MiniMeetingRoom-Archive/20261024-135356`.
`source-before-cleanup.zip`, manifest và SHA256 dùng để khôi phục/đối chiếu.
Private config, keystore và storage giữ nguyên vị trí; hash được đối chiếu
sau khi hoàn tất. Không copy secrets vào gói source công khai.

Source production được biên dịch riêng. Test chạy trong `qa-workspace` bên ngoài
với bản sao mã production mới, pom/test dependencies riêng và H2/TLS tạm.
Các lớp giao diện/probe đã nghỉ chỉ ở lại workspace QA để biên dịch những bài
thử lịch sử; chúng không nằm trong JAR production. Fixture model được đổi tham
chiếu tương ứng; không sửa logic production để chiều kết quả test.

Kết quả sau khi dọn:

| Kiểm tra | Kết quả |
|---|---|
| Maven package từ target mới | BUILD SUCCESS, 167 source Java; không có test trong JAR |
| Hồi quy Java bằng H2/TLS trong archive | 595 ca: 588 đạt, 7 bỏ qua theo điều kiện; không lỗi |
| Tích hợp WebRTC native và SFU thật | 28 ca đạt, không lỗi hoặc bỏ qua |
| Go SFU | `go test ./...` đạt; build SFU Windows đạt |
| Đối chiếu AST Java với snapshot | 155 file giữ nguyên AST; 9 file đổi tham chiếu UI; thêm 3 helper/model |
| Client portable từ ZIP mới, đường dẫn có khoảng trắng | `PORTABLE_MAINAPP_STARTUP_OK` |

Các ca bỏ qua gồm 5 ca cần MySQL riêng và 2 ca truyền tệp có điều kiện.
Không chạy những ca này với Railway. Kiểm thử media dùng nguồn tạo bằng mã;
riêng bài Windows system-audio kiểm tra vòng đời helper loopback, bỏ các mẫu
âm thanh đọc được. Không mở webcam hoặc microphone thật.

Log Java/Go nằm tại `qa-unit.log`, `qa-native.log`, `qa-go.log` trong archive;
log khởi động ZIP tại `release-verification/startup.log`. Kết quả kiểm tra hash,
SQL/resources và file đã chuyển nằm trong manifest của archive.

Đây là kiểm chứng hồi quy khi dọn source, không phải đo năng lực tải hoặc cam
kết tốc độ. Chưa kiểm thử lại bằng nhiều máy thật qua Radmin, thiết bị camera/
mic thật hoặc Railway. Các lớp legacy còn được tham chiếu vẫn được giữ lại.
