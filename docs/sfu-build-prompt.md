# Prompt xây dựng SFU cho MiniMeetingRoom

Bạn là kỹ sư phụ trách triển khai tính năng họp trực tuyến cho dự án MiniMeetingRoom hiện có. Hãy đọc mã nguồn, thiết kế, viết mã, tích hợp và kiểm thử theo các yêu cầu dưới đây. Tôi muốn một hệ thống chạy được với âm thanh và hình ảnh thật, có số liệu để cải thiện qua từng lần kiểm thử.

## 1. Mục tiêu và phạm vi “tự xây SFU”

- Tự triển khai logic SFU: quản lý publisher/subscriber, định tuyến track, lựa chọn luồng nhận, xử lý người tham gia/rời phòng, hàng đợi, chính sách chuyển tiếp và phục hồi kết nối.
- Được sử dụng thư viện WebRTC tầng thấp để xử lý ICE, DTLS-SRTP, RTP/RTCP và codec. Ghi rõ phần nào thư viện cung cấp, phần nào dự án tự viết. Không tuyên bố đã tự viết toàn bộ WebRTC.
- Không thay bài toán bằng việc tích hợp SFU hoàn chỉnh như LiveKit, mediasoup, Janus hoặc Jitsi rồi gọi đó là SFU tự xây.
- SFU chuyển tiếp media đã mã hóa codec, không giải mã rồi mã hóa lại âm thanh/video cho từng người nhận. Phân biệt mã hóa codec với bảo vệ đường truyền SRTP: mỗi kết nối WebRTC có ngữ cảnh truyền tải/bảo mật riêng; không phát lại nguyên gói SRTP của người gửi cho mọi người nhận.
- Client kết nối media đến SFU, không tạo mesh kết nối trực tiếp giữa tất cả client.
- Không thay WebRTC bằng gửi video Base64/ảnh liên tục qua TCP hoặc WebSocket. Không mặc định JavaFX WebView có khả năng WebRTC như Chrome; phải kiểm chứng môi trường thực tế nếu đề xuất dùng nó.
- Ưu tiên chạy trực tiếp trên Windows khi phát triển, không yêu cầu Docker. Docker là bước đóng gói tùy chọn về sau.
- Giữ JavaFX làm giao diện ứng dụng. MVP tập trung một server, một phòng rồi mở rộng nhiều phòng; chưa làm ghi hình, livestream, chia cụm hay mở rộng đa vùng.

## 2. Hiện trạng cần kiểm tra trước khi sửa

Workspace: `C:\MMT\MiniMeetingRoom`. Các đường dẫn tương đối bên dưới tính từ workspace này. Kiểm tra lại hiện trạng vì mã nguồn có thể đã thay đổi sau ngày soạn prompt.

- Maven, Java release 21, JavaFX 21.0.2; `pom.xml` hiện có MySQL Connector/J, Gson, JUnit 5 và H2 cho test. Chưa có dependency WebRTC.
- Client vào từ `client.Main`, giao diện khởi động ở `client.UI.MainApp`, điều hướng ở `client.UI.SceneManager`.
- `src/main/java/server/AuthServer.java` là server xác thực TCP/TLS, mặc định local `127.0.0.1:8443`.
- `client/auth/AuthClient.java`, `common/auth/*`, `server/auth/AuthService.java` đã làm đăng ký, đăng nhập, đăng xuất thật.
- Auth hiện xử lý một yêu cầu trên mỗi kết nối TLS; `AuthWire` dùng 4 byte độ dài + JSON, tối đa 16 KiB. Không dùng worker xác thực hiện có để giữ các kết nối signaling dài hạn.
- Token đăng nhập là token ngẫu nhiên dạng opaque, không phải JWT. `SceneManager` giữ token trong bộ nhớ; DB lưu SHA-256 token trong `Phien_Dang_Nhap`, có hạn dùng và thời điểm thu hồi. `AuthService` hiện chưa có API kiểm tra token dành cho signaling.
- Tiếp tục dùng MySQL Railway theo cấu hình hiện có. Không đổi sang database local, không đưa thông tin đăng nhập DB vào client hoặc log, không chạy lại toàn bộ `database/schema.sql` trên Railway.
- Schema đã mô tả `Nguoi_Dung`, `Phien_Dang_Nhap`, `Phong_Hop`, `Thanh_Vien_Phong`. Kiểm tra schema thực tế bằng thao tác chỉ đọc trước khi quyết định cần migration.
- Các lớp `server/sfu.java`, `server/signaling.java`, `client/webrtc.java`, `client/signaling.java`, `server/room.java`, `client/room.java` hiện là khung trống. Đọc trước khi thay thế; không coi chúng là hệ thống media đã hoạt động.
- `LobbyScreen`, `CreateRoomDialog`, `PreJoinScreen`, `MeetingScreen`, `VideoTile`, `DeviceSettingsDialog`, `NetworkStatsDialog` còn dùng dữ liệu hoặc hiệu ứng mô phỏng.
- Giới hạn 2–250 người trên giao diện demo không phải năng lực SFU đã kiểm chứng.
- Đã có `common/diagnostics/Diagnostics.java`, `MediaSample.java`, hướng dẫn `docs/diagnostics.md`, log `.txt` tại `logs/diagnostics/`.
- Script đang dùng: `scripts/start-server.ps1`, `scripts/start-client.ps1`, `scripts/init-local-tls.ps1`, `scripts/common.ps1`.

