# Kết quả ghép Duc và main — 15/10/2026

## Phạm vi và quyết định

Repository đang được xử lý: thư mục dự án bên trong `MiniMeetingRoom/MiniMeetingRoom`, nhánh `Duc` tại `94ac1a4`, ghép `origin/main` tại `057bf0e`. Đã đối chiếu remote main trùng với commit này. Repository bao ngoài không được sửa.

Theo quyết định của chủ dự án:

- Chat, duyệt thành viên, quản trị, video và truyền tệp dùng cùng một phòng.
- Chủ phòng rời đi sẽ tự chuyển quyền cho thành viên còn lại đầu tiên theo thứ tự tham gia. Đóng phòng bằng lệnh riêng hoặc khi hết người.

## Cách hoạt động sau khi ghép

`server.Main`/`MeetingServerMain` chia sẻ một `server.meeting.RoomService` cho signaling và auth/chat TLS. `RoomRequest` và `ChatRequest` xác minh token cùng participant ID của kết nối signaling, thay vì tạo vé và membership thứ hai. Dữ liệu tệp tiếp tục kiểm tra cùng ID phòng và membership trong database.

Người chờ duyệt chưa được ghi nhận là thành viên: không được chat, dùng tệp hoặc phát/nhận video. Client thử lại yêu cầu tham gia mỗi giây trong lúc chờ; hủy yêu cầu sẽ đóng kết nối chờ. Host có thể duyệt/từ chối, khóa phòng, chuyển chủ, tắt thiết bị, mời ra hoặc đóng phòng. Quyền chủ mới được ghi vào database và cập nhật qua snapshot; camera đang chạy của người kế nhiệm được giữ lại.

Các lớp `Legacy*` giữ tương thích với giao thức và kiểm thử cũ. Giao diện khởi chạy bình thường vẫn có một luồng phòng thống nhất. Không thêm lựa chọn hai loại phòng cho người dùng.

Sức chứa mặc định của form là 10; chính sách phòng video mặc định cho phép tối đa 16 thành viên, giữ phạm vi quản lý phòng của nhánh Duc. Đây không phải tuyên bố năng lực media đã đo ở 16 máy. Cấu hình server hiện có tiếp tục được ưu tiên.

PIN chỉ được server lưu dưới dạng hash. PIN đã nhập được giữ trong bộ nhớ phiên client để chủ phòng chủ động sao chép lời mời, không ghi file/log và được xóa khi rời phòng. Microphone và chia sẻ màn hình chưa có media thật, như giới hạn sẵn có của main.

Thống kê mạng dùng số đo thật của kênh auth/chat TLS; không lấy byte hoặc thời gian của kênh này làm thống kê RTP/video. Theme áp dụng cho cửa sổ họp và các dialog, đồng thời dọn lớp màu trùng khi đổi sáng/tối.

## Xác minh

Kết quả tổng hợp cuối: **247 kiểm thử, 245 đạt, 0 thất bại, 0 lỗi, 2 bỏ qua có điều kiện**. Cả **5 kiểm thử video native** và **3 kiểm thử phòng thống nhất** đạt. Các báo cáo JUnit nằm trong `target/surefire-reports`; log các lượt chạy nằm trong `target/merge-*.log`.

Các bộ kiểm thử chạy bằng TLS localhost và H2 tạm; không sửa database Railway. Video dùng Java WebRTC, Go/Pion và nguồn hình tạo sẵn; không bật webcam thật.

- Bộ kiểm thử Java thông thường: `mvn test`.
- Bộ Go video: `scripts/build-video-engine.ps1` (kiểm thử và build).
- Video native và hồi quy ghép phòng: profile `video-integration`, gồm `VideoCallIntegrationTest`, `UnifiedRoomCompatibilityTest` và các kiểm thử giao diện/quản lý phòng.

Có hai kiểm thử điều kiện không chạy mặc định: benchmark truyền tệp cần `-Dmmr.performance=true`, và kiểm thử symbolic link cần quyền tạo liên kết của Windows. Camera/thiết bị thật và LAN nhiều máy cần nghiệm thu riêng.

Bản ghép không thêm migration database. Các migration runtime/tệp có sẵn từ main vẫn phải được áp dụng theo hướng dẫn của chúng nếu database chưa có bảng/cột tương ứng; server không tự áp dụng.
