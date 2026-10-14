# Nhật ký kiểm thử hiệu suất và độ ổn định

Module `common.diagnostics` tự bật khi chạy `server.AuthServer` hoặc giao diện JavaFX. Mỗi tiến trình tạo một file UTF-8 `.txt` riêng trong `logs/diagnostics/`. Mỗi dòng là một bản ghi JSON để đọc bằng trình soạn thảo hoặc phân tích bằng công cụ. Đường dẫn file được in ở terminal khi khởi động. Các log được Git bỏ qua và những lần kiểm thử cũ được giữ lại.

## Chạy một lần kiểm thử

Mở hai terminal tại thư mục project. Đặt cùng một mã kiểm thử trong cả hai terminal để nhóm log client và server; chọn mã mới cho lần chạy tiếp theo.

Terminal server:

```powershell
$env:MMR_DIAGNOSTICS_RUN_ID = 'baseline-001'
powershell -ExecutionPolicy Bypass -File .\scripts\start-server.ps1
```

Terminal client:

```powershell
$env:MMR_DIAGNOSTICS_RUN_ID = 'baseline-001'
powershell -ExecutionPolicy Bypass -File .\scripts\start-client.ps1
```

Thực hiện các thao tác cần kiểm thử, đóng client và dừng server bằng Ctrl+C. Khi gửi kết quả phân tích, gửi cả file client và server của lần đó, kèm các bước đã thực hiện, số máy, cấu hình mạng và điều kiện thử. `run_id` nhóm các tiến trình của một bài kiểm thử; hiện chưa có mã yêu cầu chung trên giao thức để ghép chính xác một yêu cầu client với một yêu cầu server.

## Số liệu hiện đã đo

| Nhóm | Nội dung |
|---|---|
| Môi trường | Phiên bản Java/JVM, hệ điều hành, kiến trúc, số CPU logic, heap tối đa, PID, vai trò tiến trình, mã kiểm thử |
| Tài nguyên JVM | Heap đang dùng/đã cấp phát, số thread JVM, số lần và thời gian GC trong khoảng lấy mẫu |
| CPU | CPU tiến trình và CPU hệ thống nếu JVM/hệ điều hành hỗ trợ |
| Client xác thực | Thời gian tạo ngữ cảnh TLS, kết nối TCP, TLS handshake, ghi yêu cầu và đọc/xác minh phản hồi, tổng thời gian, giai đoạn thất bại |
| Server xác thực | Thời gian kiểm tra database khi khởi động, TLS, chờ worker, xử lý yêu cầu, tổng thời gian; số kết nối nhận/từ chối/hoàn tất, worker đang chạy, hàng đợi và kết nối còn mở |
| Lỗi | Tên lớp exception và chuỗi nguyên nhân giới hạn, SQLState nếu hợp lệ, giai đoạn và mã kết quả cố định |
| Chất lượng nhật ký | Bản ghi bị bỏ vì hàng đợi đầy, giới hạn dung lượng, dấu bắt đầu/kết thúc và trạng thái worker lúc dừng |

`process_cpu_pct` là phần trăm trên tổng năng lực của tất cả CPU logic, tính từ thời gian CPU tiến trình trong khoảng lấy mẫu. Heap JVM không phải toàn bộ RAM/RSS của tiến trình. Số thread JVM không bao gồm mọi thread native mà một SDK media tương lai có thể tạo.

`response_ms` của client là thời gian ghi yêu cầu, chờ server/database, đọc và kiểm tra phản hồi xác thực. Đây là thời gian một vòng yêu cầu ứng dụng, không phải RTT mạng thuần và không phải độ trễ audio/video. `handler_ms` của server bao gồm công việc service/database, không tách riêng từng truy vấn SQL. Các thời lượng dùng đồng hồ đơn điệu; thời điểm UTC dùng để đối chiếu bài kiểm thử. Đồng hồ khác nhau trên các máy chưa đủ để suy ra độ trễ truyền một chiều.

## Tích hợp với WebRTC và SFU sau này

