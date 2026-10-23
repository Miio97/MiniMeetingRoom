# Tích hợp stored procedure vào Java — 23/10/2026

## Chạy local / NetBeans

Các routine đã cài trên Railway không cần chạy lại hoặc chạy migration bổ sung. Giữ nguyên config và chứng chỉ hiện có. Build project, dừng phiên server cũ rồi chạy `server.Main` như trước; client chạy `client.Main`. Không chạy đồng thời hai server của cùng instance trong lúc chuyển phiên bản.

`MeetingServerMain` dùng `new RoomRepository()` nên bật đường routine mặc định. Startup chỉ đọc kiểm tra sáu routine, không CREATE/ALTER chúng. Thiếu routine báo `ROOM_ROUTINES_NOT_READY`; không tự rơi về SQL cũ và che lỗi cấu hình. Constructor `RoomRepository(ConnectionProvider)` giữ đường SQL tham chiếu cho H2/legacy; `RoomRepository(ConnectionProvider, true)` dùng để kiểm thử routine với database local riêng. Đây không phải cơ chế tự fallback khi DB production lỗi.

## Những đường đã đổi

- CREATE_ROOM gọi `mmr_create_room_v1`: một prepared CALL, không bọc transaction Java, policy maxParticipants truyền đúng cấu hình (mặc định 20, trần 50).
- JOIN_ROOM gọi `mmr_join_room_v1`: chỉ sau khi RoomService đã kiểm tra PIN, host approval, connection/generation và ngân sách. Khi đang chờ host duyệt vẫn chưa gọi JOIN procedure. Tiếp tục kiểm tra membership/generation trong database tại commit.
- LEAVE_ROOM do người dùng chủ động gọi `mmr_exit_room_v1`: xử lý LEAVE/CLOSE/TRANSFER, trả về sau commit. Không ghi lại PendingHistory cho cùng thao tác đã được procedure ghi. Callback cleanup và room events vẫn do server thực hiện.
- Signaling không SELECT xác thực trùng trước ba CALL trên; authentication TLS ban đầu, check hết hạn trong RAM, kiểm tra phiên có lock bên trong procedure và background validation/revocation vẫn giữ nguyên. Các lệnh khác giữ xác thực hiện tại.
- Ghi lịch sử nền không liên quan được đẩy sang executor cleanup hiện có, có cờ coalesce để không tạo hàng đợi vô hạn. Shutdown vẫn flush; generation/pendingHistory vẫn chặn reopen khi chưa xác nhận ghi xong.

Không chuyển REOPEN sang SQL vì cần kiểm tra cleanup tệp vật lý/manifest và trạng thái pending phía Java. Không thay đổi chat RAM/push, OTP, account, lịch hẹn, chứng chỉ, pool size hoặc runtime SFU. Disconnect, suspend, timeout mạng hai phút và thu hồi phiên tiếp tục dùng đường lịch sử nền, không biến thành một thao tác EXIT có chủ ý.

## Timeout và đồng bộ live room

Client giữ exit request ID theo cùng body và opaque room; không xóa trạng thái rồi báo “đã rời” trong finally khi request lỗi. Intent gắn trong RAM của MeetingSession, không lưu token/database về client.

Khi exit đang xử lý, server giữ một ExitWork tối đa mỗi live room; join mới và thay đổi host được fence, các remove/disconnect phát sinh được hoãn trong map hữu hạn theo connection của phòng. Không giữ monitor toàn hệ thống khi chờ JDBC. Các phòng khác vẫn xử lý được.

Nếu JDBC báo lỗi sau khi CALL có thể đã commit, không gửi ACK thành công. Worker kiểm tra receipt của đúng session/request/HMAC/opaque để xác nhận kết quả đã commit trước khi thử lại cùng intent; không tạo request mới. Retry có backoff 1–16 giây, tối đa một lịch retry mỗi phòng và thời hạn hai phút. Các lỗi nghiệp vụ xác định không được retry như lỗi kết nối.

Khi không xác minh được hết thời hạn, intent CLOSE vẫn đóng/fence đúng generation; LEAVE/TRANSFER được xử lý như mất kết nối của actor, dùng reconciliation hiện có. Đây là cơ chế fail-closed khi outcome không rõ, không phải bảo đảm ứng dụng hoạt động bình thường nếu database mất lâu dài. Reopen không được vượt pendingHistory/cleanup fence. Commit trễ sau khi server restart tiếp tục do marker instance/epoch và startup reconciliation bảo vệ.

## JDBC và log

`RoomRoutineClient` dùng PreparedStatement CALL với tham số bind, không nối dữ liệu người dùng vào SQL. Đọc hết result sets/update counts rồi mới trả connection về pool. Không gọi setAutoCommit(false), không thêm COMMIT từ Java quanh CALL. Result JSON chụp bên trong transaction được chuyển về StoredRoom; PIN hash chỉ tồn tại trong DTO nội bộ server, không phát ra client.

