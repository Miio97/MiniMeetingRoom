# Xác thực đăng ký và quên mật khẩu bằng email

## Cài đặt máy chủ

1. Sao lưu database hiện tại rồi chạy toàn bộ [002-email-otp.sql](../database/migrations/002-email-otp.sql) trên **đúng database MySQL đang dùng**. Script có thể chạy lại; chỉ tạo hai bảng `Auth_Email_OTP` và `Auth_Email_OTP_Send`, không sửa dữ liệu tài khoản/phòng hiện có. Không cần chạy lại toàn bộ `schema.sql`.
2. Sao chép `config/mail.properties.example` thành `config/mail.private.properties` trên **máy chủ**. Điền `mail.smtp.user`, `mail.smtp.password`, `mail.from`. File riêng tư phải được giữ ngoài Git và không chuyển sang máy client.
3. Với Gmail, bật Xác minh 2 bước và tạo mật khẩu ứng dụng. Dùng địa chỉ Gmail làm user/from, mật khẩu ứng dụng làm password. Gmail SMTP mặc định `smtp.gmail.com:587`, yêu cầu STARTTLS và kiểm tra tên máy chủ/chứng chỉ. Không tắt kiểm tra TLS khi lỗi kết nối.
4. Chạy máy chủ bằng cấu hình hiện tại. SMTP được tải khi gửi OTP; thiếu cấu hình hoặc lỗi gửi đăng ký trả thông báo an toàn, không in mật khẩu/OTP/lỗi SMTP thô.

Biến môi trường ưu tiên hơn file: `MMR_SMTP_HOST`, `MMR_SMTP_PORT`, `MMR_SMTP_USER`, `MMR_SMTP_PASSWORD`, `MMR_SMTP_FROM`. `MMR_MAIL_CONFIG` chọn đường dẫn file riêng (mặc định tương đối với thư mục chạy: `config/mail.private.properties`). Không đặt biến SMTP trên client. Các timeout kết nối/đọc/ghi lần lượt 10/15/15 giây.

