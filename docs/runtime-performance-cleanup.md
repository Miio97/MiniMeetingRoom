# Cleanup, chat và điều phối tài nguyên — 21/10/2026

## Phạm vi

Giữ Java/JavaFX, TCP/TLS, Java server, Go/Pion, WebRTC và MySQL. Không thêm dependency/runtime, không sửa schema, không xóa bảng `Tin_Nhan`/`Tep_Dinh_Kem`. Không chạy server chính hoặc migration trên Railway trong quá trình triển khai. Không sửa chứng chỉ, password hay private config; không commit/push.

Sau khi **người vận hành khởi động bản server mới**, cleanup sẽ áp dụng cả cho các phòng đã đóng từ trước thuộc instance. Đó là thay đổi nghiệp vụ dọn nội dung đã được duyệt; lịch sử phòng/tham gia vẫn được giữ. Các bản tải về thư mục cá nhân không bị xóa.

## 1. Đóng và mở lại phòng

`ImmediateRoomCleanup` thay lịch chờ 20 phút. Sau khi ghi đóng phòng, server đưa phòng vào hàng đợi dọn: upload đang chờ → object do storage quản lý → metadata đính kèm → tin nhắn. Mỗi lượt xử lý tối đa 100 attachment, 100 object mồ côi có đường dẫn nhận diện được và 500 message. Tin nhắn loại tệp chỉ bị xóa sau khi object/metadata đã được xử lý. Quét object của đúng thư mục phòng để kiểm chứng không còn file dù metadata đã mất; tệp không thể xác định phòng không bị xóa suy đoán mà theo cơ chế expiry storage hiện hữu.

`RoomReopenGuard` được gắn vào cả repository signaling và service phòng legacy. Nó kiểm tra lại trong transaction đang khóa dòng phòng: chưa còn tin nhắn, chưa còn upload chờ và coordinator không còn đánh dấu đang dọn. Client không quyết định điều kiện mở lại. Sảnh và màn hình sau cuộc họp nhận trạng thái dọn qua catalog.

Lỗi giữ trạng thái chặn, ghi diagnostic an toàn, thử lại với backoff 2–64 giây. Room đã đóng trong DB cùng manifest upload giúp quét phục hồi sau restart. File được xóa trước metadata; nếu commit SQL lỗi, lượt sau xử lý lại object đã mất một cách idempotent. Không giả định rollback SQL khôi phục file.

Phòng signaling được phân biệt bằng instance/opaque ID; retry đóng phòng cũ không đóng generation mới. Phòng legacy tạo mới cũng được gắn owner trong bảng runtime hiện có, với epoch `legacy`; đường legacy không được mở lại phòng signaling để bỏ qua generation. Phòng thuộc instance khác không được dọn. **Phòng legacy cũ chưa có marker được giữ lại**, không suy đoán ownership khi khởi động; nếu còn nội dung, server trả `ROOM_CLEANUP_OWNER_REQUIRED` khi mở lại. Phòng cũ đã trống có thể được chủ phòng mở lại và gắn owner cho lượt mới.

Nếu có dữ liệu legacy chưa được đánh dấu, cần xác nhận danh sách phòng/storage thực sự thuộc server này trước khi gán owner. Có mẫu DML riêng [assign-legacy-room-owner.sql](../database/manual/assign-legacy-room-owner.sql), mặc định ID 0 và `ROLLBACK`, không đổi schema và chưa chạy trên Railway. Chỉ sau khi xác nhận mapping mới cân nhắc áp dụng; commit mapping khiến các phòng đã đóng đó đủ điều kiện dọn ngay. Không có migration schema mới cho đợt này; standalone AuthServer cũng kiểm tra bảng runtime hiện hữu trước khi nhận kết nối.

Mất mạng trong reconnect grace hoặc một người rời khi phòng còn hoạt động không khởi động cleanup. Phòng đóng thật khi hết người hoặc host chọn đóng mới được dọn. Worker retention 20 phút cũ không được khởi chạy trong các entry point ứng dụng.

Tệp liên quan:

