# Phản hồi giao diện và giảm lượt truy vấn

## Baseline từ lần người dùng chạy trên Railway

Log server `server-20261014T150805.934Z-24100-c7876b5a.txt`: thời gian handler trung bình CREATE_ROOM 4069 ms (2 mẫu), JOIN_ROOM 4656 ms (3 mẫu), GET_ROOM 482 ms (8 mẫu), LOOKUP_ROOM 586 ms (4 mẫu). Queue business executor dưới 4 ms trong các mẫu này. Hai client có LIST_ROOMS khoảng 2.5 giây. Đây là ít mẫu trên cùng phiên thử, không phải benchmark tải/SLA. Thời gian handler không bao gồm toàn bộ thao tác nút **Tạo và tham gia** vì nút đó có nhiều request nối tiếp.

Pool đã loại chi phí mở kết nối vật lý mỗi request. Tuy nhiên, mỗi transaction vẫn có nhiều lượt trao đổi tới MySQL ở xa. Không thể suy ra rằng thay animation hoặc thêm thread sẽ làm commit DB tức thì.

## Thay đổi

- Room event SFU kèm snapshot bất biến và revision được chụp dưới room lock. Client coalesce snapshot mới nhất, bỏ dữ liệu khác phòng/connection và revision cũ. Join dùng snapshot trong reply; join/participant events không gọi GET_ROOM dư. KICK trả snapshot mới cho host. GET_ROOM thủ công/fallback cho event server cũ vẫn tồn tại.
- Tra mã phòng SFU đang sống từ bộ nhớ của instance đang sở hữu phòng. Transaction join vẫn xác minh phiên, marker/epoch, khóa phòng, capacity và membership trong DB; lịch sử DB không cấp quyền cho phòng sống.
- LIST_ROOMS/GET_ROOM của luồng phòng cũ chỉ chạy SELECT ở autocommit; GET_ROOM không dùng FOR UPDATE. CREATE/JOIN/LEAVE giữ transaction và khóa bảo vệ tính đúng đắn/capacity.
- Polling/danh sách phòng có executor riêng: 1 worker và queue 2. Không xếp trước login/join/leave/logout trong auth executor. Callback bị bỏ nếu token owner đã đổi hoặc view/app đã đóng.
- Danh sách sảnh cache tối đa 15 giây trong bộ nhớ client. Đổi phiên hoặc cập nhật phòng xóa cache. Nút **Làm mới** luôn hỏi server. Cache phục vụ hiển thị, không xác thực hoặc cấp quyền join.
- Mở màn hình SFU chuẩn bị TLS/auth ngoài FX thread. Thao tác có progress indicator ngay trên UI; chỉ báo thành công khi server trả kết quả. Không tự capture thiết bị hoặc join trước thao tác người dùng.

## Kiểm chứng

Test hai màn hình JavaFX xác nhận join/event/leave đồng bộ và số GET_ROOM bổ sung bằng 0. Test danh sách sảnh xác nhận quay lại dùng cache, còn Làm mới vẫn gọi server. Test chặn phản hồi LIST_ROOMS ở server chứng minh foreground GET_ROOM hoàn tất trong khi polling còn bị chặn. Các bài token/PIN/host/capacity/race join tiếp tục chạy với H2/TLS thật.

Trong quá trình kiểm thử lặp đã sửa race handoff cleanup với shutdown: giữ connection trong registry tới khi cleanup hoàn tất, dùng bộ đếm thao tác và đồng bộ việc nộp cleanup với việc đóng executor. Bài 8 worker đang chạy + 1 request chờ được lặp 3 lần.

Diagnostics client có `signaling.client.request`: `signaling_round_trip_ms` đo gửi request tới nhận response/failure, sau giai đoạn connect. Không gộp với `handler_ms`/`queue_ms` ở server hoặc suy ra độ trễ một chiều. Schema và bộ lọc bí mật giữ nguyên. Không ghi SDP, PIN, token hoặc tên phòng/người dùng.

```powershell
. .\scripts\common.ps1
$taskMaven = Resolve-Maven
& $taskMaven -B test
```

Nghiệm thu thời gian tạo/join sau tối ưu trên Railway chưa chạy. Cần restart server/client, giữ cùng cấu hình và thử lại cùng thao tác để so log trước/sau. Không tuyên bố phần trăm cải thiện trước khi có số liệu đó. Muốn giảm phần độ trễ mạng còn lại cần đưa Java backend gần database; đây là lựa chọn triển khai về sau, chưa đổi Railway hoặc triển khai cloud trong bước này.