Đọc `AGENTS.md` nếu có, kiểm tra `git status`, giữ nguyên những thay đổi không thuộc nhiệm vụ. Không chỉnh sửa bản dự án UI demo ở thư mục Downloads. Chạy bộ test hiện có làm mốc; phân biệt lỗi đã có từ trước với lỗi do thay đổi mới.

## 3. Kiểm chứng công nghệ trước khi triển khai diện rộng

Ưu tiên giải pháp phù hợp Java 21/JavaFX và Windows x64. Trước khi chốt dependency:

1. Tra tài liệu và mã nguồn chính thức, xác minh phiên bản, license, native binaries và cách build. Không tự bịa API hoặc chọn bản `latest` không cố định.
2. Tách hai nhu cầu: WebRTC phía client cần capture/render thiết bị; engine phía SFU cần đọc/ghi RTP đã mã hóa codec, RTCP feedback và kiểm soát transport. Một Java wrapper có thể đủ cho client nhưng chưa chắc đủ cho SFU.
3. Có thể đánh giá `webrtc-java` cho client, nhưng phải kiểm chứng riêng API packet-level trước khi cân nhắc dùng nó ở SFU. Không dùng API frame đã giải mã để giả làm SFU chuyển tiếp RTP.
4. Làm thử nghiệm nhỏ chứng minh: nạp được native library; thiết lập WebRTC; một publisher gửi track đến server và một subscriber khác nhận qua server mà server không transcode; đọc được thống kê; đóng sạch tài nguyên.
5. Dùng media tạo sẵn cho test tự động và công khai nguồn đó. Phần kiểm thử camera/microphone thật phải do người dùng chủ động bật trong giao diện. Kiểm chứng codec chung, ưu tiên Opus và VP8 nếu engine thực tế hỗ trợ.
6. Nếu phương án toàn Java thiếu API cần thiết, ghi rõ bằng chứng và phương án thay thế. Có thể đề xuất media service riêng dùng Go/Pion hoặc native bridge, nhưng nêu chi phí build, vận hành, tích hợp và hỏi tôi chốt thay đổi runtime trước khi chuyển kiến trúc. Không tự đổi toàn bộ backend sang ngôn ngữ khác.

Đầu ra bước này: quyết định kiến trúc ngắn gọn, sơ đồ luồng auth/signaling/media, dependency đã xác minh và kết quả chạy thử. Đây là cổng kiểm chứng tính khả thi; không được tiếp tục bằng hàng loạt class giả khi media engine chưa chạy được.

## 4. Phân chia module và vòng đời

Tách tối thiểu các trách nhiệm sau; tên lớp có thể điều chỉnh theo repository:

- `SessionValidator`: kiểm tra phiên đăng nhập phía server.
- `RoomService` / `RoomRepository`: phòng, PIN, quyền, membership và lịch sử cần lưu.
- `SignalingServer` / `SignalingClient`: kênh điều khiển kết nối lâu dài.
- `SfuEngine` / `MediaRouter`: chuyển tiếp track và quản lý subscription.
- `WebRtcClient` / `DeviceService`: PeerConnection, capture và phát media trên client.
- Adapter JavaFX: nối sự kiện/khung hình với giao diện.
- `MediaDiagnosticsAdapter`: chuyển số liệu thật sang module log hiện có.

Không dồn toàn bộ logic vào `AuthServer.java` hoặc `SceneManager.java`. Auth, signaling và media cần tách trách nhiệm dù có thể cùng JVM. Chỉ có một chủ sở hữu logger cho mỗi tiến trình.