- `server/meeting/ImmediateRoomCleanup.java`, `RoomReopenGuard.java`, `RoomRepository.java`, `RoomService.java`.
- `server/rooms/RoomService.java`, `server/MeetingServerMain.java`, `server/AuthServer.java`.
- `server/filetransfer/FileRetentionService.java`, `FileStorageService.java`, `FileMetadataRepository.java`, `FileTransferService.java`.

## 2. Chat, dữ liệu tạm và reconnect

Chat vẫn dùng **server push đã có sẵn**. Client thêm tin tạm ngay, trạng thái “Đang gửi…”, xóa ô nhập để tiếp tục soạn. Tối đa 8 tin chưa giải quyết; rate limit server vẫn 5 tin/giây, burst 10. ACK thành công chỉ tạo sau commit. ACK và push đối chiếu bằng `clientMessageId` và ID tin nhắn.

Lỗi chắc chắn chưa lưu cho phép thử lại cùng nonce. Trường hợp commit/timeout chưa rõ kết quả được hiển thị chưa xác nhận; không tự phát lại. Receipt server giữ có giới hạn trong RAM tối đa 10 phút/1024 mục, gắn session + opaque room + nonce. Client chặn thử lại sau 9 phút hoặc khi đổi server epoch/opaque room. Đây **không phải exactly-once bền vững qua restart**; đầy bộ nhớ receipt sẽ trả lỗi quá tải rõ ràng.

Sau reconnect, client đồng bộ lịch sử và hỏi `CHAT_STATUS` cho các nonce chưa rõ. Server chỉ trả receipt đã commit mà mình còn biết; không ghi lại tin. Kết quả xác nhận được ghép với lịch sử/push, tránh giữ đồng thời tin tạm và bản đã lưu khi còn receipt. Khi không còn receipt, vẫn giữ trạng thái chưa xác nhận, không suy đoán từ nội dung giống nhau.

Backend dùng CompletionStage để worker signaling không đứng chờ worker chat. Hai stripe có hàng đợi hữu hạn giữ thứ tự ghi trong một phòng; các stripe độc lập có thể chạy song song. History có executor riêng hữu hạn. Tác vụ ghi kiểm tra lại session, trạng thái phòng/opaque và membership trong transaction lúc thực thi. Mọi job còn lại được kết thúc khi handler đóng.

UI video room chuyển sang `ListView` tái sử dụng cell, cập nhật từng tin, chống trùng theo ID, cửa sổ tối đa 500 tin. Có tải tin trước và giữ mốc cuộn; tin đến không ép xuống cuối khi đang đọc tin cũ. Nháp tối đa 2000 ký tự, tin chờ và cache nằm trong RAM của tài khoản/cuộc họp; không phải outbox trên đĩa khôi phục sau khi tắt app.

Tệp liên quan: `MeetingHandler`, `SignalingServer`, `RoomWorkQueue`, `MeetingSession`, `ChatPanel`, `ChatService` và `SignalingClient`.

## 3. Sảnh và concurrency

Sảnh lấy snapshot khi vào, sau đó nhận delta `ROOM_CATALOG_CHANGED` qua kết nối signaling hiện có. Mỗi watcher có ID và revision; lệch revision/reconnect sẽ lấy lại snapshot. Chỉ gửi phòng do user làm host hoặc đã có lịch sử tham gia, không gửi PIN. Feed coalescing, hàng đợi gửi hữu hạn; kết nối quá chậm bị ngắt theo backpressure.

Không còn timer tải danh sách sảnh mỗi 2 giây. Nút Làm mới vẫn hoạt động. Row không thay đổi giữ nguyên node, không dựng lại cả sảnh. Listener đóng theo vòng đời màn hình. Feed/cache có cửa sổ tối đa 200 phòng và ưu tiên tối đa 100 ID đang hiển thị; cuộn/xem trang lịch sử đổi subscription có coalescing. Do đó phòng cũ ngoài danh sách gần đây vẫn được cập nhật khi đang xem. Server phân biệt rời cửa sổ với mất quyền xem; không làm biến mất các row lịch sử đã tải chỉ vì đổi vùng theo dõi. Màn hình kết thúc theo dõi riêng ID phòng vừa kết thúc.

