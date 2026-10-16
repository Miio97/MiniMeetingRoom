# Truyền tệp Mini Meeting Room qua TCP/TLS

Tài liệu triển khai ngày 14/10/2026. Dịch vụ tệp được tích hợp trực tiếp vào dự án Java 21/JavaFX hiện có. Cấu hình sử dụng: server Java và client JavaFX chạy local, database MySQL tiếp tục ở Railway. Binary đi qua một kết nối TCP/TLS riêng và lưu trên máy server local; Railway lưu metadata và quyền phòng. Các chức năng media/SFU chưa có backend hoạt động trong phiên bản gốc, nên không có cơ sở xác nhận chất lượng voice/video thực tế sau thay đổi.

## 1. Phân tích và phương án tích hợp

Trước thay đổi, phần hoạt động thật gồm `AuthServer`, `AuthClient`, đăng ký/đăng nhập/đăng xuất, PBKDF2 và phiên MySQL. `server.room`, `server.chat`, `server.tcp`, `server.signaling`, `server.sfu` và repository tổng quát là lớp khung. Lobby, phòng và tab tệp dùng dữ liệu mô phỏng; nút gửi tệp chỉ thêm tên tệp mẫu.

Schema thực tế đã có `Nguoi_Dung`, `Phien_Dang_Nhap`, `Phong_Hop`, `Thanh_Vien_Phong`, `Tin_Nhan` và `Tep_Dinh_Kem`. Vì chưa có dịch vụ phòng, triển khai thêm thao tác tạo/tham gia/rời phòng tối thiểu để quyền truyền tệp có dữ liệu phía server. PIN băm bằng `PasswordHasher`; mã phòng do server sinh. Không dùng mã phòng hoặc userId do client khai để thay cho xác thực.

Phương án giữ nguyên JavaFX và TLS/JDBC có sẵn, thêm listener tệp tách biệt với auth. Sau khi hợp nhất phần phòng từ nhánh đồng đội, UI dùng `AuthClient`/`ApplicationService`/`RoomService` và DTO `common.room.RoomInfo` để quản lý phòng, đồng bộ thành viên; `FileTransferClient` chỉ được UI dùng cho tệp. `AuthServer.main` khởi động cả hai listener dùng chung chứng chỉ. API phòng cũ trên listener tệp còn giữ để tương thích các client/test trước đó. Không thêm dependency, không dùng HTTP, UDP, SFU hoặc base64 để truyền binary. Worker truyền tệp và worker điều khiển tách riêng, phù hợp triển khai một server với ổ đĩa bền vững.

```text
JavaFX client A ─── TLS 8443 ─── AuthServer ───────────┐
       │                                             │
       └────────── TLS 9001 ─── FileTransferServer ────┼── MySQL: phiên/quyền/metadata
                                      │              │
JavaFX client B ─── TLS 9001 ───────────┘              └── Tin_Nhan + Tep_Dinh_Kem
                                      │
                                      └── storage: manifest + .part + object đã xác minh
```

## 2. Protocol và luồng dữ liệu

Mỗi frame có phần cố định **32 byte**, mọi số dùng big endian:

| Trường | Số byte | Nội dung |
|---|---:|---|
| Magic | 4 | `0x4d4d5246` (`MMRF`) |
| Version | 2 | `1` |
| Type | 2 | Mã thông điệp |
| Header length | 4 | Độ dài JSON UTF-8, 2–16.384 byte |
| Payload length | 4 | 0–262.144 byte |
| Transfer ID | 16 | UUID gồm hai số 64 bit |
| Header | Theo length | Một JSON object, UTF-8 hợp lệ, JSON strict, độ sâu tối đa 16 |
| Payload | Theo length | Binary thô; chỉ frame chunk được có payload |

`readFully` xử lý TCP chia nhỏ hoặc gộp nhiều lần gửi. Kiểm tra giới hạn trước khi cấp phát bộ nhớ. Chunk mặc định 65.536 byte, server thông báo chunk được chấp nhận; checksum SHA-256 dạng hex. Tên gốc chỉ là metadata, không ghép vào đường dẫn lưu trên server.

