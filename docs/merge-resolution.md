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

## Ghép thay đổi cục bộ của Khoa — 16/10/2026

Thư mục xử lý là `D:/MiniMeetingRoom`, nhánh `Khoa`. Đã cập nhật fast-forward từ `057bf0e` lên `origin/main` tại `33a7df0`, sau đó ghép lại các thay đổi chưa commit của Khoa. Bản sao lưu ban đầu vẫn ở Git stash có tên `codex-conflict-backup-2026-10-16-before-main-integration`, commit `f802a097ad3b12e74d9d6254d5c7d57e08f0dc2e`.

Chủ dự án xác nhận: **Rời phòng tự chuyển quyền; Kết thúc/Đóng phòng đóng cho tất cả**. Phòng cũng đóng khi người cuối cùng rời. Quyền mở lại thuộc chủ phòng hiện tại sau chuyển quyền.

Giữ chat realtime, lịch sử chat, duyệt/hủy/từ chối yêu cầu, quản trị, thống kê mạng, video và truyền tệp từ main. Ghép thêm mở lại phòng với cùng mã/PIN/lịch sử, từ chối tham gia bằng ID và không hiển thị ID trên giao diện; ngày giờ phòng, tệp và chat dùng `Asia/Ho_Chi_Minh`. Mỗi lượt mở lại dùng định danh signaling và vé thành viên mới, tránh yêu cầu hoặc sự kiện dọn dẹp cũ ảnh hưởng lượt họp mới.

Màn hình sau khi rời phòng lấy lại trạng thái và chủ phòng từ server: phòng còn hoạt động có nút **Vào lại phòng**; chủ hiện tại của phòng đã đóng có nút **Mở lại phòng**. Tham gia lại vẫn xác minh mã/PIN. Chỉ sửa kỳ vọng kiểm thử cũ về việc host rời là đóng phòng sang thao tác kết thúc rõ ràng; vẫn giữ kiểm thử tự chuyển quyền của đồng đội.

Các thay đổi ghép để ở working tree để chủ dự án xem lại; chưa tạo commit hoặc push. Kiểm thử dùng H2/TLS cục bộ và hình video tạo sẵn, không sửa Railway hoặc cấu hình riêng của người dùng.

Kết quả tổng hợp báo cáo JUnit mới nhất theo từng lớp: **290 ca, 288 đạt, 0 thất bại, 0 lỗi, 2 bỏ qua có điều kiện**. Lượt toàn bộ thường có 285 ca; một kiểm thử mở lại còn gửi lệnh rời sau khi server đã kết thúc phòng, đã sửa thành chờ thông báo kết thúc rồi chạy lại cả 5 ca `MeetingSessionReopenTest`, tất cả đạt. Cả 5 ca `VideoCallIntegrationTest` đạt, bao gồm chat/tệp cùng phòng, vào lại khi phòng còn mở và mở lại từ màn hình kết thúc. Cả 4 ca `UnifiedRoomCompatibilityTest` đạt, bao gồm chủ mới mở lại, giữ lịch sử chat và thu hồi vé cũ.

Log lượt toàn bộ: `target/merge-final-regression.txt`; log kiểm tra lại 5 ca mở lại và 5 ca native cuối cùng (BUILD SUCCESS): `target/merge-native-final.txt`. Tổng hợp trên lấy kết quả mới nhất của từng lớp, không phải tổng số lượt chạy lặp. Hai ca bỏ qua vẫn là benchmark tệp và symbolic link Windows như phần xác minh trước. Thử nghiệm nhiều máy LAN và webcam thật chưa chạy.

Trên máy Windows này, Maven Surefire dùng `-DargLine="-Djava.io.tmpdir=D:\MiniMeetingRoom\target\reopen-tmp -Djdk.net.unixdomain.tmpdir=D:\MiniMeetingRoom\target\reopen-tmp"` để tránh đường dẫn TEMP có tên rút gọn. Lượt native đặt TEMP/TMP của tiến trình kiểm thử về cùng thư mục để JVM nhận video riêng dùng được đường dẫn chuẩn. Đây là tham số kiểm thử cục bộ, không sửa cấu hình chạy server/client.
