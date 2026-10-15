# Kiểm chứng SFU — Mốc 0 và video tích hợp

**Mốc 0 và phòng/signaling Mốc 1 đã có bằng chứng thực thi; video hiện đã tích hợp vào phòng SFU.** Native integration trao đổi nguồn video tạo sẵn qua Go và render JavaFX. Theo yêu cầu mới nhất chỉ làm video trước; không đánh dấu hoàn thành Mốc 2 audio/video toàn bộ hoặc Mốc 3–4. Chưa có kết quả camera/microphone vật lý, khả năng tải hoặc cuộc gọi dài.

## Video tích hợp — cập nhật 15/10/2026

- Hồi quy sau luồng quyền mới: **194 tests, 0 failures/errors, 2 skipped**, BUILD SUCCESS (`target/camera-permission-entry-regression.txt`). Hai skip theo cấu hình symlink/performance.

- Quyền khi nhấn Bật camera/chặn vào họp: **16 tests PASS** (`target/camera-permission-entry-integration.txt`). Kiểm tra không tự hỏi khi mở phòng chờ, từ chối và thử lại, quyền đã lưu, view dispose và điều kiện vào họp qua nút lẫn lời gọi trực tiếp. Giao diện chờ bỏ nút quyền và các dòng mô tả thừa. Catalog/file quyền của fixture được cô lập; không mở thiết bị thật hoặc ghi Railway.

- Hồi quy sau camera switch/theme: **194 tests, 0 failures/errors, 2 skipped**, BUILD SUCCESS (`target/camera-switch-theme-regression.txt`). Hai skip vẫn theo cấu hình symlink/performance. Các kết quả 189/183 bên dưới là lần kiểm tra trước bản sửa này.

- Camera vật lý/nhấn liên tục/theme: **16 tests PASS**, gồm năm VideoCallIntegrationTest, bảy CameraControlsTest, ba CameraSwitchTest và một ThemeManagerTest (`target/camera-switch-theme-integration.txt`). Cố ý giữ khởi tạo capture rồi nhấn 100 lần: đích cuối tắt, không start nguồn; bật lại rồi nhấn 101 lần: publication SFU được dọn. Theme được đổi bằng nút thật, đối chiếu màu nền phòng họp/dialog và giữ nguyên Scene. Nguồn video tạo sẵn, không mở camera vật lý. Kiểm tra metadata trên máy thật: SDK có sáu mục, bộ lọc chỉ giữ webcam USB2.0 HD UVC WebCam (`target/camera-picker-inventory.txt`). Không gỡ/cài driver hoặc sửa Railway.

- Bản sửa camera/nhận video: **10 tests PASS**, gồm bốn VideoCallIntegrationTest và sáu CameraControlsTest (`target/camera-receive-final-integration.txt`). Client nhận chạy ở JVM riêng vẫn có frame/ảnh JavaFX khi cố ý giữ catalog camera chờ; cấu hình camera chỉ nằm trong dialog Cài đặt. Test catalog giả xác minh mặc định ưu tiên webcam vật lý, vẫn tôn trọng camera ảo đã chọn và việc khám phá bị kẹt không chặn đọc/ghi quyền. H2/TLS cô lập, nguồn I420 tạo bằng mã, không bật camera thật.
- Hồi quy mới nhất: **189 tests, 0 failures/errors, 2 skipped**, BUILD SUCCESS (`target/camera-receive-regression.txt`). Hai skip vẫn là quyền symlink Windows và kiểm tra performance truyền tệp opt-in.
- Kiểm tra metadata thiết bị thật (không capture) tái hiện ByteCast VirtualCamera2 bị kẹt đọc định dạng. Helper Java mới kết thúc sau khoảng 8 giây, sau đó đọc được 12 định dạng UVC trong khoảng 0,6 giây. Chưa nghiệm thu bật đồng thời webcam ở hai cửa sổ; một webcam có thể bị driver giữ độc quyền.

- Regression Java sau tích hợp giao diện chính: 183 tests, 0 failures, 0 errors, 2 skipped (quyền symlink Windows và performance truyền tệp opt-in); BUILD SUCCESS. Kết quả `target/unified-room-regression.txt`.
- Go: bốn bài top-level PASS kiểm tra auth/lease/generation, codec packet ownership, fanout Pion thật/cross-room/revoke, và queue keyframe hữu hạn khi writer không tiêu thụ.
- Native: ba bài `VideoCallIntegrationTest` PASS, không skip: ba client nhận sáu luồng remote và ảnh JavaFX; tắt/bật lại camera, host stop/kick và logout trong cuộc gọi. Bài giao diện chính dùng dialog tạo phòng, tham gia bằng ID/PIN, xem trước cục bộ rồi vào MeetingScreen; xác minh không publish trước khi vào họp. Gửi/tải tệp trong cùng phòng có đối chiếu nội dung; leave thu hồi file và camera, người khác vẫn phát. H2 và certificate test riêng; không ghi dữ liệu thử lên Railway. Nguồn I420 tạo bằng mã 320×240, không mở webcam/microphone. Kết quả `target/unified-video-integration.txt`, ảnh `target/video-main-meeting-preview.png`.
- Chạy lại bằng `scripts/test-video-call.ps1`. Điều kiện và giới hạn ở [video-call.md](video-call.md). Camera thật, hai máy, TURN, mất mạng, tải 5/10 client và 30 phút vẫn chưa chạy.