| Nhóm | Thông điệp |
|---|---|
| Upload | `UPLOAD_INIT`, `UPLOAD_ACCEPT`, `UPLOAD_CHUNK`, `UPLOAD_COMPLETE`, `UPLOAD_FAILED` |
| Download | `DOWNLOAD_REQUEST`, `DOWNLOAD_ACCEPT`, `DOWNLOAD_CHUNK`, `DOWNLOAD_COMPLETE`, `DOWNLOAD_FAILED` |
| Điều khiển | `TRANSFER_STATUS`, `TRANSFER_CANCEL`, `ERROR` |
| Phòng/danh sách | `ROOM_CREATE`, `ROOM_JOIN`, `ROOM_LEAVE`, `ROOM_RESULT`, `FILE_LIST`, `FILE_AVAILABLE` |

Upload: client băm nguồn bằng streaming rồi gửi metadata; server xác thực token, quyền phòng, giới hạn và cấp offset đã lưu. Client gửi chunk nối tiếp theo offset. Server nhận đủ byte, băm lại file, force dữ liệu ra đĩa, chuyển `.part` sang tên object, rồi tạo message `TEP` cùng metadata trong một transaction. Chỉ khi thành công mới trả `UPLOAD_COMPLETE`. Client retry cùng UUID nhận lại kết quả cũ, không tạo thêm message.

Download: server tra fileId để lấy phòng thật của tệp, kiểm tra thành viên rồi stream binary. Client ghi tệp tạm, kiểm tra SHA-256 đầy đủ và chỉ thay thế đích sau khi xác minh thành công. Không tự mở/chạy nội dung được tải, kể cả `.exe`.

Client cùng phòng lấy metadata bằng `FILE_LIST` có cursor `afterId`, mỗi **3 giây**. Truy vấn tối đa 100 metadata/lần và phản hồi được cắt tiếp để vừa header 16 KiB. Đây là polling qua TLS, chưa phải push. File không được tự động phát binary cho mọi người. Message `TEP` được lưu vào `Tin_Nhan`; người dùng xem và tải file trong tab **Tệp**. Tab chat vẫn mô phỏng, chưa phải dịch vụ chat mạng hay lịch sử chat đồng bộ.

## 3. Xác thực, quyền và giới hạn tài nguyên

- Mỗi yêu cầu mở kết nối phải có token đang còn hiệu lực. Server băm token để tra `Phien_Dang_Nhap`, kiểm tra `Thu_Hoi_Luc`, `Het_Han_Luc` và tài khoản `HOAT_DONG`.
- Quyền tệp yêu cầu `Thanh_Vien_Phong.Roi_Phong_Luc IS NULL` và phòng `CHO`/`DANG_HOAT_DONG`. Chủ phòng cũng cần membership. Việc biết PIN/mã phòng chưa tạo quyền cho tới khi join thành công.
- Tạo/join/leave và công bố file dùng transaction. Join khóa dòng phòng để kiểm tra sức chứa và tránh membership trùng; dùng `READ COMMITTED` để tránh snapshot cũ trong MySQL khi chờ khóa.
- Phiên/quyền được kiểm tra lại trong quá trình stream khoảng mỗi giây và trước công bố/kết thúc. Resume kiểm tra lại phiên, người sở hữu transfer, phòng, kích thước và checksum.
- TLS 1.2/1.3 bắt buộc. Client kiểm tra chứng chỉ và hostname/SAN. Cấu hình tắt TLS hoặc đổi checksum sang thuật toán khác bị từ chối.
- Server giới hạn worker, hàng đợi điều khiển, transfer theo user, số upload còn dở, file size, quota và timeout. Watchdog đóng socket khi đọc/ghi không tiến triển. Một thư mục storage chỉ được một process giữ khóa.
- Storage dự trữ dung lượng theo kích thước khai báo, kiểm tra chunk không vượt kích thước, kiểm tra không gian đĩa sau khi tính phần dự trữ chưa ghi và chừa mặc định 16 MiB. Tổng upload dở bị giới hạn 1.024, kể cả file rỗng. Kiểm tra chống đường dẫn thoát root/liên kết. Không ghi DB cho mỗi chunk. Không gọi `readAllBytes` cho nội dung file.

Log chứa loại sự kiện, UUID, userId/roomId, byte và thời lượng phù hợp. Token và nội dung file không được ghi log. Lỗi SQL gửi về dưới dạng `DATABASE_UNAVAILABLE`, không trả chuỗi kết nối hoặc SQL cho client.

