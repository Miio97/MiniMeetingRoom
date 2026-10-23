# Kết quả cleanup local — 23/10/2026

Baseline lấy trước khi đổi hành vi cleanup trên working tree bắt đầu ở HEAD `fe88a486590f1b937a1430d833d819e1d546a282`. Candidate dùng cùng fixture và thuật toán thống kê. Không dùng Railway. Không có phần trăm tăng tốc hay dự báo độ trễ internet.

Máy: Windows 11 Home Single Language, Intel Core i5-11400H, 6 core / 12 logical processor, RAM khả dụng theo hệ thống 15.8 GiB, Oracle JDK 22.0.1. Maven bundled NetBeans 22. H2 2.3.232 in-memory MySQL mode, TLS sinh riêng cho test, storage trong JUnit TempDir trên máy này. Không giả lập RTT và không cố định tải của các ứng dụng nền bên ngoài Maven.

## Baseline và candidate

10 mẫu/case/bản, có cả mẫu đầu; median là trung bình hai mẫu giữa, P95 nearest rank (với n=10 là mẫu lớn nhất). Lỗi trong hai lượt đo CSV hoàn tất: 0/40 baseline, 0/40 candidate. Thời gian gồm ghi đóng phòng, các lượt clean và xác minh; không gồm upload/khởi tạo fixture. Không chạy hai Maven workload đồng thời khi lấy hai CSV này.

| Fixture, một phòng | Median trước (ms) | P95 trước (ms) | Median sau (ms) | P95 sau (ms) | SQL trước/sau, median |
| --- | ---: | ---: | ---: | ---: | ---: |
| Không tệp/tin | 18.990 | 48.305 | 17.905 | 66.741 | 7 / 6 |
| Một tệp 4 MiB | 28.935 | 37.222 | 24.821 | 30.854 | 12 / 7 |
| 32 tệp × 1 KiB | 254.928 | 461.545 | 215.549 | 247.319 | 136 / 7 |
| 1001 tin nhắn | 61.578 | 72.205 | 49.873 | 53.350 | 24 / 18 |

Số SQL là số lần execute từ Java trong reference JDBC H2, gồm coordinator và repository cleanup. Đây **không** phải số statements bên trong stored procedure MySQL hay latency Railway. Trên production, một lượt coordinator thông thường đọc fence, CALL batch, CALL commit; mỗi CALL chứa nhiều statements ở DB. Điểm giảm RPC của lô tệp có cơ sở từ hợp đồng này, nhưng chưa có phép đo MySQL thật để khẳng định mức giảm thời gian. P95 case trống tăng trong lượt đo cuối; không khẳng định mọi workload đều nhanh hơn. Với 10 mẫu, cần thêm mẫu và kiểm soát tải nền để đánh giá đuôi phân phối.

CSV giữ riêng các cột `pool_wait_ms`, `sql_ms`, batches và errors để kiểm tra lại. “pool wait” của fixture là thời gian mở connection H2 qua provider, không phải Hikari wait dưới tải. Storage deletion vẫn chiếm phần đáng kể ở case nhiều tệp; không diễn giải toàn bộ chênh lệch thời gian thành lợi ích stored procedure.

Tái lập:

```powershell
.\scripts\test-room-cleanup.ps1 -Benchmark
.\scripts\report-room-cleanup-benchmark.ps1 | Format-Table
```

## Xác minh tính đúng đắn

Kết quả thực chạy (log dưới `target/`, không dùng dữ liệu production):

