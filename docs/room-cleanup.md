# Đóng phòng, hủy truyền và cleanup theo lô

Ngày bàn giao: 23/10/2026. SQL **chưa được áp dụng lên Railway**. Không thay schema, index, dependency, chứng chỉ, địa chỉ hoặc private config; không commit/push.

## Luồng thực thi

1. Room authority thu hồi quyền của phiên phòng; đường đóng bền vững ghi `DA_DONG`. Chat signaling được chặn/xóa trong RAM theo opaque ID. Ghi chat legacy kiểm tra trạng thái phòng và membership dưới cùng khóa dòng với reopen.
2. `RoomTransfers` giữ lease theo database room ID + `Room_Opaque_ID`. Sự kiện đóng live room chỉ đánh dấu và xếp thao tác hủy vào maintenance, không làm socket I/O dưới monitor phòng. Coordinator cũng chủ động hủy sau khi đọc fence đóng từ DB, bao gồm đường legacy/restart.
3. Hủy đóng **transport TCP bên dưới TLS**, giải phóng read/write bị kẹt; worker đóng stream trong `finally`. Lease chỉ được bỏ sau khi worker rời thao tác tệp. Không xóa khi còn lease/reader/upload lock. Sau 5 giây chưa drain, ghi `TRANSFER_DRAIN_TIMEOUT` và giữ phòng ở trạng thái retry; đây không phải thời gian cho phép reopen.
4. Mỗi lượt lấy tối đa 100 metadata, trả connection trước khi thao tác đĩa; dọn tối đa 100 manifest và một lượt tối đa 100 orphan objects trong namespace của phòng. Các object đã promote nhưng chưa commit và manifest đã commit nhưng mất ACK đều được xử lý. Metadata vẫn tồn tại cho đến bước xác nhận; tệp đã vắng là idempotent.
5. Một CALL xác nhận các tuple ID/message/key đã xóa, kiểm tra lại owner + opaque + `DA_DONG` trong transaction ngắn; xóa metadata phù hợp và tối đa 500 tin nhắn không còn attachment. SQL không xóa được tệp máy Java; rollback SQL không khôi phục bytes.
6. Java xác minh lại DB trống và storage không còn manifest/object/lease. Chỉ sau đó tạo chứng nhận trong RAM cho đúng generation, bỏ pending và phát catalog delta. Cả signaling và auth/legacy reopen dùng `ImmediateRoomCleanup.check` dưới khóa dòng phòng. Guard chỉ kiểm tra SQL và chứng nhận RAM, không làm disk I/O trong transaction reopen.

Không có thời gian chờ cố định 20 phút trong luồng này. Rời riêng, chuyển host và reconnect ngắn không gọi cleanup nếu phòng còn hoạt động. `RoomReopenGuard` không dựa vào đồng hồ. Chứng nhận được kiểm tra lại sau restart; cache chứng nhận giới hạn 256, mục bị đẩy ra được xác minh lại khi catalog/reopen cần nó. Client không tự suy ra cleanup xong theo timer.

## Điều phối và phục hồi

`ImmediateRoomCleanup` dùng một dispatcher và pool chung tối đa 2 worker, queue 32, pending tối đa 256; mỗi room một task. Với pool DB mặc định 8, cleanup dùng tối đa 2 lượt làm việc DB đồng thời (scan dùng cùng budget), để phần lớn connection cho người dùng. Nếu pool cấu hình nhỏ, dùng 1 worker. Không tăng connection pool. Đây là mức khởi đầu bảo thủ; chưa phải kết quả tuning Railway.

Một phòng chỉ xử lý một lô mỗi lượt rồi nhường, không sleep trong worker khi backoff. Lỗi theo phòng: 2 → 4 → 8 → 16 → 32 → 64 → 64 giây. Duplicate request không reset backoff. Scheduler scan 32 room mỗi lượt, cursor quay vòng, chỉ đọc các phòng `DA_DONG` có `Instance_ID` của instance hiện tại. Dòng phòng đóng là backlog bền vững khi queue/pending đầy hoặc tiến trình mất; không tự nhận legacy room chưa có marker hoặc marker của instance khác.

