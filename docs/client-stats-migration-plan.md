# Đối chiếu trước migration thống kê media sang client RAM-only

Ngày kiểm tra: 18/10/2026. Commit gốc: `d4ac9ab`.

Trạng thái: bản đối chiếu ban đầu trước sửa. **Đã được thay bằng phạm vi người phát triển xác nhận ngày 18/10/2026: xóa bảng/lịch sử qua migration thủ công, chỉ AUTO tối đa 720p/30 FPS; không MANUAL hoặc 45/60 FPS.** Các đề xuất giữ bảng và chế độ thủ công bên dưới chỉ là lịch sử phương án ban đầu, không phải hành vi triển khai. Xem [báo cáo triển khai hiện tại](client-stats-migration.md).

## 1. Luồng thực tế hiện tại

```text
VideoCallController.statsSampler (mỗi vòng xong chờ thêm 1 giây)
  -> NativeVideoSession.Peer.stats + AudioCallController.networkQuality
     + ScreenCallController.transportQuality
  -> RtcQualityMonitor / NetworkStatsCalculator
       ├─ observeNetwork -> AdaptiveVideoPolicy (CLIENT)
       │    -> NativeVideoSession.Peer.quality -> RTCRtpSender.setParameters
       │    -> VOICE_PRIORITY: tạm dừng camera và chia sẻ để bảo vệ thoại
       ├─ latestStats -> NetworkSignalWidget (JavaFX)
       └─ JSON NETWORK_STATS -> MeetingSession -> SignalingClient (TCP/TLS)
            -> SignalingServer -> MeetingHandler
            -> NetworkStatsService.queue
            -> batch INSERT Thong_Ke_Ket_Noi mỗi 10 giây
            -> xóa thống kê cũ theo retention mỗi 30 giây
```

Không tìm thấy luồng đọc bảng thống kê để ra quyết định chất lượng video. Server trả `NETWORK_STATS_ACK`, không trả lệnh đổi bitrate/FPS/resolution. Vì vậy nhiệm vụ thực tế là thay bộ lấy mẫu/phân tích hiện có và cắt telemetry/persistence; không phải chuyển một bộ quyết định từ database về client.

Media dùng native WebRTC Java và Go/Pion SFU, không dùng Janus. Không phát hiện cấu hình nhiều lớp simulcast cho camera hiện tại. Không đổi SFU, codec, RTCP, ICE, signaling phân quyền hoặc cơ chế khôi phục phòng 2 phút.

## 2. Dependency và xử lý dự kiến

