# Chất lượng video tự động

Client dùng mẫu WebRTC đang phục vụ thống kê mạng, bổ sung số liệu của các peer Opus để nhận biết thoại bị ảnh hưởng. RTT, jitter và mất gói được phản ánh lên cùng biểu tượng mạng và thông điệp `NETWORK_STATS` hiện có. Database vẫn ghi theo lô 10 giây; quyết định chạy tại client theo chu kỳ đo khoảng 1 giây, không truy vấn database thêm và không cần migration.

## Các mức camera

| Mức | Bitrate tối đa | FPS tối đa | Tỷ lệ giảm kích thước |
| --- | --- | --- | --- |
| Bình thường | 700 kbps | 15 | 1 |
| Tiết kiệm | 400 kbps | 12 | 1,5 |
| Thấp | 140 kbps | 8 | 2,5 |
| Ưu tiên thoại | Dừng RTP video | — | — |

Các kích thước tính từ nguồn camera hiện tại. Ví dụ nguồn 640×480 ở mức thấp sẽ gửi khoảng 256×192. WebRTC vẫn có thể tự giảm bitrate thấp hơn hạn mức này tùy đường truyền. Không thay chế độ capture hoặc mở lại thiết bị khi đổi mức.

Mạng xấu liên tục ít nhất 3 giây mới giảm một mức. Ngưỡng tiết kiệm: RTT ≥250 ms, jitter ≥40 ms hoặc mất gói ≥3%. Ngưỡng thấp: RTT ≥500 ms, jitter ≥80 ms hoặc mất gói ≥8%. Chỉ đi từ thấp sang ưu tiên thoại khi chính thống kê thoại có RTT ≥600 ms, jitter ≥80 ms hoặc mất gói ≥8% liên tục 5 giây ở mức thấp. Video xấu nhưng thoại khỏe sẽ giữ video thấp; không tự ngắt hẳn chỉ vì bitrate thấp hoặc một lần tăng độ trễ.

Khôi phục từng mức khi các số đo có được duy trì tốt trong 12 giây: RTT <200 ms, jitter <35 ms, mất gói <2%. Từ ưu tiên thoại sẽ trở lại thấp trước, rồi tiết kiệm, rồi bình thường. Mẫu không hợp lệ, thiếu hoàn toàn hoặc bị gián đoạn không được coi là mạng đã hồi phục. Chỉ dùng đường ICE được chọn; tính mất gói theo chênh lệch counter của từng peer/stream và bỏ cửa sổ khi counter bị reset.

## Ưu tiên thoại và thao tác người dùng

Ưu tiên thoại tắt encoding RTP camera và dừng các subscription camera, không đổi mic hay tự cấp quyền/mở microphone. Nếu đang chia sẻ màn hình, luồng chia sẻ cũng tạm dừng truyền/nhận để nhường băng thông; chất lượng chia sẻ ban đầu được giữ khi khôi phục. Kết nối camera/ICE và capture xem trước cục bộ được giữ để đo phục hồi và tiếp tục nhanh. Vì vậy camera vẫn có thể sáng đèn trong lúc tạm dừng truyền; nút Tắt camera luôn dừng capture như trước.

Khôi phục chỉ bật lại encoding của publication còn tồn tại, không tạo capture/publication mới. Người dùng tắt camera hoặc dừng chia sẻ trong lúc mạng yếu thì các luồng đó không tự bật lại. Rời phòng, bị host dừng media hoặc đóng session vẫn dùng luồng dọn hiện có. Mẫu RTP cũ của camera đã tạm dừng không được dùng để chặn phục hồi; dùng ICE và số liệu thoại còn hoạt động.

Hạn mức độ phân giải/FPS/bitrate áp dụng cho camera client phát. SFU vẫn chuyển tiếp luồng nhận nguyên trạng ở mức tiết kiệm/thấp; mức ưu tiên thoại dừng thêm luồng nhận. Bản này không thêm các tầng simulcast hoặc transcoding riêng cho từng người nhận.

Mức hiện tại xuất hiện trong Cài đặt, và chỉ hiện nhãn trên thanh trên của cuộc họp khi chất lượng đã giảm. Thay đổi chất lượng được ghi vào diagnostics với mã mức, không ghi camera, nội dung media hoặc thông tin đăng nhập.

## Kiểm thử

`AdaptiveVideoPolicyTest` kiểm tra độ trễ chuyển mức, thoại khỏe không bị cắt video, tín hiệu dao động, thiếu/sai/mẫu cũ và khôi phục từng bước. `RtcQualityMonitorTest` kiểm tra delta mất gói, peer thay đổi, đường ICE được chọn, RTCP thoại và dữ liệu cũ sau khi tạm dừng.

Bài native `VideoCallIntegrationTest.adaptiveVideoReducesResolutionProtectsVoiceAndRespectsManualCameraOff` dùng H2/TLS, Go/SFU, video/âm thanh/màn hình tạo bằng mã. Mẫu mạng xấu/tốt được đưa vào bộ điều khiển trên đồng hồ đơn điệu điều khiển được; mã hóa, truyền RTP, giải mã, đổi kích thước, giữ thoại và dừng/khôi phục media chạy thực. Bài này không giả lập mất gói thực trên card mạng và không mở thiết bị thật.

Kết quả ngày 17/10/2026:

- Lượt đầu 13 bài chính sách/parser/native/UI PASS (`target/adaptive-native-first.txt`).
- Lượt rộng 390 tests: 0 failures, 1 error, 3 skipped (`target/adaptive-regression.txt`). Error là dấu chọn Sáng/Tối trong `DucAuthFlowTest`; không sửa ThemeManager trong thay đổi này.
- Sau hoàn thiện dừng/khôi phục chia sẻ và đồng bộ chỉ số thoại, lượt cuối 54/54 PASS, 0 failures/errors/skipped (`target/adaptive-final-validation.txt`). Gồm toàn bộ 12 bài DucAuthFlowTest, 9 bài RoomFlowTest, 4 bài điều khiển phòng, 11 bài native video/audio/screen và các bài chính sách, parser, số liệu, lỗi âm thanh, nhấn camera liên tục.
- Native xác nhận video 320×240 giảm chiều rộng xuống 128; RTP camera/chia sẻ ngừng trong ưu tiên thoại trong khi PCM hai chiều vẫn tăng. Khi tốt lại, video về chiều rộng 320 và chia sẻ truyền tiếp. Tắt bằng tay trong lúc tạm dừng rồi phục hồi không tái tạo publication camera/chia sẻ.

Cần thử thêm trên đường truyền yếu thực tế để tinh chỉnh các ngưỡng theo thiết bị và mạng triển khai. Các báo cáo trên không phải kết quả thử mất gói/băng thông giới hạn trên card mạng thật.
