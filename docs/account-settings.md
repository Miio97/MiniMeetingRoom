# Cài đặt tài khoản

Mở **Cài đặt → Tài khoản**, sửa tên đăng nhập, tên hiển thị hoặc email rồi chọn **Lưu thay đổi**. Server lưu thay đổi trong bảng `Nguoi_Dung` và trả thông tin mới cho giao diện. ID tài khoản, quyền phòng, dữ liệu liên quan và phiên đăng nhập hiện tại được giữ nguyên. Những lần đăng nhập sau dùng tên đăng nhập mới và phải nhập đúng chữ hoa/thường.

- Tên đăng nhập: 1–50 ký tự, không có khoảng trắng hoặc ký tự điều khiển; ràng buộc unique hiện tại của database quyết định trùng tên.
- Tên hiển thị: 1–100 ký tự sau khi bỏ khoảng trắng hai đầu, không có ký tự điều khiển.
- Email: có thể để trống; nếu nhập phải đúng định dạng, tối đa 255 ký tự và chưa được tài khoản khác sử dụng. Chuỗi trống được lưu thành NULL.
- Lưu lại giá trị của chính tài khoản đang đăng nhập được phép; lỗi trùng tên/email không lưu một phần các trường còn lại.

**Đổi mật khẩu** luôn hiển thị. Nút được làm mờ và vô hiệu hóa cho đến khi snapshot tài khoản có email đã xác thực và ô email vẫn khớp địa chỉ đó; sửa sang email khác sẽ vô hiệu hóa nút ngay. Khi được mở, hộp thoại yêu cầu mật khẩu hiện tại, mật khẩu mới và xác nhận. Mật khẩu mới dùng cùng quy tắc đăng ký: 8–128 ký tự, có chữ thường, chữ hoa, chữ số và ký tự đặc biệt, đồng thời phải khác mật khẩu hiện tại. Server xác minh mật khẩu cũ và lưu hash có salt bằng `PasswordHasher`; không trả password/hash cho client. Các ô mật khẩu được xóa sau khi thành công hoặc đóng hộp thoại.

Đổi thành công giữ phiên gửi yêu cầu, thu hồi các phiên khác của cùng tài khoản và thông báo cho các dịch vụ phòng/signaling qua `SessionRevocationListener`. Mật khẩu và thu hồi phiên được commit trong cùng transaction; nếu database thất bại, cả hai được rollback. Account được khóa trước session để một yêu cầu đổi mật khẩu từ phiên vừa bị thu hồi không thể ghi đè mật khẩu mới.

Snapshot `AuthUser.emailVerified` được server xác định từ bằng chứng OTP gắn với đúng tài khoản và email. Xác thực đăng ký hoặc khôi phục mật khẩu thành công tạo bằng chứng này; client không được tự đánh dấu email đã xác thực. Đổi tên đăng nhập/tên hiển thị vẫn giữ xác thực, đổi sang email khác hoặc xóa email làm mất xác thực. Yêu cầu reset mới không tự xóa bằng chứng xác thực đã có.

Nút **Quên mật khẩu** tại đăng nhập mở luồng khôi phục OTP và thu hồi toàn bộ phiên cũ sau khi thành công. Trong tab tài khoản, nút này mở hộp thoại ngay trong cài đặt, dùng email đã lưu: chọn **Gửi mã OTP**, nhập mã, mật khẩu mới và xác nhận, rồi chọn **Đặt lại mật khẩu**. Không cần mật khẩu cũ, không chuyển về đăng nhập hoặc rời phòng. Sau khi thành công, giữ nguyên token và thời hạn của phiên hiện tại, thu hồi các phiên khác; vẫn ở màn hình cài đặt. Mở/đóng hộp thoại, mã sai, lỗi SMTP hoặc mật khẩu không hợp lệ đều không tự đăng xuất. Nếu chưa lưu email hoặc ô email khác địa chỉ đã lưu, giao diện yêu cầu lưu trước khi mở hộp thoại. Chỉ gửi thư khi chọn gửi mã.

