# Sửa video bị giữ chất lượng thấp dù kết nối tốt

Ngày 18/10/2026, kiểm tra bằng hai client thực, Go/Pion SFU, TLS/H2 riêng và nguồn I420 1280×720/30 tạo bằng mã. Không mở camera/microphone thật hoặc truy cập Railway.

## Bằng chứng trước và sau

Ca kiểm thử lấy thống kê native và đếm frame capture, decode và render trong cửa sổ 3 giây sau warm-up, **không bơm số liệu mạng giả vào policy**:

| Chỉ số | Trước sửa | Sau sửa |
|---|---:|---:|
| FPS nguồn đi qua local track | 30 | 30 |
| FPS nhận/giải mã | 30 | 30 |
| FPS hiển thị JavaFX | 8,33 | 30 |
| Profile AUTO | LOW | HD |
| Độ phân giải mã hóa | 640×360 | 1280×720 |
| RTT | 1 ms | 1 ms |
| Mất gói | 0% | 0% |
| Available outgoing bitrate | 2,05 Mbps | 5,50 Mbps |

Hai cột là hai lượt thử trên cùng máy và workload, không phải cam kết mọi camera/mạng luôn đạt 720p/30. Cảnh thử ít chuyển động nên bitrate thực thấp; điều đó không được coi là mạng yếu. Test trước sửa thất bại có chủ đích; log `target/video-quality-before.txt`. Lượt sau đầu tiên 21/21 test qua: `target/video-quality-after-first.txt`.

Lượt kiểm chứng cuối **61/61 test qua, không failure/error/skip; package BUILD SUCCESS** lúc 14:05 ngày 18/10/2026 (`target/video-quality-verified.txt`). Bao gồm native video/audio/screen, màu/xoay ảnh, chuyển chất lượng và ưu tiên thoại, điều khiển camera, bảng thống kê, khôi phục phòng và gửi/tải tệp trong test video. Nhóm 2/5/10 người cũng qua, telemetry đều bằng 0, nhịp sample trung bình 500,03/500,06/500,06 ms, buffer lớn nhất 15/22/17 trên trần 40 (`target/video-quality-group.csv`). Ca 10 người phát một camera; không phải 10 camera đồng thời. Các số đo CPU/heap nhóm không được dùng để tuyên bố tối ưu trên mọi máy.

Lệnh kiểm chứng (Maven trên PATH, binary test tạo sẵn bằng build script):

```powershell
mvn -B -Pvideo-integration "-Dmmr.video.test.binary=C:/MMT/MiniMeetingRoom/.tools/test-artifacts/client-stats-20261018/mmr-reconnect-video.exe" "-Dtest=AutoVideoHdIntegrationTest,AdaptiveVideoPolicyTest,CameraCaptureCapabilityTest,RtcQualityMonitorTest,NetworkStatsCalculatorTest,VideoCallIntegrationTest,AudioCallIntegrationTest,ScreenCallIntegrationTest,NetworkSignalWidgetTest,CameraControlsTest,MeetingSessionReopenTest,NetworkStatsGroupIntegrationTest" package
```

Binary trên là artifact test riêng của phiên này; không ghi đè tiến trình media đang chạy. Máy khác cần build binary test theo cấu hình trong `VideoTestFixture` hoặc dùng `scripts/test-video-call.ps1` cho bộ video/audio/screen. Log/CSV được sao lưu trong `.tools/test-artifacts/video-quality-20261018/` (Git ignored) để không mất sau Clean.

## Nguyên nhân và sửa chữa

1. `VideoFrameMailbox` đặt khoảng nghỉ 100 ms sau mỗi ảnh được nhận. Với nguồn 30 FPS và jitter lịch chạy, nó chỉ nhận khoảng mỗi ảnh thứ tư để hiển thị, tạo 8,33 FPS. Đã đổi sang cadence tối đa 30 FPS, có dung sai jitter, giữ một mẫu ảnh mới nhất và tối đa một FX job. Chuyển I420 → BGRA bằng libyuv native thay cho vòng lặp Java trên từng pixel; tái sử dụng bộ đệm giữa callback và JavaFX, vẫn giải phóng frame native trong callback. Bảo toàn xoay ảnh, thứ tự màu và generation khi tắt/đổi nguồn.
2. AUTO yêu cầu available bitrate ≥ 130% trần HD (3,25 Mbps) trước khi rời LOW (600 Kbps). Đường thử tốt có estimate 2,05 Mbps nên bị giữ LOW. Nay sau ít nhất 15 giây tốt, nguồn lớn hơn trần LOW và headroom ≥ 125% trần LOW (mặc định 750 Kbps), policy cho phép thử trần HD. `maxBitrate` vẫn là giới hạn trên; congestion control native quyết định bitrate thực, không ép min bitrate hoặc phát đủ 2,5 Mbps. Mạng xấu/CPU yếu vẫn giảm profile, thoại xấu vẫn tạm dừng video như trước.
3. Chấm điểm camera cũ có thể chọn 720p/8 hoặc 720p/15 thay vì 640×480/30. Đã đổi sang ưu tiên FPS cao nhất trong giới hạn 30 rồi chọn kích thước lớn nhất trong giới hạn 720p. Camera không đủ 30 FPS vẫn dùng khả năng thật; bỏ điều kiện chỉ camera đủ 720p/30 mới được nâng profile, không upscale hoặc restart camera giữa cuộc gọi.

Nhãn “Tốt” vẫn đánh giá đường truyền. Dòng FPS trong popup đo **frame mã hóa gửi**, không phải FPS JavaFX. Vì vậy không đồng nhất con số 8 FPS trong ảnh người dùng với kết quả 8,33 FPS render ở ca tái hiện. Camera thật chưa được mở để đo; bản sửa xử lý giới hạn render, điều kiện nâng mức và lỗi chọn mode có thể làm nguồn phát FPS thấp, không sửa số thống kê để hiển thị cao giả.

## File và nghiệm thu

- `src/main/java/client/media/VideoFrameMailbox.java`: cadence, chuyển màu native và bộ đệm ảnh.
- `src/main/java/client/media/CameraVideoInput.java`: chọn mode capture.
- `src/main/java/client/media/NetworkQualityAnalyzer.java`: điều kiện thử trần HD; hysteresis/voice priority giữ nguyên.
- `src/test/java/client/media/CameraCaptureCapabilityTest.java`, `AdaptiveVideoPolicyTest.java`: mode FPS thấp, camera 25 FPS, headroom và bảo vệ khi mạng yếu.
- `src/video-test/java/client/media/AutoVideoHdIntegrationTest.java`: native end-to-end không inject mạng; màu đỏ/BGRA, xoay ảnh 0/90/180/270, callback nguồn cũ và pause; giữ test LOW → HD → SAVING không restart capture.

Trong NetBeans, đóng các client đang chạy, **Clean and Build**, rồi chạy lại `client/Main.java`. Khởi động `server/Main.java` nếu chưa có server; không chạy thêm server vào cổng đang dùng. Không thêm migration hoặc thay đổi cấu hình database/chứng chỉ cho bản sửa này. Một webcam chỉ nên bật ở một trong hai client cùng máy; client còn lại dùng để nhận video. Quan sát sau ít nhất 15–20 giây ổn định, thử mạng yếu và tắt/bật lại camera. Camera thiếu sáng/driver chỉ cấp FPS thấp hoặc CPU quá tải vẫn có thể thấp hơn giới hạn 30 FPS.