Các phần Mốc 0 dưới đây giữ lại bằng chứng khảo sát trước khi tích hợp, không phải hiện trạng tính năng video.

## Điều kiện baseline

- Ngày: 14/10/2026 (Asia/Saigon); base commit `30039612adb101b07858cf808f7cc008aeb26adf`, worktree có thay đổi Mốc 0 chưa commit.
- Windows 11 x64, build 10.0.26200; AMD Ryzen 5 7535HS, 6 core/12 logical CPU, RAM 16.452.972.544 byte; JVM Oracle 22.0.2, Maven đi kèm NetBeans 22; project compile `--release 21`, JavaFX 21.0.2. Chưa chạy trên đúng JVM 21.
- Dependency native probe pin `webrtc-java 0.19.0`; Windows x64 JAR từ Maven Central. Không Docker, không STUN/TURN, hai endpoint trên cùng máy.
- Toolchain Go 1.27.2 Windows amd64 được tải vào `.tools/` và kiểm tra SHA-256 `1314008898bd40df77af4b014f777f08873dbdfbcd3d92308728ee03304fe04f`; không đổi PATH/cài hệ thống. Chưa khởi chạy dịch vụ public hay thay firewall. Không sửa Railway/schema hoặc tạo dữ liệu thử ở đó.

## Test đã chạy

Baseline trước thay đổi: `mvn test` qua `Resolve-Maven` trong `scripts/common.ps1` — **47 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS. Các SQLState lỗi trong output là nhánh test lỗi database được chủ động kiểm tra, không phải test thất bại. JavaFX cảnh báo unnamed module đã xuất hiện ở baseline.

`scripts/inspect-meeting-schema.ps1` đã chạy thành công trên Railway bằng cấu hình server hiện có: **KNOWN_STRUCTURES_PRESENT, missing_or_incompatible=0**, exit 0. Đã đọc metadata bốn bảng `Nguoi_Dung`, `Phien_Dang_Nhap`, `Phong_Hop`, `Thanh_Vien_Phong`, kiểu/nullable của các cột cần thiết, PK, unique username/email/token/join-code và FK. Bảng membership thật có index `(Ma_Phong,Ma_Nguoi_Dung,Roi_Phong_Luc)`. Không đọc dữ liệu tài khoản/phòng, không DDL/INSERT/UPDATE; transaction chỉ đọc và rollback. Kết quả không xác minh CHECK expressions, giá trị ENUM, defaults, triggers hoặc quyền DB. Chưa quyết định migration khi chưa triển khai service.

Maven dependency tree profile `media-probe` đã resolve **0.19.0** và **windows-x86_64:0.19.0**, scope test. SHA-256 native JAR: `04ed89a0ed1faf8a7888ae02fcc1c7e9474311f5136e27e511b237b7aec9f82e`. Đã kiểm tra JAR có DLL và `META-INF/licenses/webrtc`.

Native client probe đã PASS: **1 test, 0 failures/errors/skipped**, hai vòng nguồn tạo sẵn qua hai endpoint Java trực tiếp. Vòng 1 nhận 82 video frame, 303 audio chunk, RTP gửi/nhận 229/229; vòng 2 nhận 65 video frame, 300 audio chunk, RTP 165/165. Stats resolve codecId xác nhận Opus/VP8 và DTLS connected. Close idempotent, late callback 0, logger drop/truncation 0 và footer sạch. Đây là một bài integration ngắn trên cùng máy, không nghiệm thu thiết bị, FPS/capacity hay không rò rỉ lâu dài. Lần probe đầu thất bại do harness thiếu luồng playout ảo để kéo decoder audio; đã sửa bằng HeadlessAudioDeviceModule, không mở speaker thật.

H2 schema inspector: **6 tests PASS**. Offline diagnostics report: **8 tests PASS**. Bộ regression Java thông thường sau Mốc 0: **61 tests, 0 failures/errors/skipped, BUILD SUCCESS** (47 test baseline + 6 test schema inspector + 8 test report); không nạp WebRTC ở profile bình thường.