Nhãn ô địa chỉ là **Email**. Để xác thực tài khoản cũ hoặc địa chỉ mới: nhập email → **Lưu thay đổi** → **Xác thực email (OTP)** → **Gửi mã OTP** → nhập mã 6 số → **Xác thực**. Chỉ mở hộp thoại chưa gửi thư. Mã hết hạn sau 5 phút, khóa sau 5 lần sai; gửi lại cách nhau 60 giây và tối đa 5 lần mỗi giờ. Lỗi SMTP cũng tính vào hạn mức, giữ mã trước đó nếu mã này vẫn còn hiệu lực. Hộp thoại cho phép nhập mã đã nhận khi mở lại trong thời gian chờ gửi lại.

Server bắt buộc có phiên đang hoạt động và email phải khớp địa chỉ đã lưu. Xác thực thành công chỉ cập nhật bằng chứng email, giữ nguyên mật khẩu và các phiên. Nút đổi mật khẩu mở ngay, nút xác thực được vô hiệu hóa cho địa chỉ đã xác thực. Đổi/xóa email làm mất bằng chứng và hủy cả mã xác thực/reset cũ; mã không chuyển quyền sang tài khoản khác khi email được gán lại. Server thật kiểm tra email đã xác thực khi xử lý đổi mật khẩu, không chỉ dựa vào nút trên giao diện.

Constructor/response cũ mặc định `emailVerified=false` và không cho true khi email trống. Bằng chứng đăng ký lịch sử chưa có ID tài khoản ràng buộc không được tự nhận là đã xác thực; tài khoản vẫn đăng nhập và có thể xác thực email ngay trong cài đặt hoặc dùng khôi phục OTP để có bằng chứng mới.

## Giao thức và dữ liệu

Các lệnh chạy qua listener auth TCP/TLS hiện có:

| Lệnh | Dữ liệu | Kết quả |
| --- | --- | --- |
| `UPDATE_PROFILE` | `token`, `username`, `displayName`, `email` | Snapshot `AuthUser` mới; không cấp lại token |
| `CHANGE_PASSWORD` | `token`, `password` (hiện tại), `newPassword` | Xác nhận đổi mật khẩu và snapshot `AuthUser`; giữ token hiện tại |
| `REQUEST_EMAIL_VERIFICATION` | `token`, `email` đã lưu | Gửi OTP mục đích `VERIFY`, trả `OTP_REQUIRED`, chưa xác thực |
| `VERIFY_EMAIL` | `token`, `email`, `otp` | Tiêu thụ mã và trả snapshot `AuthUser.emailVerified=true`; không cấp/thu hồi token |
| `REQUEST_ACCOUNT_PASSWORD_RESET` | `token`, `email` đã lưu | Gửi OTP mục đích `RESET` cho đúng tài khoản đang hoạt động |
| `RESET_ACCOUNT_PASSWORD` | `token`, `email`, `otp`, `password` mới | Tiêu thụ mã, lưu mật khẩu, giữ token hiện tại và thu hồi các phiên khác |

Danh tính được suy ra từ session hợp lệ trong database, không nhận ID người dùng cần chỉnh sửa từ client. Server kiểm tra hết hạn, thu hồi và trạng thái tài khoản trong transaction. Client kiểm tra nhập liệu để phản hồi nhanh; server luôn kiểm tra lại. Khi nhận `SESSION_EXPIRED`, giao diện trở về đăng nhập.

Bản ghép tái sử dụng hai bảng từ [002-email-otp.sql](../database/migrations/002-email-otp.sql); database đã áp dụng migration OTP không cần bảng/cột mới. Nếu chưa dùng OTP, áp dụng script trước theo [hướng dẫn email OTP](email-otp.md). Không tự sửa database Railway. Cần biên dịch và khởi động lại cả server lẫn client một lần để nạp lệnh mới; sau đó quên mật khẩu trong cài đặt không đăng xuất người dùng. `AuthRequest` giữ riêng `otp`, `registrationChallenge` và `newPassword`; các constructor cũ của cả hai nhánh vẫn hoạt động. Lệnh reset truyền mật khẩu mới trong `password`, còn `CHANGE_PASSWORD` dùng `password` cho mật khẩu hiện tại và `newPassword` cho mật khẩu mới. `toString()` không chứa mật khẩu, OTP, challenge hoặc token.

