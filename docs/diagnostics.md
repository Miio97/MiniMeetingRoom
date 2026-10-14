# Nhật ký kiểm thử hiệu suất và độ ổn định

Module `common.diagnostics` tự bật khi chạy `server.AuthServer` hoặc giao diện JavaFX. Mỗi tiến trình tạo một file UTF-8 `.txt` riêng trong `logs/diagnostics/`. Mỗi dòng là một bản ghi JSON để đọc bằng trình soạn thảo hoặc phân tích bằng công cụ. Đường dẫn file được in ở terminal khi khởi động. Các log được Git bỏ qua và những lần kiểm thử cũ được giữ lại.

## Chạy một lần kiểm thử

Mở hai terminal tại thư mục project. Đặt cùng một mã kiểm thử trong cả hai terminal để nhóm log client và server; chọn mã mới cho lần chạy tiếp theo.

Terminal server:

```powershell
$env:MMR_DIAGNOSTICS_RUN_ID = 'baseline-001'
powershell -ExecutionPolicy Bypass -File .\scripts\start-server.ps1
```

Terminal client:

```powershell
$env:MMR_DIAGNOSTICS_RUN_ID = 'baseline-001'
powershell -ExecutionPolicy Bypass -File .\scripts\start-client.ps1
```

Thực hiện các thao tác cần kiểm thử, đóng client và dừng server bằng Ctrl+C. Khi gửi kết quả phân tích, gửi cả file client và server của lần đó, kèm các bước đã thực hiện, số máy, cấu hình mạng và điều kiện thử. `run_id` nhóm các tiến trình của một bài kiểm thử; hiện chưa có mã yêu cầu chung trên giao thức để ghép chính xác một yêu cầu client với một yêu cầu server.

## Số liệu hiện đã đo

| Nhóm | Nội dung |
|---|---|
| Môi trường | Phiên bản Java/JVM, hệ điều hành, kiến trúc, số CPU logic, heap tối đa, PID, vai trò tiến trình, mã kiểm thử |
| Tài nguyên JVM | Heap đang dùng/đã cấp phát, số thread JVM, số lần và thời gian GC trong khoảng lấy mẫu |
| CPU | CPU tiến trình và CPU hệ thống nếu JVM/hệ điều hành hỗ trợ |
| Client xác thực | Thời gian tạo ngữ cảnh TLS, kết nối TCP, TLS handshake, ghi yêu cầu và đọc/xác minh phản hồi, tổng thời gian, giai đoạn thất bại |
| Server xác thực | Thời gian kiểm tra database khi khởi động, TLS, chờ worker, xử lý yêu cầu, tổng thời gian; số kết nối nhận/từ chối/hoàn tất, worker đang chạy, hàng đợi và kết nối còn mở |
| Lỗi | Tên lớp exception và chuỗi nguyên nhân giới hạn, SQLState nếu hợp lệ, giai đoạn và mã kết quả cố định |
| Chất lượng nhật ký | Bản ghi bị bỏ vì hàng đợi đầy, giới hạn dung lượng, dấu bắt đầu/kết thúc và trạng thái worker lúc dừng |

`process_cpu_pct` là phần trăm trên tổng năng lực của tất cả CPU logic, tính từ thời gian CPU tiến trình trong khoảng lấy mẫu. Heap JVM không phải toàn bộ RAM/RSS của tiến trình. Số thread JVM không bao gồm mọi thread native mà một SDK media tương lai có thể tạo.

`response_ms` của client là thời gian ghi yêu cầu, chờ server/database, đọc và kiểm tra phản hồi xác thực. Đây là thời gian một vòng yêu cầu ứng dụng, không phải RTT mạng thuần và không phải độ trễ audio/video. `handler_ms` của server bao gồm công việc service/database, không tách riêng từng truy vấn SQL. Các thời lượng dùng đồng hồ đơn điệu; thời điểm UTC dùng để đối chiếu bài kiểm thử. Đồng hồ khác nhau trên các máy chưa đủ để suy ra độ trễ truyền một chiều.

## Thống kê kết nối trong phòng

Nhãn chất lượng ở góc phòng và cửa sổ "Thống kê mạng" dùng chung `client.network.NetworkMonitor` của từng client, bắt đầu khi vào phòng và dừng khi rời phòng. Đóng cửa sổ thống kê không dừng bộ đo; mở lại vẫn có lịch sử thực của phiên phòng đó. Không ghi các số liệu này vào MySQL và không mở thêm cổng hay gửi lưu lượng kiểm tra tốc độ.