| Phạm vi | Kết quả |
| --- | --- |
| Toàn bộ Maven mặc định, sau thay đổi cleanup/transfer chính | 611 ca: 601 đạt, 10 skip, 0 failure/error. `cleanup-full-final-tests.txt`. |
| Nhóm mở rộng sau bổ sung generation legacy | 246 ca, 4 skip; một test bắt hụt trạng thái RECONNECTING thoáng qua. Fixture đã được sửa để giữ offline trước khi chờ trạng thái; chạy lại cả 14 test MeetingSessionReopenTest đạt. |
| Nhóm chốt gồm UI, signaling, coordinator và TLS race | 88 ca; 87 đạt, một fixture UI thiếu ownership marker. Bổ sung marker đúng hợp đồng server mới; chạy lại toàn bộ 10 RoomFlowTest đạt, 0 failure/error. Các ca còn lại không có lỗi. Log `cleanup-final-verification.txt`, `cleanup-ui-final-tests.txt` và JUnit XML tương ứng. |
| Benchmark standalone trên source cuối | 1 test / 40 mẫu candidate, 0 lỗi; `cleanup-candidate-final-run.txt`. |
| MySQL routine thật | 2 ca skip vì không có MySQL 8 cô lập; không coi skip là đã kiểm chứng SQL. |

Đây là kết quả của full run cộng các nhóm chạy lại sau hardening, không tuyên bố đã chạy một full run mới sau mọi chỉnh sửa cuối. Một mẫu TLS chẩn đoán cuối với upload chờ dữ liệu và download backpressure 16 MiB: từ ghi đóng đến drain 11.184 ms, đến cleanup-ready 86.550 ms, đến cuối kiểm tra reopen/bảo toàn tệp 124.337 ms; n=1, không phải median/P95 hoặc latency client UI.

Các nhóm được kiểm tra bao gồm:

- Hủy upload chờ dữ liệu và download bị backpressure qua TLS thật, nhả lease trước khi xóa; tệp đã tải xong còn nguyên.
- Xóa bytes rồi mất commit, restart và retry idempotent với metadata còn giữ; chưa ACK không được giả thành thành công.
- Không rename `.part` khi cancellation đến trước xác minh; cancellation muộn không xóa tệp đã hoàn tất.
- Chặn reopen khi đang hủy, dọn, lỗi; ownership/generation/state đổi trước commit đều bị từ chối.
- Generation cũ ở upload/download/list và legacy leave bị từ chối; client legacy mang identity qua list/reopen/leave.
- Hai phòng chạy độc lập, lỗi không giữ worker qua backoff; queue overflow 270 room được scan lại, không vượt 2 worker hay task trùng cho room.
- Không giữ connection trong callback hủy/đĩa; shutdown giữ storage process lock đến khi cleanup worker kết thúc.
- Hồi quy auth/OTP/account, phòng/host/approval/reconnect, chat, file, JavaFX và media unit tests trong bộ mặc định.

## Giới hạn còn lại

- Không có MySQL 8 tương thích trong môi trường; binary XAMPP được xác nhận là MariaDB 10.4.32, không dùng thay thế. `RoomCleanupRoutineIntegrationTest` được bổ sung dưới cờ opt-in và chưa chạy với procedure thật. Chưa xác minh cú pháp/optimizer/EXPLAIN/lock scope và quyền routine trên MySQL 8 thực tế. SQL chưa đủ bằng chứng để tự triển khai production.
- Chưa có ma trận benchmark trước/sau cho nhiều phòng gần nhau, có/không có truyền đang chạy. Test 270 room và ca TLS truyền đang chạy là kiểm thử correctness, không phải phân phối latency/P95.
- Chưa định lượng ảnh hưởng lên đăng nhập/create/join/chat dưới cleanup đồng thời, client render nút mở lại, hay CPU/heap/GC theo workload. Diagnostics có các sự kiện theo giai đoạn và sampler runtime để đo tiếp; không điền số 0 cho các mục chưa đo.
- Bộ Maven mặc định không chạy các probe phần cứng camera/mic/share/SFU và các native media integration opt-in. Không khẳng định đã kiểm chứng thiết bị thật hoặc đường mạng Railway.

Không thay private config, chứng chỉ/IP/password; không chạy migration/CALL/xóa dữ liệu Railway; không commit/push. Thay đổi có sẵn trong `config/auth-client.properties` được giữ nguyên.