Shutdown ngừng dispatch, interrupt task chờ/chạy và không cấp chứng nhận hoàn tất. `FileTransferServer` giữ process lock của storage cho đến khi cả worker truyền và worker cleanup đã dừng, kể cả phải defer đóng storage. Instance thay thế không thể mở cùng storage khi worker cũ còn chạy.

Trạng thái realtime: `CANCELLING` (đang dừng truyền), `CLEANING` (đang dọn), `RETRY` (dọn lỗi, thử lại), `OWNER_REQUIRED` cho room chưa có marker quản lý. Catalog giữ tương thích mảng `cleaning` và thêm `cleanupPhases`, kiểm tra chứng nhận theo cả ID và generation từ cùng snapshot SQL. Chỉ đổi phase mới phát invalidation, không đảo lại pha hủy ở mỗi lô sau khi đã drain. Client hiển thị trạng thái, khóa nút mở lại và cập nhật khi delta đến. Khi nhận `ROOM_CLOSED`, FilePanel hủy tác vụ và bỏ retry của panel đó; thông báo “Phòng đã đóng, tác vụ truyền tệp đã dừng.” Chính sách giữ `.part` khi gián đoạn được giữ nguyên; chỉ thao tác Hủy do người dùng chọn mới dọn `.part` theo chính sách cũ. Đổi tên `.part` được tuần tự với Cancellation, sau complete frame và SHA-256. File đã tải xong trong thư mục người dùng không bị xóa.

## SQL cài riêng

File cài một lần: [`006-room-cleanup-v1.sql`](../database/procedures/006-room-cleanup-v1.sql). Không chạy lại schema/migration cũ. Bộ 005 không bị sửa.

Yêu cầu MySQL **8.0.21 trở lên, nhánh 8.x**, bảng InnoDB và schema/migration runtime hiện có; dùng JSON_TABLE/JSON aggregation. MariaDB không được coi là môi trường xác minh tương đương. Không có index/bảng/cột mới. Tài khoản cài cần `CREATE ROUTINE`; tài khoản Java cần `EXECUTE` cho cả ba routine và `SELECT`/`DELETE` trên bảng liên quan vì dùng `SQL SECURITY INVOKER`. Gỡ routine cần quyền `ALTER ROUTINE`. Không thêm GRANT hay hardcode DEFINER.

| Routine | Input | Output / trách nhiệm |
| --- | --- | --- |
| `mmr_cleanup_fence_v1` | room BIGINT, instance VARCHAR(64), generation VARCHAR(24) | Helper nội bộ, không result set; kiểm tra quyền sở hữu, generation và trạng thái, khóa đúng room/runtime trong transaction của caller. |
| `mmr_cleanup_batch_v1` | cùng fence + limit INT (1..100) | Một row `result` JSON `{files:[{id,messageId,key}]}`. Transaction chỉ bao gồm kiểm tra và lấy lô, commit trước khi trả kết quả. |
| `mmr_cleanup_commit_v1` | cùng fence + deleted JSON array tối đa 100 tuple / 65536 bytes + messages INT (1..500) | Một row `result` JSON `{files:<số metadata xóa>,messages:<số tin xóa>,empty:0|1}`. Chỉ xác nhận các key Java đã xóa thành công; `empty` chỉ nói về DB, không cấp quyền reopen. |

Hai entry point phải chạy trên connection autocommit sạch, không bọc outer transaction. Java dùng PreparedStatement CALL có tham số, timeout 10 giây và drain tất cả result/update counts. Helper không phải API client; quyền DB không được cấp cho JavaFX client. Kiểm tra quyền tại commit không chỉ tại lúc enqueue. DDL có implicit commit; cài dở phải kiểm tra routine đã có trước khi chạy tiếp, không tự DROP/REPLACE.

