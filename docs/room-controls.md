# Cập nhật điều khiển phòng — 17/10/2026

## Ghép code chat và thống kê mạng

Bản ghép giữ tab Chờ duyệt, thời hạn 5 phút, Cài đặt chung, lựa chọn host kế nhiệm/đóng phòng, icon mắt và snapshot giao diện nhất quán. Đồng thời nhận chat qua signaling, thống kê mạng WebRTC/TLS trên thanh trên và giới hạn phòng theo cấu hình mới (mặc định 20, tối đa 50) từ đồng đội. Không đưa lại cửa sổ quản lý phòng riêng hoặc các dòng trạng thái kỹ thuật dư.

Theo thay đổi được duyệt ngày 21/10/2026, dọn chat và tệp ngay sau khi phòng đóng; chỉ mở lại khi dọn xong. Xem [báo cáo triển khai](runtime-performance-cleanup.md). Luồng host chọn kế nhiệm và rời phòng cũng dọn chat của client khi rời thành công; chuyển quyền bị từ chối không làm mất chat.

Kiểm thử bản ghép: `mvn test` chạy 370 tests, 0 failures/errors, 2 skipped (`target/merge-regression.txt`); bộ native video/audio/screen và phòng thống nhất chạy 18/18 PASS (`target/merge-media-integration.txt`); `go test ./...` PASS (`target/merge-go-tests.txt`). SFU dùng binary thử `target/mmr-merge-video.exe`, không thay binary của server đang chạy. Test mới xác nhận chat signaling hoạt động trước khi chuyển host, chỉ dọn chat sau khi rời thành công.

## Sảnh tự cập nhật

Danh sách phòng gần đây tự đọc lại khoảng 2 giây/lần khi đang ở sảnh. Đây là polling tự động, không phải push trên signaling. Chỉ giữ một yêu cầu danh sách đang chạy; kết quả không đổi không dựng lại các hàng. Không xóa mã phòng/PIN đang nhập. Rời sảnh dừng timer. Nút Làm mới vẫn dùng được; cache hiển thị ngay khi quay lại sảnh rồi được cập nhật trong chu kỳ kế tiếp.

## Cài đặt và panel chờ

Phòng WebRTC dùng một hộp Cài đặt chung cho thiết bị, theme và quản lý phòng. Host thấy Khóa phòng/Duyệt thành viên; thành viên thường không thấy phần quản lý. Trong khi gửi thay đổi, hai lựa chọn được khóa tới phản hồi; lỗi trả lựa chọn về trạng thái server đã xác nhận. Không còn nút Quản lý phòng riêng ở dưới cuộc họp, không có nút Đóng phòng trong Cài đặt.

Panel bên phải có bốn tab: Thành viên, Chat, Tệp, Chờ duyệt. Tab Chờ duyệt chỉ bật cho host khi chế độ duyệt thành viên được bật, có số lượng yêu cầu trên nhãn tab. Khi tắt duyệt hoặc mất quyền host, tab bị vô hiệu hóa; nếu đang chọn tab này thì trở về Thành viên. Danh sách không còn chiếm một khối riêng bên ngoài panel. Bật khóa phòng chặn yêu cầu mới; yêu cầu đã chờ vẫn được liệt kê, nhưng phải mở khóa trước khi chấp nhận. Từ chối vẫn dùng được khi đang khóa. Giới hạn danh sách chờ hiện có là 20 người/phòng.

## Sửa duyệt người vào

Mã cũ gọi JOIN_ROOM mỗi giây lúc chờ, làm hết ngân sách kiểm tra PIN (5 lần/phút) và kết thúc bằng PIN_RATE_LIMIT. Mã mới kiểm tra PIN ở lần JOIN đầu; ADMISSION_STATUS kiểm tra trạng thái yêu cầu đã tồn tại, không gửi lại PIN và không tạo yêu cầu mới. Yêu cầu bị ràng buộc với tài khoản, phiên, connection ID, generation và phòng ban đầu. Người chờ không có membership/quyền chat, tệp hay media trước khi được duyệt.

Thời hạn cố định là 5 phút từ lúc tạo yêu cầu, không gia hạn bởi polling. Khi hết hạn, danh sách host bỏ yêu cầu và client nhận ADMISSION_EXPIRED; muốn tiếp tục phải chủ động gửi lại. Rời kết nối/hủy yêu cầu dọn yêu cầu như trước. Approve/reject và tắt chế độ duyệt được xử lý theo quyền host, sức chứa và khóa phòng hiện tại. Retry ADMISSION_STATUS sau khi đã vào phòng trả lại membership hiện có, không thêm bản ghi trùng.

