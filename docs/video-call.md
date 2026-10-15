# Video call qua WebRTC/SFU

Video đã được tích hợp vào các nút **Tạo phòng / Tham gia phòng** có sẵn trong sảnh, màn hình **PreJoinScreen** và **MeetingScreen**. Nút Mở phòng SFU riêng đã được bỏ khỏi sảnh. JavaFX/auth/phòng/signaling giữ ở Java; Go/Pion chỉ xử lý transport và chuyển tiếp VP8. Không dùng mesh, video Base64, WebView hoặc SFU hoàn chỉnh của bên thứ ba. Voice chat và screenshare chưa triển khai theo phạm vi người dùng yêu cầu.

## Database

Không có bảng hoặc cột mới cho bước video. Tái sử dụng auth/session và room/membership cùng hai bảng MMR đã được người dùng áp dụng. Lease, publication, transport, subscription và trạng thái video sống trong bộ nhớ. Không lưu SDP, ICE hoặc nội dung camera vào Railway. Không tự chạy migration hoặc tạo dữ liệu thử trên Railway.

## Chạy trực tiếp trong NetBeans

1. Dừng server/client cũ. Nếu máy mới chưa có Go nội bộ, chạy một lần tại thư mục dự án:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\init-go-toolchain.ps1
   ```

2. **Clean and Build** để Maven lấy dependency `webrtc-java:0.19.0` cùng native Windows x64.
3. Run File `server/Main.java`, chờ `MMR_MEETING_SERVER_READY`. Main gọi bộ khởi động chung MeetingServerMain cho auth, phòng, signaling, truyền tệp (nếu được bật) và media. Java tự build khi cần và quản lý child process `.tools/bin/mmr-video.exe`. Binary nằm ngoài `target` để không mất sau Maven clean. Secret control tự tạo trong `config/video-server.private.properties`, được Git bỏ qua; không chép tới client. Go không nhận thông tin Railway.
4. Run File `client/Main.java` hai lần; dùng hai tài khoản khác nhau. Tại sảnh chọn **Tạo phòng**; client kia nhập **ID hoặc mã phòng và PIN** rồi nhấn **Tham gia**. Form phòng mới giới hạn 10 người theo chính sách MVP mặc định; chưa phải năng lực media đã đo.
5. Phòng chờ không tự hỏi quyền và không có nút **Quyền camera**. Nhấn **Bật camera**: nếu chưa cho phép, hộp thoại **Cho phép / Không cho phép** xuất hiện. Chấp nhận được lưu trên máy, danh sách thiết bị được tải và camera được bật theo chính thao tác vừa yêu cầu; những lần sau không hỏi lại nếu quyền đã lưu còn hợp lệ. Từ chối hoặc đóng hộp thoại giữ camera tắt và chặn vào giao diện họp; nhấn Bật camera để thử lại. Danh sách lọc camera ảo, ưu tiên webcam đã chọn rồi camera tích hợp; khi không tìm thấy thiết bị hiện **Không có camera**. Phòng chờ không hiển thị số camera, mô tả trạng thái kỹ thuật hoặc thông báo tính năng sẽ bổ sung. Preview vẫn cục bộ, không có PeerConnection publish.
6. Nút **Vào phòng họp** chỉ khả dụng khi quyền camera đã được cho phép. SceneManager kiểm tra lại điều kiện ở điểm chuyển màn hình để gọi trực tiếp cũng không bỏ qua được. Được phép nhưng đã tắt camera vẫn vào được; không bắt buộc phải phát hình. Camera đang xem trước sẽ gửi qua SFU khi vào họp. Nút **Camera** trên thanh công cụ bật/tắt nhanh thiết bị đã chọn. Mở **Cài đặt → Camera trên máy của bạn** để chọn thiết bị; cấu hình không chiếm vùng bên dưới lưới video. Đóng Cài đặt không dừng video. **Tắt camera** dừng capture/publication; rời phòng/logout đóng media.

Tab **Thành viên** có danh sách chờ duyệt và menu tắt thiết bị, mời ra, chuyển chủ cho host. Nút **Quản lý phòng** có khóa/mở khóa, duyệt thành viên và đóng phòng. Người bị dừng phải chủ động bật lại camera; host không bật camera từ xa. Tab **Tệp** vẫn dùng dịch vụ tệp của đồng đội với cùng ID phòng và membership; chat gửi/nhận thật qua TLS trong cùng phòng. Chủ phòng rời đi sẽ tự chuyển quyền cho thành viên còn lại; chỉ đóng phòng khi hết người hoặc chủ phòng chọn đóng.

**Ẩn thanh công cụ / Hiện thanh công cụ** ở mép dưới thu gọn toàn bộ phần điều khiển để video có nhiều chỗ hơn. Nút hiện lại luôn còn trên màn hình. Thu gọn không tắt camera, không đóng cuộc gọi hoặc dịch vụ tệp.

**Camera** có thể nhấn liên tục ở phòng chờ, trong cuộc họp và Cài đặt. Nhãn/biểu tượng phản hồi theo trạng thái người dùng vừa chọn; capture và SDP vẫn chạy nền. Một bộ điều phối chung giữ một tác vụ đang chạy và một trạng thái đích, không xếp hàng một lần mở thiết bị cho mỗi lần nhấn. Nếu tắt lúc đang đọc định dạng/mở camera, kiểm tra hủy trước capture và trước publish; nếu server đã nhận publish thì dọn publication. Định dạng được cache trong controller của phiên; mở danh sách vẫn cập nhật camera cắm/rút. Thời gian thiết bị khởi động và ICE không thể loại bỏ hoàn toàn.

**Cài đặt → Sáng/Tối** thay màu phòng họp và hộp Cài đặt ngay tại chỗ. Không dựng lại Scene hoặc đóng media. Vùng hiển thị video giữ nền riêng; các bề mặt cuộc họp, thanh công cụ, panel và dialog đổi theo theme chung.

## Quyền camera và lựa chọn được ghi nhớ

Lựa chọn được lưu cho người dùng Windows hiện tại tại `%LOCALAPPDATA%/MiniMeetingRoom/camera-preferences.properties`, không lưu vào Railway. Cả cho phép và từ chối được lưu qua restart/logout; khi đã từ chối, thao tác **Bật camera** sẽ hỏi lại để người dùng có thể đổi quyết định. File có quyết định quyền và descriptor thiết bị được chọn, không có token/tài khoản/phòng. Metadata không ghi ra diagnostics. Test dùng file riêng trong target/temp, không đọc hoặc thay lựa chọn camera thật của người dùng. Đây là điều kiện điều hướng phía client; phòng/thành viên vẫn được server xác thực như trước.

Đây là sự đồng ý cho ứng dụng; Windows vẫn quyết định quyền truy cập camera. Nếu Windows chặn desktop apps, mở **Windows Settings → Privacy & security → Camera**; ứng dụng không tự đổi quyền Windows. [Tài liệu Microsoft về camera privacy](https://learn.microsoft.com/en-us/windows/apps/develop/camera/camera-privacy-setting).

SDK `webrtc-java:0.19.0` có danh sách video devices nhưng không có API default video device. Chọn camera tích hợp theo tên là chính sách của dự án, không phải khẳng định đã đọc được mặc định của Windows. Bộ lọc camera ảo dựa vào tên/descriptor (Virtual, ByteCast, OBS Virtual, ManyCam, v.v.); SDK không cung cấp cờ hardware nên không xem đây là chứng nhận phần cứng cho driver lạ. Không lọc nhầm thương hiệu webcam vật lý OBSBOT. Không tự kết nối điện thoại qua mạng. Mở danh sách camera sẽ cập nhật thiết bị, kể cả camera vừa cắm, mà không cần nút tìm. Muốn đổi camera đang dùng, tắt camera và đợi tác vụ dọn thiết bị xong trước rồi chọn lại.

Phòng mới dùng RoomService/signaling làm chủ sở hữu membership. Snapshot mang `collaborationRoom` là RoomInfo thật do server tạo, gồm ID database và thành viên để FilePanel tiếp tục dùng API cũ; không lấy role/ID từ client để cấp quyền. LIST_ROOMS hiển thị cả phòng này trong danh sách gần đây, còn GET/JOIN/LEAVE trên kênh auth vẫn chặn sửa phòng signaling. Phòng còn hoạt động được tạo bằng phiên bản luồng auth cũ vẫn tham gia được qua fallback khi signaling trả ROOM_NOT_FOUND; những phòng đó giữ chức năng cũ và chưa có video WebRTC. Tạo phòng mới bằng giao diện cập nhật để thử video.

Một webcam vật lý có thể không mở đồng thời ở hai process, tùy driver. Nghiệm thu video hai chiều cần hai camera khả dụng hoặc hai máy. Kiểm tra quyền camera cho desktop apps trong Windows nếu thiết bị không hoạt động. Không có thao tác tự bật microphone.

Khi thử **hai cửa sổ trên cùng một máy có một webcam**, bật camera ở một cửa sổ, giữ camera cửa sổ còn lại tắt và kiểm tra cửa sổ đó nhận hình của người phát. Trên máy phát triển, SDK liệt kê webcam **USB2.0 HD UVC WebCam** và năm camera ảo OBS/ByteCast; sau bộ lọc mới chỉ còn một webcam UVC trong danh sách. Đây là kết quả kiểm tra metadata, không bật capture hoặc gỡ driver Windows.

Đã tái hiện driver ByteCast kẹt khi SDK đọc định dạng. Trước đây việc này chiếm cùng worker với subscribe nên cả nhận video có thể ngừng. Hiện đọc lựa chọn/quyền, khám phá thiết bị và điều khiển media dùng các hàng đợi riêng. Truy vấn định dạng chạy trong JVM Java phụ có giới hạn 8 giây; tiến trình do ứng dụng tạo được dừng khi timeout/đóng, không cần cài runtime khác. Descriptor chỉ đi qua stdin riêng, không qua tham số/log; helper không bật capture, microphone hoặc truy cập DB. Đọc metadata ByteCast đã timeout khoảng 8 giây, truy vấn webcam UVC tiếp theo thành công với 12 định dạng khoảng 0,6 giây. Đây là kiểm tra metadata, không phải nghiệm thu hình webcam thật. Sau thao tác bật, không có frame sau 5 giây sẽ báo lỗi thay vì báo camera đã bật.

Lệnh PowerShell tương đương:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-meeting-server.ps1
# Mỗi terminal client:
powershell -ExecutionPolicy Bypass -File .\scripts\start-client.ps1
```