## 4. Storage, resume và xử lý lỗi giao dịch

```text
storage/
  .lock
  temporary/
    <transferId>.properties
    <transferId>.part
  rooms/<roomId>/files/<transferId>.bin
```

Manifest chứa metadata và thời điểm tạo, không chứa token. Offset resume là độ dài file trên đĩa, không tin offset client tự gửi. Sau restart, server đọc manifest và khôi phục upload còn hạn. Download resume xác minh hash phần đầu ở cả client/server trước khi truyền tiếp; file đích chỉ xuất hiện sau kiểm tra toàn bộ.

Nếu đã chuyển file sang object nhưng DB lỗi, object cùng manifest được giữ lại để retry. Không tự xóa object này khi cancel/cleanup vì DB có thể đã commit nhưng phản hồi bị mất. `TRANSFER_STATUS`, retry upload và tác vụ đối chiếu định kỳ mỗi 60 giây có thể dọn manifest cho transfer đã có metadata commit. Upload `.part` chưa được công bố sẽ được dọn sau thời hạn mặc định 24 giờ; transfer đang được giữ khóa không bị cleanup chen vào.

Chính sách `file.transfer.retention-minutes=20` tự động xóa file khi phòng ở trạng thái `DA_DONG` liên tục ít nhất 20 phút, tính từ `Ngay_Dong`. Tác vụ chạy mỗi 60 giây và quét theo trang 100 file cho đến hết; thời điểm xóa thường nằm trong khoảng 20–21 phút nếu server đang chạy và storage/DB hoạt động bình thường. Mở lại phòng đặt `Ngay_Dong=NULL`, hủy mốc cũ; khi phòng đóng lần nữa, 20 phút được tính lại từ lần đóng mới. File đã xóa trước khi mở lại không thể khôi phục. Đặt `retention-minutes=0` để tắt chính sách. Khóa cũ `retention-days` vẫn được hỗ trợ nếu không cấu hình khóa phút; khóa phút luôn được ưu tiên.

Trên UI, **Tạm dừng** đóng socket nhưng giữ dữ liệu để **Tiếp tục**. **Hủy** yêu cầu xóa upload chưa công bố hoặc xóa download tạm. Nếu upload đã commit trước yêu cầu hủy, UI giữ thông tin đã thành công. Nếu kết nối không đủ để dọn tệp, UI báo việc dọn chưa thành công.

UUID và đường dẫn nguồn upload hiện nằm trong bộ nhớ của màn hình: resume qua mạng/server restart hoạt động khi client còn giữ tác vụ; đóng ứng dụng hoặc rời màn hình sẽ mất khả năng khôi phục upload đó từ UI. Server vẫn dọn `.part` theo hạn. Download tạm nằm trên đĩa và có thể tiếp tục khi chọn lại cùng file/cùng thư mục đích. Chưa có màn hình hàng đợi bền vững để khôi phục tất cả tác vụ sau khi client restart.

## 5. Database và migration

Không tạo bảng file mới. `Tep_Dinh_Kem` tiếp tục lưu metadata và đường dẫn tương đối; binary nằm ngoài DB. Thêm `Transfer_Id VARCHAR(36) NULL` và ràng buộc unique `UQ_TepDinhKem_Transfer` để chống hoàn tất lặp. Thêm index cho membership theo phòng/user/trạng thái và message theo phòng. Chỉ insert metadata của file đã xác minh, vì vậy không cần thêm cột upload status vào bảng này.

- **Database mới:** chạy `database/schema.sql` một lần.
- **Database đã có schema cũ:** chọn đúng database hiện có trong công cụ MySQL, rồi áp dụng `database/migrations/001_file_transfer.sql` một lần. Migration không chứa `USE` để hỗ trợ tên database khác trên Railway. Không chạy lại `schema.sql` trên dữ liệu có sẵn; không áp dụng cả schema mới và migration.
- Migration không tự chạy khi server khởi động. Phiên làm việc này không áp dụng migration hoặc ghi test lên MySQL/Railway đang dùng. Hãy kiểm tra backup và đúng database đích trước khi áp dụng.
- Các dòng attachment cũ có `Transfer_Id = NULL`. Migration không di chuyển binary cũ và không tự bổ sung checksum; cần kiểm tra/di chuyển những file này riêng nếu có.

## 6. Cấu hình và chạy