Luồng họp của giao diện hiện chưa tích hợp media, signaling hoặc SFU; sự kiện khởi động vẫn ghi `media_status=not_implemented`. Đã có harness kiểm chứng WebRTC native riêng ở profile `media-probe`, dùng nguồn được tạo bằng mã trong test. Harness này không biến giao diện demo thành cuộc gọi thật. Module không lấy các số RTT/jitter/loss ngẫu nhiên của UI demo làm dữ liệu thực.

Khi có engine WebRTC/SFU, adapter gọi `Diagnostics.current().mediaSample(MediaSample)` với số liệu đo được. `MediaSample` nhận mã track dạng số cục bộ, loại AUDIO/VIDEO/SCREEN, độ dài cửa sổ đo, số gói và byte gửi/nhận/mất, RTT, jitter và số frame encode/decode/drop. Không dùng ID hoặc tên người dùng làm mã track.

- Counters là chênh lệch trong cửa sổ đo, không phải tổng từ lúc mở track. Khi counters reset hoặc nguồn đo thay đổi, bỏ cửa sổ đó và thiết lập mốc mới.
- Byte là payload RTP, không gồm header giao thức; giữ cùng cách tính giữa các bản thử.
- Bitrate được tính bằng `bytes * 8000 / interval_ms`, đơn vị bit/giây.
- Tỷ lệ mất gói nhận là `lost / (received + lost) * 100`; nếu chưa có mẫu số thì không ghi tỷ lệ.
- Counter mất gói tích lũy có thể được hiệu chỉnh khi gói đến muộn. Nếu chênh lệch loss âm, adapter bỏ số loss của cửa sổ đó và thiết lập lại mốc; không ép thành 0 rồi báo không mất gói, không cộng lại lifetime totals vào từng mẫu.
- RTT và jitter dùng mili giây; adapter phải đổi đơn vị từ nguồn WebRTC/RTCP trước khi ghi.
- FPS encode và decode tính từ số frame tương ứng trong cửa sổ; không coi FPS decode là FPS thực sự hiển thị.
- Giá trị chưa đo được dùng `null` và được bỏ khỏi log. Không điền số 0 hoặc số mô phỏng để thay dữ liệu thiếu.

Ghi mẫu thống kê định kỳ, không gọi logger cho từng packet/frame. Các counter số như độ dài hàng đợi SFU, số track/subscriber, NACK/PLI và thời gian chuyển tiếp có thể bổ sung qua `event` khi triển khai thực tế; cần xác định đơn vị và phạm vi đo trước.

## Harness WebRTC native của Mốc 0

Chạy nguồn PCM/I420 được tạo từ mã của harness và tạo báo cáo bằng một terminal; không cần khởi động auth/client, không mở camera/microphone/loa:

```powershell
$env:MMR_DIAGNOSTICS_RUN_ID = 'm0-client-probe-001'
powershell -ExecutionPolicy Bypass -File .\scripts\test-media-probe.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\report-diagnostics.ps1 `
    -RunId 'm0-client-probe-001' -OutputPath 'target/diagnostics-m0-client-probe-001.txt'