Ưu tiên package như `server.media`, `client.media`, `common.signaling`, `common.meeting`. Kiểm tra xung đột giữa các lớp khung trống hiện có (`server.sfu`, `client.webrtc`, `common.protocol`, v.v.) và package cùng tên trước khi tổ chức lại.

Quy định rõ ai sở hữu room, transport, track, executor, timer và native buffer. Đóng tài nguyên idempotent khi rời phòng, logout, lỗi kết nối hoặc thoát ứng dụng. Chuyển từ pre-join sang meeting không được vô tình đóng engine đang được dùng chung chỉ vì màn hình cũ gọi `dispose()`.

## 5. Xác thực và quản lý phòng thật

- Bổ sung kiểm tra token server-side dựa vào hash, hạn dùng, thu hồi và trạng thái tài khoản. Không chỉ kiểm tra `SceneManager.hasSession()` hay tin user ID/role do client tự gửi.
- Chỉ cho phép signaling, publish và subscribe sau khi xác thực và kiểm tra membership của đúng phòng.
- Xác định rõ cách xử lý token hết hạn/thu hồi trong cuộc gọi đang diễn ra. Logout cần kết thúc quyền dùng media; nếu dùng cache xác thực phải có thời hạn/invalidation và đo được độ trễ thu hồi.
- Chỉ truyền token qua kênh bảo mật; không đưa token vào URL, log hoặc thông báo lỗi.
- Làm tạo phòng, tham gia bằng mã/PIN, rời phòng, liệt kê thành viên và đồng bộ trạng thái mic/camera/share. Dùng DTO thật thay `MockData.Room`/`Participant` trong luồng họp thật.
- Dùng các bảng phòng/membership sẵn có khi phù hợp; PIN lưu dạng hash. Không lưu media vào MySQL, không truy vấn DB trên từng RTP packet.
- Server quyết định host, khóa phòng, giới hạn người và quyền mời ra. Xử lý đồng thời nhiều yêu cầu join để không vượt capacity; ghi rõ chính sách một tài khoản mở nhiều client.
- Host có thể buộc ngừng publish hoặc mời người ra theo quyền. Không cho host bật từ xa camera/microphone của người khác.
- Xử lý host rời phòng, phòng đóng, người mất kết nối và server restart theo chính sách được ghi rõ. Phân biệt membership đang sống với lịch sử trong DB.
- Test dùng DB cô lập/H2 phù hợp. Nếu cần sửa schema, tạo migration tăng dần để kiểm tra trước; không tự áp dụng migration hoặc tạo dữ liệu test lên Railway đang dùng.

## 6. Signaling

- Dùng kênh bảo mật lâu dài, ví dụ WebSocket TLS hoặc TCP/TLS với framing riêng. Chọn dựa trên engine và ứng dụng hiện có, giải thích lựa chọn.
- Có phiên bản protocol, `requestId`, mã lỗi và định danh opaque cho room/participant/track; server xác minh mọi tham chiếu, không dùng tên hiển thị làm khóa.
- Token ở đây là mã phiên đăng nhập server đã cấp sau khi login, dùng để chứng minh client có phiên hợp lệ. Nó không tự cấp quyền host hay quyền truy cập mọi phòng; server vẫn kiểm tra membership và quyền của từng thao tác.
- Có thông điệp cho authenticate, create/join/leave room, participant events, SDP offer/answer, ICE candidate, publish/unpublish, subscribe/unsubscribe, media state và heartbeat.
- Có trạng thái kết nối rõ ràng, timeout, giới hạn kích thước/tần suất thông điệp, hàng đợi hữu hạn và lỗi client hiểu được.
- Xử lý ICE candidate đến trước remote description, offer collision/renegotiation, thêm/bỏ track và sự kiện đến muộn của phiên đã đóng.
- Request thử lại phải tránh tạo phòng, membership hoặc subscription trùng. Mỗi lần reconnect có định danh/generation để bỏ sự kiện cũ.
- Không mặc định giới hạn 16 KiB của auth phù hợp mọi SDP; cấu hình giới hạn riêng có kiểm thử, không tăng vô hạn.
- Giữ tương thích giao thức auth hiện có và kiểm tra certificate/hostname. Không dùng trust-all để làm demo chạy được.

## 7. Media và chuyển tiếp SFU

