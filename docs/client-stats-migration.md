# Thống kê media và chất lượng video AUTO trên client

## Phạm vi đã được xác nhận

Ngày 18/10/2026, người phát triển xác nhận thay đổi so với prompt ban đầu:

- Chỉ AUTO; không thêm MANUAL hoặc lựa chọn 45/60 FPS.
- Camera và encoder tối đa 1280×720/30 FPS, thiết bị thấp hơn dùng khả năng thực tế.
- Ngừng telemetry ứng dụng định kỳ và lưu thống kê database; **xóa bảng `Thong_Ke_Ket_Noi` cùng lịch sử bằng migration chạy thủ công trên Railway**.
- Giảm video trước; chỉ tạm dừng video/chia sẻ khi có bằng chứng thoại bị ảnh hưởng kéo dài. Giữ thao tác tắt camera/mic/share bằng tay và khôi phục phòng 2 phút.

Không có thao tác Railway nào được agent thực thi. Các bản ghi người dùng, phòng, chat, phiên đăng nhập, lịch hẹn và tệp không thuộc migration này.

## A. Hệ thống cũ đã được kiểm chứng

`VideoCallController.sampleNetworkStats()` chạy trên scheduler riêng, chờ thêm 1 giây sau mỗi vòng; lấy `getStats()` của camera, thoại và ICE chia sẻ. Nó tính số liệu, cập nhật UI, gọi policy và gửi JSON `NETWORK_STATS` qua `MeetingSession` → `SignalingClient` TCP/TLS.

`MeetingHandler` kiểm tra các trường, giới hạn tần suất và đẩy `StatsRecord` vào `NetworkStatsService`. Service có queue tối đa 1024 mẫu; mỗi 10 giây INSERT batch vào `Thong_Ke_Ket_Noi`; mỗi 30 giây SELECT/DELETE dữ liệu hết retention. `MeetingServerMain` khởi động và đóng service.

Các trường cũ: phòng, người dùng, RTT ms, jitter ms, mất gói %, bitrate Kbps, FPS, kích thước, nhãn chất lượng, thời điểm lấy mẫu. SQL đọc bảng chỉ phục vụ preflight/retention; **không tìm thấy database reader cho adaptive video**. Bộ quyết định cũ đã chạy ở client (`AdaptiveVideoPolicy`), không phải ở server. `NETWORK_STATS_ACK` không chứa lệnh chỉnh video.

SFU thực tế là **Go/Pion**, với native `webrtc-java` 0.19.0. Không chuyển sang Janus, không thay codec, bật simulcast hoặc thêm encoder layer. RTCP/ICE/congestion control/signaling nghiệp vụ được giữ.

`client.network.NetworkMonitor` đo JSON/auth/ROOM_POLL và giao diện legacy, không phải WebRTC bandwidth. Nó được giữ cho chẩn đoán kênh máy chủ; không dùng để điều chỉnh sender camera.

## B. File và trách nhiệm sau migration