Lỗi nghiệp vụ SQLSTATE 45000 có mã cố định được ánh xạ sang RoomException; lỗi SQL khác giữ SQLException và không báo thành công. Các truy vấn phục hồi receipt chỉ dùng trên intent đã được server nhận trước đó, không phải API đọc lịch sử tùy ý của client.

Log mới `room.routine.completed` gồm action, outcome, queue_ms (chờ lấy connection), database_ms (CALL, nhận result và đóng/trả connection). Khi không lấy được connection, database_ms không có giá trị, không giả lập bằng 0. Không ghi SQL, body, token/hash, PIN, HMAC hoặc room ID. Reporter hiện nhận event này. Đây là elapsed time phía Java, không phải thời gian thực thi SQL thuần ở MySQL.

## Bằng chứng và giới hạn

Kiểm tra chỉ đọc trên Railway xác nhận MySQL 9.7.2 và ba chữ ký routine: create 15 tham số, join 12, exit 14. Không gọi routine ghi dữ liệu trên Railway trong đợt kiểm thử.

Kiểm thử JDBC thật dùng Connector/J hiện tại và MariaDB 10.4.32 local tách biệt. Các ca tích hợp gồm create/replay, join, chuyển host và đóng phòng không ghi lịch sử hai lần, mất ACK sau commit và khôi phục cùng receipt, TLS signaling và phiên bị thu hồi. Không dùng config hoặc credentials Railway cho fixture; URL test bị giới hạn 127.0.0.1 và database có prefix riêng.

Nhóm hồi quy H2/TLS giữ đường SQL tham chiếu để kiểm tra các chức năng cũ. Chúng không thay thế bằng chứng thực thi procedure trên MySQL production. Chưa có số đo button-to-render hoặc median/P95 mới trên Railway; cần đo log client/server sau khi người vận hành chạy bản mới trong điều kiện tương đương.

Phép đếm trực tiếp repository (không gồm SELECT auth ngoài handler hoặc lệnh quản lý transaction):

| Thao tác | SQL tham chiếu: execute từ Java | Procedure: execute từ Java |
|---|---:|---:|
| Create | 7 | 1 |
| Join | 8 | 1 |
| Host đóng/rời phòng | 3 | 1 |

Số SQL nội bộ trong MySQL không biến mất. Local có độ trễ rất nhỏ, EXIT mới còn ghi thêm receipt nên không mặc định nhanh hơn đường cũ trên local. Mục tiêu là giảm round trip trên đường Railway có độ trễ cao. Không dùng số đếm này làm phần trăm tăng tốc UI.

Kết quả kiểm thử cuối:

- Các báo cáo mới nhất của 529 test thường + routine: 527 đạt, 2 skip, 0 failure/error. Đây là tổng hợp lượt toàn bộ và lượt chạy lại nhóm routine, không phải tuyên bố một lượt toàn bộ cuối cùng đều xanh. Lượt toàn bộ ban đầu có một fixture TLS thiếu trường PIN rỗng; đã sửa fixture và chạy lại cả 5 test routine đạt. 524 test cũ không có failure/error, gồm 2 skip theo điều kiện.
- 5 test JDBC/routine: lifecycle create/join/transfer/close, mất ACK sau commit, từ chối outer transaction, đếm lệnh tham chiếu/CALL, TLS và phiên thu hồi.
- 7 test media riêng: AudioCallIntegrationTest (3), ScreenCallIntegrationTest (2), VideoCallIntegrationTest (2: adaptive/ưu tiên thoại và reconnect). H2/TLS tạm, SFU thật, nguồn media tạo bằng mã; không dùng camera/microphone thật.
- Log lượt toàn bộ: `target/routine-check/java-test.log`; log media: `target/routine-check/media-test.log`. Log toàn bộ vẫn giữ lỗi fixture của lượt đầu để không sửa bằng chứng. Báo cáo XML mới nhất của nhóm routine nằm trong `target/surefire-reports/TEST-server.meeting.RoomRoutineIntegrationTest.xml`.

Một mẫu benchmark local sau một lượt warm-up và 5 mẫu đo, DriverManager/loopback, không phải Hikari/Railway: create tham chiếu 8.350 ms / CALL 6.988 ms; join 8.230 / 7.182 ms; exit 5.652 / 7.304 ms. EXIT local chậm hơn trong mẫu này; không bỏ số đó hoặc suy ra phần trăm tăng tốc trên Internet. Số lượt execute tương ứng vẫn là 7/8/3 so với 1/1/1. Năm mẫu chưa đủ để đưa ra cam kết P95.

Chạy test routine local khi đã có fixture server riêng, ví dụ:

```powershell
mvn -B -Dmmr.test.routines.port=PORT_LOCAL_RIENG -Dtest=RoomRoutineIntegrationTest test
```

Không truyền port Railway vào test. Test chỉ chấp nhận loopback root không mật khẩu của fixture, tự tạo và gỡ database có tên `mmr_java_routines_*`. Không thêm/grant quyền kiểu đó cho Railway.
