# Stored procedure cho create / join / exit phòng

Bộ SQL chuẩn bị ngày 23/10/2026, đối chiếu `RoomRepository`, `RoomService`, `SessionValidator`, schema và migration runtime hiện tại. Java server hiện đã tích hợp create/join/exit; xem `docs/database.md` để chạy bản mới. Cài routine không tự cập nhật một tiến trình Java đang chạy. Không có tỷ lệ tăng tốc được cam kết, chưa có benchmark MySQL/Railway cho bộ routine này.

## File đưa lên database

**Muốn chạy một lần:** dùng `005-install-all.sql`. File này đã gộp preflight, toàn bộ định nghĩa procedure/helper và truy vấn kiểm tra sau cài đặt. Chọn database hiện có, chạy toàn bộ bằng chế độ Execute SQL Script hỗ trợ DELIMITER, dừng nếu gặp lỗi. Kết quả cuối phải có `installed_routines = 6`. Không chạy thêm file tạo routine riêng sau khi đã dùng bản gộp. Đây là bản cài lần đầu; không có DROP/REPLACE để ghi đè routine đang dùng. Kiểm tra môi trường ở đầu chỉ xuất thông tin, không tự đánh giá hay bảo đảm schema phù hợp; cần xem kết quả/lỗi của công cụ. Không có rollback hoặc dữ liệu thử trong file gộp.

Các file thành phần vẫn được giữ để đối chiếu:

1. `005-room-preflight.sql`: chỉ đọc version, database đang chọn, engine, index, routine và quyền hiện tại.
2. `005-room-operations-v1.sql`: tạo 3 routine nghiệp vụ và 3 helper. Đây là file cần cài, không phải file Python.
3. `005-room-rollback.sql`: chỉ gỡ routine; chỉ dùng khi chưa có server Java sử dụng chúng.

Chọn đúng database ứng dụng trong MySQL Workbench/DBeaver hoặc mysql CLI trước khi chạy. File cài không có `USE`, không tạo bảng/cột/index, không xóa/sửa dữ liệu hiện có, không tự gọi routine, không thay chứng chỉ/config. Việc **CALL** routine sau này có ghi dữ liệu.

Dùng chế độ **Execute SQL Script** hỗ trợ `DELIMITER`. `DELIMITER` là lệnh của công cụ SQL, không phải câu SQL gửi nguyên văn qua JDBC. Nếu công cụ không hỗ trợ, dùng trình tạo procedure và gửi từng khối `CREATE PROCEDURE ... END` riêng, bỏ dòng DELIMITER và dấu `$$` kết thúc.

Cần quyền CREATE ROUTINE/EXECUTE và quyền SELECT/INSERT/UPDATE trên các bảng liên quan. Không thêm GRANT hoặc hardcode DEFINER trong bộ SQL. `SQL SECURITY INVOKER` dùng quyền của tài khoản server gọi procedure. Không cung cấp tài khoản database hay quyền gọi helper cho client.

Trước khi cài: xác minh MySQL 8.x/InnoDB, các cột giống schema repo, index membership và các khóa chính runtime tồn tại, tên routine `mmr_*_v1` chưa được dùng. Không chạy lại schema.sql hoặc các migration cũ lên database đã có. Cài routine là DDL, không rollback toàn bộ bằng transaction: nếu cài dở, kiểm tra danh sách routine trước khi tiếp tục, không tự drop routine đang được sử dụng.

Sau khi cài, chỉ kiểm tra metadata:

```sql
SELECT ROUTINE_NAME, SECURITY_TYPE
FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA = DATABASE() AND ROUTINE_NAME LIKE 'mmr%v1';
```

Phải thấy 6 routine. Việc tạo routine chưa làm ứng dụng nhanh hơn; cần tích hợp Java rồi đo lại.

## Phạm vi, quyền quyết định và giới hạn

`mmr_create_room_v1`: tạo phòng, marker owner và receipt trong cùng transaction. Trần tuyệt đối 50; Java truyền giới hạn chính sách hiện hành (giữ mặc định 20). Java vẫn chịu trách nhiệm ngân sách toàn server, PIN hash/HMAC, cấp mã ngẫu nhiên và đăng ký live room.

`mmr_join_room_v1`: lock phiên, kiểm tra receipt, lock phòng, xác minh instance/epoch/opaque, trạng thái, khóa phòng, chỗ trống, thêm membership và receipt. Các phòng độc lập có thể chạy song song; cùng phòng được tuần tự hóa qua khóa dòng. COUNT và kiểm tra user trùng được gom thành một lần đọc. Đếm quota receipt chỉ đọc tối đa 1024 khóa theo PK đã có.