| File | Thay đổi và lý do |
|---|---|
| `src/main/java/client/media/VideoCallController.java` | Thay vòng thống kê cũ bằng collector chung, buffer/analyzer/policy; bỏ toàn bộ JSON NETWORK_STATS; throttle UI/analysis, kiểm tra generation khi reconnect/close |
| `src/main/java/client/media/NetworkStatsCollector.java` | Một scheduler cho các peer camera/audio/screen; một batch đang chạy; timeout và bỏ callback generation cũ; dọn counter và scheduler khi đóng |
| `src/main/java/client/media/SingleFlightStats.java` | Mỗi native peer tối đa một getStats chưa trả callback; timeout không mở lại slot native; đóng peer giải phóng waiter |
| `src/main/java/client/media/MediaStatsSource.java` | Mô tả nguồn ngắn hạn trong một vòng, tách loại media/chiều gửi nhận |
| `src/main/java/client/media/RtcQualityMonitor.java` | Dùng lại parser/delta, mở rộng nhận biết peer/stat/SSRC và timestamp; không cộng counter các stream trước khi lấy delta; chọn đúng ICE pair; tách 6 nhóm camera/audio/screen × gửi/nhận |
| `src/main/java/client/media/NetworkSample.java` | Record nhỏ, immutable, chỉ số thiếu dùng null/UNKNOWN; không giữ report/JSON/frame/PeerConnection |
| `src/main/java/client/media/NetworkStatsBuffer.java` | Vòng cố định 40 mẫu, ghi đè cũ, snapshot nhất quán; reset generation chặn mẫu cuộc gọi cũ |
| `src/main/java/client/media/NetworkQualityAnalyzer.java` | Đánh giá EXCELLENT/GOOD/FAIR/POOR/CRITICAL/UNKNOWN, cửa sổ suy giảm/phục hồi và nguồn giới hạn; tách uplink/downlink |
| `src/main/java/client/media/AdaptiveVideoPolicy.java` | Thay các mức cũ bằng SAVING/LOW/HD và trạng thái an toàn VOICE_PRIORITY, duy nhất AUTO, hysteresis/cooldown và không nâng mù khi thiếu headroom |
| `src/main/java/client/media/NetworkStatsConfig.java` | Đọc properties client hiện có, validate giá trị, không có mode thủ công/persistence |
| `src/main/java/client/media/NetworkStatsCalculator.java` | Projection cho UI và helper nhãn ba vạch tại client; bỏ bộ tính counter tổng hợp cũ |
| `src/main/java/client/media/NativeVideoSession.java` | Áp bitrate/FPS/scale từ kích thước thật của input, không upscale, dùng native stats gate, không tái tạo PeerConnection khi đổi mức |
| `src/main/java/client/media/NativeAudioSession.java` | Dùng native stats gate, giữ capture/playback/PCM độc lập |
| `src/main/java/client/media/AudioCallController.java` | Cung cấp các peer cho collector chung; không tự chạy thêm vòng lấy stats thoại |
| `src/main/java/client/media/ScreenCallController.java` | Cung cấp peer gửi/nhận; giữ pause/resume bảo vệ thoại và lựa chọn chia sẻ của người dùng |
| `src/main/java/client/media/CameraVideoInput.java`, `VideoInput.java` | Chọn mode thực tối đa 720p/30 FPS, ưu tiên FPS rồi độ phân giải để không chọn 720p/8 thay vì 480p/30, cung cấp kích thước/FPS nguồn để scale an toàn |
| `src/main/java/client/media/VideoFrameMailbox.java` | Bỏ trần hiển thị 10 FPS, cadence tối đa 30 FPS có dung sai jitter; chuyển I420 bằng libyuv native, tái sử dụng bộ đệm BGRA và giữ một FX job/mẫu ảnh mới nhất |
| `src/main/java/client/UI/components/NetworkSignalWidget.java` | Bỏ import class server, dùng phân loại client; hiển thị riêng gửi/nhận trong cùng ô bitrate |
| `src/main/java/client/UI/screens/MeetingScreen.java` | Giữ layout và popup thống kê; chỉ một tick UI khoảng 1 giây, bỏ đường cập nhật widget trùng với listener stats |
| `src/main/java/client/meeting/MeetingSession.java` | Bỏ NETWORK_STATS khỏi allow-list của client mới; giữ nguyên membership/reconnect |
| `src/main/java/server/signalingapi/MeetingHandler.java` | Bỏ stats queue/validation và dependency writer; NETWORK_STATS cũ nhận ACK với dropped=true, retired=true, không ghi dữ liệu |
| `src/main/java/server/MeetingServerMain.java` | Bỏ khởi tạo/start/close writer thống kê; giữ chat retention và dịch vụ khác |
| `src/main/java/server/meeting/NetworkStatsService.java` | Đã loại bỏ writer/retention/preflight cũ, không còn scheduler nền truy cập bảng |
| `database/schema.sql` | Database mới không tạo bảng thống kê |
| `database/migrations/004_drop_network_statistics.sql` | Xóa bảng và lịch sử cho database đang tồn tại, không tự chạy |
| `database/rollback/004_restore_network_statistics_table.sql` | DDL tùy chọn để dựng lại cấu trúc cũ; không tự khôi phục lịch sử và không bật writer |
| `config/auth-client.properties.example` | Thêm khóa cấu hình cục bộ; không ghi đè cấu hình kết nối riêng của người dùng |
| `pom.xml` | Các test media mới chỉ chạy trong profile video-integration; một lần chạy test thường sau đó không vô tình chạy native suite từ class còn trong target |

Các test tương ứng được thay/bổ sung; test kiểm tra INSERT thống kê cũ được thay bằng kiểm tra không còn ghi/không cần bảng. Tài liệu chat giữ chính sách xóa tin sau 20 phút, không gộp với việc xóa bảng thống kê.

## C. Kiến trúc mới