`scripts/test-sfu-probe.ps1` đã chạy ngày 14/10/2026: `go test ./...` trong `media/probe` PASS, build executable PASS, và **1 native test `generatedMediaCrossesPionWithoutTranscoding`, 0 failures/errors/skipped**, 4.537 giây theo Surefire. Go có **5 test functions** trong `media/probe/router_test.go`: ownership payload/header stripping và sequence wrap/reorder/duplicate; queue giới hạn packet/byte và close; cancellation; counter/hash payload; HTTP auth/JSON boundaries/session cleanup. Đây là test router tự viết, không phải tải hay cuộc gọi thiết bị thật.

Nguồn chứng cứ E2E: `run_id=m0-sfu-probe-20261014-02`, file `logs/diagnostics/client-media-probe-20261014T085036.142Z-25168-756734d0.txt`. JVM PID 25168 ghi 7 record, khoảng quan sát 4397.759 ms, `run.end` sạch, logger drop/truncation 0; không có runtime sample vì bài thử ngắn hơn chu kỳ sampler 5 giây. Báo cáo an toàn đã sinh tại `target/reports/diagnostics-m0-sfu-probe-20261014-02.txt` bằng script thật; 1 file/1 nhóm process, 2646 byte input, 0 dòng hỏng/quá lớn, không chạm giới hạn reader.

| Số đo cuối sau drain/close | AUDIO (Opus) | VIDEO (VP8) |
|---|---:|---:|
| SFU ingress packet tích lũy | 134 | 89 |
| SFU forwarded packet tích lũy | 134 | 89 |
| Queue packet / byte còn lại | 0 / 0 | 0 / 0 |
| Queue drop / expired drop | 0 / 0 | 0 / 0 |
| Read error / write error | 0 / 0 | 0 / 0 |
| PLI request | 0 | 1 |

Java subscriber quan sát **88 video frame, 302 audio chunk** với nguồn chạy khoảng 3 giây; không coi các số này là FPS render JavaFX hay khả năng server. Stats của endpoint resolve codecId xác nhận Opus/VP8 và transport DTLS connected. SFU có 2 active PeerConnection khi kết nối và 0 sau cleanup. Harness kiểm tra packet count > 0 và ingress/forward count bằng nhau từng kind; số byte codec payload bằng nhau và streaming SHA-256 chuỗi payload khớp sau drain. Chuỗi hash dùng assertion, không ghi log. Service chỉ đọc/ghi RTP encoded, không có encoder/decoder; Pion tạo ngữ cảnh SRTP riêng ở hop egress, rewrite SSRC/PT theo binding. Điều này chứng minh cổng khả thi cho một publisher/một subscriber và một nguồn/track; chưa chứng minh nhiều publisher hoặc source switching.

Ở lần `-02`, số byte payload không được giữ trong diagnostics vì key `*_payload_bytes` bị privacy filter hiện có loại; không bịa số byte từ packet count. Adapter dùng key an toàn `ingress_codec_bytes`/`forwarded_codec_bytes` cho lần chạy kế tiếp, giữ field Go API nguyên nghĩa, không nới bộ lọc logger. Kết quả packet và hash assertion của lần `-02` vẫn là chứng cứ đã chạy.

HTTP điều khiển chỉ bind loopback, dùng secret ngẫu nhiên riêng của bài thử. Media dùng candidate host UDP IPv4 cùng máy, có cả host interface và loopback; native libwebrtc bỏ loopback theo mặc định, nên lần connect đầu dùng loopback-only đã timeout trước khi cấu hình host candidate được sửa. Không STUN/TURN, không thay firewall, không dịch vụ public hoặc credentials Railway trong child process. Test cùng máy sử dụng địa chỉ host không đồng nghĩa đã thử hai máy LAN.

## Chạy lại trên Windows

Auth và client: giữ nguyên hai terminal và thứ tự trong README. Các công cụ Mốc 0 dưới đây chạy một terminal, không cần mở auth/client, không cần camera/microphone:

```powershell
# Native CLIENT feasibility: hai endpoint, nguồn được tạo bằng mã trong test.
$env:MMR_DIAGNOSTICS_RUN_ID = 'm0-client-probe-001'
powershell -ExecutionPolicy Bypass -File .\scripts\test-media-probe.ps1

# Java publisher -> Pion ingress/router/egress -> Java subscriber.
$env:MMR_DIAGNOSTICS_RUN_ID = 'm0-sfu-probe-001'
powershell -ExecutionPolicy Bypass -File .\scripts\test-sfu-probe.ps1

# Báo cáo từ log đã ghi; không ghi đè log nguồn.
powershell -ExecutionPolicy Bypass -File .\scripts\report-diagnostics.ps1 `
    -RunId 'm0-sfu-probe-001' -OutputPath 'target/reports/diagnostics-m0-sfu-probe-001.txt'

# Metadata schema thật, sử dụng cấu hình server hiện có; chỉ đọc.
powershell -ExecutionPolicy Bypass -File .\scripts\inspect-meeting-schema.ps1