- Mỗi client gửi track audio/video/screenshare của mình đến SFU; SFU gửi cho những subscriber được phép trong cùng phòng. Tài liệu phải nêu mô hình một hay nhiều PeerConnection trên mỗi client.
- MVP dùng một lớp chất lượng cho mỗi track. Thiết kế điểm mở rộng cho simulcast/SVC, nhưng chỉ công bố hỗ trợ khi đã triển khai và kiểm thử.
- Có publish/unpublish, chọn track nhận, xử lý người vào muộn và yêu cầu keyframe đúng lúc. Không tự gửi ngược remote track của người dùng cho chính họ; local preview là luồng riêng.
- Nêu rõ phần thư viện xử lý và phần tự xử lý đối với SSRC, payload type, sequence/timestamp, RTP extensions, MID/RID khi có, SR/RR, NACK/RTX, PLI/FIR, pacing và congestion feedback. Chỉ bật cơ chế đã thương lượng và hỗ trợ thật.
- Không sao chép mù toàn bộ RTP/RTCP giữa các transport có ánh xạ khác nhau. Nếu rewrite hoặc chuyển lớp/nguồn, phải giữ ánh xạ đúng và có test wraparound, reorder, duplicate và discontinuity liên quan.
- Subscriber chậm không được chặn publisher hay các subscriber khác. Giới hạn hàng đợi/cache theo số lượng, byte và tuổi dữ liệu; có chính sách drop và keyframe recovery phù hợp.
- Hạn chế copy/cấp phát trong đường nóng, quy định ownership của buffer. Không tối ưu zero-copy khi chưa chứng minh an toàn vòng đời.
- Có chính sách xử lý băng thông hạn chế và ưu tiên audio. Nếu MVP chỉ có một lớp video, ghi rõ giới hạn điều chỉnh từng subscriber; không hứa chất lượng thích ứng như SFU đã hoàn thiện.
- Khi tắt media phải dừng dữ liệu tương ứng, khi rời phòng phải xóa track/subscription/timer/cache liên quan. Không chỉ đổi biểu tượng trên UI.

## 8. Tích hợp JavaFX và thiết bị

- Tận dụng bố cục hiện có của `PreJoinScreen`, `MeetingScreen`, `VideoTile`, `DeviceSettingsDialog`, `NetworkStatsDialog`, `ControlBar` và `ParticipantPanel`.
- Pre-join dùng danh sách thiết bị, preview và mức microphone thật; xử lý không có thiết bị, thiết bị bị chiếm dụng, rút thiết bị và quyền truy cập bị từ chối.
- Meeting hiển thị/phát track thật; mute, camera, chọn thiết bị, screenshare và leave có tác dụng thực tế. Screenshare cần thao tác chọn nguồn của người dùng.
- Không thực hiện DB/network/capture/encode/decode trên JavaFX Application Thread. Cập nhật UI đúng thread, hạn chế tần suất; không đẩy một `Platform.runLater` không giới hạn cho mọi frame.
- Có hàng đợi render hữu hạn hoặc giữ frame mới nhất; giải phóng frame/native buffer bị thay thế. Tách dropped frame ở render khỏi dropped frame của decoder.
- Loại bỏ số đo ngẫu nhiên trong luồng thật. Giá trị chưa có hiển thị “Chưa có dữ liệu”. Các màn hình ngoài phạm vi như chat/file/history có thể giữ trạng thái demo được ghi rõ, không tự mở rộng nhiệm vụ sang triển khai toàn bộ sản phẩm.

## 9. Đo đạc bằng module diagnostics hiện có