Quyết định đã đưa ra trước mốc 5 phút được giữ nội bộ thêm tối đa 30 giây để client nhận kết quả, không còn xuất hiện trong danh sách chờ của host. Điều này tránh biến một lần chấp nhận ở giây 299 thành lỗi hết hạn khi client kiểm tra ở giây 301.

Khi kiểm tra lỗi yêu cầu biến mất sớm ở lần cập nhật tiếp theo, tiến trình server vẫn khởi chạy từ 12:02, trước thời điểm thêm ADMISSION_STATUS (12:17) và build class mới (12:36). Build trong lúc server đang mở không thay mã đã nạp trong JVM. Server cũ trả UNKNOWN_OPERATION khi client mới hỏi trạng thái chờ; client trước đây hiển thị lỗi kết nối chung rồi đóng kết nối, khiến hàng chờ bị dọn. Client hiện chuyển riêng lỗi này thành SERVER_UPDATE_REQUIRED, hướng dẫn cập nhật/khởi động lại server. Cần khởi động lại cả server và client sau khi build; sửa thời hạn trong mã nguồn không cập nhật được một tiến trình cũ đang chạy.

## Host rời phòng

Khi bấm Rời phòng (hoặc quay lại sảnh từ phòng chờ khi đang là host), hộp thoại cho chọn người kế nhiệm hoặc đóng phòng cho mọi người. Không mặc định chọn người vào sớm nhất. Hủy giữ nguyên cuộc họp và media. Server kiểm tra người kế nhiệm còn trong phòng và có phiên hợp lệ, chuyển quyền trong DB trước khi xóa host cũ khỏi membership sống. Đích không còn hợp lệ sẽ báo lỗi và giữ host ở lại để chọn lại. Đóng phòng dọn cả thành viên và danh sách chờ.

Luồng UI gửi quyết định rời rõ ràng cho cả thành viên thường; nếu người dùng vừa trở thành host mà giao diện chưa kịp nhận sự kiện, server yêu cầu chọn lại thay vì tự chuyển cho người đầu danh sách. Hộp thoại tự đóng khi phòng kết thúc hoặc người dùng mất vai trò host trong lúc đang chọn.

Cơ chế dự phòng khi mất kết nối/đóng tiến trình đột ngột và tương thích client cũ vẫn như trước; hộp chọn áp dụng cho thao tác rời chủ động qua giao diện. Không thay giao thức chat/tệp hoặc triển khai lại media.

## Ẩn/hiện thanh công cụ

Icon mắt gạch nằm cùng thanh điều khiển. Khi ẩn, toàn bộ footer ngừng chiếm chiều cao; icon mắt ở góc trái trên vùng họp cho phép hiện lại. Không dừng capture, subscription, chat hoặc truyền tệp.

Giao diện họp bỏ dòng giới thiệu dưới thanh công cụ và trạng thái kỹ thuật WebRTC/SFU. Dòng lỗi chỉ chiếm diện tích khi có nội dung. Cài đặt bỏ mô tả dài về camera vật lý/ảo; form tham gia bỏ hai dòng hướng dẫn lặp lại mã phòng/PIN. Thông báo quyền, lỗi thiết bị và lỗi thao tác vẫn được giữ.

Kiểm thử phát hiện thêm lỗi dựng video lúc người vừa được duyệt rời phòng: số ô lấy từ danh sách trên FX thread, còn dữ liệu ô lại đọc snapshot mới trên worker, có thể gây `ArrayIndexOutOfBoundsException`. Màn hình hiện giữ một snapshot riêng trên FX thread cho cả danh sách, ô video và phần chia sẻ. Test cố tình giữ hàng đợi FX, để worker nhận sự kiện rời trước rồi nhấn ghim; giao diện phải dựng được trạng thái đang hiển thị và cập nhật sau khi FX tiếp tục. Không đổi giao thức hay engine media.

## Database và kiểm thử

Không có bảng/cột/migration mới. Trạng thái duyệt và yêu cầu chờ vẫn ở bộ nhớ server; quyền host và lịch sử membership dùng các bảng hiện có. Không ghi dữ liệu thử lên Railway.