# Regression bình thường, không nạp WebRTC.
. .\scripts\common.ps1
$taskMaven = Resolve-Maven
& $taskMaven -B clean test
```

Profile `media-probe` chỉ thêm dependency scope test và `src/media-test/java`. Script thất bại nếu probe thất bại; xem `target/surefire-reports`. Không coi compile thành công là kết quả native/media.

Nguồn thử: PCM 48 kHz mono, tone 440 Hz, chunk 10 ms; I420 320×240, moving pattern 30 fps mục tiêu. Đây là dữ liệu tạo sẵn từ mã của harness, dựa trên cách dùng API trong [test upstream pinned](https://github.com/devopvoid/webrtc-java/blob/v0.19.0/webrtc/src/test/java/dev/onvoid/webrtc/TestMediaCall.java). Không dùng media của người dùng. Không bật capture/render thiết bị thật; kết quả không được gọi là nghiệm thu cuộc gọi camera/microphone.

## Cổng đã đạt và phần mở rộng còn lại

1. Đã chọn Go/Pion v4.2.23, build/chạy trên Windows x64; JavaFX/auth/room/signaling tiếp tục ở Java. Chi phí và ranh giới thư viện ở tài liệu kiến trúc.
2. Đã chạy một publisher → engine server → một subscriber, Opus/VP8, hai WebRTC transport và đối chiếu packet/payload/hash. Queue giới hạn 256 packet/2 MiB/750 ms mỗi route và cleanup ngắn đã kiểm thử; chưa đo tổng RSS/native memory của service.
3. Đã quan sát PLI và có route feedback; người vào muộn, drop recovery/chậm subscriber, multi-source switching, NACK/RTX dưới mất mạng và congestion control đầy đủ chưa nghiệm thu.
4. Mốc 1 đã có session/token server-side, room/membership, signaling và màn hình điều khiển phòng thật. Tests H2/TLS/JavaFX cùng cách chạy sau khi ghép code ở [sfu-m1-running.md](sfu-m1-running.md). Bước video mới đã nối quyền publish/subscribe với session sản phẩm; xem mục cập nhật ở đầu tài liệu.

## Kế hoạch nghiệm thu chưa chạy

| Bài thử | Trạng thái |
|---|---|
| Đường publisher → SFU → subscriber không transcode | PASS Mốc 0: một publisher/một subscriber, nguồn tạo sẵn cùng máy; chưa phải cuộc gọi sản phẩm |
| 2/3 client JavaFX có audio/video thiết bị thật | Chưa chạy; cần người dùng bật thiết bị |
| Camera/leave, host stop/kick, logout trong video | PASS native với nguồn tạo sẵn; thiết bị vật lý chưa chạy |
| Mute, screenshare, đổi thiết bị, reconnect/ICE restart | Chưa triển khai/nghiệm thu; camera tắt/bật dùng PC và publication mới |
| Multi-room isolation, subscriber chậm, loss/jitter/bandwidth | Chưa chạy |
| 2/3/5/10 client và cuộc gọi 30 phút | Chưa chạy |
| Thread/socket/native RSS/cache sau cleanup dài hạn | Chưa đo; hai cycle client probe và một chu kỳ SFU ngắn không chứng minh không rò rỉ |
| LAN/Internet, TURN/certificate/SAN/firewall/NAT | Chưa chạy |

Khi triển khai sẽ giữ cùng phần cứng, mạng, codec/resolution/FPS/bitrate/thời lượng/diagnostics giữa các lần so sánh. Client cùng máy chỉ là bài integration và có thể bị giới hạn capture/encode/decode; không suy ra năng lực server thực tế. Ngưỡng nghiệm thu hiệu suất sẽ chốt bằng baseline thật, không bịa mục tiêu.

## Kiểm chứng sau khi ghép code ngày 2026-10-14

Baseline bản ghép: 162 bài, 0 failure/error, 2 skip. Sau bổ sung Mốc 1: 176 bài, 0 failure/error, 2 skip (test symlink thiếu quyền Windows và test hiệu suất truyền tệp cần opt-in). Hai màn hình JavaFX không hiển thị chạy create/join/leave qua TLS thật, xác nhận danh sách và quyền host; không dùng mock room/member, không có dữ liệu thử Railway.

Probe `m1-merged-sfu-probe-20261014-01` chạy lại trên Windows: Go unit tests PASS và một native SFU integration test PASS, không skip, 4.565 giây. Log `client-media-probe-20261014T142854.085Z-36504-eacb66b1.txt` nằm trong `logs/diagnostics`; các byte codec được ghi bằng `ingress_codec_bytes`/`forwarded_codec_bytes` để giữ nguyên filter chặn payload. Đây vẫn là media tạo sẵn cùng máy, không phải nghiệm thu cuộc gọi JavaFX hoặc thiết bị thật.