## Kiểm thử

`AuthServiceTest` kiểm tra lưu dữ liệu, đăng nhập bằng tên mới, giá trị trùng/không hợp lệ, session hết hạn/thu hồi/tài khoản khóa, mật khẩu cũ sai, hash mới, thu hồi đúng phiên, rollback và yêu cầu đồng thời.

`AuthFlowTest` thao tác JavaFX qua TLS thật và database H2 riêng: lưu hồ sơ, cập nhật sidebar, báo lỗi và thử lại, xác nhận mật khẩu, đổi mật khẩu, trở về đăng nhập khi session hết hạn và mở form khôi phục. `OtpAccountSettingsUiTest` dùng backend thật để kiểm tra đăng ký OTP, trạng thái xác thực, cài đặt hồ sơ, đổi mật khẩu và khôi phục ngay trong cài đặt; khẳng định không gửi `LOGOUT`, giữ token/màn hình hiện tại, thu hồi các phiên khác, báo lỗi gửi thư và đóng/mở lại hộp thoại. `EmailOtpAccountSettingsIntegrationTest` kiểm tra bằng chứng xác thực bền vững, thay đổi email, mã cũ bị hủy, chuyển địa chỉ giữa tài khoản, rollback và các yêu cầu đồng thời. `AuthWireTest`/`AccountOtpCompatibilityTest` kiểm tra cả hai loại password và OTP đi đúng trường, che secret trong `toString`. `WorkspaceThemeTest` kiểm tra tab cài đặt theo theme.

Chạy nhóm kiểm tra:

```powershell
mvn -B "-Dtest=AuthServiceTest,AuthWireTest,AuthServerTest,AuthFlowTest,EmailOtp*,OtpAccountSettingsUiTest,AccountOtpCompatibilityTest,WorkspaceThemeTest" test
```

Kiểm thử gồm trạng thái chưa/đã xác thực, tài khoản cũ chưa có email, lưu địa chỉ mới, gửi/nhập OTP thật qua backend thử nghiệm, mã sai/hết hạn/gửi lại, lỗi SMTP và hạn mức, chuyển email giữa tài khoản, dùng mã đồng thời, đổi mật khẩu và khôi phục mật khẩu. Log kiểm tra ba luồng: `target/account-email-validation.txt`. Ảnh giao diện: `target/qa/account-settings.png`. Kiểm thử không cập nhật database Railway hoặc gửi mail thật.

### Kiểm chứng ngày 18/10/2026

Kết quả mới nhất của 12 lớp xác thực: **120/120 ca đạt, không lỗi hoặc bỏ qua**, tổng hợp trong `target/account-email-result-summary.json`. Lượt đầu 119 ca còn một kỳ vọng cũ rằng nút OTP phải bị vô hiệu hóa; đã cập nhật kỳ vọng cho chức năng hiện có rồi chạy lại cả lớp liên quan. Lượt cuối **19/19 ca đạt**, **BUILD SUCCESS**, tạo `target/MiniMeetingRoom-1.0-SNAPSHOT.jar`; log `target/account-email-final.txt`. Có 11 ca mới cho xác thực email tài khoản, dữ liệu qua TLS, phản hồi sai và giao diện đầy đủ.

Đã xem ảnh tab tài khoản sau khi đổi nhãn và ảnh hộp thoại `target/qa/account-email-verification.png`. Kiểm tra Gmail STARTTLS, chứng chỉ và đăng nhập SMTP thành công, không gửi email (`target/account-email-smtp-check.txt`). Kiểm tra metadata MySQL hiện tại xác nhận cả hai bảng OTP có đủ cột (`target/account-email-schema-check.txt`); chỉ đọc schema, không đọc dữ liệu tài khoản hay ghi database. Không cần script MySQL mới. Chưa thử gửi email thật tới hộp thư người dùng; bộ kiểm thử chức năng dùng hộp thư giả.

### Nút vẫn bị mờ sau khi build

