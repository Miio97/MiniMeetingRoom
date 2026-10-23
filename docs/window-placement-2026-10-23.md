# Vị trí và chế độ cửa sổ — 23/10/2026

- Đăng nhập và sảnh dùng kích thước cửa sổ xác định, căn giữa theo cả hai chiều trên màn hình đang chứa ứng dụng.
- Kích thước được giới hạn bởi `Screen.visualBounds`, chừa 16 logical pixel mỗi cạnh. Không nhân lại tỷ lệ DPI, không cộng dồn chênh lệch kích thước Scene/cửa sổ qua các lần điều hướng.
- Phòng chờ và phòng họp (cả giao diện cũ) tự mở tối đa bằng `Stage.setMaximized(true)`. Đây là chế độ phóng tối đa của Windows, giữ thanh tiêu đề và taskbar.
- Khi quay về sảnh, cửa sổ trở về kích thước bình thường và được căn giữa. Những màn hình ngoài phòng vẫn giữ chế độ mở rộng do người dùng tự chọn.
- Thay đổi chế độ maximize/restore khi cửa sổ tạm ẩn rồi hiển thị lại. Kiểm thử Windows phát hiện chỉ đặt cờ maximize có thể vẫn giữ kích thước cũ 1100×700; cách chuyển mới áp đúng kích thước native.

## Kiểm tra

```powershell
mvn -B '-Dtest=WindowPlacementTest,PreJoinLayoutTest,NavigationThemeRegressionTest,AuthFlowTest,RoomFlowTest' test
```

Kết quả cuối: **30 test, 0 failure, 0 error, 0 skipped**, BUILD SUCCESS (45,201 giây). `git diff --check` thành công.

WindowPlacementTest kiểm tra vùng hiển thị nhỏ, tọa độ màn hình phụ âm/dương và cửa sổ thật qua đăng nhập → sảnh → phòng chờ → phòng họp → sảnh; điều hướng lặp không tăng kích thước. Test kiểm tra kích thước thực tế khi maximize, không chỉ kiểm tra cờ boolean.

Máy kiểm thử Windows, DPI 125%: vùng hiển thị JavaFX 1536×816. Auth: 960×600 tại (288,108); sảnh: 1100×700 tại khoảng (218,58). Phòng chờ và phòng họp có kích thước ngoài khoảng 1550,4×830,4 tại (-7,2;-7,2), gồm viền vô hình của cửa sổ maximized trên Windows; phần nội dung nằm trong vùng hiển thị. Đã xem ảnh render của cả bốn màn hình, các nút footer/toolbar hiển thị đủ.

Ảnh và tọa độ: `target/qa/window-placement/{auth,lobby,prejoin,meeting}.{png,txt}`. Log kiểm tra riêng cửa sổ: `target/window-placement-visual-run.log`; log hồi quy: `target/window-placement-run.log`.

Test dùng tài khoản/phòng thử nghiệm; không mở camera/mic và không sử dụng tài khoản của người dùng. Biên dịch với release 21 trên JDK 22.0.1 như các lượt kiểm thử trước.
