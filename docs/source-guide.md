# Hướng dẫn đọc source

## Thứ tự trình bày

1. `client.Main` → `client.UI.MainApp` → `SceneManager`: khởi động/điều hướng.
2. `client.UI.screens`: các màn hình đăng nhập, sảnh, chờ, họp, lịch sử và cài đặt.
3. `client.meeting.MeetingSession`: session signaling, reconnect và push.
4. `server.Main` → `MeetingServerMain`: khởi động các dịch vụ và shutdown.
5. `server.auth`, `server.signalingapi`: xác thực, giao thức và phân phối yêu cầu.
6. `server.meeting`: trạng thái phòng, repository, procedure, admission và cleanup.
7. `client.media`, `server.media`, `media/video`: thu/phát, quyền SFU và RTP.
8. `client.filetransfer`, `server.filetransfer`: truyền tệp, storage và metadata.
9. `common`: DTO, wire protocol và validation.

## Model giao diện và tương thích

`client.UI.model.MeetingUiModels` chứa dữ liệu trình bày; không thay server DTO
làm nguồn quyết định quyền. `client.UI.util.DisplayFormat` chứa định dạng thời
lượng và avatar. Không còn `MockData` hoặc phòng/tin nhắn/tệp mẫu trong source
production. Trạng thái chưa vào phòng dùng một model rỗng có tên trung tính.

`LegacyDeviceOptions` chỉ phục vụ nhánh thiết bị mô phỏng của phòng cũ, với
nhãn mô phỏng rõ ràng; camera/mic thật dùng `CameraControls`/`AudioControls`.
`LegacyLobbyScreen`, `LegacyMeetingScreen`, `LegacyCreateRoomDialog` và các
backend tương thích được giữ có chủ đích. Chưa loại bỏ đường tương thích
trong đợt làm sạch này để tránh thay đổi nghiệp vụ ngoài ý muốn.

`CameraFormats.main()` là helper đang được gọi để cô lập việc đọc driver camera,
không phải chương trình thử. `WindowsSystemAudioInput` cần resource C#
`media/SystemAudioLoopback.cs`; không xóa chỉ vì nó không phải Java.

## Quy ước

Java dùng Google Java Format 1.22.0, chế độ AOSP bốn dấu cách; formatter chỉ là
công cụ dọn mã bên ngoài runtime, không thêm dependency vào pom. Go dùng gofmt.
Giữ comment giải thích đồng thời, quyền và vòng đời; bỏ header IDE tự sinh.
Không đổi package hàng loạt hoặc tách module trong giai đoạn bàn giao.

Test, probe, benchmark, công cụ database và báo cáo phát triển được lưu tại
archive ngoài project, có snapshot, SHA256 và đường dẫn khôi phục. Git history,
private config, storage và các runtime tools hiện hoạt động được giữ nguyên.

Bộ thu thập diagnostics phục vụ phát triển đã chuyển ra archive. Client,
server Java và SFU không còn ghi file thống kê CPU/heap/thread hoặc thời gian
xử lý để làm báo cáo. Thống kê kết nối trong cuộc họp và video AUTO vẫn dùng
số đo WebRTC tại client; đây là chức năng ứng dụng và không ghi telemetry định kỳ.
