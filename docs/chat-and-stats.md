# Tài liệu Kỹ thuật: Chat Realtime, Thống kê Kết nối và Mở rộng Sức chứa Phòng

Tài liệu này ghi lại kiến trúc, các quyết định kỹ thuật và cấu hình cho 3 tính năng mới:
1. Chat realtime qua kênh signaling và chính sách tự hủy vĩnh viễn 20 phút.
2. Đo và lưu trữ thống kê chất lượng mạng vào bảng `Thong_Ke_Ket_Noi`.
3. Tăng giới hạn số lượng người tham gia phòng họp lên 20 người (trần cứng 50).

---

## 1. Chat Realtime và Tự hủy Vĩnh viễn (Task 1)

### 1.1. Luồng truyền tin Realtime
- **Giao thức**: Sử dụng kênh signaling TCP/TLS hiện có, không đi qua SFU media server.
- **Message Types**:
  - `CHAT_SEND`: Gửi tin nhắn mới. Kiểm tra thành viên phòng (`rooms.assertMember()`). Nội dung giới hạn 1–2000 ký tự sau khi strip, chỉ chứa văn bản thuần (plain text), loại bỏ ký tự điều khiển.
  - `CHAT_HISTORY`: Tải lịch sử tin nhắn gần nhất (`limit <= 100`). Chỉ thành viên đang có mặt trong phòng mới được phép gọi. Bỏ qua các tin nhắn có `Ngay_Xoa` hoặc loại `TEP`.
  - `CHAT_MESSAGE`: Sự kiện phát tán (broadcast) từ server tới toàn bộ thành viên trong phòng (bao gồm cả người gửi).
- **Tránh đói sự kiện phòng (Room Starvation)**:
  - `RoomService.event()` mặc định gắn kèm snapshot đầy đủ và đi qua hàng đợi outbound có `eventBudget` (383).
  - Để chat realtime không làm đói các sự kiện vòng đời quan trọng (như `ROOM_CLOSED`, `PARTICIPANT_JOINED`), chat sử dụng đường phát tán riêng: lấy danh sách `connectionId` của phòng từ `RoomService` và phát trực tiếp qua `SignalingServer.event()` với `revision = null`.
  - Phía client (`MeetingSession.event()`), sự kiện `CHAT_MESSAGE` được chặn và xử lý trước khi kiểm tra snapshot revision, không làm tăng `room.revision` và không kích hoạt `GET_ROOM` thừa.
- **Executor & Rate Limiting**:
  - Ghi database cho tin nhắn chat chạy trên executor riêng có giới hạn (2 thread, hàng đợi tối đa 256).
  - Handler chờ tối đa 5 giây; nếu hàng đợi đầy trả về `CHAT_BUSY`.
  - Rate-limit: tối đa 5 tin/giây, burst 10 tin/kết nối.

### 1.2. Chính sách Xóa vĩnh viễn (Chat Retention)
- **Service**: `server.meeting.ChatRetentionService`. Khởi chạy khi server start và lặp lại định kỳ mỗi 30–60 giây.
- **Quy tắc**:
  - Quét theo batch $\le 500$ dòng `Tin_Nhan` có `Loai_Tin_Nhan IN ('VAN_BAN', 'HE_THONG')` thuộc các phòng có `Trang_Thai = 'DA_DONG'` và `Ngay_Dong <= NOW() - 20 phút`.
  - Thực hiện `DELETE` cứng (hard delete) theo ID, không dùng soft-delete (`Ngay_Xoa`) để đảm bảo tin nhắn không thể khôi phục.
  - Cấu hình: `chat.retention-minutes=20` (hỗ trợ cấu hình 1–1440 phút).