```text
Native camera/audio/screen peers
  -> getStats async (một native request chưa hoàn tất/peer)
  -> NetworkStatsCollector (một batch; scheduler 500 ms)
  -> RtcQualityMonitor (delta từng stream, phân chiều)
  -> NetworkStatsBuffer (RAM, tối đa 40 mẫu)
  -> NetworkQualityAnalyzer (1 giây)
  -> AdaptiveVideoPolicy (AUTO)
  -> VideoCallController owner -> sender.setParameters
                      └─ voice bị ảnh hưởng kéo dài -> tạm dừng video/share
  -> UI snapshot coalesced (tối đa khoảng 1 giây)
```

Bitrate/FPS lấy từ delta byte/frame chia thời gian đơn điệu, mất gói lấy delta theo peer/stat/SSRC; remote RTCP fractionLost được đổi 0..1 thành %. Counter reset, đổi SSRC/peer, báo cáo trùng timestamp và NaN/Infinity không tạo số 0 giả. Lịch sử không chứa report native hoặc tham chiếu peer; counter ngắn hạn được prune/reset.

Camera gửi chỉ dùng số liệu uplink và giới hạn encoder/source phù hợp. Video nhận bị mất gói không tự làm hạ sender của local client. Available outgoing bitrate không được gán cho chiều nhận; chiều nhận chỉ dùng available incoming nếu API có. Không chủ động speed test và không suy ra băng thông từ bitrate thấp của cảnh tĩnh.

Thu thập chạy khi đã vào cuộc gọi, còn signaling và có peer media. Vào sảnh/phòng chờ hoặc không có peer thì không gọi getStats. Không block JavaFX hoặc PCM owner. Native lifecycle lock ngắn bảo vệ dispatch getStats/setParameters trước thao tác close; không giữ lock trong lúc chờ callback/future hoặc xử lý buffer/UI. Lock này tách khỏi track callback/capture để tránh deadlock native.

Timeout kết thúc waiter và round hiện tại, nhưng slot native chỉ được mở khi callback thật trả về (hoặc peer đóng). Một native request treo không làm phát sinh native request mới vô hạn. Callback cũ bị loại bằng round/transport generation. Khi close, scheduler dừng và clear counter; khi reconnect, buffer/policy/mốc delta được reset. Media vẫn cần bật lại bằng tay sau mất signaling, như cơ chế khôi phục phòng đã có.

## D. Cấu hình và quy tắc AUTO

Các khóa được đọc từ `mmr.client.config` hoặc `config/auth-client.properties`. Chỉ bổ sung các khóa cần thiết từ file example, không thay host/certificate.

| Khóa | Mặc định | Giới hạn |
|---|---:|---|
| `network.monitor.enabled` | true | false ngừng monitor |
| `network.stats.interval.ms` | 500 | 500–2000 |
| `network.stats.ui-update.ms` | 1000 | 1000–10000 |
| `network.stats.analysis.interval.ms` | 1000 | 1000–5000 |
| `network.stats.buffer.capacity` | 40 | Cố định 40, không tăng theo config |
| `network.stats.max-inflight` | 1 | Cố định một batch/collector và một request native/peer |
| `network.stats.timeout.ms` | 3000 | 500–3000 |
| `network.stats.persist-raw` | false | Luôn RAM-only, true không bật persistence |
| `video.adaptive.enabled` | true | false giữ mức LOW, không tạo manager thứ hai |
| `video.adaptive.degrade-window.ms` | 4000 | 2000–8000 |
| `video.adaptive.upgrade-window.ms` | 15000 | 10000–18000 |
| `video.adaptive.cooldown.ms` | 10000 | 5000–30000 |

Giá trị không hợp lệ dùng mặc định. Các mức chất lượng là giới hạn trên, không phải cam kết FPS/độ phân giải/bitrate đạt được:

| Mức | Kích thước tối đa | FPS tối đa | Max bitrate mặc định |
|---|---|---:|---:|
| SAVING | 320×180 | 15 | 200.000 bps |
| LOW, khởi đầu | 640×360 | 30 | 600.000 bps |
| HD | 1280×720 | 30 | 2.500.000 bps |

Bitrate cấu hình qua `video.profile.saving/low/hd.max-bitrate`, có giới hạn an toàn trong code. Giữ tỷ lệ nguồn, không upscale; ví dụ nguồn 4:3 có thể ra 240×180 ở SAVING. Camera không có mode phù hợp dùng mode thấp hơn; không thử 45/60 FPS, không tự restart khi đổi profile.