- Tái sử dụng `Diagnostics.current().event(...)`, `failure(...)`, `mediaSample(MediaSample)` và giữ khả năng tắt bằng `MMR_DIAGNOSTICS_ENABLED=false`.
- Mỗi tiến trình ghi file `.txt` riêng; cùng bài thử dùng chung `MMR_DIAGNOSTICS_RUN_ID`. Thêm correlation ID không chứa danh tính để nối signaling/room/track trong phạm vi bài thử; phân biệt ID cục bộ của các process.
- Giữ khả năng tháo adapter đo đạc mà không sửa thuật toán routing. Không ghi file hoặc log từng packet/frame trong đường xử lý media.
- Thu thập số phòng, participant, publisher/subscriber, track; thời gian signaling, ICE/DTLS, join và frame/audio đầu tiên khi đo được; disconnect/reconnect; queue depth/age/drop; NACK/PLI/retransmission; thời gian xử lý/chờ chuyển tiếp nội bộ SFU.
- Client đo RTT, jitter, packet loss, bitrate, resolution, FPS encode/decode/render, dropped frames và freeze khi API hỗ trợ. SFU không decode thì không tự gán FPS decode hay freeze video cho server.
- Giữ thống kê JVM hiện có. Khi dùng native engine hoặc process riêng, bổ sung RSS/native/process CPU nếu đo được; không gọi heap JVM là toàn bộ RAM hệ thống.
- `MediaSample` dùng delta theo khoảng lấy mẫu, byte payload RTP, RTT/jitter theo ms. Đổi đơn vị đúng; bỏ cửa sổ khi counter reset, xử lý counter mất gói được hiệu chỉnh do gói đến muộn. Không cộng lặp số loss và không trộn số từ nguồn khác định nghĩa.
- `null` nghĩa là chưa đo được, không thay bằng 0. Không suy ra độ trễ một chiều từ hai đồng hồ chưa đồng bộ; phân biệt network RTT, signaling round-trip, thời gian forward nội bộ và độ trễ người dùng nhìn/nghe được.
- Kiểm tra allowlist và giới hạn trường của logger trước khi thêm metric. Giữ tương thích schema hoặc tăng version có tài liệu. Không nới filter thành cho phép chuỗi bất kỳ.
- Không log token, PIN, mật khẩu, khóa, DB URL, SDP/ICE nguyên bản, địa chỉ mạng cá nhân, tên người dùng hoặc nội dung audio/video/chat. Thu gọn lỗi thành mã và loại lỗi đủ phân tích.
- Có công cụ đọc log tạo báo cáo `.txt`: điều kiện thử, số mẫu, khoảng đo, lỗi, p50/p95/p99 cho metric có ý nghĩa, drop/truncation của chính logger và thiếu `run.end`. Tách dữ liệu của các process, không tính percentile của các percentile. Chỉ kết luận trong phạm vi có dữ liệu.

## 10. Cấu hình và cách chạy

- Giữ lệnh chạy auth/client đang có; thêm script/entry point riêng cho signaling/SFU nếu kiến trúc cần. Tài liệu phải nói rõ cần mở bao nhiêu terminal và chạy theo thứ tự nào.
- Tách bind address khỏi địa chỉ quảng bá cho client, cấu hình cổng signaling/media, STUN/TURN, timeout và giới hạn tải. Không hardcode IP máy phát triển hoặc credential.
- Có cấu hình mẫu cho cùng máy, hai máy LAN và triển khai Internet. TURN là đường relay khi kết nối cần đến nó; Docker không thay thế cấu hình mạng WebRTC.
- Với Internet, mô tả mở cổng/firewall/NAT và certificate đúng hostname/SAN; không tự thay firewall toàn máy hoặc triển khai dịch vụ public chỉ để hoàn thành test.
- Cấu hình thật/bí mật được Git bỏ qua. Nếu media là process riêng, có cơ chế xác thực giữa nó và Java backend; không chuyển mật khẩu Railway sang SFU chỉ để kiểm tra phiên.

## 11. Các mốc triển khai và nghiệm thu

### Mốc 0 — Khảo sát và chọn engine bằng chạy thử

Đọc repo, lập danh sách thật/demo, chạy test nền, kiểm chứng dependency và thử đường publisher → SFU → subscriber. Báo cáo phương án khả thi cùng giới hạn. Test nguồn tạo sẵn không được gọi là kiểm thử thiết bị thật.

### Mốc 1 — Auth, phòng và signaling thật

Đăng nhập hiện có tiếp tục chạy; tạo/join/leave room thật; kiểm tra token/role/capacity; hai client thấy membership đồng nhất. Kiểm thử token sai/hết hạn/thu hồi, cross-room, sai PIN, race join và shutdown.

### Mốc 2 — Cuộc gọi tối thiểu qua SFU

Hai client trao đổi audio/video thật qua SFU; sau đó ba client trong một phòng. Pre-join và VideoTile dùng media thật. Mute/camera/leave có hiệu lực; một client rời không làm hỏng cuộc gọi của những người còn lại. Có số liệu đo và bằng chứng đường media đi qua SFU.

### Mốc 3 — Vòng đời và screenshare

Thêm/bỏ track trong cuộc gọi, người vào muộn, screenshare, đổi thiết bị, host controls, reconnect với backoff hữu hạn, ICE restart hoặc tạo transport mới theo tình huống, hết hạn phiên và dừng ứng dụng sạch. Không tự bật lại capture trái với trạng thái người dùng đã chọn.

### Mốc 4 — Kiểm thử độ ổn định và tải