`p_admission_verified` chỉ được Java server đặt TRUE **sau** khi kiểm tra PIN, host approval, connection/generation và ngân sách. Database không có đủ thông tin để tự xác minh các điều kiện đang ở RAM. Không lấy boolean này từ JSON client. Java phải điều phối theo phòng để quyết định duyệt và khóa không bị thay đổi trong khoảng kiểm tra đến commit; procedure vẫn kiểm tra lại khóa/trạng thái/generation tại DB.

`mmr_exit_room_v1`: ba mode chính xác `LEAVE`, `TRANSFER`, `CLOSE`. Thành viên thường LEAVE; host phải chọn CLOSE hoặc TRANSFER sang người cụ thể. TRANSFER xác minh cả hai phiên theo thứ tự Ma_Phien, xác minh successor còn trong phòng rồi chuyển host và ghi rời phòng nguyên tử. CLOSE ghi đóng phòng và kết thúc các membership, **không xóa chat/tệp**. Java phải thu hồi media, cập nhật live authority, push sự kiện và gọi ImmediateRoomCleanup; reopen tiếp tục bị fence cho tới khi cleanup thực sự hoàn tất.

Điểm khác so với repository cũ cần thống nhất khi tích hợp: EXIT ghi thêm một receipt `EXIT_ROOM` để retry không lặp tác dụng. Tức là thêm một INSERT cho mỗi exit mới; đây là đánh đổi an toàn retry, không phải phương án giảm số bản ghi. Receipt create mới lưu opaque generation vào trường Participant_Opaque_ID vốn chưa dùng cho CREATE, không thêm cột. Receipt create cũ có trường đó NULL sẽ không được replay bằng routine mới (`REQUEST_ALREADY_ENDED`); request đang dở từ server cũ cần được xử lý rõ, không tự đổi ID để thử lại. JOIN/EXIT replay của membership đã kết thúc/generation khác bị từ chối. Khi nâng cấp, nên drain thao tác đang chạy và có quy trình xử lý timeout cũ trước khi bật đường mới.

Không có procedure REOPEN: cleanup hiện kiểm tra cả manifest/upload/tệp vật lý và trạng thái pending trong RAM. SQL chỉ thấy bảng trống chưa chứng minh tệp đã dọn xong. Giữ repository/reopen guard hiện tại cho đến khi tích hợp một giao thức cùng điều phối cleanup được kiểm thử. Không thêm cột/trạng thái để né vấn đề đó.

Không viết procedure chat: chat signaling hiện ở RAM và push; không đưa chat trở lại đường ghi database. Không thay đổi OTP, lịch hẹn, account hoặc legacy rooms chưa đo là điểm nghẽn. Các routine chỉ dành cho phòng signaling do đúng instance/epoch sở hữu, không gọi cho marker epoch `legacy`.

## Hợp đồng gọi từ Java server

Connection phải có `autocommit=true`, không nằm trong transaction của caller, và time_zone `+00:00` như cấu hình JDBC hiện tại. Procedure tự SET TRANSACTION READ COMMITTED, START/COMMIT và ROLLBACK khi lỗi; không bọc thêm `setAutoCommit(false)` bên ngoài. READ COMMITTED tránh snapshot cũ từ truy vấn receipt ảnh hưởng phép đếm chỗ sau khi chờ khóa phòng. SET TRANSACTION trước START cũng từ chối transaction đã hoạt động, thay vì âm thầm commit transaction của caller.

Tham số chung, đúng thứ tự trong file SQL:

| Tham số | Nguồn |
|---|---|
| p_session / p_user / p_token_hash | SessionPrincipal phía server; token **đã hash**, không phải token thô |
| p_request | UUID ổn định cho cùng intent, 16–64 ký tự ASCII `[A-Za-z0-9_-]` |
| p_body_hmac | 32 byte HMAC canonical từ server; JDBC setBytes, không phải chuỗi hex |
| p_instance / p_epoch | cấu hình instance và epoch server hiện tại |
| p_room / p_expected_opaque | ID số và opaque của đúng lượt họp; không thay bằng joinCode |
| p_max_participants | giới hạn cấu hình đã kiểm tra; 2–50, mặc định 20 |
| p_next_session/user/token_hash | principal successor đã được server xác minh khi TRANSFER |

HMAC phải bao gồm toàn bộ ý định nghiệp vụ có ảnh hưởng (phòng/generation, lựa chọn host/close/successor, flags...). Không đưa opaque ngẫu nhiên vừa sinh lại của cùng create retry vào canonical intent; cùng request phải giữ cùng body HMAC như cơ chế Java hiện tại. Không dùng riêng Request_ID để tin dữ liệu client.

