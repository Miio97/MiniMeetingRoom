# Báo cáo voice trên hai thiết bị cùng Wi-Fi

Ngày kiểm tra: 16/10/2026, giờ Việt Nam. Phần đầu ghi nhận chẩn đoán ban đầu; phần cập nhật bên dưới ghi nguyên nhân đã tái hiện và bản sửa. Không thay đổi cấu hình mạng.

## Cập nhật sau tái hiện bằng microphone Windows thật

- Trước khi sửa, thao tác bật mic hết hạn 20 giây dù worker đã rảnh: JNI ném `java.lang.Error: Init recording failed` tại `AudioDeviceModuleBase.initRecording`, nhưng controller chỉ bắt `Exception | LinkageError`, khiến future không bao giờ hoàn tất. Đây không phải deadlock ở worker trong bài tái hiện này.
- Khi chọn đúng thiết bị mặc định, publish hoàn tất nhưng server vẫn nhận 0 gói. Cảnh báo native xác nhận: `Playout must be started before recording when using the built-in AEC`. Factory chỉ gửi không có luồng phát, nên AEC của Windows chặn recording.
- Đã bỏ việc tự init/start/stop recording trên ADM mà factory đang quản lý; chọn rõ microphone mặc định khi chưa chọn thiết bị. Tắt AEC trong factory chỉ gửi, giữ noise suppression/high-pass và tắt auto gain. Không mở loa khi chỉ thu mic. Chưa có khử vọng âm học giữa hai factory; nên dùng tai nghe.
- Đã bỏ init/start playout khỏi callback `onTrack`, tránh gọi điều khiển thiết bị trong luồng callback của SDP. Việc phát âm thanh nhận do WebRTC quản lý.
- Controller hoàn tất future cả khi native ném Error, dọn trạng thái khi lỗi, giữ polling sau lỗi native, giới hạn thời gian chờ đóng và phân biệt timeout/interruption với lỗi microphone.
- Bản thử mở playout rỗng để đáp ứng AEC đã gây tiếng rè và cảnh báo `Invalid audio transport`. Bài thử đã dừng, người dùng xác nhận hết tiếng rè; đoạn mã thử đó đã được loại bỏ.
- Kiểm chứng đầu tiên sau bản sửa: mic Windows thật → Opus → SFU Go → decoder headless nhận 6.240 mẫu, tắt mic và đóng owner thành công. Không lưu âm thanh, không phát ra loa trong bài kiểm chứng này.

Hai thiết bị vật lý qua Wi-Fi vẫn cần nghiệm thu với cùng bản client mới; không suy diễn kết quả loopback thành nghiệm thu hai máy thật.

### Kiểm chứng bản sửa cuối

`target/qa/voice-fix-integration.txt`: **23 tests, 0 failures/errors, 0 skipped, BUILD SUCCESS**, hoàn tất 00:44:58 ngày 16/10/2026.

`target/qa/voice-fix-regression.txt`: **208 tests, 0 failures/errors, 2 skipped, BUILD SUCCESS**, hoàn tất 00:48:27. Maven đã tạo lại JAR sản phẩm. Hai skip thuộc cấu hình kiểm thử; test mic thật đã chạy riêng và đạt trong bộ 23 bài ở trên.

- WindowsAudioIntegrationTest: mic thật → SFU → decoder headless, bật/tắt 3 lần, nhận tích lũy 49.440 / 105.120 / 161.760 mẫu; tắt mic và đóng owner đều hoàn tất. Không mở loa vật lý, không lưu PCM.
- AudioCallIntegrationTest: 3 bài đạt, gồm trao đổi Opus hai chiều giữa hai JVM riêng biệt, ba client, host stop/kick/logout/leave.
- AudioFailureTest: 4 bài đạt, gồm native Error không để future treo, thử lại chủ động, polling tiếp tục sau lỗi, đóng có hạn thời gian và phân loại timeout/interruption.
- VideoCallIntegrationTest 5 bài, ScreenCallIntegrationTest 2 bài và các test audio UI/device/playback đều đạt.
- Kiểm tra mạng chỉ đọc: auth/signaling/file đang nghe `192.168.1.8:8443/8444/9001`; control Go nghe `127.0.0.1:8766`. Wi-Fi hiện dùng profile Public và đã có rule Allow inbound cho Java/mmr-video trên profile này. Không thêm hoặc sửa rule firewall.

Lệnh nghiệm thu mic thật (chỉ chạy khi chủ động muốn dùng microphone, không phát loa):

```powershell
mvn -B -Pvideo-integration -Dtest=WindowsAudioIntegrationTest -Dmmr.test.audio.devices=true test
```

Khi dùng hai máy, đóng client cũ rồi mở lại cả hai bằng cùng bản sửa, đăng nhập hai tài khoản khác nhau, tạo phòng mới, vào màn hình họp và bật mic. Nên dùng tai nghe vì AEC giữa hai factory chưa được triển khai. Máy thứ hai phải giải nén toàn bộ gói LAN mới; không tiếp tục chạy JAR cũ.

## Kết luận

Voice chưa có dữ liệu âm thanh đi tới SFU. Dấu hiệu mạnh nhất là luồng audio phía client bị kẹt trong xử lý native/lifecycle: polling audio ngừng, các luồng media khác tiếp tục và đóng media bị timeout. Đây là chẩn đoán ưu tiên, chưa phải xác nhận deadlock tại một hàm cụ thể. Không có thread dump hoặc log máy thứ hai để xác định chính xác lời gọi bị kẹt.