Tạo/mở lại, admission, đổi khóa, chuyển host và lưu lịch sử đã được đưa ra ngoài monitor RAM dùng chung của authority chính. Reservation bảo vệ giới hạn mở phòng/join đồng thời; SQL khóa dòng và opaque kiểm tra tại commit. Chuyển host dùng khóa session theo thứ tự và kiểm tra host hiện tại trong transaction. Công việc lịch sử lỗi được giữ để retry; snapshot/phòng khác không phải đợi JDBC của một admission chậm.

Chat legacy dùng stripe theo phòng để tránh giữ khóa chung của dịch vụ chat lúc truy vấn. Adapter `LegacyRoomService` cũ vẫn giữ điều phối bảo thủ riêng; đường vận hành chính là `server.Main`, không có cam kết tối ưu toàn bộ adapter cũ như authority signaling.

Không tăng connection pool. Tổng số JDBC connection vẫn bị chặn bởi pool đã cấu hình; các nhóm công việc mới có số thread/queue hữu hạn. Index `IX_TinNhan_PhongTep (Ma_Phong, Ma_Tin_Nhan)` và `IX_ThanhVienPhong_QuyenTep (Ma_Phong, Ma_Nguoi_Dung, Roi_Phong_Luc)` đã có trong schema/migration hiện hữu. Chưa chạy EXPLAIN trên Railway và không đề xuất thêm migration index trong đợt này.

## 4. Video và ngân sách media

Trần cứng 50 được kiểm tra ở validation chung, UI, repository tạo phòng và Java/Go config. Phòng cũ vượt chính sách bị từ chối mở lại; không tự sửa bản ghi Railway. Mặc định/policy server vẫn 20; tăng cấu hình không đồng nghĩa có bằng chứng máy phục vụ được 50 người cùng phát media.

Video chính + tối đa 5 ô nhỏ; khi màn hình chia sẻ là video chính thì tối đa 5 camera nhỏ. Client chỉ tạo receiver cho camera thuộc viewport. Đổi trang/ghim sẽ đóng receiver không dùng và gửi `VIDEO_UNSUBSCRIBE` theo lô để SFU giải phóng PeerConnection ngay. Lệnh hủy kiểm tra owner/room/generation, idempotent, không dừng publisher hoặc receiver của người khác. SFU cũng giới hạn tối đa 6 camera nhận/người.

Ngân sách PeerConnection là **toàn engine**, gồm cả pending negotiation và cả camera/voice/screen. Công thức tương thích hiện tại tính đủ camera + voice + screen là `max(512, 2*N*N+N)` (N là policy participants, không phải số kết nối signaling). Có thể đặt trần engine qua biến môi trường hiện hữu `MMR_MEDIA_MAX_PEER_CONNECTIONS`. Dành một phần ngân sách cho yêu cầu voice; không đuổi transport đang chạy để nhận yêu cầu mới. Khi đầy trả `MEDIA_BUSY`/`VIDEO_VIEWPORT_LIMIT` có thông báo phù hợp.

**Giới hạn SDK đã được kiểm chứng:** webrtc-java 0.19.0 tạo được hai encoding và SDP RID/simulcast. Nhưng `sender.setParameters(sender.getParameters())` với hai encoding báo `INVALID_MODIFICATION: Attempted to change RID values in the encodings`; binding Java chưa giữ được RID để cập nhật adaptive an toàn. Phần thử hai lớp không được đưa vào runtime. Hiện ô nhỏ chưa có bitrate riêng; thu nhỏ UI không được tính là giảm bandwidth. Đã triển khai fallback subscription/viewport theo yêu cầu, giữ AUTO tối đa 720p/30 và ưu tiên thoại.

Nếu triển khai hai lớp sau này: cần sửa/nâng binding để round-trip RID an toàn, kiểm thử adaptive và cả hai encoding thật, rồi bổ sung định tuyến RID trên Pion. Bước thay dependency/native binding cần được duyệt riêng.

## 5. Database lỗi