Yêu cầu: JDK 21 trở lên, Maven hoặc Maven trong NetBeans, kết nối từ máy local tới MySQL Railway hiện có và quyền ghi vào một thư mục storage bền vững trên máy local. Không cần MySQL local hoặc listener cổng 3306 trên máy để chạy cấu hình này. Dữ liệu storage phải được giữ qua các lần deploy/restart và sao lưu cùng metadata. Dung lượng quota là giới hạn cấu hình, không phải dung lượng ổ đĩa được cấp phát sẵn.

Server đọc `config/auth-server.properties` hoặc JVM property `mmr.server.config`; client đọc `config/auth-client.properties` hoặc `mmr.client.config`. File server thật được Git bỏ qua và giữ riêng các khóa `db.url`, `db.user`, `db.password` của Railway. Client chỉ có địa chỉ server local, port và public certificate; không kết nối JDBC và không cần tài khoản database. Không sao chép file server chứa mật khẩu DB sang client.

Giữ host public, port và tên database trong kết nối Railway hiện có; JDBC URL dùng dạng `jdbc:mysql://<host-public>:<port>/<database>?sslMode=REQUIRED`, không dùng nguyên chuỗi bắt đầu bằng `mysql://`. Bản mẫu server chứa placeholder Railway; thông tin thật nằm riêng trong file bị Git bỏ qua. Kết nối hiện tại đã được xác minh qua TLS bằng truy vấn chỉ đọc: cả 7 bảng đều truy cập được, nhưng Railway chưa có cột `Transfer_Id` và cần áp dụng migration trước khi bật đầy đủ dịch vụ tệp. `MMR_DB_URL`, `MMR_DB_USER`, `MMR_DB_PASSWORD` nếu đặt sẽ ưu tiên hơn file, kể cả giá trị rỗng. Cấu hình file được nạp một lần; khởi động lại server sau khi chỉnh.

Các giá trị mặc định trong `TransferConfig`:

| Khóa server | Mặc định | Ý nghĩa |
|---|---|---|
| `file.transfer.enabled` | `true` | Bật dịch vụ và kiểm tra migration |
| `file.transfer.bind` | Theo `auth.bind` | Bản mẫu đặt rõ `127.0.0.1` |
| `file.transfer.port` | `9001` | Listener TCP/TLS riêng |
| `file.transfer.tls-enabled` | `true` | Bắt buộc TLS |
| `file.transfer.checksum` | `SHA-256` | Bắt buộc SHA-256 |
| `file.transfer.chunk-size` | `65536` | Byte/chunk, phạm vi 4 KiB–256 KiB |
| `file.transfer.max-file-size-mb` | `1024` | Đơn vị MiB, tối đa một file |
| `file.transfer.storage-quota-mb` | `10240` | Đơn vị MiB, gồm dung lượng dự trữ upload |
| `file.transfer.max-concurrent-transfers` | `8` | Tổng upload/download đang chạy |
| `file.transfer.max-transfers-per-user` | `3` | Upload/download đang chạy của một user, tính chung |
| `file.transfer.max-pending-per-user` | `4` | Upload chưa hoàn tất của một user |
| `file.transfer.socket-timeout-ms` | `30000` | Timeout không tiến triển |
| `file.transfer.storage-path` | `storage` | Tính từ thư mục làm việc nếu là đường dẫn tương đối |
| `file.transfer.resume-enabled` | `true` | Cho phép giữ và tiếp tục dữ liệu dở |
| `file.transfer.expiry-hours` | `24` | Hạn upload dở tính từ lúc tạo |
| `file.transfer.retention-minutes` | `20` | Xóa file sau 20 phút đóng phòng; mở lại hủy mốc cũ; 0 tắt tự xóa |
| `file.transfer.connections-per-ip-minute` | `600` | Gồm cả polling; điều chỉnh khi nhiều client dùng chung IP/NAT |

Biến server đổi dấu `.`/`-` thành `_` và thêm `MMR_`, ví dụ `MMR_FILE_TRANSFER_PORT`, `MMR_FILE_TRANSFER_STORAGE_PATH`. Riêng client dùng `MMR_FILE_HOST`, `MMR_FILE_PORT`, `MMR_FILE_TIMEOUT_MILLIS`. Client lấy host từ `file.transfer.host` nếu đặt, nếu không dùng `auth.host`; dùng `auth.certificate` cho TLS. Cổng file client và server phải khớp, kể cả khi dùng biến môi trường.

