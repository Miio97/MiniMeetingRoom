# Cài đặt tài khoản

Mở **Cài đặt → Tài khoản**, sửa tên đăng nhập, tên hiển thị hoặc email rồi chọn **Lưu thay đổi**. Server lưu thay đổi trong bảng `Nguoi_Dung` và trả thông tin mới cho giao diện. ID tài khoản, quyền phòng, dữ liệu liên quan và phiên đăng nhập hiện tại được giữ nguyên. Những lần đăng nhập sau dùng tên đăng nhập mới và phải nhập đúng chữ hoa/thường.

- Tên đăng nhập: 1–50 ký tự, không có khoảng trắng hoặc ký tự điều khiển; ràng buộc unique hiện tại của database quyết định trùng tên.
- Tên hiển thị: 1–100 ký tự sau khi bỏ khoảng trắng hai đầu, không có ký tự điều khiển.
- Email: có thể để trống; nếu nhập phải đúng định dạng, tối đa 255 ký tự và chưa được tài khoản khác sử dụng. Chuỗi trống được lưu thành NULL.
- Lưu lại giá trị của chính tài khoản đang đăng nhập được phép; lỗi trùng tên/email không lưu một phần các trường còn lại.

**Đổi mật khẩu** luôn hiển thị. Nút được làm mờ và vô hiệu hóa cho đến khi snapshot tài khoản có email đã xác thực và ô email vẫn khớp địa chỉ đó; sửa sang email khác sẽ vô hiệu hóa nút ngay. Khi được mở, hộp thoại yêu cầu mật khẩu hiện tại, mật khẩu mới và xác nhận. Mật khẩu mới dùng cùng quy tắc đăng ký: 8–128 ký tự, có chữ thường, chữ hoa, chữ số và ký tự đặc biệt, đồng thời phải khác mật khẩu hiện tại. Server xác minh mật khẩu cũ và lưu hash có salt bằng `PasswordHasher`; không trả password/hash cho client. Các ô mật khẩu được xóa sau khi thành công hoặc đóng hộp thoại.

Đổi thành công giữ phiên gửi yêu cầu, thu hồi các phiên khác của cùng tài khoản và thông báo cho các dịch vụ phòng/signaling qua `SessionRevocationListener`. Mật khẩu và thu hồi phiên được commit trong cùng transaction; nếu database thất bại, cả hai được rollback. Account được khóa trước session để một yêu cầu đổi mật khẩu từ phiên vừa bị thu hồi không thể ghi đè mật khẩu mới.

Nút **Xác thực email (OTP)** nằm bên phải ô email và đang disabled theo yêu cầu. Nút **Quên mật khẩu** ở màn hình đăng nhập và tab tài khoản cũng đang disabled. Chưa triển khai gửi email, OTP hoặc đặt lại mật khẩu. Snapshot `AuthUser` bổ sung `emailVerified`, mặc định false với constructor/response cũ và không cho true khi email trống. Hiện server chưa có nguồn xác thực OTP nên tất cả tài khoản thực tế vẫn chưa xác thực; nút đổi mật khẩu vì vậy vẫn được làm mờ và chưa cho bấm. Không tự đánh dấu email là đã xác thực khi nhập hoặc lưu hồ sơ.

## Giao thức và dữ liệu

Hai lệnh mới chạy qua listener auth TCP/TLS hiện có:

| Lệnh | Dữ liệu | Kết quả |
| --- | --- | --- |
| `UPDATE_PROFILE` | `token`, `username`, `displayName`, `email` | Snapshot `AuthUser` mới; không cấp lại token |
| `CHANGE_PASSWORD` | `token`, `password` (hiện tại), `newPassword` | Xác nhận đổi mật khẩu và snapshot `AuthUser`; giữ token hiện tại |

Danh tính được suy ra từ session hợp lệ trong database, không nhận ID người dùng cần chỉnh sửa từ client. Server kiểm tra hết hạn, thu hồi và trạng thái tài khoản trong transaction. Client kiểm tra nhập liệu để phản hồi nhanh; server luôn kiểm tra lại. Khi nhận `SESSION_EXPIRED`, giao diện trở về đăng nhập.

Không thay đổi schema hoặc cấu hình Railway. Cần biên dịch và khởi động lại cả server lẫn client để dùng lệnh mới. Các constructor cũ của `AuthRequest` được giữ để tương thích mã nguồn hiện có; `toString()` không chứa mật khẩu hoặc token.

## Kiểm thử

`AuthServiceTest` kiểm tra lưu dữ liệu, đăng nhập bằng tên mới, giá trị trùng/không hợp lệ, session hết hạn/thu hồi/tài khoản khóa, mật khẩu cũ sai, hash mới, thu hồi đúng phiên, rollback và yêu cầu đồng thời.

`AuthFlowTest` thao tác JavaFX qua TLS thật và database H2 riêng: lưu hồ sơ, cập nhật sidebar, báo lỗi và thử lại, xác nhận mật khẩu, đổi mật khẩu, trở về đăng nhập khi session hết hạn và nút quên mật khẩu không thực hiện tác vụ. `AuthWireTest` kiểm tra truyền password mới và che secret trong `toString`. `WorkspaceThemeTest` kiểm tra tab cài đặt theo theme.

Chạy nhóm kiểm tra:

```powershell
mvn -B "-Dtest=AuthServiceTest,AuthWireTest,AuthServerTest,AuthFlowTest,WorkspaceThemeTest" test
```

Log kiểm tra giao diện OTP: `logs/account-email-ui-check.log`. Kiểm thử gồm trạng thái chưa/đã xác thực, sửa email, nút luôn hiển thị nhưng chỉ cho bấm khi email đã xác thực và nút OTP không gửi yêu cầu. Log kiểm tra chức năng tài khoản trước đó: `logs/account-check.log`. Ảnh giao diện: `target/qa/account-settings.png`. Kiểm thử không cập nhật database Railway.