Auth 8443, signaling 8444, file service cổng cấu hình (thường 9001), media control HTTP nội bộ 127.0.0.1:8766. RTP dùng ICE/UDP riêng; cổng control không phải cổng video. Không chạy AuthServer cũ cùng server kết hợp vì trùng cổng. Go không mở control ra Internet. Hai máy LAN cần bind/hostname và certificate SAN đúng cùng firewall cho UDP; Internet/TURN chưa nghiệm thu, chưa thêm TURN vào MVP này.

## Transport, quyền và giới hạn

`VIDEO_PUBLISH`, `VIDEO_SUBSCRIBE`, `VIDEO_STOP`, `VIDEO_STATE` dùng envelope signaling v1. Mọi thao tác kiểm tra phiên và membership thật. Client không được gửi secret control, role hoặc user ID để tự cấp quyền. SDP đi qua TLS, không qua URL/log. Go control dùng secret 256 bit, constant-time comparison, body 128 KiB và queue/control slot hữu hạn.

Mỗi client có một PeerConnection gửi camera và một PeerConnection nhận cho mỗi publication của người khác. Client là offer owner; offer/answer chứa ICE đã gather (deadline 10 giây). Không trickle hoặc renegotiate cùng PC trong MVP: tắt/bật lại camera cấp publication nonce mới và dựng PC nhận mới. Một nguồn VP8/lớp chất lượng, mục tiêu tối đa 15 fps/700 kbit/s ở sender; không cam kết bitrate/FPS đo được hoặc adaptive simulcast/SVC.