Mốc trước sửa: 324 tests, 0 failures/errors, 2 skipped (`target/room-controls-baseline.txt`). Các bài mới kiểm tra TTL 299/300 giây bằng clock giả, khóa kết hợp duyệt, đúng phiên/kết nối, chờ vượt ngân sách PIN, host chọn người vào sau, đích chuyển quyền không hợp lệ, đóng cả phòng, quyền tệp/chat sau chuyển chủ, sảnh tự thấy phòng mở lại mà không mất nội dung nhập, dialog Cài đặt và icon mắt. Native integration dùng nguồn được tạo bằng mã, không bật thiết bị thật.

Chạy `mvn test`; native dùng profile `video-integration` và binary Go tương ứng. Bằng chứng/log kiểm thử nằm trong target, có thể mất khi Clean and Build.

Kết quả chạy: bộ hồi quy 335 tests không failure/error, 2 skip theo cấu hình (`target/room-controls-regression.txt`). Sau kiểm tra biên cuối, bộ 16 bài UI/phòng/signaling/native chính đều PASS (`target/room-controls-final-check.txt`), bao gồm quyết định ở giây 299 được nhận ở giây 301. Ba bài native audio và hai bài native screenshare PASS trong các báo cáo Surefire tương ứng. Nguồn video/âm thanh/màn hình trong test được tạo bằng mã, không phải nghiệm thu camera/microphone/màn hình thật hay tải nhiều máy LAN.

Ảnh kiểm tra giao diện: `target/room-settings-preview.png`, `target/room-admission-preview.png`, `target/room-toolbar-hidden-preview.png`, `target/room-host-leave-preview.png`. Native được build vào `target/mmr-room-controls-video.exe` cho bài thử, không thay binary của server đang chạy. Khi cập nhật ứng dụng, Clean and Build rồi khởi động lại cả server và client để hai phía cùng hỗ trợ ADMISSION_STATUS và quyết định rời phòng.

### Kiểm tra lại tab duyệt và thời hạn

Sau khi dừng các tiến trình server/client cũ, Clean and Build, chạy `src/main/java/server/Main.java`, chờ `MMR_MEETING_SERVER_READY` rồi mở hai client bằng hai tài khoản. Host bật duyệt trong Cài đặt, mở tab Chờ duyệt; người còn lại gửi yêu cầu tại sảnh. Giữ client của người chờ kết nối và không bấm Hủy, chấp nhận hoặc từ chối. Yêu cầu phải còn trước mốc 5 phút; đến hạn bị xóa và người chờ nhận thông báo hết hạn. Gửi lại yêu cầu phải được, rồi thử chấp nhận/từ chối. Khi tắt duyệt, tab Chờ duyệt bị khóa và chuyển về Thành viên nếu đang chọn.

Bài `UnifiedRoomCompatibilityTest.pendingAdmissionRemainsUntilFiveMinutesThenExpiresAndAllowsANewRequest` dùng TLS/H2 tạm, kiểm tra các mốc 4/30/60/120/240/299/300 giây, hết hạn và gửi lại. Mặc định dùng clock điều khiển để hồi quy nhanh; thêm `-Dmmr.admission.test.realWait=true` để chờ đủ 5 phút thực tế. Bài mô phỏng server cũ xác nhận thông báo yêu cầu cập nhật/khởi động lại thay cho lỗi kết nối chung. Test giao diện/native xác nhận tab host/guest, bật/tắt tab, số lượng chờ, chấp nhận bằng nút thật và video/chat/tệp vẫn hoạt động.

Lần chạy chờ thực tế ngày 17/10: cả 8 bài `UnifiedRoomCompatibilityTest` PASS; riêng bài chờ hết hạn và gửi lại chạy 302,761 giây. Kết quả nằm trong `target/admission-tab-regression.txt` và XML Surefire tương ứng.

Bộ rộng đầu tiên chạy 349 tests, 1 failure, 3 skipped: bài tắt mic kiểm tra camera trước khung RTP đầu tiên. Bài test hiện chờ video được nhận trước thao tác và xác nhận khung hình tiếp tục sau tắt mic. Một lượt native sau đó có timeout audio/screenshare độc lập; lượt chạy lại ba bài audio đều PASS (22,16 giây), không sửa engine audio/screenshare. Bốn bài điều khiển phòng và chín bài luồng UI đều PASS trong `target/admission-tab-verified.txt`. Sau khi sửa lỗi snapshot giao diện và bổ sung applyCss/layout cho kiểm tra ScrollPane, bài native UI cuối PASS, không có lỗi FX chưa xử lý (`target/admission-tab-ui-final.txt`, BUILD SUCCESS). Các kết quả này thuộc nhiều lượt chạy; không phải tuyên bố toàn bộ bộ test rộng đã xanh trong một lượt cuối.