| Thành phần | Hiện tại | Xử lý dự kiến |
|---|---|---|
| `client/media/VideoCallController.java` | Lấy mẫu, phân tích, cập nhật UI, gửi NETWORK_STATS trong một owner | MODIFY: một luồng monitor cục bộ thay thế vòng cũ; bỏ sender JSON thống kê |
| `client/media/RtcQualityMonitor.java` | Delta theo peer/stat, gộp số liệu gửi/nhận thành worst case | REUSE/MODIFY: giữ delta theo stream, tách uplink/downlink/audio, hạn chế giữ peer cũ |
| `client/media/NetworkStatsCalculator.java` | Delta bitrate/FPS và số liệu tổng hợp | REUSE/MODIFY: kiểm tra thời gian, counter reset, missing/NaN, định danh stream |
| `client/media/AdaptiveVideoPolicy.java` | NORMAL/MEDIUM/LOW/VOICE_PRIORITY; hysteresis 3/5/12 giây | MODIFY: một state machine, bốn profile mới, AUTO/MANUAL, window/cooldown; giữ chính sách bảo vệ thoại đã có |
| `client/media/NativeVideoSession.java` | Sender parameters và getStats native | REUSE/MODIFY: apply profile theo khả năng capture; single-flight thật kể cả callback timeout |
| `client/media/NativeAudioSession.java` | getStats native phục vụ nhận biết thoại | REUSE/MODIFY: lấy số liệu gọn, không chặn PCM owner, chặn request native bị treo tích lũy |
| `client/media/AudioCallController.java` | Thu thập stats thoại qua caller của video | MODIFY phần cung cấp stats; KEEP capture/playback/consent |
| `client/media/ScreenCallController.java` | Cung cấp ICE RTT và pause khi ưu tiên thoại | MODIFY phần cung cấp stats; KEEP luồng share và thao tác người dùng |
| `client/media/CameraVideoInput.java` | Chọn mode gần 640×480/15 FPS, chỉ xét kích thước tối đa 1280×720 | Chờ chốt chính sách capture; không hứa 45/60 FPS khi source chỉ phát 15/30 |
| `client/UI/components/NetworkSignalWidget.java` | Hiện số liệu media; đang import class server để tính nhãn | MODIFY: dùng snapshot và analyzer client; không phụ thuộc server.meeting |
| `client/UI/dialogs/CameraSettingsDialog.java` | Nhãn chất lượng tự động, chưa có chọn AUTO/MANUAL | MODIFY tối thiểu: thêm chọn chế độ/profile trong Cài đặt hiện tại |
| `client/UI/screens/MeetingScreen.java` | Nhãn profile và thống kê mạng | MODIFY bindings/throttle UI; giữ layout/room/file/approval |
| `client/network/NetworkMonitor.java` | Đo lưu lượng JSON và RTT request auth/ROOM_POLL | KEEP: chẩn đoán kênh nghiệp vụ/legacy, không dùng để quyết định uplink media |
| `client/UI/dialogs/NetworkStatsDialog.java` | Giao diện legacy của NetworkMonitor | KEEP; không coi đây là bộ thống kê WebRTC thứ hai |
| `client/meeting/MeetingSession.java` | Cho phép lệnh NETWORK_STATS | MODIFY: client mới bỏ lệnh telemetry khỏi allow-list; KEEP reconnect/membership |
| `server/signalingapi/MeetingHandler.java` | Validate telemetry, throttle, đẩy StatsRecord vào queue | REMOVE đường ghi stats; giữ ACK bỏ qua cho client cũ trong cutover |
| `server/signalingapi/SignalingServer.java` | Nhánh NETWORK_STATS dùng xác thực cached | MODIFY tương thích, không tạo truy vấn DB thêm cho telemetry cũ; giữ xác thực/phân quyền |
| `server/meeting/NetworkStatsService.java` | Queue, INSERT batch, SELECT/DELETE retention; helper nhãn cho UI | REMOVE sau tách helper và thay test; không để writer/cleanup mồ côi |
| `server/MeetingServerMain.java` | Khởi động/đóng NetworkStatsService | REMOVE lifecycle riêng của thống kê; KEEP chatRetention và các dịch vụ khác |
| `database/schema.sql` | Định nghĩa Thong_Ke_Ket_Noi | KEEP bảng và lịch sử; đánh dấu không còn dùng bởi phiên bản mới |

Các điểm SQL runtime của `Thong_Ke_Ket_Noi` chỉ được tìm thấy trong `NetworkStatsService`: preflight SELECT, INSERT batch, SELECT khóa cần xóa và DELETE retention. Không tìm thấy chức năng nghiệp vụ khác đọc bảng này trong source hiện tại. Đây là kết quả kiểm tra repository; không chứng minh rằng bên ngoài dự án không có báo cáo dùng dữ liệu lịch sử.

## 3. Thay đổi quan trọng cần chốt

### Dừng lịch sử thống kê mạng trên server

Theo prompt, client mới sẽ không gửi telemetry ứng dụng định kỳ; server mới không ghi hoặc tự xóa lịch sử thống kê. Bảng và dữ liệu đã có được giữ nguyên. Không DROP, không thực thi SQL trên Railway, không thêm bảng thay thế. Hệ quả: không có thêm lịch sử thống kê mạng tập trung cho các cuộc gọi mới; buffer 40 mẫu nằm riêng trên mỗi client và hết khi kết thúc phiên.

Phương án triển khai: cập nhật server trước để ngừng writer/retention và ACK-bỏ-qua telemetry từ client cũ, rồi cập nhật client. Client cũ còn gửi telemetry nhưng không làm database tiếp tục tăng. Client mới vẫn làm việc với server cũ bằng signaling hiện có; server cũ vẫn có thể chạy retention cho tới khi được nâng cấp. Không để quality control cũ ghi đè state machine mới.

### Capture và bốn mức chất lượng

Prompt yêu cầu SAVING 320×180/15 FPS/200 Kbps; LOW 640×360/30 FPS/600 Kbps; HD 1280×720/45 FPS/2,5 Mbps; FULL_FPS 1280×720/60 FPS/4,5 Mbps. Đây là mục tiêu/giới hạn trên, luôn bị giới hạn bởi camera, nguồn hình và encoder thực tế.

Source hiện ưu tiên 640×480/15 FPS. Chỉ đổi giới hạn sender sẽ không tạo được hình 720p hoặc 60 FPS từ source đó. Phải thay chính sách chọn capture mode; đây là thay đổi có thể tăng tải CPU và công suất camera.