Kết quả công khai duy nhất là result set 1 dòng: `code='OK'`, `replay=0/1`, `result` là JSON. Không dùng OUT parameter công khai để tránh thêm lượt lấy biến OUT. Driver có thể có thêm lượt metadata/prepare; phải đo thực tế, không tuyên bố CALL luôn bằng đúng một network packet.

JSON gồm databaseRoomId, roomId (opaque), joinCode, name, pinHash, hostUserId, status (CHO/DANG_HOAT_DONG/DA_DONG), locked, capacity, instanceId, serverEpoch, createdAt. JOIN thêm membershipId, participantId, membershipActive. EXIT thêm membershipId, cleanupRequired. Đây là DTO **nội bộ server**: pinHash/token/receipt không được gửi vào giao diện hay push ra client. Chuyển status về RoomStatus bằng mapping hiện tại; đọc timestamp theo UTC.

JSON được lấy vào biến local khi transaction còn giữ khóa, COMMIT xong mới SELECT trả kết quả. Không có kết quả OK cho thao tác rollback. Helper có thể tạo update counts; Java phải đọc hết result/update counts (`getMoreResults`) trước khi trả connection về pool.

Lỗi nghiệp vụ dùng SQLSTATE `45000`, MESSAGE_TEXT là mã như SESSION_INVALID, ROOM_FULL, ROOM_LOCKED, STALE_ROOM, HOST_DECISION_REQUIRED. Lỗi DB gốc/deadlock/unique key được RESIGNAL nguyên trạng để phân biệt. Chỉ log mã an toàn; không dump SQL với token hash/PIN/HMAC. Không biến SQLException thành mã OK.

Timeout/đứt kết nối sau commit có thể chưa rõ kết quả: giữ intent/request/HMAC, không tự tạo request mới. Retry cùng intent khi phiên/generation còn hợp lệ có thể trả receipt; deadlock có rollback có thể thử lại có giới hạn và backoff. Không xóa receipt còn phiên hợp lệ. Duy trì reconnect grace 2 phút và cơ chế database grace hiện có; mất mạng không phải CLOSE hay EXIT.

Khi tích hợp, không giữ đường cũ và mới cùng ghi cho một thao tác. Chẳng hạn EXIT mới đã ghi lịch sử thì không enqueue lại PendingHistory của cùng exit; vẫn giữ queue đó cho disconnect/restart/background cần thiết. Không tạo live member hoặc push thành công trước khi transaction được xác nhận. Nếu DB commit nhưng connection đã đổi, dùng cơ chế bù/fence hiện tại, không cài membership vào generation khác.

## Kiểm thử và số đo

Đã cài và chạy 26 kiểm tra trên **MariaDB 10.4.32 local tách biệt**, dùng named pipe, không TCP/Railway, fixture tự tạo, gồm: create/replay, HMAC mismatch, token hash phân biệt hoa/thường, rollback lỗi unique, host join, replay, race tranh chỗ cuối, capacity, leave/replay, khóa/duyệt, chuyển host, đóng phòng, thu hồi phiên và generation cũ. Script `test_room_procedures.py` chỉ kết nối named pipe hardcode của server thử, không dùng config/credentials ứng dụng.

**Chưa kiểm chứng chạy trên MySQL 8 của Railway** và chưa có benchmark mới so sánh với RoomRepository. MariaDB không thay thế bằng chứng tương thích MySQL. Không gọi procedure với fixture lên Railway. Cần chạy cùng nhóm ca trên MySQL local/staging đúng version trước khi bật Java mới. Không cài runtime/dependency mới trong đợt này; dùng MariaDB có sẵn trong máy.

Phép đo trước đó SELECT 1 mất khoảng 269–335 ms cho đường remote; bộ này tập trung giảm round trip chứ không giảm bằng cách bỏ quyền/receipt. Số đo local MariaDB không dự báo tốc độ Railway. Khi đo trước/sau phải giữ cùng Java, vị trí DB, tải, logger và pool warm; đo riêng create/join/exit median/P95, số lượt JDBC, thời gian giữ transaction và pool acquire. PIN hashing, thời gian media teardown, cleanup và UI cũng phải được tách ra.

`generate_room_procedures.py` chỉ sinh SQL, không kết nối database. Nếu sửa generator, sinh lại SQL rồi kiểm thử lại trước khi cài. Các file test/generator không cần upload vào Railway.
