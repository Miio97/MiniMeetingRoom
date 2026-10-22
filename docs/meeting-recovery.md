# Khôi phục kết nối phòng

## Hành vi

- Mạng yếu nhưng signaling còn hoạt động: dùng chính sách [chất lượng video tự động](adaptive-video.md).
- Signaling đứt: server giữ chỗ, cùng bản ghi `Thanh_Vien_Phong`, cùng participant và vai trò chủ phòng trong 120 giây. Không ghi `Roi_Phong_Luc`, không chuyển chủ ngay.
- Client hiện thông báo đang kết nối lại, thử lại cách nhau 2 giây sau mỗi lần thất bại. Đồng hồ 120 giây độc lập với hàng đợi request, không kéo dài khi thử nhiều lần hoặc khi socket đang bị treo.
- Kết nối lại thành công: xác thực token, gửi `RESUME_ROOM` với room/participant cũ. Server chỉ chấp nhận đúng phiên đăng nhập, không chạy JOIN mới, không yêu cầu nhập PIN hay duyệt lại. Khóa phòng không chặn thành viên đã được nhận khôi phục kết nối.
- Hết hạn: client xóa phòng hiện tại, dừng media và chuyển sang màn hình kết thúc. Server ghi rời phòng; tác vụ dọn phòng chạy mỗi 5 giây nên ghi DB có thể trễ tối đa một nhịp. Nếu còn người, áp dụng chuyển chủ dự phòng; nếu không còn ai, đóng phòng.
- Kick, thu hồi phiên, phòng đóng và server shutdown vẫn kết thúc quyền tham gia. Khôi phục không thể vượt qua các trường hợp này. Mỗi transport mới phải có generation mới hơn; callback/socket cũ không được làm mất thành viên vừa khôi phục.

120 giây bắt đầu khi mỗi phía phát hiện mất kết nối; việc phát hiện phụ thuộc heartbeat và socket timeout (hiện khoảng 20 giây khi không nhận được phản hồi). Không thể phát hiện chính xác thời điểm rút mạng ngay lập tức.

## Đóng ứng dụng và chuyển thiết bị

- Bấm nút đóng cửa sổ hoặc Alt+F4: client gửi `LEAVE_ROOM`, chờ xác nhận rồi thu hồi phiên đăng nhập và đóng ứng dụng. Nếu là chủ phòng, server tự chọn một thành viên còn lại để chuyển quyền; người cuối cùng rời đi thì phòng đóng theo chính sách hiện có. Không mở hộp thoại chọn người kế nhiệm khi đóng ứng dụng.
- Nếu đang chờ duyệt, client hủy yêu cầu chờ. Nếu đang khôi phục kết nối, client thử khôi phục đúng membership để gửi lệnh rời phòng. Chờ rời phòng tối đa 10 giây và toàn bộ quá trình đóng tối đa 15 giây khi server không phản hồi.
- Đăng nhập cùng tài khoản ở thiết bị mới rồi nhập mã phòng/PIN để vào **cùng phòng**: khi tham gia thành công, thiết bị mới thay thế thiết bị cũ, kể cả phòng đầy hoặc khóa. Giữ nguyên membership và quyền chủ phòng; phòng của chủ phòng duy nhất vẫn hoạt động.
- Chỉ đăng nhập hoặc nhập sai PIN chưa đẩy thiết bị đang họp ra. Thiết bị cũ nhận thông báo đã chuyển thiết bị, dừng media và rời màn hình họp; tài khoản vẫn đăng nhập để dùng các màn hình khác. Phiên cũ không tự kết nối lại hoặc giành lại phòng; muốn chuyển về cần đăng nhập mới và vào phòng lại.
- Trên thiết bị mới, camera/microphone/chia sẻ màn hình không tự phát lại. Quyền chat, điều khiển phòng và truyền tệp của thiết bị cũ bị chặn sau khi chuyển.
- Không cần thêm bảng/cột hoặc chạy migration mới. Cần build và khởi động lại cả server và client. Tắt cưỡng bức tiến trình, mất điện hoặc mất mạng vẫn áp dụng chính sách mất kết nối ở trên.

## Media, tệp và lịch sử