- **Phản hồi (RTT)**: thời gian từ lúc ghi `ROOM_POLL` đến khi đọc/xử lý đủ phản hồi JSON, dùng đồng hồ đơn điệu. Không tính hàng đợi giao diện, tạo TLS context, kết nối TCP và bắt tay TLS; có tính xử lý server/database. Đây là thời gian phản hồi ứng dụng, không phải ping ICMP hay RTT media thuần.
- **Dao động RTT**: trung bình trị tuyệt đối chênh lệch của các cặp phản hồi liên tiếp trong 30 giây, không ghép cặp qua lỗi hoặc khoảng trống trên 3 giây. Cần ít nhất hai mẫu; không gọi đây là jitter RTP.
- **Bitrate ứng dụng**: byte thực sự ghi/đọc trên stream TLS sau giải mã, gồm JSON UTF-8 và tiền tố độ dài 4 byte, bao gồm chat đẩy từ server. Bộ đếm không chứa nội dung thông điệp. Tốc độ lấy tổng byte trong các bucket một giây gần nhất chia đúng thời gian quan sát, cửa sổ 5 đến dưới 6 giây; tách gửi/nhận và cộng hai chiều cho ô tổng. Không gồm header IP/TCP/TLS, ACK, dữ liệu truyền lại hay lưu lượng ứng dụng khác trên máy; không phải dung lượng tối đa của mạng.
- **Yêu cầu lỗi**: tỷ lệ yêu cầu TLS không hoàn thành do lỗi kết nối/timeout/đọc frame trên tổng yêu cầu hoàn tất trong 30 giây. Phản hồi hợp lệ từ chối đăng nhập/quyền phòng không bị tính thành lỗi mạng. Kết nối chat đẩy lâu dài không được tính thành một chuỗi yêu cầu; byte chat vẫn được đếm.
- **Packet loss / FPS / độ phân giải**: hiện hiển thị `--`. Chưa có engine media/RTP; socket TCP thông thường không cung cấp bộ đếm mất gói hoặc truyền lại cho module này. Không lấy tỷ lệ yêu cầu lỗi làm packet loss và không suy FPS từ animation giao diện. Xem [đặc tả TCP](https://www.rfc-editor.org/rfc/rfc9293.html) và [thống kê WebRTC](https://www.w3.org/TR/webrtc-stats/).

Biểu đồ chỉ chứa mẫu `ROOM_POLL` đã thực sự hoàn tất trong 30 giây, không chèn điểm giả hoặc giá trị 0 cho timeout. Trục thời gian dùng giây từ khi vào phòng; trục ms tự điều chỉnh, không cắt phản hồi chậm ở mức 100 ms. Đường biểu diễn ngắt khi yêu cầu lỗi hoặc không có mẫu quá 3 giây.

Quy tắc phân loại dùng thời gian phản hồi mới nhất, dao động và tỷ lệ yêu cầu lỗi: Tốt khi RTT < 80 ms, dao động < 20 ms và không có lỗi; Khá khi RTT từ 80 ms, dao động từ 20 ms hoặc có lỗi; Yếu khi RTT từ 160 ms, dao động từ 40 ms, tỷ lệ lỗi từ 10% hoặc phản hồi cuối đã cũ trên 3 giây. Một lần `ROOM_POLL` thất bại hoặc không có phản hồi trên 5 giây hiển thị Mất kết nối. Chưa có mẫu thì hiển thị Đang đo, không mặc định Tốt. Đây là đánh giá kết nối ứng dụng đến máy chủ, không chứng minh mạng vật lý có lỗi; server/database chậm cũng tăng RTT.

Kiểm thử thủ công: chạy server và hai client với hai tài khoản thật, vào cùng phòng rồi mở thống kê. Gửi chat để quan sát byte/tốc độ tăng; đóng và mở lại thống kê để kiểm tra lịch sử vẫn còn. Dừng server để kiểm tra trạng thái Mất kết nối và RTT `--`. Khởi động server lại, tạo/vào phòng mới để bắt đầu bộ đếm mới. Trên một máy, số ms thấp và bitrate nhỏ khi không chat là bình thường.

Test tự động dùng TLS localhost và H2 tạm: đếm byte cả hai chiều, xử lý EOF/lỗi ghi, dao động, cửa sổ thời gian, ngưỡng chất lượng, server chậm/ngắt kết nối, chat thực, đổi theme, reset theo phiên phòng và dừng bộ đo. Không chèn tài khoản/tin nhắn thử vào MySQL thật.

## Tích hợp với WebRTC và SFU sau này

Hiện media, signaling và SFU chưa triển khai; sự kiện khởi động ghi `media_status=not_implemented`. Module không lấy các số RTT/jitter/loss ngẫu nhiên của UI demo làm dữ liệu thực.

Khi có engine WebRTC/SFU, adapter gọi `Diagnostics.current().mediaSample(MediaSample)` với số liệu đo được. `MediaSample` nhận mã track dạng số cục bộ, loại AUDIO/VIDEO/SCREEN, độ dài cửa sổ đo, số gói và byte gửi/nhận/mất, RTT, jitter và số frame encode/decode/drop. Không dùng ID hoặc tên người dùng làm mã track.

- Counters là chênh lệch trong cửa sổ đo, không phải tổng từ lúc mở track. Khi counters reset hoặc nguồn đo thay đổi, bỏ cửa sổ đó và thiết lập mốc mới.
- Byte là payload RTP, không gồm header giao thức; giữ cùng cách tính giữa các bản thử.
- Bitrate được tính bằng `bytes * 8000 / interval_ms`, đơn vị bit/giây.
- Tỷ lệ mất gói nhận là `lost / (received + lost) * 100`; nếu chưa có mẫu số thì không ghi tỷ lệ.
- RTT và jitter dùng mili giây; adapter phải đổi đơn vị từ nguồn WebRTC/RTCP trước khi ghi.
- FPS encode và decode tính từ số frame tương ứng trong cửa sổ; không coi FPS decode là FPS thực sự hiển thị.
- Giá trị chưa đo được dùng `null` và được bỏ khỏi log. Không điền số 0 hoặc số mô phỏng để thay dữ liệu thiếu.

Ghi mẫu thống kê định kỳ, không gọi logger cho từng packet/frame. Các counter số như độ dài hàng đợi SFU, số track/subscriber, NACK/PLI và thời gian chuyển tiếp có thể bổ sung qua `event` khi triển khai thực tế; cần xác định đơn vị và phạm vi đo trước.

## Giới hạn và cấu hình

Logger ghi ở thread nền bằng hàng đợi có giới hạn. Khi hàng đợi đầy, sự kiện được bỏ và đếm lại thay vì chờ ghi đĩa trên luồng ứng dụng. Một file mặc định tối đa 20 MiB. Khi đạt giới hạn, `log.truncated` đánh dấu phần còn thiếu; footer ghi số sự kiện bị bỏ. Module không tự xóa log của lần thử trước; người dùng quản lý dung lượng thư mục khi cần.

Kết thúc bình thường có `run.end`. Nếu không có bản ghi này, lần chạy có thể bị buộc dừng, gặp lỗi ghi đĩa hoặc chưa kịp hoàn tất việc ghi; không coi đó là một log đầy đủ. Đóng logger chờ tối đa hai giây. Server chờ worker tối đa một giây và ghi `workers_terminated` để thể hiện có worker còn chạy khi dừng hay không. Các sự kiện kết nối có thể đến khác thứ tự; ghép bằng `connection_id` trong cùng tiến trình.

Module lọc trường nhạy cảm và chỉ nhận scalar có giới hạn; lỗi chỉ ghi loại, không ghi message/stack. Các điểm tích hợp xác thực không ghi username, email, mật khẩu, token, nội dung chat/media hoặc URL database. Không truyền request/response, cấu hình hay văn bản tự do vào API logger. Mã kiểm thử cũng chỉ nên là nhãn kỹ thuật.

| Biến môi trường | Giá trị mặc định | Ý nghĩa |
|---|---|---|
| `MMR_DIAGNOSTICS_ENABLED` | `true` | `false` để tắt ghi log |
| `MMR_DIAGNOSTICS_DIR` | `logs/diagnostics` | Thư mục xuất |
| `MMR_DIAGNOSTICS_RUN_ID` | UUID tự sinh | Nhóm các log của cùng bài thử |
| `MMR_DIAGNOSTICS_SAMPLE_SECONDS` | `5` | Khoảng lấy mẫu tài nguyên, từ 1 đến 60 giây |
| `MMR_DIAGNOSTICS_MAX_BYTES` | `20971520` | Giới hạn file, từ 8192 đến 104857600 byte |
| `MMR_DIAGNOSTICS_QUEUE_CAPACITY` | `1024` | Số sự kiện chờ ghi, từ 8 đến 8192 |

JVM properties tương ứng là `mmr.diagnostics.enabled`, `dir`, `runId`, `sampleSeconds`, `maxBytes`, `queueCapacity`, ưu tiên hơn biến môi trường. Khi so sánh hai phiên bản, giữ cùng cấu hình logger và cùng điều kiện tải. Ghi log cũng dùng tài nguyên; kết quả có logger phải được so sánh với kết quả có cùng chế độ ghi.