Lỗi JDBC được phân biệt với kết quả xác thực âm tính. Kết nối đã xác thực có grace tối đa 2 phút tính từ lần xác minh thành công cuối; không gia hạn mốc này khi đọc cache. Có thể tiếp tục renew grant media của thành viên đã có, trong hạn token; không cấp quyền ghi/publish mới dựa trên lỗi database. Logout/revocation xác nhận được vẫn có hiệu lực ngay.

Đây là grace database, tách với reconnect mạng 2 phút. Các thao tác cần DB báo tạm gián đoạn; không báo tin chat/upload chưa commit là đã lưu. Upload kiểm tra token trong cùng transaction finalize và kiểm tra membership generation; manifest cũ không được tiếp tục trong lần tham gia mới. Cache phòng được giữ có giới hạn, xóa khi đổi/thoát tài khoản; không tải toàn database, password hash hoặc OTP xuống client.

## 6. Kiểm thử và số đo

Các test chỉ dùng H2/MySQL mode, chứng chỉ TLS tạo trong thư mục tạm, SFU thật và nguồn video/audio/screen tạo bằng mã. Không dùng Railway hoặc thiết bị thật để kiểm thử xóa dữ liệu.

Các log nghiệm thu được liệt kê ở phần kết quả cuối tài liệu. Bộ test bao gồm cleanup theo lô, retry lỗi đĩa, chặn reopen, restart/instance, membership cũ, revoked token, chat nonce/ACK/push/status sau resume, hàng đợi quá tải, quyền catalog, sảnh tự cập nhật, admission đồng thời, chuyển host, reconnect, viewport đổi lại nhiều lần, camera/voice/screen/file và UI hợp nhất.

Đo chat bằng cùng `RuntimeLatencyBenchmarkTest` trên commit gốc `847074e` và mã mới, chạy tuần tự trên máy này, 3 mẫu warm-up + 30 mẫu, cách gửi 220 ms. Schema H2 giống nhau, TCP/TLS loopback, có cùng instrumentation JDBC và diagnostics, không có người dùng Internet. H2 dùng DriverManager nên số acquire dưới đây **không phải** thời gian chờ Hikari/Railway.

| Chỉ số | Trước (`847074e`) | Sau |
|---|---:|---:|
| ACK trung vị | 4.551 ms | 5.769 ms |
| ACK P95 | 5.308 ms | 7.228 ms |
| Push trung vị | 4.328 ms | 5.602 ms |
| Push P95 | 5.113 ms | 6.852 ms |
| Signaling queue P95 | 0.361 ms | 0.380 ms |
| JDBC acquire P95 | 0.585 ms | 0.658 ms |
| JDBC execute P95 | 0.365 ms | 0.224 ms |
| CPU JVM trong workload | 1109.4 ms | 1078.1 ms |
| Heap tại lúc lấy mẫu | 16,485,488 byte | 23,209,408 byte |
| Thread tại lúc lấy mẫu | 35 | 35 |
| Thời gian GC trong workload | 5 ms | 5 ms |
| Số execute JDBC đo được | 66 | 165 |

**ACK/push chưa nhanh hơn trong phép đo này.** Kiểm tra quyền/generation tại transaction tăng số SQL; không được diễn giải rằng cả backend đã tăng tốc. Cải thiện cảm nhận UI đã được kiểm tra độc lập bằng cách giữ commit server: tin pending xuất hiện khoảng 9.8–20.5 ms trong các lần chạy, trước khi server được phép ghi. Chưa có số UI trước/sau tương đương trên Railway hoặc thiết bị người dùng; không đưa phần trăm tăng tốc.

Media tạo bằng mã: AUTO đạt 1280×720; capture/decode/render đều khoảng 30 FPS trong bài HD. Bitrate nguồn tổng hợp thấp, không đại diện video người thật. Mẫu tải 5 giây ghi nhận JVM:

| Thành viên / người phát camera | CPU (core) | Heap MiB | GC | Thread đầu/cuối |
|---|---:|---:|---:|---:|
| 2 / 2 | 0.234 | 61.73 | 0 | 77 / 78 |
| 5 / 5 | 1.059 | 48.99 | 1 | 137 / 137 |
| 10 / 1 | 0.528 | 58.37 | 1 | 171 / 173 |