Suy giảm sender cần mất gói/jitter xấu kéo dài, hoặc available bitrate thấp kèm RTT/jitter/mất gói xấu. RTT cao đơn lẻ, cờ native `bandwidth limited` đơn lẻ và bitrate thực thấp của cảnh tĩnh không đủ kết luận uplink nghẽn. Mức mất gói/jitter nghiêm trọng hoặc available bitrate rất thấp kèm bằng chứng đường truyền xấu kéo dài 2 giây cho phép giảm nhanh, vượt cooldown. Nâng từng mức sau ít nhất 15 giây tốt; thử trần HD khi nguồn lớn hơn trần LOW và available bitrate ≥ 125% trần bitrate LOW (mặc định 750 Kbps). Không bắt đường truyền đạt 130% trần HD ngay khi encoder còn bị giới hạn LOW. Native congestion control vẫn quyết định bitrate thật trong trần mới; không áp min bitrate hoặc ép phát đủ 2,5 Mbps. Camera 25 FPS cũng được nâng độ phân giải theo khả năng thật, không bị chặn bởi điều kiện capture ≥ 30 FPS. Cửa sổ đường truyền tốt cho phép thoát SAVING dù bandwidth estimate còn thấp; không để giới hạn bitrate của chính profile giữ máy ở SAVING mãi. Thiếu bằng chứng giữ mức thấp an toàn, không giả định mạng tốt.

CPU limitation giảm FPS tối đa xuống 15 và có thể hạ profile; không bỏ giới hạn CPU ngay sau một mẫu tốt mà chờ cửa sổ phục hồi. Khi getStats không hỗ trợ reason thì UNKNOWN, không tự gọi một FPS thấp là CPU yếu.

VOICE_PRIORITY là trạng thái bảo vệ, không phải nút chọn: chỉ vào sau ít nhất 5 giây thoại có RTT/jitter/loss xấu. Tạm dừng RTP camera và chia sẻ gửi/nhận, giữ PCM/voice; preview/capture camera có thể vẫn hoạt động như trước. Phục hồi sau cửa sổ tốt, không tạo publication đã được người dùng tắt.

## E. Kiểm thử và số đo

Baseline logic trước sửa: 25/25 bài qua (`target/client-stats-baseline.txt`). Bộ test mới bao phủ buffer/concurrency/delta/SSRC/timestamp, số liệu thiếu, CPU/camera limitation, uplink khác downlink, hysteresis/cooldown, native single-flight sau timeout, idle/reset/close, migration chỉ xóa bảng thống kê và tương thích client cũ khi bảng không tồn tại.

Lượt hồi quy sạch toàn dự án với profile `video-integration`: **504 test, 0 failure, 0 error, 3 skipped**, BUILD SUCCESS (`target/client-stats-final.txt`). Ba test bỏ qua thuộc Windows audio, file storage và file transfer theo điều kiện môi trường của bộ test. Sau các chỉnh sửa cuối về bằng chứng nghẽn, thu hồi counter của peer cũ và nhãn chất lượng thoại/chia sẻ, lượt kiểm tra tập trung **44/44 qua**, gồm native camera/audio/screen (`target/client-stats-last-check.txt`).

Test nguồn 1280×720/30 kiểm chứng chuyển LOW → HD → SAVING không khởi động lại capture, ảnh nhận tiếp tục chạy và kích thước/FPS không vượt giới hạn profile. Native congestion control vẫn có thể xuất ảnh thấp hơn giới hạn; test không ép phải đạt đúng 720p/30. Trong quá trình phát triển đã sửa lỗi null-unboxing và tên cột fixture H2; đã sửa thứ tự khởi tạo nguồn hình JNI trong test. Test media cũng phát hiện cờ native `bandwidth limited` có thể xuất hiện trên đường loopback tốt: classifier đã được sửa để cần bằng chứng đường truyền đi kèm, có regression test giữ LOW khi chỉ có cờ này và estimate thấp.

Native dùng nguồn hình/PCM/màn hình tạo bằng mã, TLS/H2 riêng và Go SFU thật. Không mở thiết bị thật hoặc dùng Railway. Đã kiểm tra ảnh giảm kích thước thực, RTP video/share dừng khi ưu tiên thoại, PCM hai chiều tiếp tục, khôi phục đúng mức và không tự bật lại luồng đã tắt; khôi phục phòng giữ membership/quyền tệp.