Java cấp/renew lease tối đa 15 giây sau xác thực; TTL không vượt expiry của session. Revalidation signaling renew quyền; Go hết lease tự đóng transport. Leave/logout/kick/host stop thu hồi hoặc dừng publication. Role host không cho phép bật camera của người khác. Khi mất signaling/media, không tự bật lại camera. Generation/engine instance và publication nonce chặn transport cũ.

Pion cung cấp ICE, DTLS-SRTP và RTP/RTCP; dự án tự viết quyền lease, chọn source, fanout, queue và lifecycle. Payload/sequence/timestamp/marker được giữ cho một source/PC; bỏ ingress extensions/CSRC/padding, Pion binding dùng SSRC/PT và SRTP của transport nhận. NACK và SR/RR từ default interceptors; PLI/FIR đi ngược nguồn. Không decode/transcode ở SFU.

Mỗi subscriber có queue riêng 256 packet/2 MiB, bỏ dữ liệu quá 750 ms; drop có keyframe recovery. Không có source switching trên cùng PC nên discontinuity được xử lý bằng PC mới. Các kiểm chứng congestion/loss/jitter dài hạn chưa chạy; không tuyên bố tương đương SFU trưởng thành.

`MeetingSession` sở hữu `VideoCallController` qua các view. Native/network/capture/convert chạy ngoài FX thread. Sink release từng native frame; mailbox giữ một frame RGB mới nhất và một job FX, throttle cập nhật ảnh 10 lần/giây. Số cập nhật WritableImage không phải số frame compositor đã trình bày trên màn hình. View dispose gỡ binding/listener, không tự đóng media đang được view khác dùng.

