# Cài đặt tài khoản

Mở **Cài đặt → Tài khoản**, sửa tên đăng nhập, tên hiển thị hoặc email rồi chọn **Lưu thay đổi**. Server lưu thay đổi trong bảng `Nguoi_Dung` và trả thông tin mới cho giao diện. ID tài khoản, quyền phòng, dữ liệu liên quan và phiên đăng nhập hiện tại được giữ nguyên. Những lần đăng nhập sau dùng tên đăng nhập mới và phải nhập đúng chữ hoa/thường.

- Tên đăng nhập: 1–50 ký tự, không có khoảng trắng hoặc ký tự điều khiển; ràng buộc unique hiện tại của database quyết định trùng tên.
- Tên hiển thị: 1–100 ký tự sau khi bỏ khoảng trắng hai đầu, không có ký tự điều khiển.
- Email: có thể để trống; nếu nhập phải đúng định dạng, tối đa 255 ký tự và chưa được tài khoản khác sử dụng. Chuỗi trống được lưu thành NULL.
- Lưu lại giá trị của chính tài khoản đang đăng nhập được phép; lỗi trùng tên/email không lưu một phần các trường còn lại.

**Đổi mật khẩu** luôn hiển thị. Nút được làm mờ và vô hiệu hóa cho đến khi snapshot tài khoản có email đã xác thực và ô email vẫn khớp địa chỉ đó; sửa sang email khác sẽ vô hiệu hóa nút ngay. Khi được mở, hộp thoại yêu cầu mật khẩu hiện tại, mật khẩu mới và xác nhận. Mật khẩu mới dùng cùng quy tắc đăng ký: 8–128 ký tự, có chữ thường, chữ hoa, chữ số và ký tự đặc biệt, đồng thời phải khác mật khẩu hiện tại. Server xác minh mật khẩu cũ và lưu hash có salt bằng `PasswordHasher`; không trả password/hash cho client. Các ô mật khẩu được xóa sau khi thành công hoặc đóng hộp thoại.

Đổi thành công giữ phiên gửi yêu cầu, thu hồi các phiên khác của cùng tài khoản và thông báo cho các dịch vụ phòng/signaling qua `SessionRevocationListener`. Mật khẩu và thu hồi phiên được commit trong cùng transaction; nếu database thất bại, cả hai được rollback. Account được khóa trước session để một yêu cầu đổi mật khẩu từ phiên vừa bị thu hồi không thể ghi đè mật khẩu mới.

Snapshot `AuthUser.emailVerified` được server xác định từ bằng chứng OTP gắn với đúng tài khoản và email. Xác thực đăng ký hoặc khôi phục mật khẩu thành công tạo bằng chứng này; client không được tự đánh dấu email đã xác thực. Đổi tên đăng nhập/tên hiển thị vẫn giữ xác thực, đổi sang email khác hoặc xóa email làm mất xác thực. Yêu cầu reset mới không tự xóa bằng chứng xác thực đã có.

Nút **Quên mật khẩu** tại đăng nhập mở luồng khôi phục OTP đang hoạt động. Trong tab tài khoản, nút này đăng xuất phiên hiện tại qua server trước rồi mở cùng luồng khôi phục; không gửi OTP trước khi người dùng nhập email và chọn gửi mã. Nếu đăng xuất lỗi, vẫn giữ phiên và báo lỗi. Nút **Xác thực email (OTP)** bên phải ô email vẫn là chỗ dành cho xác thực email mới trực tiếp khi đang đăng nhập; tooltip phân biệt chức năng này với đăng ký/khôi phục OTP đã hoạt động.

Constructor/response cũ mặc định `emailVerified=false` và không cho true khi email trống. Bằng chứng đăng ký lịch sử chưa có ID tài khoản ràng buộc không được tự nhận là đã xác thực; tài khoản vẫn đăng nhập và dùng khôi phục OTP bình thường để có bằng chứng mới.

## Giao thức và dữ liệu

Hai lệnh mới chạy qua listener auth TCP/TLS hiện có:

| Lệnh | Dữ liệu | Kết quả |
| --- | --- | --- |
| `UPDATE_PROFILE` | `token`, `username`, `displayName`, `email` | Snapshot `AuthUser` mới; không cấp lại token |
| `CHANGE_PASSWORD` | `token`, `password` (hiện tại), `newPassword` | Xác nhận đổi mật khẩu và snapshot `AuthUser`; giữ token hiện tại |

Danh tính được suy ra từ session hợp lệ trong database, không nhận ID người dùng cần chỉnh sửa từ client. Server kiểm tra hết hạn, thu hồi và trạng thái tài khoản trong transaction. Client kiểm tra nhập liệu để phản hồi nhanh; server luôn kiểm tra lại. Khi nhận `SESSION_EXPIRED`, giao diện trở về đăng nhập.

Bản ghép tái sử dụng hai bảng từ [002-email-otp.sql](../database/migrations/002-email-otp.sql); database đã áp dụng migration OTP không cần bảng/cột mới. Nếu chưa dùng OTP, áp dụng script trước theo [hướng dẫn email OTP](email-otp.md). Không tự sửa database Railway. Cần biên dịch và khởi động lại cả server lẫn client để dùng lệnh mới. `AuthRequest` giữ riêng `otp`, `registrationChallenge` và `newPassword`; các constructor cũ của cả hai nhánh vẫn hoạt động. `toString()` không chứa mật khẩu, OTP, challenge hoặc token.

## Kiểm thử

`AuthServiceTest` kiểm tra lưu dữ liệu, đăng nhập bằng tên mới, giá trị trùng/không hợp lệ, session hết hạn/thu hồi/tài khoản khóa, mật khẩu cũ sai, hash mới, thu hồi đúng phiên, rollback và yêu cầu đồng thời.

`AuthFlowTest` thao tác JavaFX qua TLS thật và database H2 riêng: lưu hồ sơ, cập nhật sidebar, báo lỗi và thử lại, xác nhận mật khẩu, đổi mật khẩu, trở về đăng nhập khi session hết hạn và mở form khôi phục. `OtpAccountSettingsUiTest` dùng backend thật để kiểm tra đăng ký OTP, trạng thái xác thực, cài đặt hồ sơ, đổi mật khẩu và khôi phục mật khẩu cùng nhau; kiểm tra thêm đăng xuất lỗi rồi thử lại nút khôi phục. `EmailOtpAccountSettingsIntegrationTest` kiểm tra bằng chứng xác thực bền vững, thay đổi email, mã cũ bị hủy, chuyển địa chỉ giữa tài khoản và các yêu cầu đồng thời. `AuthWireTest`/`AccountOtpCompatibilityTest` kiểm tra cả hai loại password và OTP đi đúng trường, che secret trong `toString`. `WorkspaceThemeTest` kiểm tra tab cài đặt theo theme.

Chạy nhóm kiểm tra:

```powershell
mvn -B "-Dtest=AuthServiceTest,AuthWireTest,AuthServerTest,AuthFlowTest,EmailOtp*,OtpAccountSettingsUiTest,AccountOtpCompatibilityTest,WorkspaceThemeTest" test
```

Kiểm thử gồm trạng thái chưa/đã xác thực, sửa email, nút đổi mật khẩu luôn hiển thị nhưng chỉ cho bấm khi email đã xác thực và nút xác thực email trong cài đặt chưa gửi yêu cầu. Ảnh giao diện: `target/qa/account-settings.png`. Log bản ghép và kết quả hồi quy được ghi trong [biên bản ghép](merge-resolution.md). Kiểm thử không cập nhật database Railway hoặc gửi mail thật.