Nhóm 2/5/10 người: 2 và 5 người đều phát camera; ca 10 người phát 1 camera để giới hạn tải và dùng 2 nguồn thoại. Đây không phải kiểm thử 10 camera đồng thời. Test đếm `NETWORK_STATS` tại handler và kiểm tra buffer không quá 40, ghi CPU process Java, heap/GC/thread và nhịp lấy mẫu trong cửa sổ 5 giây. CPU của tiến trình Go không được cộng vào số CPU Java.

So sánh baseline dùng source commit `d4ac9ab` giải nén riêng trong `target/client-stats-baseline-d4ac9ab`; chỉ thêm cùng harness/fixture đo để workload giống nhau. Không reset checkout của người dùng. Đây là một lượt đo ngắn, có warm-up/tải nền/GC khác nhau, không dùng để tuyên bố một phần trăm tối ưu tổng quát.

Kết quả hai lượt chạy riêng trước bản sửa cadence/render và điều kiện nâng HD bổ sung (mỗi dòng là 5 giây sau khi media đã chạy). Lượt migration có **3/3 test nhóm qua và package BUILD SUCCESS**, tạo `target/MiniMeetingRoom-1.0-SNAPSHOT.jar`; log `target/client-stats-package.txt`, số đo `target/stats-after-final.csv`. Bằng chứng và lượt kiểm chứng bản sửa video sau đó ở [video-quality-fix.md](video-quality-fix.md); không dùng bảng dưới để tuyên bố hiệu năng của renderer mới:

| Người / camera phát | Telemetry cũ → mới | CPU Java cũ → mới, số core trung bình | Heap cũ → mới, MiB | Số lần GC cũ → mới | Nhịp mẫu mới |
|---|---:|---:|---:|---:|---:|
| 2 / 2 | 10 → 0 | 0,284 → 0,269 | 59,35 → 36,91 | 1 → 1 | 499,77 ms |
| 5 / 5 | 27 → 0 | 0,809 → 0,937 | 38,64 → 39,57 | 4 → 5 | 500,13 ms |
| 10 / 1 | 50 → 0 | 0,834 → 0,834 | 97,55 → 26,88 | 2 → 3 | 500,03 ms |

Thread Java trước/sau trong cửa sổ đo: bản cũ 72→73, 135→137, 171→173; bản mới 71→72, 133→136, 170→173. Đây là toàn bộ thread JVM/native attach, không chỉ monitor. Buffer lớn nhất quan sát được ở lượt mới là 15/22/17 mẫu, dưới trần 40; unit test còn ghi 10.000/20.000 mẫu để kiểm tra ghi đè và đọc đồng thời.

Không có cải thiện CPU/heap đồng đều ở mọi cỡ nhóm trong lượt đo này. Tần suất đo media tăng từ khoảng 1 giây lên 500 ms; số liệu heap phụ thuộc thời điểm GC và phiên này không ép `System.gc()`. Kết luận được xác nhận là không còn telemetry ứng dụng định kỳ, bộ nhớ lịch sử có trần và sampling xấp xỉ 500 ms; không suy ra phần trăm tiết kiệm CPU/RAM. CSV/log gốc được giữ trong `.tools/test-artifacts/client-stats-20261018/` (ignored) và các báo cáo `target/client-stats-*.txt` sau build; archive baseline cũng được giữ trong thư mục artifact đó sau Clean.

## F. Áp dụng trên Railway và kiểm chứng

### Thứ tự triển khai

1. Build và chạy **server mới trước**, bảo đảm không còn instance server cũ. Server mới không khởi động writer/retention bảng thống kê; client cũ gửi telemetry chỉ được ACK bỏ qua.
2. Nếu muốn có đường khôi phục lịch sử, tạo bản backup trước khi xóa (bước tùy chọn dưới đây).
3. Chạy thủ công [migration 004](../database/migrations/004_drop_network_statistics.sql) trên đúng database Railway. MySQL `DROP TABLE` xóa toàn bộ lịch sử và không thể hoàn tác bằng lệnh ROLLBACK.
4. Build/chạy client mới. Không cần thêm entity/bảng mới. Database cài mới dùng schema đã bỏ bảng thống kê, không cần chạy migration 004 lần nữa.

Migration chỉ có:

```sql
DROP TABLE IF EXISTS Thong_Ke_Ket_Noi;
```

Nếu database ngoài repository có khóa ngoại hoặc báo cáo phụ thuộc bảng này, kiểm tra và dừng để xử lý dependency; không tắt kiểm tra FK hoặc tự dùng DROP CASCADE để ép xóa.