- **Quyết định kỹ thuật quan trọng - KHÔNG xóa tin loại TEP**:
  - Bảng `Tep_Dinh_Kem` có khóa ngoại cascade tới `Tin_Nhan`. Việc xóa cứng dòng `Tin_Nhan` loại `TEP` sẽ xóa dòng đính kèm trong DB nhưng để lại file mồ côi (orphan file) trên đĩa lưu trữ.
  - Do đó, các dòng tin nhắn loại `TEP` được bảo lưu và tiếp tục do `FileRetentionService` cùng chính sách `file.transfer.retention-days` quản lý toàn vẹn cả file trên đĩa và bản ghi DB.

---

## 2. Lưu Thống kê Kết nối vào Thong_Ke_Ket_Noi (Task 2)

### 2.1. Đo đạc WebRTC Stats phía Client
- **Nguồn số liệu**: Đọc trực tiếp từ `RTCPeerConnection.getStats()` thông qua `NativeVideoSession.Peer.stats(long timeoutMs)`. Đã được kiểm chứng thực tế trên `webrtc-java` 0.19.0.
- **Bộ tính toán Monotonic Delta** (`NetworkStatsCalculator`):
  - Lấy mẫu định kỳ 1 giây trên executor riêng `statsSampler` trong `VideoCallController` (không chặn luồng JavaFX, không dùng chung worker với publish/subscribe).
  - Tính delta trên đồng hồ monotonic. Tự động loại bỏ cửa sổ nếu counter bị reset hoặc giá trị delta âm. Đổi giây sang mili-giây.
  - **RTT**: Lấy từ `currentRoundTripTime` (candidate-pair) hoặc `roundTripTime` (remote-inbound-rtp).
  - **Jitter & Loss**: Gộp từ các luồng inbound subscription. Jitter lấy giá trị lớn nhất; Loss tính bằng `packetsLost / (packetsReceived + packetsLost) * 100`.
  - **Bitrate, FPS, Kích thước**: Đo từ luồng camera đang phát (`outbound-rtp` và `media-source`).
  - Thuộc tính không đo được để `null`. Nếu toàn bộ mẫu không đo được thì không gửi lên server.

### 2.2. Kiểm tra và Ghi nhận phía Server
- **Signaling Message**: Client gửi `NETWORK_STATS` lên server.
- **Xác thực phiên tối ưu**:
  - `SignalingServer` mặc định gọi `validator.validateHash()` (truy vấn DB) cho mỗi request. Vì Railway DB có độ trễ ~0.5s/truy vấn và pool chỉ có 8 kết nối, việc 1 request/giây/client truy vấn DB sẽ làm nghẽn toàn bộ server.
  - Riêng với `NETWORK_STATS`, server dựa vào `validatedAt` (được revalidate định kỳ 3 giây và ngắt kết nối ngay khi logout/thu hồi token) thay vì tạo truy vấn DB mới.
- **Validation**:
  - RTT: 0–60,000 ms.
  - Jitter: 0–10,000 ms.
  - Loss: 0–100%.
  - Bitrate: 0–100,000 kbps.
  - FPS: 0–120.
  - Chiều rộng / chiều cao: $\le 7680$ px.
  - Vượt ngưỡng lập tức trả về `INVALID_REQUEST`.
  - Chống spam: Tối đa 1 mẫu/giây/kết nối (các mẫu đến sớm hơn 900ms được bỏ qua và trả về `dropped: true`).
- **Phân loại chất lượng mạng**:
  - `TOT`: RTT < 80 ms, Loss < 1.0%, Jitter < 20 ms.
  - `TRUNG_BINH`: RTT < 160 ms, Loss < 3.0%, Jitter < 40 ms.
  - `KEM`: Các trường hợp còn lại.
- **Ghi Batch & Hàng đợi giới hạn**:
  - Hàng đợi giới hạn 1024 mẫu (`NetworkStatsService`). Khi hàng đợi đầy, tự động bỏ mẫu cũ nhất và tăng bộ đếm `dropped`.
  - Worker định kỳ 10 giây gom batch (10 dòng cho mỗi người tham gia trong 10 giây) thực hiện `INSERT INTO Thong_Ke_Ket_Noi` trong một transaction duy nhất.