Chạy server local sau khi kiểm tra schema/migration trên Railway và giữ đúng cấu hình kết nối Railway:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-server.ps1
```

Script tạo chứng chỉ phát triển nếu chưa có và chạy `server.AuthServer`, hiện cũng khởi động file server. Chờ cả hai thông báo sẵn sàng cho auth và file. Mở terminal khác:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-client.ps1
```

Client local và bản mẫu dùng `auth.host=localhost`; cấu hình hiện tại đặt thêm `file.transfer.host=localhost`, cổng auth 8443 và cổng tệp 9001. Nếu muốn chạy riêng auth trước khi áp dụng migration, đặt `file.transfer.enabled=false`; tạo/join phòng và tab tệp sẽ không khả dụng trong chế độ này.

Để chạy LAN/Internet:

1. Đặt cả `auth.bind` và `file.transfer.bind` thành địa chỉ mạng phù hợp; bản mẫu file bind `127.0.0.1` cần đổi riêng. Cho phép TCP **8443** và **9001** trên firewall. Chỉ mở cổng DB cho các kết nối server thực sự cần.
2. Cấp chứng chỉ có SAN khớp host/IP mà client sử dụng. Với LAN phát triển, có thể dùng `scripts/init-local-tls.ps1 -CertificateHost <host-hoặc-IP>`. Script chỉ tạo keystore khi chưa tồn tại; khi đổi SAN cần dùng keystore mới. Giữ private key và mật khẩu trên server, chỉ chuyển public certificate cho client.
3. Đặt `auth.host`, tùy chọn `file.transfer.host`, các port và `auth.certificate` trên từng client. Production vẫn bật TLS và hostname verification; không dùng lựa chọn trust-all.
4. Cấp persistent storage đủ dung lượng và quyền ghi cho tiến trình. Một instance giữ một storage root. Bản triển khai này chưa hỗ trợ nhiều server chia sẻ cùng root hoặc phân phối object giữa nhiều node.

Trong cấu hình hiện tại, máy local chạy Java mở listener và lưu tệp; Railway chỉ chạy database. Thay đổi địa chỉ client/server sang localhost không thay đổi nơi lưu database. Phần LAN/Internet ở trên là hướng dẫn tùy chọn nếu sau này chuyển phạm vi kết nối; không có tác vụ deploy ra Internet hoặc cấu hình firewall được tự thực hiện trong thay đổi này.

## 7. Kiểm tra bằng nhiều client

1. Chạy một server và hai client; đăng nhập hai tài khoản khác nhau.
2. Client A tạo phòng, nhập tên/PIN; sao chép mã phòng server cấp cho B. B nhập đúng mã/PIN để join. Mở tab **Tệp** ở cả hai.
3. A gửi TXT, PDF, PNG, MP4, ZIP, EXE, file rỗng và file có tên Unicode. B thấy metadata sau một chu kỳ polling hoặc nhiều chu kỳ nếu danh sách dài, bấm tải và chọn đích.
4. So sánh SHA-256 nguồn/đích bằng `Get-FileHash -Algorithm SHA256`. UI báo thành công chỉ sau xác minh.
5. Dùng tài khoản C ở phòng khác: danh sách không có file của A; API tải fileId phòng A phải trả `PERMISSION_DENIED`.
6. Thử tạm dừng/tiếp tục; ngắt socket hoặc restart server giữa upload rồi tiếp tục tác vụ còn mở ở client. Thử download dở và chọn lại đích; sửa dữ liệu `.part` sẽ gây `PREFIX_MISMATCH`/checksum lỗi.
7. Chạy nhiều client với tài khoản riêng để kiểm tra tải lên/tải xuống đồng thời trong quota. Mỗi tài khoản mặc định có tối đa ba lượt upload/download chạy cùng lúc (tính chung). Giới hạn bốn upload chưa hoàn tất bao gồm cả lượt đang chạy và đang tạm dừng; ví dụ ba lượt đang gửi cộng một lượt tạm dừng đã chiếm đủ bốn phiên. File gửi thành công không còn chiếm phiên upload dở, nên đây không phải giới hạn tổng số file được gửi trong ngày hay trong phòng.

## 8. Kiểm thử và số liệu đo