```

Harness ghi file riêng với `role=client-media-probe` và giữ schema `mmr.diagnostics.v1`. `media.probe.native` đo `native_load_ms`; mỗi `cycle` tạo hai endpoint WebRTC trực tiếp, gửi nguồn tạo sẵn, đọc thống kê và đóng tài nguyên. Đây là bài kiểm chứng phía client; kết quả không chứng minh đường publisher → SFU → subscriber, kiểm thử thiết bị thật, khả năng tải hoặc không rò rỉ trong cuộc gọi dài.

| Sự kiện/trường | Ý nghĩa |
|---|---|
| `media.probe.connected`, `peer_setup_ms` | Thời gian thiết lập endpoint/kết nối trong cùng chu kỳ; không phải RTT mạng |
| `media.probe.received`, `video_frames_cumulative` | Tổng frame đã giao cho sink video trong chu kỳ; không phải FPS render JavaFX |
| `audio_chunks_cumulative` | Tổng callback chunk audio đã nhận; không tự đổi thành số frame audio hoặc thời lượng nghe được |
| `video_encoded_frames_cumulative`, `audio_encoded_frames_cumulative` | Số callback encoded media quan sát tại sender; không phải chứng cứ server forward RTP |
| `input_duration_ms` | Thời gian harness cấp nguồn tạo sẵn; không phải độ trễ media |
| `media.probe.stats`, `rtp_packets_cumulative` | Tổng packet RTP từ thống kê theo `phase=send` hoặc `receive`; gồm các luồng audio/video của endpoint, không phải payload byte hoặc số packet trong cửa sổ |
| `media.probe.closed`, `cycle_ms`, `shutdown_ms` | Tổng thời gian chu kỳ và phần đóng tài nguyên được đo riêng |
| `late_media_callbacks` | Callback media quan sát sau khi đóng theo kiểm tra harness; số 0 chỉ được ghi khi đã thực sự đếm |
| `media.probe.failed` | Lỗi có phase và loại exception; không ghi message, stack, SDP hoặc ICE |

Các counter có hậu tố `_cumulative` là lifetime totals của **một cycle**. Không đưa trực tiếp vào `MediaSample`, không coi mean/min/max của chúng là bitrate/FPS, không gộp sender với receiver và không trừ counter của cycle trước khỏi cycle mới. Harness chưa cung cấp RTT/jitter/loss delta hoặc RSS/native memory; các giá trị đó tiếp tục là chưa có dữ liệu.

Nếu media engine dùng process Go/Pion riêng, `Diagnostics` của JVM chỉ mô tả JVM sở hữu nó. Telemetry của process media phải có vai trò/PID và file riêng, cùng mã bài thử; hoặc adapter Java phải ghi rõ đó là số đo của process khác thay vì trộn vào `runtime.sample` của JVM. Chỉ nhận metric có định nghĩa và đơn vị đã kiểm chứng; counter packet/byte inbound/outbound, queue/drop hoặc thời gian forward không chứng minh cuộc gọi production. Không gán decode FPS/freeze cho SFU không decode. Bộ đọc báo cáo hiện chỉ xử lý schema v1 và các trường được liệt kê trong allowlist mã nguồn; trường mới cần bổ sung có test, không mở lọc chuỗi tự do. Kết quả bài thử SFU riêng được ghi khi thực sự chạy trong [sfu-testing.md](sfu-testing.md).

Trong harness SFU Mốc 0, adapter Java lấy số đo từ endpoint `/stats` đã xác thực của process Go/Pion và ghi `sfu.probe.stats`, tách bằng `kind=AUDIO/VIDEO` và `cycle`; `sfu.probe.connected` và `sfu.probe.closed` ghi các mốc vòng đời. Logger vẫn có chủ sở hữu `client-media-probe` trong JVM. Các trường `ingress_packets`, `ingress_codec_bytes`, `forwarded_packets`, `forwarded_codec_bytes`, `queue_packets`, `queue_bytes`, `queue_drops`, `expired_drops`, `write_errors`, `read_errors`, `pli_requests`, `active_peer_connections` là số đo **của SFU ngoài JVM**. Adapter đổi tên key Go API `ingress_payload_bytes`/`forwarded_payload_bytes` thành key `*_codec_bytes` để giữ privacy filter hiện có; không thay định nghĩa byte và không nới lọc key chứa `payload`. Byte ingress/forward chỉ tính codec payload RTP; counter ingress/forward/drop/error/PLI tích lũy trong chu kỳ, còn queue/active-peer là snapshot tại lúc đọc. Không trộn chúng với `media.probe.stats` của endpoint Java hoặc mẫu tài nguyên JVM. SHA-256 payload phục vụ assertion trong harness, không chuyển chuỗi hash vào logger hoặc báo cáo.

Đã có bài thử SFU nguồn tạo sẵn PASS với `run_id=m0-sfu-probe-20261014-02`: AUDIO ingress/forward 134/134 packet, VIDEO 89/89, subscriber 88 video frame/302 audio chunk, active PC 2→0; logger drop/truncation 0 và footer sạch. Lần này filter đã bỏ key `*_payload_bytes`, nên log/báo cáo chưa có byte payload; không suy ra byte bằng packet count. Báo cáo an toàn: `target/reports/diagnostics-m0-sfu-probe-20261014-02.txt`, 7 record/1 nhóm process và 0 lỗi reader. Đây là chứng cứ cổng Mốc 0 cùng máy, không chứng minh thiết bị thật, độ ổn định 30 phút hoặc năng lực nhiều client. Chi tiết assertion/điều kiện và các bài chưa chạy ở [sfu-testing.md](sfu-testing.md).

## Tạo báo cáo `.txt` từ log

`scripts/report-diagnostics.ps1` đọc file `.txt` UTF-8 JSON-lines đã tồn tại; không tạo logger mới hoặc sửa log nguồn. Mặc định đọc các file trực tiếp trong `logs/diagnostics`, ghi `target/diagnostics-report.txt`. Chọn thư mục/file và một bài thử cụ thể:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\report-diagnostics.ps1 `
    -InputPath 'logs/diagnostics' -RunId 'baseline-001' `
    -OutputPath 'target/diagnostics-baseline-001.txt'
```