### Backup và rollback tùy chọn

Tạo file dump riêng bằng thông tin kết nối public MySQL Railway; `--password` hỏi mật khẩu tương tác:

```powershell
mysqldump --host=PUBLIC_HOST --port=PUBLIC_PORT --user=DB_USER --password --single-transaction DB_NAME Thong_Ke_Ket_Noi --result-file=network-statistics-backup.sql
```

Giữ file dump ngoài Git. Để khôi phục lịch sử, nhập file dump bằng MySQL client/Railway SQL tooling. [DDL rollback](../database/rollback/004_restore_network_statistics_table.sql) chỉ tạo lại cấu trúc rỗng nếu không có dump; không khôi phục dữ liệu đã xóa. Việc tạo lại bảng không tự bật writer: muốn rollback toàn bộ ứng dụng phải triển khai phiên bản server/client cũ nhất quán và xem lại retention cũ trước khi khởi động.

### NetBeans và hai client

- Clean and Build dự án. Chạy `src/main/java/server/Main.java` bằng Run File với cấu hình server hiện có.
- Chạy `src/main/java/client/Main.java` cho mỗi client, giữ địa chỉ/certificate đúng theo cấu hình đang dùng.
- Tạo/tham gia phòng từ giao diện có sẵn. Bật camera/mic theo quyền người dùng. Mở popup biểu tượng mạng để xem RTT/loss/jitter/bitrate gửi/nhận/FPS/kích thước. Cài đặt cuộc họp chỉ hiển thị mức tự động, không có MANUAL.
- Khi nguồn lớn hơn trần LOW, mạng tốt và đủ headroom, quan sát nâng từng mức; trên camera thấp hơn kiểm tra thông số thực, không kỳ vọng upscale hoặc FPS vượt capture.
- Để thử mạng yếu thực tế, dùng thiết bị thử nghiệm/router hạn chế băng thông hoặc packet loss riêng cho đường media; kiểm tra uplink và downlink riêng. Agent không sửa firewall/router hoặc cắt kết nối máy người dùng.
- Uplink xấu kéo dài: video giảm trước. Downlink riêng xấu không tự hạ sender local. Thoại xấu kéo dài: ưu tiên thoại; mạng tốt: phục hồi từng mức. Tắt camera/share bằng tay trong lúc pause rồi kiểm tra không tự bật lại.
- Đứt signaling ngắn rồi nối lại: khôi phục trong thời gian chờ 2 phút; thử gửi tệp. Quá hạn phải ra màn hình kết thúc. Thống kê của phiên cũ không tác động cuộc gọi mới.
- Trong kiểm thử tự động, fixture đếm NETWORK_STATS = 0. Trên triển khai thực có thể lọc log/đếm request ở môi trường test; RTCP/ICE/signaling media vẫn tồn tại và là cần thiết, không được coi là telemetry cũ.
- Xác minh bảng đã xóa bằng `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='Thong_Ke_Ket_Noi';` (kết quả 0). Không còn dòng thống kê để ghi hoặc truy vấn quyết định chất lượng.
- Join/leave nhiều lần: theo dõi thread `media-stats`, buffer về rỗng khi đóng, CPU/heap ổn định theo tải; không so số thread native tức thì với số luồng Java của monitor.

## G. Giới hạn và phần chưa đo

- Chưa thử camera/microphone thật, LAN/WAN thật hoặc nhiễu mạng thực trên card mạng. Chỉ số xấu/tốt trong test policy được đưa vào bằng mã; encode/decode/RTP vẫn chạy thực.
- Chưa kiểm thử 10 camera cùng phát, cuộc gọi hàng giờ hoặc thiết bị cấu hình thấp cụ thể. Số đo ngắn không chứng minh hiệu năng dài hạn hay zero memory leak ở mọi driver.
- Không có SFU layer selection/simulcast trong kiến trúc hiện tại, nên không giả vờ đổi resolution của từng video nhận. Voice-priority có thể tạm ngừng nhận video/share khi cần bảo vệ thoại.
- Chỉ số WebRTC không được API cung cấp là UNKNOWN; thiếu available bitrate có thể giữ LOW thay vì nâng HD. Không thêm speed test để đoán băng thông.
- Thay bitrate/FPS/scale không tạo thêm chi tiết ảnh hoặc FPS cao hơn capture. Cảnh ít chuyển động có bitrate thấp dù đường truyền tốt.
