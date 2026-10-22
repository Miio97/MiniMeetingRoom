# Tài liệu Kỹ thuật: Chat Realtime, Thống kê Kết nối và Mở rộng Sức chứa Phòng

Tài liệu này ghi lại kiến trúc, các quyết định kỹ thuật và cấu hình cho 3 tính năng mới:
1. Chat realtime qua kênh signaling và dọn nội dung ngay sau khi đóng phòng.
2. Thống kê media RAM-only và AUTO video trên client; bảng thống kê cũ được xóa bằng migration 004.
3. Tăng giới hạn số lượng người tham gia phòng họp lên 20 người (trần cứng 50).

---

## 1. Chat Realtime và Tự hủy Vĩnh viễn (Task 1)

### 1.1. Luồng truyền tin Realtime
- **Giao thức**: Sử dụng kênh signaling TCP/TLS hiện có, không đi qua SFU media server.
- **Message Types**:
  - `CHAT_SEND`: Gửi tin nhắn mới. Kiểm tra thành viên phòng (`rooms.assertMember()`). Nội dung giới hạn 1–2000 ký tự sau khi strip, chỉ chứa văn bản thuần (plain text), loại bỏ ký tự điều khiển.
  - `CHAT_HISTORY`: Tải lịch sử tin nhắn gần nhất (`limit <= 100`). Chỉ thành viên đang có mặt trong phòng mới được phép gọi. Đọc RAM theo opaque room ID, hỗ trợ `beforeId`, trả ID tăng dần. Không đọc MySQL hoặc tin nhắn loại `TEP`.
  - `CHAT_MESSAGE`: Sự kiện phát tán (broadcast) từ server tới toàn bộ thành viên trong phòng (bao gồm cả người gửi).
- **Tránh đói sự kiện phòng (Room Starvation)**:
  - `RoomService.event()` mặc định gắn kèm snapshot đầy đủ và đi qua hàng đợi outbound có `eventBudget` (383).
  - Để chat realtime không làm đói các sự kiện vòng đời quan trọng (như `ROOM_CLOSED`, `PARTICIPANT_JOINED`), chat sử dụng đường phát tán riêng: lấy danh sách `connectionId` của phòng từ `RoomService` và phát trực tiếp qua `SignalingServer.event()` với `revision = null`.
  - Phía client (`MeetingSession.event()`), sự kiện `CHAT_MESSAGE` được chặn và xử lý trước khi kiểm tra snapshot revision, không làm tăng `room.revision` và không kích hoạt `GET_ROOM` thừa.
- **Executor & Rate Limiting**:
  - `CHAT_SEND` / `CHAT_HISTORY` / `CHAT_STATUS` chỉ thao tác RAM; không mượn JDBC. Kiểm tra phiên tại kết nối và membership RAM; xác thực DB định kỳ/revoke và giới hạn database grace vẫn hoạt động.
  - `RoomChatStore` giữ tối đa 500 tin gần nhất/phòng, ID tăng đơn điệu, khóa buffer theo phòng và kiểm tra quyền nguyên tử với vòng đời phòng. Không còn executor/stripe ghi DB cho chat signaling.
  - Nonce gắn session + opaque room; tối đa 1024 receipt, TTL 10 phút; ACK/push và optimistic UI giữ nguyên JSON.
  - Rate-limit: tối đa 5 tin/giây, burst 10 tin/kết nối.

### 1.2. Dọn nội dung khi đóng phòng

Buffer chat và receipt của phiên phòng bị xóa ngay khi phòng rời authority RAM (đóng, hết người hoặc dừng server), kể cả khi DB đang gián đoạn. Mở lại sinh opaque ID mới và lịch sử trống. Restart làm mất chat RAM; reconnect cùng phiên còn hoạt động giữ lịch sử trong giới hạn 500 tin.

Bảng `Tin_Nhan` và schema được giữ nguyên để bản ghi `TEP` phục vụ khóa ngoại của `Tep_Dinh_Kem`. Kênh chat legacy `ChatService` vẫn dùng MySQL và giữ hợp đồng kiểm thử cũ; thay đổi RAM-only áp dụng cho chat signaling của phòng video.
`ImmediateRoomCleanup` thay thế lịch chờ 20 phút. Khi phòng đã đóng thực sự, server dọn upload/tệp vật lý/metadata trước, sau đó xóa tin nhắn theo lô 500 dòng. Cả hai đường mở lại phòng dùng `RoomReopenGuard`; chỉ mở lại khi không còn dữ liệu hoặc upload chờ dọn. Dọn lỗi giữ khóa nghiệp vụ mở lại và thử lại với backoff. Lịch sử phòng/tham gia được giữ.

`ChatRetentionService` cũ còn lại để tương thích kiểm thử cũ, không được khởi chạy bởi entry point ứng dụng. Các khóa retention cũ không điều khiển chính sách dọn phòng mới. Chi tiết: [runtime-performance-cleanup.md](runtime-performance-cleanup.md).

---

## 2. Thống kê media sau migration

Client lấy getStats cục bộ mỗi 500 ms, buffer 40 mẫu trong RAM, phân tích và cập nhật UI khoảng 1 giây. Không gửi NETWORK_STATS định kỳ, không ghi/đọc bảng thống kê để chỉnh video. Server bỏ writer/retention và chỉ ACK-bỏ-qua telemetry của client cũ. Chat dùng chính sách dọn ngay khi đóng phòng như mục 1.2.

Theo xác nhận ngày 18/10/2026, xóa bảng Thong_Ke_Ket_Noi cùng lịch sử bằng migration 004 chạy thủ công trên Railway sau khi cập nhật server. Chỉ AUTO tối đa 720p/30 FPS, không có MANUAL hay 45/60 FPS. Chi tiết và hướng dẫn backup/rollback: [client-stats-migration.md](client-stats-migration.md).


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