- **Xóa định kỳ (Cleanup)**:
  - Scheduler chạy mỗi 30 giây: `DELETE FROM Thong_Ke_Ket_Noi WHERE Thoi_Diem_Lay_Mau < NOW() - retention`.
  - Chạy theo batch $\le 5000$ dòng đến khi hết.
  - Cấu hình mặc định:
    ```properties
    stats.sample-interval-ms=1000
    stats.flush-interval-seconds=10
    stats.cleanup-interval-seconds=30
    stats.retention-seconds=60
    ```
  - *Lưu ý*: Nếu muốn xóa sạch toàn bộ sau mỗi 30 giây, đặt `stats.retention-seconds=0`.
- **Preflight Check**:
  - Khi server khởi động, thực hiện `SELECT ... WHERE 1=0`. Nếu bảng `Thong_Ke_Ket_Noi` không tồn tại, tính năng tự động tắt an toàn (`NETWORK_STATS_DISABLED`), không tạo bảng và không làm sập server.

---

## 3. Tăng Giới hạn Số lượng Người trong Phòng (Task 3)

### 3.1. Đồng bộ Chính sách Cấu hình
- **Mặc định mới**: `meeting.maxParticipants = 20`, cho phép cấu hình từ 2 đến trần cứng 50.
- **Java Server**:
  - `MeetingServerMain`: Đọc `meeting.maxParticipants`, giới hạn trần cứng `Math.min(50, Math.max(2, val))`.
  - `RoomService`: Cấu hình mặc định `DEFAULT_MAX_PARTICIPANTS = 20`, trần cứng `HARD_CAP_PARTICIPANTS = 50`.
  - `SignalingServer.Options`: Nâng mặc định `maxConnections = 256`, `requestsPerSecond = 50`.
  - Gửi `maxParticipants` về client trong cả `SERVER_WELCOME` và phản hồi `AUTHENTICATED`.
- **Java Client**:
  - `SignalingClient` & `MeetingSession`: Đọc `maxParticipants` từ server welcome/auth để xác định trần sức chứa phòng.
  - `SceneManager.maxRoomCapacity()`: Đọc động từ session SFU với fallback 20 (thay vì 16 hoặc 10 trước đây).
  - `CreateRoomDialog` & `SfuRoomScreen`: Cho phép spinner sức chứa tăng lên đến trần phòng được server cho phép.
  - `MeetingScreen` & `BalancedVideoGrid`:
    - Hỗ trợ hiển thị 20 người tham gia.
    - Phân trang giao diện 12 người/trang (Page 1: 1–12, Page 2: 13–20) kèm nút điều hướng `← Trước` và `Sau →`.
    - Thuật toán `BalancedVideoGrid` tính toán tỷ lệ khung hình 16:9 tối ưu, không có ô lẻ hoặc toạ độ âm.

### 3.2. Mở rộng Engine Video Go/Pion
- `VideoServiceProcess.java` truyền tham số sức chứa qua biến môi trường khi khởi chạy tiến trình Go con:
  - `MINIMEETING_MAX_PEERS`: Số người tham gia tối đa (20..50).
  - `MMR_MAX_PEERS`: Tương thích dự phòng.
- `media/video/engine.go`:
  - `maxPeers`: Tính toán động `max(512, maxParticipants * maxParticipants * 2)`. Với 20 người tham gia, sức chứa đạt ít nhất 800 peer connections; với 50 người, đạt tới 5000 peer connections.
  - `maxLeases`: Mở rộng từ 128 lên `max(128, maxParticipants * 4)`.
  - `slots`: Mở rộng kênh HTTP control concurrency từ 16 lên `max(64, maxParticipants * 2)`.
  - `portRange`: Hỗ trợ dải cổng UDP thông qua `MMR_MEDIA_PORT_MIN` và `MMR_MEDIA_PORT_MAX`.