Tài liệu nhà cung cấp: [Angus Mail SMTP](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html), [thiết lập Gmail cho ứng dụng email](https://support.google.com/mail/answer/7126229?hl=en), [mật khẩu ứng dụng Google](https://support.google.com/accounts/answer/185833?hl=en).

## Luồng xử lý

- `REGISTER`: yêu cầu email hợp lệ. Lưu tên đăng nhập/tên hiển thị và **mật khẩu đã băm** ở trạng thái chờ, gửi OTP, trả `success=true, code=OTP_REQUIRED` cùng `registrationChallenge` ngẫu nhiên cho luồng đăng ký hiện tại. Chưa tạo `Nguoi_Dung`, chưa tạo phiên đăng nhập. Client chỉ giữ challenge trong bộ nhớ; database lưu SHA-256 của challenge.
- `VERIFY_REGISTRATION(email, otp, registrationChallenge)`: phải đúng cả luồng đăng ký và OTP mới tạo tài khoản và tiêu thụ mã trong cùng transaction. Sau đó đăng nhập bình thường. Luồng cũ không thể vô tình kích hoạt mật khẩu của một yêu cầu đăng ký mới cùng email.
- `RESEND_REGISTRATION_OTP(email, registrationChallenge)`: gửi mã mới cho đúng luồng đăng ký đang chờ, giữ nguyên challenge; mã cũ mất hiệu lực sau khi gửi thành công và commit. Yêu cầu đăng ký mới thay thế challenge; UI cũ cần quay lại đăng ký thay vì tiếp tục xác thực luồng khác.
- `REQUEST_PASSWORD_RESET(email)`: trả cùng thông báo chung dù email có tài khoản, bị khóa, chưa tồn tại, bị giới hạn gửi hay SMTP không hoạt động. Chỉ tài khoản `HOAT_DONG` nhận email. Không trả thông tin xác nhận sự tồn tại tài khoản.
- `RESET_PASSWORD(email, otp, password)`: xác minh mã, tài khoản đang hoạt động và email vẫn gắn với tài khoản lúc yêu cầu; cập nhật mật khẩu, thu hồi **mọi phiên đăng nhập** của tài khoản và tiêu thụ OTP trong cùng transaction. Sau commit, thông báo các dịch vụ trong tiến trình để đóng phiên cũ; các lần kiểm tra phiên định kỳ là phương án dự phòng.

Email được cắt khoảng trắng hai đầu và chuyển chữ thường; các dấu/ký tự khác vẫn phải khớp chính xác. Hai bảng OTP dùng `utf8mb4_bin` cho cột email. Máy chủ kiểm tra email thực tế của tài khoản bằng Java trước khi gửi và khi đặt lại mật khẩu, tránh nhầm mailbox do collation không phân biệt dấu của bảng tài khoản cũ; không sửa collation của `Nguoi_Dung` hiện có. Tên đăng nhập vẫn phân biệt hoa/thường khi đăng nhập; quy tắc mật khẩu hiện có được giữ cho đăng ký và đặt lại mật khẩu. Tài khoản cũ có email có thể dùng quên mật khẩu; tài khoản không có email vẫn đăng nhập được nhưng cần quy trình quản trị để bổ sung email.

## Giới hạn và tính nhất quán

OTP gồm 6 chữ số ngẫu nhiên bằng `SecureRandom`, hết hạn sau 5 phút, chỉ dùng một lần. Database chỉ lưu mã băm PBKDF2 có salt. Tối đa 5 lần nhập sai cho mỗi mã; lần thứ 5 khóa mã. Mỗi email/mục đích (`REGISTER` hoặc `RESET`) cách nhau tối thiểu 60 giây giữa các lần yêu cầu được chấp nhận, tối đa 5 lần trong **60 phút gần nhất**. Lần gửi lỗi SMTP cũng tính vào hạn mức để tránh gọi SMTP liên tục khi nhà cung cấp gặp sự cố. Gửi lại thành công sau thời gian chờ tạo mã mới, đồng thời đặt lại số lần nhập sai.

Hàng challenge khóa bằng InnoDB `FOR UPDATE` và upsert, nên nhiều tiến trình máy chủ dùng chung database vẫn không thể cùng sử dụng một mã. Gửi email nằm trong transaction đang giữ khóa; lỗi gửi không thay thế mã cũ. Email gửi được nhưng commit database thất bại có thể nhận mã không dùng được; yêu cầu gửi lại khi gặp tình huống này. Không tự chạy migration khi mở ứng dụng.

`AuthService()` và `AuthService(SessionRevocationListener)` trong máy chủ thật luôn bật OTP. Constructor tiêm 4/5 tham số cũ chỉ giữ đăng ký trực tiếp để tương thích **fixture kiểm thử nội bộ**; không dùng chúng làm cấu hình production. Test OTP dùng constructor 6 tham số với `EmailOtpService` và mail giả, không gửi thư thật và không kết nối Railway.

Các bản ghi pending có thể chứa tên/email/tên hiển thị; coi database là dữ liệu riêng tư. Các hàng lịch sử gửi cũ được xóa theo email/mục đích khi có lần gửi mới; quản trị có thể định kỳ xóa thêm lịch sử `Auth_Email_OTP_Send` cũ hơn 1 ngày và challenge `REGISTER`/`RESET` đã hết hạn lâu theo chính sách lưu dữ liệu. Không xóa challenge đang dùng nếu có tiến trình gửi/xác minh. Giữ các hàng `Purpose='VERIFIED'`: đây là bằng chứng xác thực email hoặc dấu vô hiệu hóa bằng chứng cũ, không phải mã OTP hết hạn.

## Tích hợp cài đặt tài khoản

Đăng ký hoặc đặt lại mật khẩu bằng OTP thành công lưu bằng chứng `VERIFIED` gắn với ID tài khoản và email trong bảng `Auth_Email_OTP` hiện có. Server dùng bằng chứng này cho `AuthUser.emailVerified`; yêu cầu gửi reset mới không làm mất trạng thái đã xác thực. Không cần thêm bảng/cột hoặc chạy migration mới ngoài `002-email-otp.sql`.

Đổi tên đăng nhập/tên hiển thị giữ trạng thái xác thực. Đổi hoặc xóa email hủy bằng chứng và mã reset đang chờ của tài khoản trong cùng transaction. Đổi email trở lại địa chỉ cũ không tự khôi phục bằng chứng hoặc mã đã hủy. Các đăng ký lịch sử chưa ràng buộc `Account_Id` không được tự suy ra đã xác thực; người dùng vẫn đăng nhập và có thể xác thực qua luồng khôi phục OTP mới.

Trong cài đặt, **Đổi mật khẩu** xác minh mật khẩu hiện tại, giữ phiên đang dùng và thu hồi những phiên khác. **Quên mật khẩu** đăng xuất qua server rồi mở form khôi phục; hoàn thành reset sẽ thu hồi toàn bộ phiên cũ. Nếu đăng xuất lỗi, giao diện giữ phiên và cho phép thử lại. Xem [cài đặt tài khoản](account-settings.md).

## Kiểm chứng ngày 18/10/2026

Đã cấu hình SMTP trên máy phát triển trong file riêng tư được Git bỏ qua. Kiểm tra kết nối STARTTLS, tên máy chủ/chứng chỉ và đăng nhập Gmail thành công; kiểm tra này **không gửi email** (`target/otp-smtp-auth-check.txt`). Client cho phép chờ phản hồi tối đa ít nhất 60 giây cho ba lệnh gửi mail; không chặn luồng giao diện.

Bộ xác thực cuối đạt **103/103 kiểm thử**, không lỗi/bỏ qua, gồm **34 kiểm thử mới**: 25 backend OTP, 4 giao diện JavaFX, 4 transport TLS và 1 luồng đầy đủ TLS/H2. Bao phủ thời hạn mã, thử sai, gửi lại, giới hạn 60 phút, lỗi SMTP, dùng lại mã, xác minh đồng thời, mailbox khác dấu, thay thế đăng ký đang chờ và thu hồi mọi phiên cũ. Log BUILD SUCCESS: `target/otp-final-validation.txt`. Mail trong các kiểm thử được thay bằng hộp thư giả, không gửi thư đến người khác.

Tổng hợp báo cáo mới nhất theo từng lớp sau lượt hồi quy và lượt kiểm tra lại: **418 ca, 416 đạt, 2 bỏ qua có điều kiện, 0 thất bại, 0 lỗi**, tại `target/otp-result-summary.json`. Lượt toàn bộ ban đầu (`target/otp-full-regression.txt`) được chạy trong khi hoàn thiện bảo vệ OTP, còn hai lỗi; cả hai lớp liên quan cùng các lớp xác thực phụ thuộc đã được biên dịch và chạy lại trong bộ 103 ca cuối. Tổng hợp là kết quả mới nhất của mỗi lớp, không phải tổng số lượt chạy lặp. Không chạy lại bộ native media vì thay đổi chỉ liên quan xác thực.

Đã kiểm tra ảnh giao diện 960×600 tại `target/auth-register-otp-preview.png` và `target/auth-reset-password-preview.png`. Mật khẩu SMTP không xuất hiện trong các file có thể đưa lên Git. Chưa áp dụng migration lên Railway: chọn đúng database đang dùng, chạy toàn bộ `002-email-otp.sql`, Clean and Build, rồi khởi động lại cả server và client bản mới. Database mới cũng cần migration này sau `schema.sql`.