Lệnh kiểm tra thông thường:

```powershell
mvn -B test
```

Test dùng H2 cô lập và TLS socket loopback, không sửa MySQL đang dùng. Fixture repository/integration đọc schema thật và bỏ chỉ các chỉ thị MySQL không dùng được trên H2. Các nhóm kiểm thử đã được thêm:

| Nhóm | Tệp test và nội dung |
|---|---|
| Session | `SessionServiceTest`: token băm, hết hạn, thu hồi, user khóa, lỗi DB |
| Room/metadata | `FileMetadataRepositoryTest`: PIN, sức chứa, join đồng thời, rời phòng, truy cập chéo phòng, transaction rollback, idempotency, cursor, Unicode |
| Protocol | `TransferProtocolTest`: TCP chia frame, giới hạn length, JSON/UTF-8/độ sâu, payload sai loại |
| Storage | `FileStorageServiceTest`: quota, dự trữ đĩa, path traversal, checksum, cancel/expiry, restart, khóa process, hoạt động hash/shutdown |
| Client | `FileTransferClientTest`: nguồn thay đổi, resume, chunk sai thứ tự, checksum đích, lỗi quyền, cancel và đóng socket |
| Tích hợp | `FileTransferIntegrationTest`: các loại file và rỗng, hai user cùng phòng, năm upload/năm download cùng lúc, trộn upload/download, mất kết nối, restart, quyền/session, DB lỗi sau promote, phục hồi manifest đã commit, retention và retry lỗi xóa đĩa |

**Sau khi xử lý merge: `mvn -B package` thành công lúc 20:07:16 ngày 14/10/2026 — 129 test, 0 lỗi, 0 thất bại, 2 skip (127 pass).** Hai trường hợp skip là benchmark không bật opt-in và kiểm tra symlink do quyền Windows; benchmark đã chạy thành công riêng cho cả năm kích thước bên dưới. Suite gồm auth, phòng, diagnostics, JavaFX, interface storage, recovery và retention. `RoomFileTransferCompatibilityTest` kiểm tra hai listener dùng chung quyền phòng: rời phòng/đăng xuất chặn truy cập tệp, mất quyền giữa upload ngăn công bố file, người gửi rời vẫn giữ file cho thành viên còn lại. `AuthFlowTest` kiểm tra đăng xuất khi đang trong phòng gửi LEAVE_ROOM rồi LOGOUT qua AuthClient. Tác vụ tệp được dừng trước khi rời phòng và khôi phục nếu leave thất bại. Báo cáo chi tiết nằm trong `target/surefire-reports`, log `target/merge-final-build.log`, artifact `target/MiniMeetingRoom-1.0-SNAPSHOT.jar`. JAR này cần dependency như Maven cấu hình; cách chạy chuẩn là các script hoặc `javafx:run`/`exec:java`, không phải standalone fat JAR. Ảnh kiểm tra JavaFX: `target/qa/file-tab.png`. Chưa tự động thao tác hộp thoại FileChooser của hệ điều hành.

Benchmark là opt-in:

```powershell
mvn -B '-Dtest=FileTransferIntegrationTest#measuredStreamingSizes' '-Dmmr.performance=true' '-DargLine=-Xmx256m' test
```

Số liệu dưới đây lấy từ `target/file-transfer-performance.csv`, tạo lúc **13:58:42 ngày 14/10/2026 (Asia/Saigon)**. Lần benchmark này thành công với heap tối đa 256 MiB, file 1/10/100/500/1.024 MiB:

| MiB | Upload (s) | Upload (MiB/s) | Download (s) | Download (MiB/s) | CPU process (ms) | Heap peak tổng các pool (MiB) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 0,186 | 5,37 | 0,067 | 14,88 | 703,1 | 82,7 |
| 10 | 0,222 | 44,97 | 0,082 | 121,36 | 1.078,1 | 98,3 |
| 100 | 0,589 | 169,78 | 2,207 | 45,31 | 3.296,9 | 97,5 |
| 500 | 1,666 | 300,11 | 2,505 | 199,60 | 5.671,9 | 96,4 |
| 1.024 | 3,353 | 305,43 | 4,324 | 236,83 | 10.703,1 | 95,9 |

