# Đo bật/tắt mic và camera — 23/10/2026

## Thay đổi đã triển khai

Ba cải tiến được ghép vào kiến trúc Java/JavaFX, TLS signaling và Go/Pion
hiện có:

1. `MEDIA_WATCH`/`MEDIA_SOURCES` truyền snapshot nguồn media đầy đủ đã lọc
   theo phòng/quyền người nhận, kèm revision và generation. Client nhận
   trạng thái mới ngay, bỏ snapshot cũ và đăng ký lại khi reconnect. Vì mỗi
   bản là snapshot đầy đủ, revision toàn engine có thể nhảy do phòng khác
   thay đổi; không cần coi mỗi lần nhảy là mất delta. Watch loop Java–Go
   dùng long-poll loopback có giới hạn, không phải mỗi client truy vấn DB
   để hỏi trạng thái thường xuyên. Client cũ hoặc chưa có snapshot còn
   đường đối soát dự phòng. Grant SFU vẫn được gia hạn độc lập sau xác minh
   authority; bỏ polling không bỏ kiểm tra quyền.
2. Client có chuỗi yêu cầu theo loại media và chiều gửi/nhận. Server cũng
   điều phối các chuỗi tương ứng trên executor/hàng đợi hữu hạn. Tác vụ
   nhận camera không giữ worker điều khiển mic/phòng trong lúc chờ ICE.
   Cùng chuỗi vẫn giữ thứ tự; kết quả được kiểm tra lại ngữ cảnh trước khi
   dùng. Rời phòng hoặc đổi kết nối làm kết quả cũ mất hiệu lực.
3. Mic/camera hỗ trợ PAUSE/RESUME, giữ transport tối đa 30 giây để bật lại.
   Nguồn thu được tách và đóng khi tắt; SFU ngừng forward RTP, màn hình
   receiver được xóa nhưng có thể giữ receiver để tái sử dụng. Hết hạn,
   mất quyền, rời phòng hoặc transport lỗi thì giải phóng. RESUME không
   còn hợp lệ quay về publish mới. Share vẫn được đồng bộ push và tách
   hàng đợi, không áp dụng giữ nguồn thu màn hình ngầm.

Không thay schema, stored procedure, connection pool, chứng chỉ, private
config, dependency hoặc runtime. **Cache xác thực mới và trickle ICE chưa
triển khai**. Các lệnh media vẫn xác thực theo cơ chế hiện có; giảm DB chủ
yếu đến từ giảm yêu cầu trạng thái định kỳ. Khi còn phải publish mới, vẫn
có chi phí mở thiết bị và thu thập ICE.

Các nhóm file chính:

- Client: `MeetingSession`, `MediaSourceState`, `VideoCallController`,
  `AudioCallController`, `ScreenCallController`, `NativeVideoSession`,
  `NativeAudioSession`, `AudioRtpWatchdog` và các control mic/camera.
- Server: `SignalingServer`, `MeetingHandler`, `VideoCoordinator`,
  `VideoEngineClient`.
- Go: `media/video/engine.go`, `sources.go` và test tương ứng.
- Bằng chứng: `MediaToggleBenchmarkTest`, test vòng đời/tái sử dụng native,
  dispatch TLS và `scripts/test-media-toggle-benchmark.ps1`.

## Chạy ứng dụng sau thay đổi

Đóng các tiến trình server/SFU cũ trước khi build lại, để không chạy Java
mới với engine cũ. Trong PowerShell tại thư mục dự án:

```powershell
./scripts/build-video-engine.ps1
```

Script này chạy Go test và build engine trong `.tools/bin`; không migration
database. Trong NetBeans, Clean and Build, chạy file `server.Main` để khởi
chạy server đầy đủ, rồi chạy `client.Main` cho mỗi client thử nghiệm. Giữ
config/chứng chỉ đang hoạt động. Không chỉ chạy `server.AuthServer`, vì
entry point đó không thay cho toàn bộ signaling/media server.

Kiểm tra thủ công với hai client: một người bật mic/camera, người còn lại
xác nhận hình/tiếng; tắt/bật lại trong 30 giây rồi thử sau hơn 30 giây.
Kiểm tra đèn camera tắt, microphone ngừng thu và đúng thiết bị loa được
chọn. Test tự động không dùng thiết bị thật nên không xác nhận thay bước
này. Thử thêm host tắt media, kick, rời/đóng phòng và reconnect.

