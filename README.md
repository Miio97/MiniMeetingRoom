# MiniMeetingRoom
Mini Meeting Room là ứng dụng họp trực tuyến được xây dựng bằng Java, hỗ trợ tạo/tham gia phòng họp, nhắn tin và giao tiếp giữa nhiều người dùng qua mạng.

## Chạy giao diện client

Toàn bộ giao diện từ MiniMeetingRoom-UIDemo nằm trong `src/main/java/client/UI`
(package `client.UI`), gồm các màn hình, hộp thoại, thành phần giao diện và dữ liệu
giả trong `mock/MockData.java`. CSS nằm tại `src/main/resources/styles/app.css`.
Class khởi chạy là `client.Main` tại `src/main/java/client/Main.java`.

Cần JDK 21 trở lên và Maven. Chạy từ thư mục chứa `pom.xml`:

```sh
mvn clean javafx:run
```

Trong NetBeans, mở dự án MiniMeetingRoom rồi chọn **Run Project**. `nbactions.xml`
đã cấu hình Run/Debug để khởi chạy `client.Main` bằng JavaFX.

Hiện tại client chỉ chạy UI demo với dữ liệu giả và các tương tác có sẵn của demo;
chưa kết nối giao diện với database, server, socket hoặc WebRTC.
Đăng nhập bằng tên và mật khẩu bất kỳ không trống để xem giao diện.