- Chọn 2/3/5/10 client làm các mức thử ban đầu theo khả năng máy; đây là mục tiêu kiểm thử, không phải cam kết năng lực. Ghi số publisher, subscriber, track, codec, resolution, FPS và bitrate thực tế.
- Tối thiểu thử cuộc gọi kéo dài 30 phút trên môi trường có đủ thiết bị/tài nguyên, nhiều vòng join/leave, client bị đóng đột ngột, người nhận chậm và mất mạng rồi kết nối lại.
- Thử mất gói, jitter, trễ và giới hạn băng thông bằng công cụ được hỗ trợ; ghi rõ mô phỏng ở lớp nào. Nếu máy hiện tại thiếu công cụ, cung cấp kịch bản thực thi được ở môi trường phù hợp và ghi “chưa chạy”.
- Kiểm thử nhiều phòng cách ly; spike tải và backpressure; đo thread/socket/native memory/cache sau các vòng cleanup để tìm xu hướng rò rỉ.
- Có test tự động cho auth, room, signaling, routing, lifecycle và stats; test media integration dùng engine thật và nguồn deterministic. Mock test không thay thế thử WebRTC end-to-end.
- Giữ cùng phần cứng, cấu hình media, mạng, thời lượng và chế độ diagnostics khi so sánh trước/sau. Ghi commit/build, dependency, OS và cấu hình đã loại bí mật.
- Tách cổ chai của SFU khỏi capture/render ở client hoặc máy sinh tải. Không dùng nhiều client trên cùng máy để suy ra năng lực server ngoài thực tế mà không nêu hạn chế.
- Chốt ngưỡng nghiệm thu bằng số từ baseline và mục tiêu ứng dụng, không bịa kết quả hay cam kết 250 người. Mỗi tối ưu phải gắn với điểm nghẽn đã đo và kiểm tra tác dụng phụ.

## 12. Cách làm việc và bàn giao

- Trình bày kế hoạch ngắn, sau đó triển khai từng mốc có kiểm chứng; không dừng ở danh sách class, pseudocode hoặc TODO.
- Khi gặp lựa chọn kỹ thuật thông thường, tự quyết định trong phạm vi trên. Chỉ hỏi khi thiếu thông tin quyết định kiến trúc hoặc cần thao tác ngoài phạm vi; trước đó làm xong phần độc lập và đưa ra phương án cụ thể.
- Sau mỗi mốc: nêu file thay đổi, hành vi mới, lệnh chạy, test đã thực sự chạy, kết quả, giới hạn và việc còn lại. Không đánh dấu toàn hệ thống hoàn thành khi mới xong signaling hoặc một demo chuyển tiếp đơn giản.
- Bàn giao mã nguồn, dependency cố định, config mẫu, script PowerShell, test, `docs/sfu-architecture.md`, `docs/sfu-testing.md` và cập nhật `docs/diagnostics.md` khi schema/metric thay đổi.
- Tài liệu kiến trúc phải có sơ đồ, ranh giới tự viết/thư viện, luồng phiên xác thực, vòng đời tài nguyên, giao thức signaling, giới hạn MVP và cách tắt/tháo module diagnostics.
- Đánh giá hiệu suất và độ ổn định bằng bằng chứng kiểm thử. Không khẳng định ngang thư viện SFU trưởng thành chỉ vì build thành công hoặc gọi được trên localhost.

Bắt đầu bằng kiểm tra repository và thực hiện Mốc 0. Chỉ mở rộng triển khai khi đã có bằng chứng engine đáp ứng cách xây SFU này; nếu khả thi thì tiếp tục các mốc theo thứ tự, không hỏi lại quyền sửa mã nguồn cho từng bước thông thường.

## Tài liệu kỹ thuật để đối chiếu

Các nguồn sau giúp xác minh API và khái niệm, không thay thế việc thử nghiệm trên môi trường của dự án:

- [WebRTC media transport — RFC 8834](https://www.rfc-editor.org/rfc/rfc8834.html).
- [WebRTC statistics — W3C](https://www.w3.org/TR/webrtc-stats/).
- [webrtc-java — mã nguồn và tài liệu chính thức](https://github.com/devopvoid/webrtc-java): ứng viên cho phía Java desktop; phải kiểm chứng riêng khả năng dùng ở SFU.
- [Pion SFU example](https://github.com/pion/example-webrtc-applications/tree/main/sfu-ws): tham khảo nếu cân nhắc service Go; đây là ví dụ, không phải bằng chứng về độ ổn định production.