## Phạm vi phép đo

`MediaToggleBenchmarkTest` dùng tài khoản/bảng H2 tạm, chứng chỉ TLS tạm,
server Java thật, engine Go/Pion thật và WebRTC native. Camera tạo khung
320×240/15 FPS bằng mã; mic dùng PCM 48 kHz tạo bằng mã. Không mở camera,
microphone, loa thật và không dùng Railway. Hai client và server Java ở cùng
JVM; SFU là tiến trình riêng trên loopback.

Baseline được lấy từ Git `afffa45`, giải nén vào
`.tools/media-toggle-baseline-afffa45`. Engine baseline được build từ đúng
source của snapshot. Cùng một source benchmark được dùng trước/sau.
Baseline này không chứa các thay đổi media mới.

Mỗi round tạo fixture và hai thành viên mới. Lần bật đầu được đánh dấu
`cold`; 50 lần bật lại tiếp theo là `warm`. Sau mỗi lần tắt có 300 ms để
thoát dữ liệu đang đệm, nằm ngoài thời gian đo. `warm` chỉ có nghĩa là bật
lại sau khoảng đó, không tự chứng minh SDK đã tái sử dụng PeerConnection.
Khẳng định tái sử dụng phải dựa vào test vòng đời/identity riêng.

## Các mốc trong CSV

- `fx_dispatch_ms`: từ trước khi đặt tác vụ lên JavaFX đến khi callback gọi
  controller trả về future. Đây là độ trễ điều phối FX của fixture không có
  cửa sổ họp đầy đủ; không phải thời gian dựng toàn bộ giao diện thực tế.
- `publish_complete_ms`: từ lúc yêu cầu bật đến khi future bật hoàn tất.
- `remote_data_ms`: observer độc lập tính từ cùng mốc, đến frame đã decode
  đầu tiên của camera, hoặc PCM callback **và** RTP inbound mới của audio.
  Observer lấy mẫu mỗi 10 ms; không đo thời điểm trình bày lên màn hình/loa.
  PCM callback đơn lẻ không đủ bằng chứng vì WebRTC có thể tạo concealment.
- `process_cpu_ms`: CPU tích lũy của JVM trong thời gian bật/nhận đầu tiên,
  gồm cả hai client/server Java trong fixture, không gồm CPU của Go và
  không phải phần trăm CPU. Không thể tách CPU client/server từ chỉ số này.
- Heap, thread, GC là mẫu/tổng tích lũy JVM. Không coi heap là RSS hoặc
  so sánh mức đỉnh heap là bằng chứng rò rỉ nếu chưa kiểm soát GC.
- `receiving_peers` hiện chỉ đếm các route audio ở receiver, không phải
  tổng PeerConnection toàn SFU. Tổng SFU lấy từ diagnostic engine riêng.

Diagnostics trong `target/media-toggle-diagnostics-<label>` ghi round-trip
signaling, thời gian queue/handler server và runtime JVM. Thời gian handler
không được gọi là riêng thời gian SQL; fixture không đo riêng chờ pool hoặc
Railway. Việc giảm số `*_STATE` cho thấy giảm request định kỳ, không tự
chứng minh số millisecond tiết kiệm trên Railway.
Script cũng tách các log SFU mới vào `target/media-toggle-sfu-<label>`.
Nên dùng label mới cho mỗi lượt chạy để không trộn diagnostics của nhiều
lượt; CSV cùng label sẽ bị ghi đè khi bắt đầu lượt mới.

## Chạy lại local/NetBeans terminal

Trong terminal PowerShell tại thư mục dự án:

```powershell
./scripts/test-media-toggle-benchmark.ps1 -Label current -Samples 50 -Rounds 3
```

Nếu engine đã được build đúng source hiện tại và dependency đã có sẵn:

```powershell
./scripts/test-media-toggle-benchmark.ps1 -Label current -Samples 50 -Rounds 1 -SkipEngineBuild -Offline
```