Đây là nhiều session test trong JVM, không phải tải 50 desktop client. Test policy vào phòng tăng 5 → 10 → 20 → 50, từ chối người 51; không tạo media PeerConnection ở test này. Cleanup 1001 tin trong 3 lô mất khoảng 17.95 ms trên H2, chưa bao gồm tệp lớn/Railway. Chưa đo đầy đủ bandwidth qua Internet, trần CPU của Go, thời gian encode riêng tách khỏi capture/decode/render, hoặc tải dài hạn 50 người cùng camera+voice+share. Không dùng các số trên để công bố sức chứa production.

## 7. Chạy local / NetBeans

1. Giữ private config/chứng chỉ đang dùng; không cần migration mới cho thay đổi này.
2. Dừng server cũ trước khi chạy bản mới; dùng JDK 21 trở lên như cấu hình Maven hiện tại.
3. NetBeans: chạy file `src/main/java/server/Main.java` (package `server`) để mở toàn bộ server; Go engine tự build lại khi source mới hơn binary. Chạy `src/main/java/client/Main.java` cho client.
4. Hoặc hai terminal PowerShell tại thư mục dự án:

```powershell
mvn -B -Dexec.mainClass=server.Main compile exec:java
mvn -B process-classes javafx:run
```

Không cần chạy script khởi tạo TLS lại khi chứng chỉ/private config hiện tại vẫn đúng.

Kiểm thử không chạm Railway:

```powershell
mvn -B test
# Build SFU riêng cho test, rồi chạy profile native với mmr.video.test.binary trỏ tới binary đó.
mvn -B -Pvideo-integration -Dmmr.video.test.binary=C:/path/to/test-sfu.exe -Dtest=VideoCallIntegrationTest,AudioCallIntegrationTest,ScreenCallIntegrationTest,AutoVideoHdIntegrationTest,NetworkStatsGroupIntegrationTest test
```

## Kết quả nghiệm thu cuối

Các nhóm đã kiểm chứng:

- Suite đầy đủ: 508 test, 0 failure/error, 2 skip; `target/runtime-complete-final.txt`.
- Native video/audio/screen/AUTO/nhóm 2–5–10 thành viên: 21/21, 0 skip; `target/runtime-release-media.txt`.
- Hồi quy UI/auth/theme/chat/phòng: 123/123; `target/runtime-final-followup.txt`.
- Cleanup/storage/capacity/legacy/file: 123 test, 0 failure/error, 2 skip; `target/runtime-storage-and-capacity-final.txt`.
- Guard object mồ côi và mở lại: 2/2; `target/runtime-orphan-guard-final.txt`.
- Catalog viewport/phòng cũ/quyền xem/revision gap và UI mở lại: 36/36; `target/runtime-catalog-window-verified.txt`.
- Bổ sung kiểm tra ownership legacy/file và UI media hợp nhất: 63/63; `target/runtime-owner-verified.txt`.
- Kiểm tra cuối sau các thay đổi ownership/phân trang video: 77 test, 0 failure/error, 1 skip; `target/runtime-final-acceptance.txt`. Bao gồm test mọi thành viên đều có thể xuất hiện khi chuyển trang với pin/screen, creator legacy lưu owner, từ chối ghi từ instance khác và giữ nguyên file tải cá nhân.
- `go test ./...`: PASS; `target/runtime-go-unsubscribe.txt`.
- Cặp benchmark trước/sau: `target/runtime-benchmark-before-final.txt`, `target/runtime-benchmark-after-final.txt`.

Các nhóm có test lặp lại để kiểm tra hồi quy; không cộng chúng thành số test độc lập. Suite đầy đủ 508 bài chạy trước các bổ sung ownership/phân trang cuối; các phần đó được chạy lại trong các nhóm 63/77 bài nêu trên. Hai skip là kiểm tra symlink Windows cần điều kiện hệ thống và benchmark truyền tệp opt-in.