`-RunId` là tùy chọn; bỏ nó để liệt kê các bài thử theo nhóm riêng. Để tránh ghi đè log nguồn, lưu báo cáo ngoài thư mục input. Nên đọc sau khi tiến trình đã đóng logger; khi đọc file còn đang ghi, footer hoặc dòng cuối có thể chưa có. Việc tắt logger không xóa file cũ: chọn mã bài thử mới để không nhầm dữ liệu cũ với một lần chạy đã tắt đo đạc.

Báo cáo chia theo `(source_file, run_id, pid, role)`, sau đó chia metric theo sự kiện, action, phase, kind, track và cycle của native/SFU probe. `source_file` và `run_group` là bí danh cục bộ; không in tên file/đường dẫn/run ID, danh tính, lỗi thô hoặc chuỗi sự kiện lạ. Sự kiện không được đặt tên trong allowlist hiển thị bằng `other.event#N`. Những giá trị đo không có, âm, không hữu hạn hoặc sai kiểu được bỏ; không tạo mẫu 0 để lấp chỗ trống.

Nội dung có số bản ghi, số mẫu runtime/media, số lỗi, khoảng quan sát `observed_span_ms`, điều kiện số từ `run.start`, số lần start/end, thiếu `run.end`, max counter logger đã drop/truncate và trạng thái file bị giới hạn. `dropped_events` là counter tích lũy: lấy max quan sát được, không cộng cùng counter qua mọi dòng. Khi thiếu footer hoặc reader báo giới hạn/lỗi input, báo cáo chỉ mô tả phần đã đọc.

Các metric thời gian được allowlist như `total_ms`, `response_ms`, `rtt_ms`, `jitter_ms`, `peer_setup_ms` có `n`, min/max/mean và p50/p95/p99 theo nearest rank trên **mẫu thô**. Không đọc trường percentile đã tính sẵn để tính percentile lần nữa. Counter/rate/CPU/heap chỉ có n và min/max/mean theo định nghĩa trường. Percentile chỉ có một mẫu sẽ bằng mẫu đó; nó không chứng minh phân bố hoặc ngưỡng hiệu năng ổn định. Điều kiện phần cứng, topology, codec, nguồn/thời lượng media và commit/dependency vẫn cần manifest bài thử trong tài liệu kiểm thử.