Để so sánh, chạy lần lượt baseline và candidate, không đồng thời; giữ cùng
số mẫu/round, máy, nguồn media và điều kiện tải. Copy cùng benchmark vào
snapshot baseline trước khi chạy. Không copy private config/certificates.
Khi chạy trong sandbox chặn loopback, kiểm thử native phải được phép dùng
TCP/TLS local; `VIDEO_TEST_PROCESS_FAILED` có thể là lỗi môi trường, cần
phân biệt với lỗi application. Không tắt xác thực/TLS để né lỗi môi trường.

Tóm tắt bằng Python có sẵn:

```powershell
python scripts/report-media-toggle-benchmark.py path/to/baseline.csv target/media-toggle-current.csv
```

Thêm `--diagnostics <directory>` để thống kê request từ JSON-lines.
Đọc kết quả Maven trước: CSV của lượt chạy lỗi là dữ liệu dở, không được
gọi là lượt nghiệm thu hoàn tất. P95 dùng nearest-rank; chỉ có một cold
sample/round nên P95 cold không có ý nghĩa cho đánh giá đuôi phân bố.

## Giới hạn và kiểm thử bổ sung

Loopback/H2 không mô phỏng độ trễ Railway, Wi-Fi, NAT, thiết bị bận hoặc
driver Windows. Hai client chung JVM không chứng minh sức chứa server hoặc
50 người cùng phát media. Lượt baseline chạy trong lúc triển khai chỉ là
thăm dò; số liệu P95 nghiệm thu cần cặp chạy tuần tự trong điều kiện yên.

Cần kiểm thử thiết bị thật riêng: đèn camera tắt khi tắt camera, microphone
ngừng thu, thiết bị được ứng dụng khác mở sau khi tắt, màn hình/loa có hình
và tiếng; không thay những kiểm tra đó bằng PCM callback. Cần đo thêm lần
bật sau khi hết hạn giữ transport, reconnect, thu hồi quyền và quá tải.
Không suy ra phần trăm tăng tốc ứng dụng chung từ độ trễ bật media.

## Kết quả

### Cặp chạy tuần tự trong điều kiện yên

Đo ngày 23/10/2026 khoảng 15:31–15:35 giờ Việt Nam. Không chạy đồng thời
build/test khác của dự án. Hai bản đều hoàn tất 50 lần bật lại cho mỗi
loại media, cộng một lần bật đầu; không bỏ outlier. Maven baseline thành
công trong 3:07, candidate thành công trong 48.119 giây. Đây là thời gian
cả bài thử, gồm khoảng tắt 300 ms và setup, không phải độ trễ một thao tác.

Mỗi ô là **trung vị / P95**, đơn vị ms; n=50 warm/loại/bản:

| Chỉ số | Baseline `afffa45` | Sau cải tiến |
|---|---:|---:|
| Camera: FX dispatch | 0.214 / 0.329 | 0.232 / 0.356 |
| Camera: future bật hoàn tất | 532.582 / 574.531 | 36.918 / 39.189 |
| Camera: máy nhận có frame decode mới | 1690.278 / 1754.082 | 73.973 / 76.895 |
| Mic: FX dispatch | 0.195 / 0.289 | 0.188 / 0.301 |
| Mic: future bật hoàn tất | 509.653 / 543.260 | 4.474 / 5.933 |
| Mic: máy nhận có PCM và RTP mới | 1215.412 / 1266.065 | 57.120 / 59.249 |

Kết quả cho thấy lợi ích lớn ở **bật lại trong thời gian giữ transport**,
không phải tăng tốc FX dispatch vốn đã dưới 1 ms trong fixture. Phép đo
không cho phép nói camera vật lý/mạng Railway cũng sẽ đạt 74 ms hoặc mic
57 ms. Các lệnh RESUME vẫn xác thực theo cơ chế hiện có; RTT Railway và
thời gian mở thiết bị thật còn cộng thêm.

Mỗi bản quiet chỉ có một cold sample/loại: camera trước/sau có frame mới
1571.167/1329.240 ms, mic 1474.575/1063.613 ms. Không dùng n=1 này để khẳng
định cải thiện độ trễ cold hoặc P95. Bật sau hơn 30 giây đi theo đường tạo
mới, không được áp kết quả warm vào trường hợp đó.

Số lệnh trong cùng bài 51 lần bật camera và 51 lần bật mic:

| Lệnh từ client | Trước | Sau |
|---|---:|---:|
| `VIDEO_PUBLISH` / `VIDEO_SUBSCRIBE` | 51 / 51 | 1 / 1 |
| `AUDIO_PUBLISH` / `AUDIO_SUBSCRIBE` | 51 / 51 | 1 / 1 |
| `VIDEO_RESUME` / `AUDIO_RESUME` | 0 / 0 | 50 / 50 |
| Tổng `VIDEO_STATE` + `AUDIO_STATE` + `SCREEN_STATE` | 859 | 3 |

Ba STATE sau cải tiến là đối soát ban đầu trong lượt đo. Đây là tổng lệnh
để hoàn thành cùng bài; thời lượng hai bài khác nhau, không phải tỷ lệ
request/giây trong một cuộc họp bất kỳ. Xác thực nền vẫn hoạt động, không
có khẳng định đã loại bỏ toàn bộ truy vấn database. H2 không đo chờ pool
Railway. Queue server quiet P95 trước/sau 0.036/0.056 ms: bản cũ không quá
tải server trong bài này; lợi ích hàng đợi được kiểm chứng thêm bằng bài
TLS chèn tác vụ chậm và kiểm tra lệnh độc lập vẫn tiến triển.

Chi phí tài nguyên cũng thay đổi: thread JVM được lấy mẫu trong CSV trước
66–70, sau 72–78. SFU lấy mẫu thấy tối đa 2 PeerConnection trước và 4 sau,
do camera tạm dừng được giữ khi bắt đầu thử mic. Đây là đánh đổi có thật.
Heap JVM tại các điểm đo trước 12.62–88.86 MiB, sau 13.08–91.08 MiB; không
suy ra rò rỉ hoặc tiết kiệm bộ nhớ từ riêng khoảng này. Các mẫu Go thấy
queue depth bằng 0; drops tích lũy cuối baseline 8, candidate 0. Không suy
diễn số drops khi tắt/bật trong fixture thành chất lượng mạng ngoài đời.

CSV đầy đủ được giữ tại:

- [Baseline quiet](benchmarks/media-toggle-20261023-baseline.csv).
- [Candidate quiet](benchmarks/media-toggle-20261023-candidate.csv).
- [Provenance và SHA256](benchmarks/media-toggle-20261023-provenance.txt).

Logs chi tiết local: baseline trong
`.tools/media-toggle-baseline-afffa45/target`, candidate trong
`.tools/media-toggle-candidate-20261023/target`; mỗi thư mục có
`target-quiet.log`, CSV và thư mục `media-toggle-diagnostics-*-quiet` /
`media-toggle-sfu-*-quiet`. Bản tóm tắt cả hai nằm tại
`.tools/media-toggle-final-summary.txt`. Artifact `.tools` bị Git ignore;
hai CSV và provenance ở docs không chứa token, tài khoản hoặc private config.

### Lặp ba round và hồi quy

Trước cặp quiet đã chạy thăm dò ba round cho mỗi bản, 150 warm/loại/bản
và ba cold/loại/bản, đều thành công. Có các test/build khác chạy trong lúc
đo nên chỉ dùng để kiểm tra độ ổn định, không làm số liệu nghiệm thu P95.
Camera remote trung vị/P95 trước 1703.710/1778.004 ms, sau
92.019/107.178 ms; mic trước 1284.642/2357.385 ms, sau
61.403/62.811 ms. Hướng thay đổi nhất quán với cặp quiet.

Kiểm thử hồi quy của đợt triển khai: bộ Java thường 582 test, 575 pass,
0 fail/error, 7 skip có sẵn (5 bài stored procedure MySQL cần môi trường
riêng, 2 bài truyền tệp có điều kiện). Profile native cuối cùng 21/21 pass,
gồm camera, audio, share, adaptive HD, lease và tái sử dụng transport; Go
test toàn engine pass. Riêng baseline kiểm tra lại bài ba client video và
bốn client audio cũng pass, ngoài benchmark.

Không coi các kết quả này là thử tải 50 người hay kiểm thử driver Windows.
Chưa có số đo FPS capture/encode/decode/render dưới tải người dùng lớn,
RTT Railway, thời gian chờ pool thực tế, băng thông wire đầy đủ hoặc CPU
riêng từng tiến trình production. Các kiểm tra thiết bị thật ở trên vẫn
cần người dùng thực hiện; không giả số liệu cho những phần chưa đo.