Thứ tự triển khai:

1. Chạy integration test trên MySQL local cô lập và xem SQL trước. Dừng/drain bản Java đang dùng cùng instance/storage; không chạy trộn hai process cùng ownership.
2. Người vận hành chọn đúng database hiện có, chạy file 006 bằng Execute SQL Script hỗ trợ DELIMITER. File chỉ tạo ba routine và SELECT kiểm tra, không tự CALL hay xóa nội dung hiện có.
3. Kiểm tra ba tên routine và SECURITY_TYPE=INVOKER bằng truy vấn cuối file; xem quyền thực thi của tài khoản Java. Các index FK/PK hiện có được tận dụng; chưa có số đo EXPLAIN/lock thực tế trên Railway.
4. Triển khai Java server **và client** mới. Không tự áp dụng SQL lúc server start. MySQL production luôn gọi routine; không có fallback SQL cũ. Nếu thiếu routine, log `CLEANUP_PROCEDURE_MISSING`, phòng giữ pending/RETRY và không báo thành công giả.
5. Kiểm tra phòng thử riêng: đóng trong lúc upload/download, thấy hủy → dọn → catalog cho mở lại; chat/tệp phiên mới trống. Theo dõi log retry/SQL và tác động lên request người dùng trước khi tăng tải.

SQL mới không thay routine cũ nên bản Java cũ không tự bị đổi hành vi bởi việc cài SQL. Bản Java mới yêu cầu SQL 006; client cũ thiếu `roomIdentity` sẽ bị từ chối upload/download/list/leave tệp và reopen/leave auth legacy bằng `ROOM_GENERATION_REQUIRED`, cần cập nhật/vào lại. Signaling dùng opaque ID sẵn có; snapshot/list legacy và ROOM_CREATE/ROOM_JOIN trên kênh tệp bổ sung identity, xoay identity khi reopen. AuthClient ghi nhận identity từ snapshot/list trước khi tạo request; FilePanel giữ identity riêng cho vòng đời panel. LEAVE_ROOM đến muộn không kết thúc membership của lần mở lại. Constructors cũ của RoomInfo/RoomRequest vẫn có để giữ tương thích mã nguồn trong dự án.

Rollback: dừng/drain Java mới trước; nếu cần trở về bản cũ, chỉ chạy [`006-room-cleanup-rollback.sql`](../database/procedures/006-room-cleanup-rollback.sql) sau khi không còn process dùng 006. Rollback gỡ định nghĩa routine, **không phục hồi chat/bytes đã dọn**. Không drop bảng hoặc lịch sử phòng/tham gia. Không đổi ownership lúc worker đang chạy; chuyển ownership/storage cần dừng process cũ và xác minh storage đã nhả lock.

## Chạy local / NetBeans

JDK 21+ (máy kiểm thử dùng Oracle JDK 22.0.1), Maven đi kèm NetBeans được `scripts/common.ps1` tự tìm. Mở Maven project hiện tại trong NetBeans; chạy Test Project để chạy bộ test mặc định. Các test H2/TLS/storage tạo fixture riêng, không nạp cấu hình DB production.

```powershell
# Nhóm cleanup và các đường liên quan, không cần MySQL:
.\scripts\test-room-cleanup.ps1

# Benchmark 4 fixture local, ghi CSV candidate riêng:
.\scripts\test-room-cleanup.ps1 -Benchmark
.\scripts\report-room-cleanup-benchmark.ps1 | Format-Table

# Chỉ dùng MySQL 8 cô lập trên 127.0.0.1, tài khoản root trống của fixture, port riêng:
.\scripts\test-room-cleanup.ps1 -MySqlPort 13306
```