Reader giới hạn 128 file, 100 MiB/file, 256 MiB tổng input, 64 KiB/dòng, JSON sâu tối đa 32, 256 nhóm process, 256 tên sự kiện/nhóm, 4096 chuỗi metric và 200000 mẫu giữ lại để tính percentile. Báo cáo đếm dòng hỏng/quá lớn, file không đọc được, record bị lọc và giới hạn chạm tới. Nếu một chuỗi thiếu mẫu vì giới hạn bộ nhớ, percentile của chuỗi đó là `unavailable(sample_limit)`; không tính trên đoạn đầu rồi coi là toàn bộ lần chạy. Giữ log nguồn để chạy công cụ chuyên dụng khi bài thử vượt các giới hạn này.

## Giới hạn và cấu hình

Logger ghi ở thread nền bằng hàng đợi có giới hạn. Khi hàng đợi đầy, sự kiện được bỏ và đếm lại thay vì chờ ghi đĩa trên luồng ứng dụng. Một file mặc định tối đa 20 MiB. Khi đạt giới hạn, `log.truncated` đánh dấu phần còn thiếu; footer ghi số sự kiện bị bỏ. Module không tự xóa log của lần thử trước; người dùng quản lý dung lượng thư mục khi cần.

Kết thúc bình thường có `run.end`. Nếu không có bản ghi này, lần chạy có thể bị buộc dừng, gặp lỗi ghi đĩa hoặc chưa kịp hoàn tất việc ghi; không coi đó là một log đầy đủ. Đóng logger chờ tối đa hai giây. Server chờ worker tối đa một giây và ghi `workers_terminated` để thể hiện có worker còn chạy khi dừng hay không. Các sự kiện kết nối có thể đến khác thứ tự; ghép bằng `connection_id` trong cùng tiến trình.

Module lọc trường nhạy cảm và chỉ nhận scalar có giới hạn; lỗi chỉ ghi loại, không ghi message/stack. Các điểm tích hợp xác thực không ghi username, email, mật khẩu, token, nội dung chat/media hoặc URL database. Không truyền request/response, cấu hình hay văn bản tự do vào API logger. Mã kiểm thử cũng chỉ nên là nhãn kỹ thuật.

API `event` kiểm tra tối đa 32 trường đầu vào; key phải đúng dạng `[a-z][a-z0-9_]{0,63}` và không thuộc bộ lọc nhạy cảm. Chuỗi chỉ được nhận ở các key allowlist như `phase`, `status`, `code`, `kind` với tối đa 96 ký tự theo mẫu tag. Chỉ truyền mã cố định do dự án định nghĩa vào các key này: lọc không thể xác định rằng một chuỗi có hình thức hợp lệ chính là credential. Không truyền PIN số, SDP/ICE thô hoặc thông tin mạng vào key số/boolean; adapter chịu trách nhiệm chọn dữ liệu an toàn trước khi gọi logger.

| Biến môi trường | Giá trị mặc định | Ý nghĩa |
|---|---|---|
| `MMR_DIAGNOSTICS_ENABLED` | `true` | `false` để tắt ghi log |
| `MMR_DIAGNOSTICS_DIR` | `logs/diagnostics` | Thư mục xuất |
| `MMR_DIAGNOSTICS_RUN_ID` | UUID tự sinh | Nhóm các log của cùng bài thử |
| `MMR_DIAGNOSTICS_SAMPLE_SECONDS` | `5` | Khoảng lấy mẫu tài nguyên, từ 1 đến 60 giây |
| `MMR_DIAGNOSTICS_MAX_BYTES` | `20971520` | Giới hạn file, từ 8192 đến 104857600 byte |
| `MMR_DIAGNOSTICS_QUEUE_CAPACITY` | `1024` | Số sự kiện chờ ghi, từ 8 đến 8192 |

JVM properties tương ứng là `mmr.diagnostics.enabled`, `dir`, `runId`, `sampleSeconds`, `maxBytes`, `queueCapacity`, ưu tiên hơn biến môi trường. Khi so sánh hai phiên bản, giữ cùng cấu hình logger và cùng điều kiện tải. Ghi log cũng dùng tài nguyên; kết quả có logger phải được so sánh với kết quả có cùng chế độ ghi.