Đề xuất tiết kiệm: AUTO khởi đầu LOW, chọn source tối đa 720p/30 FPS nếu camera hỗ trợ, giảm bằng bitrate/FPS/scale mà không restart camera khi mạng dao động. Chọn 45/60 FPS bằng tay chỉ áp dụng khi có mode phù hợp; nếu capture đang chạy thấp hơn, thông báo giới hạn và yêu cầu người dùng chủ động bật lại camera để áp dụng, không âm thầm restart. Giữ tỷ lệ ảnh và fallback khi thiết bị không có mode mục tiêu, không upscale để giả đạt profile.

Phương án khác: mở source tối đa 720p/60 FPS ngay từ đầu nếu camera hỗ trợ, để AUTO có thể sử dụng cả 45/60 FPS mà không đổi source. Nhược điểm: overhead capture lớn hơn kể cả lúc encoder đang gửi LOW/SAVING. Không thể kết luận mức tăng CPU khi chưa đo trên thiết bị.

### Giữ chính sách ưu tiên thoại

Theo yêu cầu đã chốt trước đó: giảm video trước, chỉ tạm dừng video/chia sẻ khi có bằng chứng thoại bị ảnh hưởng kéo dài. Đề xuất giữ điều kiện bảo vệ thoại trong cả AUTO và MANUAL dưới dạng trạng thái an toàn riêng. Trong MANUAL, lựa chọn profile của người dùng không bị đổi; khi hết tình trạng nghiêm trọng thì trả về lựa chọn đó. Không tự bật camera/mic/share đã tắt bằng tay. Mất signaling vẫn áp dụng cơ chế khôi phục phòng 2 phút hiện có.

## 4. Thiết kế sau khi được xác nhận

- Thay vòng lấy mẫu cũ, không chạy song song hai monitor media/hai quality managers.
- Mặc định sampling 500 ms, phân tích 1.000 ms, UI tối đa 1.000 ms, circular buffer 40 mẫu nhỏ trong RAM, không lưu RTCStatsReport/JSON/frame làm lịch sử.
- Bounded single-flight, timeout/callback generation, task không chồng vô hạn; request native chưa thực sự kết thúc phải được theo dõi dù caller đã hết timeout.
- Tách số liệu uplink/downlink/audio; chỉ dùng uplink và dấu hiệu thiết bị phù hợp để điều chỉnh sender. Không suy ra băng thông từ bitrate thấp hoặc một video nhận bị giật.
- Phân tích cửa sổ ngắn 3–5 giây và dài 15–20 giây; degrade mặc định 4 giây, upgrade 15 giây, cooldown 10 giây, cho phép hạ khẩn cấp. UNKNOWN giữ trạng thái an toàn.
- Xử lý availableOutgoingBitrate, encoder qualityLimitationReason và khả năng camera khi API cung cấp; thiếu dữ liệu phải ghi rõ UNKNOWN, không coi là 0 hoặc mạng tốt.
- Cấu hình qua cơ chế properties client hiện có, cập nhật file example; không ghi đè host/certificate hoặc cấu hình riêng đang dùng.
- Giữ congestion control, RTCP, SFU và codec. Không thêm simulcast hay transcoding chỉ để hỗ trợ profile.
- Giới hạn API/downlink phải được ghi rõ; không tuyên bố client có thể giảm chất lượng từng stream nhận nếu SFU hiện không hỗ trợ layer tương ứng.

## 5. Baseline và kiểm chứng dự kiến

Đã chạy Maven trên mã gốc: `AdaptiveVideoPolicyTest`, `RtcQualityMonitorTest`, `NetworkStatsCalculatorTest`, `NetworkStatsServiceTest`, `NetworkSignalWidgetTest`: **25/25 đạt**, build thành công. Log: `target/client-stats-baseline.txt`. Test dùng dữ liệu giả/H2, không truy cập Railway hoặc mở thiết bị thật. Chưa đo baseline CPU/RAM/GC hoặc thử đường truyền thật.

Sau xác nhận sẽ kiểm thử: buffer ghi đè/đọc đồng thời; delta/counter reset/SSRC; uplink khác downlink; unknown/CPU/camera limitation; AUTO/MANUAL và ưu tiên thoại; cooldown; callback treo/cũ; lifecycle join/leave/reconnect; sender bitrate/FPS/scale thực qua nguồn tổng hợp; client không gửi NETWORK_STATS; server không INSERT/DELETE bảng thống kê, kể cả client cũ; hồi quy voice/chat/room/file/share. Chạy nhóm nhiều client trong giới hạn tài nguyên và báo rõ trường hợp chưa thử. Không khẳng định phần trăm tối ưu khi chưa có phép đo so sánh.

Các bước nguồn thực hiện lần lượt: monitoring + buffer, analyzer/state machine, native/UI integration, cutover sender/writer, cleanup, build và kiểm thử hồi quy. Không xóa dữ liệu lịch sử trong quá trình này.
