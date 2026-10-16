# Kiểm tra Light / Dark Theme

## Nguyên nhân và phạm vi sửa

- `app.css`: `.room-title`, `.room-meta`, `.sidebar-user`, badge, ready chip và tab Settings ghi cứng màu sáng. Chuyển sang looked-up color hiện có và bổ sung token semantic cho selected, hover, success, danger, disabled. Tên phòng và tài khoản áp dụng cho mọi dữ liệu, không phụ thuộc tên fixture.
- Navigation/logout chỉ có layout và trạng thái trong `.light-mode`; thêm kiểu cơ sở dùng cho cả hai theme, xử lý specificity của hover ở Light. `Icons` dùng `.sidebar-icon`; `IconButton` dùng `.vector-icon`. Nền avatar/video/branding có màu riêng được giữ nguyên có chủ đích.
- `ThemeManager`: popup có Scene riêng. Theo dõi PopupWindow khi xuất hiện, gắn stylesheet và đăng ký root với cơ chế weak roots hiện có. CSS bridge của PopupControl có styleable parent qua owner, nên được cấp palette sáng/tối rõ ràng để tránh giữ màu cũ phụ thuộc thứ tự cập nhật CSS. Bao phủ ContextMenu, ComboBox dropdown và Tooltip mới tạo/mở lại, kể cả đang mở lúc đổi theme.
- Nền `.root` tối phủ vùng ngoài bo góc popup. Root popup nay trong suốt; phần nội dung ContextMenu có border 1 px, nền, bo góc và shadow riêng. Không cần clip làm mất shadow.
- `Sidebar`: neo menu trên toàn bộ card hồ sơ; lần mở đầu dùng chiều cao skin đã đo để chừa khoảng cách 10 px. Bấm lại đóng menu.
- `LobbyScreen`: bỏ newline và binding chiều rộng mô tả theo phần trang trí chồng lên nội dung. Hình trang trí nằm trong hàng tiêu đề, phần mô tả có chiều dài dòng tối đa và vẫn co theo card. Tăng contrast camera qua CSS. Room title có ràng buộc co giãn; badge giữ chiều rộng để không bị ép xuống dòng.
- `SettingsScreen`: dùng bộ chọn Sáng/Tối sẵn có trong tab Giao diện; các tab và switch dùng token chung.
- `HostMenuPopup`: bỏ đăng ký theme riêng vì đã có lifecycle chung.
- `DeviceSettingsDialog`: bổ sung `Ui.style` cho xác nhận đóng phòng còn thiếu.

Không sửa authentication, PIN, trạng thái phòng, protocol, database, server hoặc media.

## Regression tự động

`WorkspaceThemeTest` chạy runtime JavaFX thật, không mock CSS hoặc dùng so sánh pixel:

- Lobby production, room active/closed và tài khoản Toan; đổi theme tại chỗ và tạo lại component trong theme hiện tại.
- Contrast tối thiểu 4,5:1 cho tên phòng, metadata, username, caption, badge, button disabled và navigation active/hover.
- Menu tài khoản thật: border, root trong suốt, khoảng cách phía trên profile và đổi theme khi menu đang mở/mở lại.
- TextField, PasswordField, ComboBox và Tooltip; dropdown đang mở, control disabled và node tạo sau khi đổi theme.
- Ba tab Settings production; bộ chọn theme, trạng thái switch, giữ nguyên Scene.

Ảnh JavaFX snapshot được xuất ở `target/qa/themes/`: Lobby 1380 × 915, popup tài khoản, controls và ba tab Settings trong cả hai theme. Đây là ảnh dựng từ component production/test fixture, không phải ảnh chụp một phiên họp production với server thực.

## Kiểm tra thủ công còn nên làm

1. Chạy `client.Main` trên JDK 21 và máy đích, đổi Sáng/Tối trong Settings, quay về Lobby, mở/đóng menu bằng chuột và bàn phím.
2. Kiểm tra Windows scaling 100/125/150%, cửa sổ tối thiểu, màn hình nhỏ và nhiều màn hình; popup không che profile khi có screen auto-fix.
3. Refresh phòng từ server khi đang ở Dark; kiểm tra tên dài, danh sách rỗng, active/closed, host mở lại phòng và guest thấy Đã đóng.
4. Kiểm tra hover/pressed/focus/disabled bằng chuột và Tab; dropdown thiết bị, Tooltip và dialog đang mở lúc đổi theme.
5. Vào cuộc họp nhiều người có video/share thật, đổi theme, kiểm tra thanh công cụ, chat, file, participant, host menu và dialog. Các ảnh snapshot chưa xác nhận toàn bộ các tổ hợp media này.

## Kết quả ngày 16/10/2026

- Maven đi kèm NetBeans 22, JDK 22.0.1, compiler `release 21`, JavaFX 21.0.2. Chưa chạy runtime JDK 21 riêng.
- `mvn -B -DskipTests compile`: BUILD SUCCESS.
- `mvn -B test` ngoài sandbox trên bản sửa cuối (gồm CSS bridge và mở lại popup 4 lần): BUILD SUCCESS lúc 14:38, 16/10/2026 (giờ Việt Nam); 322 tests, 0 failures, 0 errors, 2 skipped. Skip do bài performance cần `-Dmmr.performance=true` và Windows không cấp quyền tạo symlink. Log: `target/full-test-unrestricted.log`.
- Lượt toàn bộ trong sandbox trước đó thất bại do loopback bị chặn và đổi tên file tạm bị AccessDenied; lượt ngoài sandbox đã xác nhận đây là giới hạn môi trường.
- Sau chỉnh sửa CSS disabled/prompt cuối cùng, chạy lại `WorkspaceThemeTest,ThemeManagerTest,HostMenuPopupTest`: 6 tests, 0 failures/errors/skipped. Log: `target/final-theme-test.log`. Compile cuối: `target/final-compile.log`.
- Đã mở và quan sát ảnh snapshot Lobby, popup tài khoản, controls, ba tab Settings trong cả hai theme. Ảnh không thay thế kiểm tra media thật và Windows scaling ở trên.
- `git diff --check`: không có lỗi whitespace.