Build không thay thế những lớp Java đã được server/client đang chạy nạp vào bộ nhớ. Ngày 18/10, cửa sổ client mở từ 10:32 và server từ 01:31 vẫn dùng bản trước khi nút OTP được triển khai lúc 10:48. Kiểm tra trực tiếp listener localhost trả `INVALID_REQUEST` cho hai lệnh xác thực email. Đã dừng hai tiến trình cũ và mở lại đầy đủ server/client bằng bản mới; listener hiện nhận cả hai lệnh và trả `SESSION_EXPIRED` khi kiểm tra bằng token trống, đúng yêu cầu xác thực phiên. Log đối chiếu: `target/account-email-runtime-check.txt` và `target/account-email-runtime-after-restart.txt`; không gửi OTP trong phép kiểm tra này.

Sau khi sửa/build, dừng cả hai lượt chạy cũ trong NetBeans rồi chạy `server.Main` (hoặc `server.MeetingServerMain`) và `client.Main`, đăng nhập lại. Nhập email, chọn **Lưu thay đổi**, sau đó **Xác thực email (OTP)** và **Gửi mã OTP**. Nếu email đã được xác thực, nút OTP bị vô hiệu hóa là trạng thái đúng và nút đổi mật khẩu phải được mở. Với cách khởi động từ môi trường có TEMP rút gọn trên máy Windows này, hai VM option `-Djava.io.tmpdir=D:\MiniMeetingRoom\target\reopen-tmp` và `-Djdk.net.unixdomain.tmpdir=D:\MiniMeetingRoom\target\reopen-tmp` giúp Java kết nối loopback tới tiến trình video.

### Quên mật khẩu trong cài đặt, giữ phiên — 18/10/2026

Thay luồng đăng xuất trước khi khôi phục bằng `AccountPasswordResetDialog` ngay trong cài đặt. Gửi OTP và đặt lại mật khẩu dùng hai lệnh có token riêng ở bảng giao thức bên trên. Server xác minh phiên, tài khoản và email đã lưu trước khi tiêu thụ mã; mật khẩu, bằng chứng email và thu hồi các phiên khác được commit cùng nhau. Token/thời hạn của phiên gửi yêu cầu không đổi. Luồng ở màn hình đăng nhập vẫn thu hồi toàn bộ phiên cũ.

**140/140 kiểm thử đạt**, không thất bại/lỗi/bỏ qua, trong `target/account-recovery-in-place-validation.txt` (**BUILD SUCCESS**). Bộ này bao gồm 23 kiểm thử kết hợp backend; 7 ca backend mới kiểm tra giữ đúng phiên, tài khoản khác, email đã đổi, phiên bị thu hồi, mã sai/hết hạn, yêu cầu đồng thời và rollback khi ghi mật khẩu thất bại. Có thêm một ca transport TLS cho hai lệnh mới. Ba ca UI xác minh không gửi `LOGOUT`, không đổi màn hình/token khi thành công hoặc lỗi gửi thư, vẫn có thể đóng/mở lại hộp thoại, và thu hồi đúng các phiên khác.

Sau khi sửa bố cục để các thông báo dài không bị cắt, đã chạy lại **3/3 ca UI**, **BUILD SUCCESS**, tạo lại `target/MiniMeetingRoom-1.0-SNAPSHOT.jar`; log `target/account-recovery-in-place-ui-final.txt`. Đã xem ảnh hộp thoại tại `target/qa/account-password-recovery.png`. Kiểm thử dùng TLS localhost, H2 và hộp thư giả, không gửi email thật hoặc ghi MySQL. Không thêm bảng/cột/script SQL.

Đã nạp lại server/client cục bộ bằng bản mới lúc 11:44 ngày 18/10. Server báo sẵn sàng trên các cổng hiện có; phép kiểm tra trực tiếp hai lệnh mới bằng token trống trả `SESSION_EXPIRED`, xác nhận đã nhận lệnh và bắt buộc có phiên hợp lệ (`target/account-recovery-runtime-check.txt`). Không gửi OTP trong phép kiểm tra này. Việc nạp bản mới cần đăng nhập lại một lần; thao tác quên mật khẩu trong bản mới giữ nguyên phiên đang dùng.