Đây là TLS loopback, server/client/test cùng JVM, DB H2 trong RAM. File nguồn được tạo bằng seek rồi ghi byte zero ở cuối nên có nội dung zero và có thể sparse; cache hệ điều hành/đĩa tác động mạnh. Thời gian upload bắt đầu **sau** `prepareUpload` nên không gồm hash ban đầu của nguồn; CPU process đo cả chuẩn bị, truyền và kiểm tra checksum. Heap là tổng peak từng pool, không phải RSS toàn hệ điều hành hay peak đồng thời chính xác. Chưa đo disk I/O phần cứng, CPU%, RAM tổng tiến trình, mạng WAN, MySQL thật hoặc throughput dưới tải nhiều client trên máy khác. Không dùng các con số này như cam kết tốc độ production.

## 9. Các file thay đổi chính

Đường dẫn tính từ root repository:

| File mới | Vai trò |
|---|---|
| `src/main/java/common/filetransfer/TransferProtocol.java` | Framing binary, mã thông điệp, giới hạn đầu vào |
| `src/main/java/common/filetransfer/FileMetadata.java` | Metadata công khai, không có đường dẫn storage/token |
| `src/main/java/common/filetransfer/RoomInfo.java` | Phòng thật từ backend |
| `src/main/java/common/filetransfer/TransferException.java` | Mã lỗi nghiệp vụ an toàn |
| `src/main/java/server/auth/SessionService.java` | Tra token hiện có thành userId phía server |
| `src/main/java/server/filetransfer/TransferConfig.java` | Cấu hình và validation giới hạn |
| `src/main/java/server/filetransfer/FileTransferServer.java` | TLS listener, worker, admission, timeout, cleanup |
| `src/main/java/server/filetransfer/FileTransferService.java` | Điều phối control/upload/download, reauth và reconciliation |
| `src/main/java/server/filetransfer/TransferStorage.java` | Interface storage để bổ sung backend khác mà không gắn service với đường dẫn đĩa |
| `src/main/java/server/filetransfer/FileStorageService.java` | Streaming disk, checksum, quota, manifest/resume, khóa process |
| `src/main/java/server/filetransfer/FileMetadataRepository.java` | Phòng/quyền, metadata, transaction và idempotency |
| `src/main/java/server/filetransfer/FileRetentionService.java` | Dọn file phòng đã đóng theo chính sách quản trị, retry khi lỗi đĩa |
| `src/main/java/client/filetransfer/FileTransferClient.java` | TLS API, streaming nền, progress, cancel và resume |
| `database/migrations/001_file_transfer.sql` | Migration tương thích DB có sẵn |
| `src/test/java/server/auth/SessionServiceTest.java` | Kiểm thử phiên |
| `src/test/java/server/filetransfer/{FileMetadataRepositoryTest,FileStorageServiceTest,FileTransferIntegrationTest}.java` | Kiểm thử server và vòng truyền thật |
| `src/test/java/common/filetransfer/TransferProtocolTest.java` | Kiểm thử framing |
| `src/test/java/client/filetransfer/FileTransferClientTest.java` | Kiểm thử client độc lập |
| `docs/file-transfer.md` | Báo cáo, vận hành, kiểm thử và giới hạn |

| File chỉnh sửa | Vai trò |
|---|---|
| `src/main/java/server/AuthServer.java` | Khởi động hai listener; preflight schema và shutdown |
| `src/main/java/server/config/ServerConfig.java` | Chuẩn hóa tên biến môi trường chứa dấu gạch ngang |
| `src/main/java/client/UI/SceneManager.java` | Giữ phiên/phòng server, tác vụ create/join/leave và vòng đời client |
| `src/main/java/client/UI/components/FilePanel.java` | Nối tab hiện tại với chooser, progress, download, pause/resume/cancel và polling |
| `src/main/java/client/UI/screens/{LobbyScreen,MeetingScreen}.java` | Chuyển luồng phòng sang backend và quản lý vòng đời FilePanel |
| `src/main/java/client/UI/dialogs/CreateRoomDialog.java` | Dùng mã server, thể hiện giới hạn chức năng khóa phòng |
| `src/test/java/client/UI/AuthFlowTest.java` | Hồi quy auth và kiểm thử JavaFX tạo phòng, tab Tệp với metadata thật, leave/logout qua TLS/H2 |
| `database/schema.sql` | Schema mới có transferId/index |
| `config/auth-server.properties.example`, `config/auth-client.properties.example` | Cấu hình mẫu file transfer |
| `.gitignore` | Bỏ qua storage runtime |
| `README.md` | Điểm bắt đầu và liên kết tài liệu |