MySQL integration test tạo database ngẫu nhiên `mmr_cleanup_test_<uuid>`, cài schema và 006 **chỉ trong database đó**, rồi drop đúng database fixture. Từ chối MariaDB và port ngoài 1025..65535; host được cố định loopback, không đọc URL/credentials từ config. Không trỏ test sang Railway. Không tự tải/cài thêm runtime MySQL trong lượt triển khai này.

Server chạy local thủ công: chuẩn bị config local của riêng môi trường, cài SQL vào DB local được chọn, rồi dùng `scripts/start-meeting-server.ps1` hoặc Run `server.MeetingServerMain` trong NetBeans; đường auth/legacy là `server.AuthServer`. Không chạy hai server cùng port/storage. Nếu tắt file server hoặc storage không sẵn sàng, không thể chứng minh tệp đã dọn: phòng tiếp tục bị khóa reopen; bật lại đúng storage và kiểm tra log trước khi retry.

## Đo lường và giới hạn

CSV baseline/candidate trong `docs/benchmarks/cleanup-20261023-*.csv`, cùng test `FileTransferIntegrationTest.cleanupBenchmark`, cùng máy và dataset: trống, 1 tệp 4 MiB, 32 tệp 1 KiB, 1001 tin nhắn. Mỗi case 10 mẫu, không loại mẫu đầu. Đo từ ghi đóng room đến hoàn tất một chuỗi `clean()`; SQL count gồm coordinator và repository cleanup, không gồm chuẩn bị fixture. Provider proxy đo thời gian acquire connection và execute SQL; H2 dùng connection mới, không đại diện wait của pool production. Không đo Railway và không tuyên bố phần trăm tăng tốc.

Diagnostics production có `room.close.blocked`, `room.cleanup.queue`, `.cancel`, `.pool`, `.sql`, `.disk`, `.batch`, `.failed`, `.completed`; số room/generation hash, phase, code, attempt, retry seconds, file/message count, queue/worker count. Bộ sampler Diagnostics hiện có ghi CPU, heap, GC và thread. Không ghi token, secrets, đường dẫn storage, tên/nội dung tệp hoặc chat. `.cancel` đo từ lần chủ động hủy đầu đến lúc xác nhận lease đã drain, gồm thời gian nhường lượt/retry nếu có. `.sql` bao gồm borrow + CALL; `.pool` tách borrow. `.queue` đo từ yêu cầu đầu, gồm các lượt yield/retry, không phải chỉ chờ executor của lượt cuối.

Các số đo chưa hoàn tất: ma trận baseline/candidate nhiều phòng có/không truyền đang chạy; latency đăng nhập/create/join/chat dưới cleanup đồng thời; thời gian cleanup hoàn tất → nút UI thực sự bật; P95 trên MySQL/Railway; profiling CPU/GC/heap theo từng workload. Test đồng thời/queue/retry xác minh tính đúng đắn và giới hạn worker, không thay thế các benchmark này. Cần MySQL 8 local tương thích trước khi coi SQL đủ điều kiện triển khai production. Chi tiết kết quả thực chạy: `docs/benchmarks/cleanup-20261023-results.md`.

## File chính

- Server: `ImmediateRoomCleanup`, `RoomCleanupStore`, `RoomTransfers`, `FileTransferServer`, `FileTransferService`, `FileRetentionService`, `FileStorageService`, `FileMetadataRepository`.
- Wiring/guards và legacy: `MeetingServerMain`, `AuthServer`, hai `RoomService`, `LegacyRoomService`, `RoomCatalog`, `MeetingHandler`.
- Client/protocol: `MeetingSession`, `AuthClient`, `FileTransferClient`, `SceneManager`, `FilePanel`, `LobbyScreen`, `MeetingEndedScreen`, hai RoomInfo và RoomRequest auth.
- SQL 006, scripts chạy/report và test cleanup/H2/TLS/MySQL opt-in. Các API retention cũ còn phục vụ regression/reference tests, không được chọn làm fallback trong hai entry point production mới.
