# Khôi phục kết nối phòng

## Hành vi

- Mạng yếu nhưng signaling còn hoạt động: dùng chính sách [chất lượng video tự động](adaptive-video.md).
- Signaling đứt: server giữ chỗ, cùng bản ghi `Thanh_Vien_Phong`, cùng participant và vai trò chủ phòng trong 120 giây. Không ghi `Roi_Phong_Luc`, không chuyển chủ ngay.
- Client hiện thông báo đang kết nối lại, thử lại cách nhau 2 giây sau mỗi lần thất bại. Đồng hồ 120 giây độc lập với hàng đợi request, không kéo dài khi thử nhiều lần hoặc khi socket đang bị treo.
- Kết nối lại thành công: xác thực token, gửi `RESUME_ROOM` với room/participant cũ. Server chỉ chấp nhận đúng phiên đăng nhập, không chạy JOIN mới, không yêu cầu nhập PIN hay duyệt lại. Khóa phòng không chặn thành viên đã được nhận khôi phục kết nối.
- Hết hạn: client xóa phòng hiện tại, dừng media và chuyển sang màn hình kết thúc. Server ghi rời phòng; tác vụ dọn phòng chạy mỗi 5 giây nên ghi DB có thể trễ tối đa một nhịp. Nếu còn người, áp dụng chuyển chủ dự phòng; nếu không còn ai, đóng phòng.
- Kick, thu hồi phiên, phòng đóng và server shutdown vẫn kết thúc quyền tham gia. Khôi phục không thể vượt qua các trường hợp này. Mỗi transport mới phải có generation mới hơn; callback/socket cũ không được làm mất thành viên vừa khôi phục.

120 giây bắt đầu khi mỗi phía phát hiện mất kết nối; việc phát hiện phụ thuộc heartbeat và socket timeout (hiện khoảng 20 giây khi không nhận được phản hồi). Không thể phát hiện chính xác thời điểm rút mạng ngay lập tức.

## Media, tệp và lịch sử

Giữ nguyên đối tượng media mà màn hình đang dùng, nhưng đóng capture/publication/subscription cũ. Không tự mở lại camera, mic hoặc chia sẻ màn hình; người dùng bật lại sau khôi phục. Khi kết nối mới sẵn sàng, tự nhận lại media của người khác và đo lại chất lượng trên đường truyền mới, tránh kẹt ở chế độ chỉ thoại do số liệu cũ.

Tab Tệp tạm ngừng và hủy các tác vụ đang chạy khi đứt signaling, sau khôi phục tạo lại panel để lấy danh sách và gửi/tải tệp với membership cũ. Tệp đang truyền dở không tự gửi lại; người dùng chủ động thử lại. Chính sách lưu/xóa chat không thay đổi.

Không thêm thực thể, cột, migration hoặc truy vấn DB định kỳ để reconnect. Membership giữ trong RoomService đang chạy; server restart vẫn đối soát phòng cũ như trước. Cần build và khởi động lại server cùng client để có thao tác `RESUME_ROOM` mới. Nếu server cũ không hỗ trợ, client kết thúc phiên thay vì giả vờ đã khôi phục.

## Kiểm thử

- `RoomServiceTest`: thời hạn 119/120 giây bằng đồng hồ điều khiển, không gia hạn bởi disconnect lặp; không ghi leave/không tạo thêm membership khi khôi phục; giữ host; callback/generation cũ; không vượt kick, phiên khác, khóa hoặc hết hạn.
- `MeetingSessionReopenTest`: socket TLS thật, H2 tạm, nhiều lần tạo transport thất bại rồi khôi phục đúng membership/quyền tệp; deadline vẫn đưa JavaFX sang màn hình kết thúc khi tác vụ kết nối bị chặn. Riêng bài UI dùng thời hạn rút ngắn 5 giây để không phải chờ 2 phút.
- Native `VideoCallIntegrationTest.transportRecoveryKeepsMembershipAndBorrowedMediaOwnerWithoutReopeningCapture`: nguồn camera/âm thanh/chia sẻ tạo bằng mã, TLS + Go SFU + RTP thật; mất control khi đang ưu tiên thoại, khôi phục cùng host/membership/quyền tệp, nhận video trở lại, không tự bật capture và có thể bật lại video/thoại bằng tay.

Không thao tác Railway, không mở camera/mic/màn hình thật. Chưa thử rút mạng thực tế trên hai máy LAN.

Kết quả ngày 17/10/2026:

- Toàn bộ profile `video-integration`: 397 bài, 0 failures/errors, 3 skipped; `target/reconnect-regression.txt`.
- Sau hoàn thiện đồng bộ timeout/cleanup và bổ sung kiểm tra tab Tệp phục hồi: 32/32 bài qua, 0 failures/errors/skipped; `target/reconnect-final-validation.txt`.
- Native xác nhận giữ nguyên đối tượng media và membership sau mất kết nối, thoát chế độ ưu tiên thoại cũ để nhận video trở lại, chỉ phát camera/voice sau thao tác bật lại bằng tay. JavaFX xác nhận tab Tệp được tạo lại, nút camera hoạt động trở lại, và lần mất mạng tiếp theo vẫn tự chuyển màn hình kết thúc dù tạo kết nối đang bị chặn.