## Bằng chứng

Phiên gần nhất có dữ liệu: client `client-20261015T171234.216Z-22860-3a11bee9.txt`, server `server-20261015T171231.698Z-22916-7f0abb28.txt`, SFU `sfu-video-20261015T171238.915Z-27132.txt`, trong `logs/diagnostics/`. Thời gian dưới đây đã chuyển từ UTC sang giờ Việt Nam.

| Chỉ số SFU, giá trị lớn nhất trong phiên | AUDIO | VIDEO |
| --- | ---: | ---: |
| PeerConnection được ghi nhận | 2 | 4 |
| Gói nhận vào | 0 | 17.979 |
| Gói chuyển tiếp | 0 | 12.276 |

PeerConnection được tạo không chứng minh ICE/DTLS đã kết nối. Tuy nhiên video có gói chuyển tiếp trong cùng phiên cho thấy hệ thống đã truyền được media; không đủ cơ sở quy toàn bộ lỗi cho việc hai máy không liên lạc được qua Wi-Fi.

Client ghi `AUDIO_STATE` lúc 00:13:47, sau đó có bốn yêu cầu `AUDIO_PUBLISH` lúc khoảng 00:13:49, 00:13:58, 00:19:26 và 00:20:06. Không có `AUDIO_STATE` tiếp theo hoặc `AUDIO_SUBSCRIBE` trong log client này, trong khi `VIDEO_STATE`/`SCREEN_STATE` tiếp tục. Lúc 00:21:36 có `video.client.close_failed` với `TimeoutException`. Sự kiện đóng này bao quát nhiều owner media nên không đủ để quy riêng timeout cho audio.

Phiên SFU trước đó `sfu-video-20261015T160229.799Z-23624.txt` cũng có tối đa hai peer audio nhưng ingress/forwarded đều bằng 0; hiện tượng không chỉ xuất hiện một lần.

## Giải thích từ mã nguồn

1. `AudioCallController.java:57` dùng một worker cho polling và thao tác audio. Tạo thiết bị, tạo/đóng peer, offer/answer và stop đều chạy trong luồng công việc này. Nếu lời gọi native không trả về, polling và tác vụ xếp sau cùng bị chặn.
2. `NativeAudioSession.java:34` gọi `initRecording()`/`startRecording()`; phần SDP và đóng phiên cũng gọi native trực tiếp. Timeout chờ future không giới hạn được thời gian một lời gọi native bị kẹt trước khi tới bước chờ future. Cần thread dump để phân biệt capture, SDP và cleanup.
3. `AudioCallController.java:198` chờ worker kết thúc trong vòng lặp không có hạn tổng. Interrupt không bảo đảm giải phóng được một lời gọi native đang kẹt.
4. `media/video/engine.go:199` chỉ đưa publisher vào danh sách nguồn khi track đã active; `OnTrack` xử lý tại dòng 355. Không có RTP audio đến thì không có nguồn hoạt động để bên kia subscribe. Việc bật biểu tượng mic hoặc gửi yêu cầu publish chưa chứng minh có tiếng.
5. Lỗi `MICROPHONE_OPEN_FAILED` trong phiên client trước lúc 00:07:07 đi kèm `InterruptedException`, ngay thời điểm đóng media. `CameraErrors.java:21` ánh xạ nhiều lỗi chung sang nhãn microphone. Không được dùng nhãn này để kết luận Windows đã chặn mic.

## Kết nối LAN và giới hạn bằng chứng

IP Wi-Fi hiện tại là `192.168.1.8`, khớp `auth.host`/`file.transfer.host` trong cấu hình client và các bind auth/signaling/file trên server. SFU dùng UDP IPv4; control HTTP `127.0.0.1:8766` chỉ phục vụ nội bộ Java–Go, không phải địa chỉ client phải truy cập.

Chưa xác minh được firewall, UDP/ICE của từng peer audio, quyền mic/loa và ứng dụng thực tế trên máy thứ hai. Video hoạt động không loại trừ việc các cổng UDP riêng của audio bị chặn. Máy còn có adapter ZeroTier, nhưng chưa có bằng chứng nó gây lỗi. Tại lúc kiểm tra không thấy listener TCP của ứng dụng hoặc tiến trình Java/SFU đang chạy; vì vậy không lấy được thread dump của phiên lỗi.

## Kiểm thử và bước xử lý ưu tiên

Kết quả đã lưu trong `target/surefire-reports`: AudioCallIntegrationTest 2/2 đạt, AudioRoomPlaybackTest 2/2 đạt, AudioDeviceTesterTest 3/3 đạt. Integration dùng HeadlessAudioDeviceModule và PCM tạo sẵn; không kiểm chứng micro/loa Windows thật hoặc hai máy LAN. Lần gọi Maven trong đợt kiểm tra này báo `Access is denied`, không tạo ra một lần chạy test mới thành công.

Ưu tiên tiếp theo là lấy thread dump khi audio ngừng polling và ghi trạng thái từng bước capture/SDP/ICE cùng số mẫu thu/gói gửi. Sau đó xử lý lời gọi native/lifecycle gây kẹt và giới hạn thời gian đóng; sửa phân loại lỗi để interruption/timeout không bị báo nhầm thành lỗi mở mic. Nghiệm thu lại bằng micro/loa thật trên hai máy, xác nhận RTP audio tăng ở server và nghe được cả hai chiều. Chỉ điều chỉnh firewall khi có bằng chứng ICE/UDP audio thất bại.
