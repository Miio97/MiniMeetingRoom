# Chất lượng video tự động

Phiên bản ngày 18/10/2026 thay cơ chế cũ bằng thống kê media cục bộ: lấy mẫu 500 ms, RAM buffer 40 mẫu, phân tích/UI khoảng 1 giây. Không gửi NETWORK_STATS hoặc lưu thống kê database. Bảng và lịch sử cũ xóa bằng migration 004 chạy thủ công sau cập nhật server.

Chỉ AUTO: SAVING tối đa 320×180/15 FPS/200 Kbps, LOW 640×360/30 FPS/600 Kbps (khởi đầu), HD 1280×720/30 FPS/2,5 Mbps. Giá trị thực bị giới hạn bởi source/camera, giữ tỷ lệ ảnh và không upscale. Không có MANUAL hoặc chế độ 45/60 FPS.

Hạ mức sau 4 giây xấu, phục hồi sau 15 giây tốt, cooldown 10 giây; được giảm nhanh khi nghiêm trọng. Chỉ số uplink/downlink tách biệt; không suy ra thiếu băng thông từ bitrate thấp hoặc RTT cao đơn lẻ. CPU limitation ưu tiên giảm FPS; dữ liệu thiếu là UNKNOWN.

Thử trần HD sau cửa sổ tốt khi nguồn lớn hơn LOW và bandwidth estimate ≥ 125% trần LOW (mặc định 750 Kbps); native congestion control quyết định bitrate thực dưới trần HD. Không yêu cầu đạt sẵn 3,25 Mbps khi đang phát LOW. Capture ưu tiên FPS rồi độ phân giải trong giới hạn 720p/30; camera 25 FPS vẫn có thể nâng độ phân giải. Hiển thị dùng cadence tối đa 30 FPS và chuyển I420 → BGRA bằng native libyuv, thay cho giới hạn cũ 100 ms/ảnh.

VOICE_PRIORITY chỉ tạm dừng video/chia sẻ khi thoại bị ảnh hưởng kéo dài, giữ voice và phục hồi dần khi có bằng chứng tốt. Không tự bật capture đã tắt bằng tay; khôi phục signaling 2 phút vẫn hoạt động như trước.

Xem [báo cáo đầy đủ, file thay đổi, cấu hình, kết quả kiểm thử, Railway migration và backup/rollback](client-stats-migration.md).
