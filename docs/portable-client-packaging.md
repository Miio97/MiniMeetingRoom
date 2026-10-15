# Đóng gói Java runtime cho client Windows

Runtime phải chứa `jdk.management`: Diagnostics dùng `com.sun.management.OperatingSystemMXBean` ngay trong `MainApp.init()`. `java.se` không bao gồm module này. Phân tích `jdeps --print-module-deps` trên JAR và các dependency runtime cũng yêu cầu `jdk.jfr`.

Tạo runtime vào một thư mục mới trong `target`:

```powershell
./scripts/build-client-runtime.ps1 -JavaHome 'C:/Program Files/Java/jdk-22' -OutputPath 'target/portable-build/runtime'
```

Sau khi sao chép JAR, runtime dependency, JavaFX Windows, cấu hình client và chứng chỉ công khai, biên dịch `scripts/packaging/PortableStartupCheck.java` với JDK, rồi chạy bằng **java.exe trong gói**, từ thư mục `app` và với module-path/classpath của launcher. Đặt diagnostics và thư mục tạm của bài thử bên ngoài thư mục đóng gói. Bài kiểm tra gọi `MainApp.init()`, khởi động màn hình đăng nhập với cửa sổ ẩn, đóng ứng dụng và nạp WebRTC ở chế độ headless; không truy cập mic/loa hoặc đăng nhập tài khoản.

Không chỉ dùng `Class.forName("client.Main")` hoặc khởi tạo JavaFX toolkit: các bước đó không chạy phần diagnostics và đã bỏ sót lỗi thiếu module.

Kiểm chứng ngày 16/10/2026: bài kiểm tra trên image cũ tái hiện `ClassNotFoundException: com.sun.management.OperatingSystemMXBean`; image mới trả `PORTABLE_MAINAPP_STARTUP_OK`, exit 0. Bằng chứng local nằm tại `target/qa/lan-client-portable-startup.txt`, `target/qa/lan-client-portable-runtimefix-startup.txt` và `target/qa/portable-runtime-fix-build.txt`.

Gói phân phối chỉ chứa cấu hình client và chứng chỉ công khai; không sao chép keystore, private properties, cấu hình database hoặc file `.env`.