Giữ nguyên đối tượng media mà màn hình đang dùng, nhưng đóng capture/publication/subscription cũ. Không tự mở lại camera, mic hoặc chia sẻ màn hình; người dùng bật lại sau khôi phục. Khi kết nối mới sẵn sàng, tự nhận lại media của người khác và đo lại chất lượng trên đường truyền mới, tránh kẹt ở chế độ chỉ thoại do số liệu cũ.

Tab Tệp tạm ngừng và hủy các tác vụ đang chạy khi đứt signaling, sau khôi phục tạo lại panel để lấy danh sách và gửi/tải tệp với membership cũ. Tệp đang truyền dở không tự gửi lại; người dùng chủ động thử lại. Chính sách lưu/xóa chat không thay đổi.

Không thêm thực thể, cột, migration hoặc truy vấn DB định kỳ để reconnect. Membership giữ trong RoomService đang chạy; server restart vẫn đối soát phòng cũ như trước. Cần build và khởi động lại server cùng client để có thao tác `RESUME_ROOM` mới. Nếu server cũ không hỗ trợ, client kết thúc phiên thay vì giả vờ đã khôi phục.

## Kiểm thử

- `RoomServiceTest`: thời hạn 119/120 giây bằng đồng hồ điều khiển, không gia hạn bởi disconnect lặp; không ghi leave/không tạo thêm membership khi khôi phục; giữ host; callback/generation cũ; không vượt kick, phiên khác, khóa hoặc hết hạn.
- `MeetingSessionReopenTest`: socket TLS thật, H2 tạm, nhiều lần tạo transport thất bại rồi khôi phục đúng membership/quyền tệp; deadline vẫn đưa JavaFX sang màn hình kết thúc khi tác vụ kết nối bị chặn. Riêng bài UI dùng thời hạn rút ngắn 5 giây để không phải chờ 2 phút.
- Native `VideoCallIntegrationTest.transportRecoveryKeepsMembershipAndBorrowedMediaOwnerWithoutReopeningCapture`: nguồn camera/âm thanh/chia sẻ tạo bằng mã, TLS + Go SFU + RTP thật; mất control khi đang ưu tiên thoại, khôi phục cùng host/membership/quyền tệp, nhận video trở lại, không tự bật capture và có thể bật lại video/thoại bằng tay.

Không thao tác Railway, không mở camera/mic/màn hình thật. Chưa thử rút mạng thực tế trên hai máy LAN.

Kiểm tra đóng ứng dụng/chuyển thiết bị ngày 23/10/2026: 546 bài trong 77 lớp, kết quả mới nhất 0 failures/errors, 2 skipped theo điều kiện môi trường. Bộ hồi quy đầy đủ ở `target/device-close-regression.txt`; nhóm UI được chạy lại sau khi cập nhật assertion theo thông báo mới và đạt 15/15 (`target/device-close-ui-final.txt`). Bảng tổng hợp theo XML mới nhất ở `target/device-close-validation.txt`.

Các bài mới kiểm tra đóng cửa sổ chờ xác nhận trước logout, tự chuyển chủ/đóng phòng cuối cùng, hủy chờ duyệt, rời khi đang reconnect, đổi thiết bị trong phòng đầy/khóa, chặn phiên cũ, thu hồi media/file, và đổi thiết bị đồng thời chuyển quyền chủ phòng.

Kết quả ngày 17/10/2026:

- Toàn bộ profile `video-integration`: 397 bài, 0 failures/errors, 3 skipped; `target/reconnect-regression.txt`.
- Sau hoàn thiện đồng bộ timeout/cleanup và bổ sung kiểm tra tab Tệp phục hồi: 32/32 bài qua, 0 failures/errors/skipped; `target/reconnect-final-validation.txt`.
- Native xác nhận giữ nguyên đối tượng media và membership sau mất kết nối, thoát chế độ ưu tiên thoại cũ để nhận video trở lại, chỉ phát camera/voice sau thao tác bật lại bằng tay. JavaFX xác nhận tab Tệp được tạo lại, nút camera hoạt động trở lại, và lần mất mạng tiếp theo vẫn tự chuyển màn hình kết thúc dù tạo kết nối đang bị chặn.