## 10. Hạn chế và việc chưa xác minh

- Chat văn bản, media và thiết bị vẫn có thành phần demo; danh sách thành viên đã được đồng bộ từ `RoomService`. Chưa có push event dùng chung cho chat/room. Không thể nghiệm thu cuộc gọi voice/video thật từ source ban đầu.
- Chưa có heartbeat/lease thành viên. Nếu ứng dụng bị kill hoặc mất mạng trước khi leave thành công, membership DB có thể còn active, giữ chỗ trong phòng và quyền cho lần đăng nhập hợp lệ sau. Rời phòng bình thường qua `AuthClient` thu hồi membership; người cuối cùng rời sẽ đóng phòng và trạng thái đóng chặn truy cập tệp. Chưa có luồng host khóa/kick đồng bộ đầy đủ qua server.
- Room membership hiện gắn user, không gắn từng phiên/thiết bị. Hai client cùng tài khoản chia sẻ membership; một client leave ảnh hưởng client còn lại. Dùng tài khoản riêng cho kiểm thử nhiều người.
- Chưa lưu bền vững danh sách upload ở client, chưa tự khôi phục UI tác vụ upload khi client restart. Đã có resume server và API cần giữ UUID/metadata nguồn.
- Retention mặc định 20 phút sau khi phòng đóng; dựa vào mốc DB nên tiếp tục hoạt động sau khi server khởi động lại. Các phòng đã đóng quá hạn từ trước cũng được dọn. Phòng phải có trạng thái `DA_DONG` và `Ngay_Dong` hợp lệ; đóng ứng dụng mà chưa cập nhật được DB không tạo mốc đóng giả.
- Chưa áp dụng migration lên MySQL/Railway thật, chưa kiểm thử hai máy qua LAN/WAN, ổ đĩa vật lý thật sự đầy, hoặc crash mất điện. Test quota và lỗi metadata là mô phỏng có kiểm soát.
- Chưa chạy soak test kéo dài để khẳng định không rò rỉ tài nguyên dưới mọi tình huống. Socket/stream được đóng theo scope, pool/hàng đợi có giới hạn; cần theo dõi thêm khi vận hành thật.

### Dọn file theo vòng đời phòng

`FileRetentionService` kiểm tra lại trạng thái và `Ngay_Dong` dưới khóa dòng `Phong_Hop` trước mỗi lần xóa; cả hai luồng mở lại phòng dùng cùng dòng này. Một danh sách ứng viên đọc trước khi mở lại không đủ quyền xóa file. Khóa được giữ qua thao tác xóa object và metadata để lượt mở lại không chen ngang một lần xóa đang thực hiện.

File đang có stream đọc hoặc upload đang giữ khóa phiên được bỏ qua và thử lại ở lượt sau. Lỗi xóa đĩa rollback thay đổi metadata để file chưa xóa vẫn còn khi mở lại phòng. Xóa object là idempotent: nếu DB lỗi sau khi object đã bị xóa, lượt sau tiếp tục dọn metadata. Message được đánh dấu xóa để giữ lịch sử; metadata attachment và nội dung file bị xóa, quota được giải phóng.

Upload dở (`.part`, manifest) và object đã promote nhưng chưa công bố cũng được dọn sau cùng thời hạn, khi không còn worker sử dụng. Trước đó đối chiếu `Transfer_Id` với DB để giữ đúng file đã commit nhưng chưa dọn manifest. Đây là thao tác quản trị nội bộ, không thay đổi giới hạn hủy upload của client. File thuộc phòng đang mở và bản đã tải về máy người dùng không bị xóa bởi chính sách này.

Kiểm chứng retention 16/10/2026: chạy các nhóm test file transfer/storage/metadata/config và cả hai RoomService — 91 test, 0 failure, 0 error, 2 skip (benchmark opt-in và symlink Windows). Bao phủ ranh giới 20 phút, mở lại/đóng lại, ứng viên xóa cũ, stream đang đọc, upload đang giữ khóa, upload dở và promote chưa công bố sau restart, hơn 100 file và retry lỗi đĩa. Log: logs/retention-check.log.