## Kiểm thử đã chạy

Hồi quy sau đổi luồng quyền: **194 tests, 0 failures/errors, 2 skipped**, BUILD SUCCESS (`target/camera-permission-entry-regression.txt`). Hai skip vẫn là symlink Windows và performance truyền tệp opt-in. Camera vật lý cần người dùng chủ động nghiệm thu, không mở trong các bài tự động.

Luồng quyền mới: **16 tests PASS** (`target/camera-permission-entry-integration.txt`). Không hỏi khi mở view; chỉ nút Bật camera mới hiện hộp thoại. Từ chối và thử lại, đóng view khi hộp hỏi đang mở, ghi nhớ quyền và camera đã chọn được kiểm tra bằng file tạm/camera giả. Bài giao diện chính kiểm tra nút vào họp bị khóa khi chưa cho phép, từ chối vẫn ở phòng chờ và gọi SceneManager.meeting trực tiếp cũng bị chặn; chấp nhận mới vào họp. Phòng chờ không còn nút Quyền camera hay mô tả số camera/tính năng tương lai. Factory media trong fixture dùng catalog rỗng và file quyền riêng, không dò/bật thiết bị thật hoặc sửa lựa chọn camera của người dùng.

Bản cập nhật lọc camera/nhấn liên tục/theme: **16 tests PASS**, gồm năm bài native video, bảy bài CameraControls, ba bài CameraSwitch và một bài ThemeManager (`target/camera-switch-theme-integration.txt`). Hồi quy **194 tests, 0 failures/errors, 2 skipped**, BUILD SUCCESS (`target/camera-switch-theme-regression.txt`). Bài native mới giữ khởi tạo capture rồi nhấn 100 lần và xác minh yêu cầu cuối tắt không start nguồn; bật lại rồi nhấn 101 lần và xác minh publication SFU được dọn. Bài theme đổi bằng nút thật, so sánh màu nền của phòng họp/dialog và giữ nguyên Scene. Metadata SDK trên máy phát triển có sáu nguồn nhưng bộ lọc chỉ hiển thị một webcam UVC (`target/camera-picker-inventory.txt`). Không bật camera thật trong kiểm thử.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-video-engine.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\test-video-call.ps1
```

Go: bốn bài top-level PASS, bao gồm control auth/TTL/generation, ownership/header/wraparound/reorder/duplicate, queue keyframe hữu hạn và engine Pion thật fanout một nguồn tới hai receiver, payload không đổi, cross-room bị từ chối, revoke/stop giữ receiver khác.

Native: năm bài PASS, không skip. Ba client native trong cùng JVM test, H2/TLS thật, trao đổi video qua process Go riêng và decode/render JavaFX. Kiểm tra tắt/bật lại camera, xóa ảnh sau khi dừng, publication mới, host stop/kick và logout; client còn lại tiếp tục. Bài giao diện chính kiểm tra dialog tạo phòng, sai PIN/đúng PIN, tham gia bằng ID, preview không publish, chuyển sang MeetingScreen và trao đổi video; mở Cài đặt để thấy cấu hình camera và xác minh không còn cấu hình nằm ngoài dialog. Gửi/tải tệp thật trong cùng phòng có đối chiếu nội dung; rời phòng đóng camera và mất quyền file, người còn lại tiếp tục phát. Bài thứ tư khởi động client nhận trong JVM riêng trên cùng máy, cố ý giữ catalog camera của bên nhận đang chờ; bên nhận vẫn decode và cập nhật ảnh JavaFX từ publisher. Nguồn I420 320×240 được tạo bằng mã, không mở thiết bị thật. Screenshots `target/video-call-generated-preview.png` và `target/video-main-meeting-preview.png` không phải ảnh webcam.

Native chạy trên JDK thực tế 22.0.2/Windows x64, compile release 21. Kiểm tra camera/nhận video gồm bốn bài video và sáu bài CameraControlsTest: 10 tests, không failure/error/skip (`target/camera-receive-final-integration.txt`). Chưa nghiệm thu camera thật, 2/3 JVM ở các máy khác nhau, LAN/Internet, đổi/rút camera, 30 phút, 5/10 client hoặc RSS/native memory dài hạn.

Cập nhật quyền/toolbar: kiểm thử với catalog thiết bị giả lập xác minh quyền trước enumeration, ghi nhớ qua controller mới, chọn camera tích hợp/đã chọn, từ chối không tự hỏi lại, catalog rỗng và thiết bị mới, hộp hỏi JavaFX thật cùng đóng khi view dispose. Native integration kiểm tra thu gọn làm tăng chiều cao vùng video và vẫn nhận frame mới, hiện lại không làm tắt camera. Không mở thiết bị thật trong các bài này. Kết quả ở `target/camera-toolbar-integration.txt` và `target/camera-toolbar-regression.txt`.

Hồi quy sau bản sửa camera/nhận video: 189 tests, không failure/error, hai skip theo cấu hình (quyền symlink Windows và performance truyền tệp opt-in), BUILD SUCCESS (`target/camera-receive-regression.txt`). Sáu bài CameraControlsTest nằm trong bộ hồi quy này; bốn bài native video chạy riêng bằng profile integration.

Bằng chứng local được Git bỏ qua: `target/video-engine-tests.txt`, `target/unified-video-integration.txt`, `target/unified-room-regression.txt` và `target/video-diagnostics/`. Maven clean xóa các file trong target; cần giữ bản sao trước khi clean nếu muốn so sánh nhiều lần thử.

Go ghi `.txt` riêng, mẫu mỗi 5 giây, giới hạn 10 MiB; Java tiếp tục một diagnostics owner. `video.engine.sample` chứa counter codec bytes/packets, queue/drop/PLI và Go heap/goroutine; Go heap không phải RSS. `video.integration.received` ghi frame callback và cập nhật ảnh của bài thử. Không log frame, SDP/ICE, PIN/token, secret, tên người dùng hoặc địa chỉ mạng. Dùng chung `MMR_DIAGNOSTICS_RUN_ID` khi so các process. Reporter nhận các event video; giá trị chưa đo vẫn unavailable.
