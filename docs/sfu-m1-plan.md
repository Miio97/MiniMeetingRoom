# Mốc 1 — thiết kế đề xuất sau cổng media

Tài liệu này là thiết kế, **chưa phải phần triển khai**. Runtime media được chọn là service Go/Pion; JavaFX, auth, room/signaling Java và MySQL Railway giữ vai trò hiện có. Chỉ bắt đầu mã production Mốc 1 khi parent xác nhận bài publisher → SFU → subscriber qua hai transport đã đạt Mốc 0. Không áp dụng migration hoặc tạo dữ liệu thử trên Railway trong bước thiết kế này.

## Module và chủ sở hữu

| File/package đề xuất | Trách nhiệm và tài nguyên sở hữu |
|---|---|
| `server.auth.SessionValidator` | Hash token, đọc phiên + trạng thái tài khoản, trả principal nội bộ; dùng `ConnectionProvider`, `Clock` injectable |
| `server.auth.SessionRegistry` | Các connection/permission epoch theo hash phiên; scheduler revalidation; invalidation sau logout |
| `server.meeting.RoomRepository` | JDBC transaction, PIN hash, phòng, membership lịch sử, request receipt và marker do server sở hữu |
| `server.meeting.RoomService` | Live room/membership, quyền host, admission/capacity, revision, generation; executor/hàng đợi hữu hạn |
| `server.signalingapi.SignalingServer` | Listener TLS riêng, connection state, decoder, request dispatch và writer queue; không dùng auth worker |
| `client.signalingapi.SignalingClient` | TLS/hostname validation, reader/writer, request futures, heartbeat và timeout |
| `common.signaling` / `common.meeting` | Envelope và DTO có kiểu; không phụ thuộc JavaFX, JDBC hoặc Pion |
| `server.media.MediaControlClient` | Kênh có xác thực tới Go, transport lease, ack/revoke; không nhận JDBC connection |
| `client.meeting.MeetingSession` | Một phiên họp; signaling membership, capture/PeerConnection về sau; dispose idempotent |
| `server.MeetingServerMain` | Bootstrap AuthServer + SignalingServer chung JVM, shutdown và logger duy nhất |

Tránh package `server.room`, `server.signaling`, `client.signaling`, `client.webrtc`, `common.protocol` vì repo có class trùng tên. `SceneManager` chỉ điều hướng và giữ owner `MeetingSession`; screen/tile chỉ mượn binding. Không đóng session trong `PreJoinScreen.dispose()` khi chuyển vào meeting.

Lệnh `scripts/start-server.ps1` tiếp tục là lệnh chạy server; sau Mốc 1 có thể chuyển entry point mặc định sang bootstrap chung, giữ option auth-only và `server.AuthServer` độc lập tương thích. Auth port/wire và lệnh client không đổi. Bản auth-only với signaling ở JVM khác chỉ có revalidation định kỳ, không có invalidation trong RAM; tài liệu chạy phải nêu rõ giới hạn đó.

## Phiên và điểm tích hợp AuthService

`AuthService.handle` hiện chỉ có REGISTER/LOGIN/LOGOUT. `tokenHash(String)` là package-private nên validator trong `server.auth` dùng cùng thuật toán mà không mở helper xử lý token ra client. Login trả `expiresAt`; `SceneManager.acceptSession` hiện bỏ trường này, cần giữ `Instant` để hiển thị/đóng UI kịp thời, nhưng quyền vẫn do server quyết định.

Principal chỉ ở server:

```java
record SessionPrincipal(long sessionId, long userId, String displayName,
                        Instant expiresAt, String tokenHash) {}
```

Override `toString()` loại toàn bộ identity/hash. Token/credential DTO mới cũng cần override; không dựa vào default `record.toString()`. Raw token chỉ đi trong body AUTHENTICATE trên TLS, không đi URL, media service, errors hoặc logger. Java String không bảo đảm xóa nội dung khỏi RAM; hạn chế giữ reference sau authenticate.

Query xác thực dùng prepared statement và UTC, không đọc password hash:

```sql
SELECT s.Ma_Phien, s.Ma_Nguoi_Dung, s.Het_Han_Luc, u.Ten_Hien_Thi
FROM Phien_Dang_Nhap s JOIN Nguoi_Dung u ON u.Ma_Nguoi_Dung = s.Ma_Nguoi_Dung
WHERE s.Token_Bam = ? AND s.Thu_Hoi_Luc IS NULL
  AND s.Het_Han_Luc > ? AND u.Trang_Thai = 'HOAT_DONG'
```

- Validate khi authenticate, create/join và trước các thay đổi quyền publish/subscribe. Định kỳ tối đa 5 giây khi connection đang hoạt động; bounded DB executor, không truy vấn trên packet/frame.
- Registry giữ hash/principal/permission epoch; không cache kết quả quá hạn revalidation hoặc quá `expiresAt`. Revalidation không thành công vì DB timeout cũng dừng gia hạn quyền; fail closed khi lease hết hạn.
- Thêm overload constructor `AuthService(..., SessionRevocationListener)`; constructor đang có dùng no-op để giữ test/API hiện tại. Sau UPDATE logout thành công mới notify hash. Không notify trước DB commit hoặc khi DB update lỗi. Unknown/expired logout vẫn idempotent như hiện có.
- Logout invalidation tăng epoch, từ chối các request đang chờ, revoke media và kết thúc live membership. DB update thành công vẫn là logout thành công; nếu Go không ack, lease tối đa 5 giây là fallback. Đo thời gian từ DB revocation đến ngừng forwarding; đây là mục tiêu cần kiểm thử, chưa là số đã đạt.
- Hết hạn dùng đồng hồ UTC ở validator và deadline monotonic ở scheduler. Account bị khóa từ ngoài JVM được nhận thấy trong cửa sổ revalidation. Signaling không tin `AuthUser.id`, role hay display name trong request.

## Phòng, lịch sử và đồng thời

Server cap ban đầu cấu hình tối đa 10 participant/phòng, gồm host; đây là policy thử nghiệm, không phải năng lực đã đo. UI thật nhận cap server thay cho hằng 250 của demo. Mỗi signaling connection ở tối đa một phòng; một account có tối đa một live participant trong cùng phòng, kể cả token login khác. Account có thể tham gia phòng khác từ connection khác; không đuổi client cũ khi client thứ hai bị từ chối.

- CREATE tạo phòng `CHO`, PIN PBKDF2 salted qua `PasswordHasher`, join code ngẫu nhiên `MMR-` + 16 ký tự Base32 (đúng 20 ký tự cột hiện có), marker owner và receipt trong cùng transaction. Chưa có live membership/capture. Collision code do unique DB xử lý với số lần thử hữu hạn.
- Host đầu tiên JOIN chuyển phòng sang `DANG_HOAT_DONG` và tạo row host membership. Người khác join khi phòng còn `CHO` nhận `ROOM_NOT_STARTED`. Phòng `CHO` bị bỏ lại đóng sau deadline hữu hạn; creator có thể đóng trước khi join.
- JOIN kiểm PIN trên bounded hashing executor; re-read/check phòng trong transaction sau khi hashing. Dùng `SELECT ... FOR UPDATE` ở row phòng, serialize admission của cùng phòng, kiểm khóa/capacity/duplicate account rồi INSERT history. Không công bố participant trước commit; rollback không để ghost membership. Reservation được tính vào capacity đến khi commit/rollback hoàn tất.
- DB history `Thanh_Vien_Phong` không phải quyền sống. Không thêm unique `(Ma_Phong,Ma_Nguoi_Dung)` làm mất khả năng rejoin. Live identity là participant opaque mới cho mỗi lần join + connection generation; row ID/account ID chỉ ở server.
- Mọi mutation của một phòng được serial hóa qua queue hữu hạn trên pool chung, không tạo thread vô hạn cho từng phòng. Không giữ room lock khi gọi Go hoặc viết socket. Kết quả async mang revision/epoch; callback stale bị bỏ. Revoke tăng generation trước khi xóa routing quyền.
- LEAVE revoke trước, đóng row history một lần (`Roi_Phong_Luc IS NULL`), rồi remove live entry. Host LEAVE/timeout/kick/logout đóng phòng MVP; tất cả member được revoke và ghi `PHONG_DONG`. Không tự chuyển host. Disconnect thường ghi `MAT_KET_NOI`, kick ghi `BI_MOI_RA`, tự leave ghi `TU_ROI`; chi tiết hết hạn/thu hồi chỉ dùng mã lý do control/diagnostics.
- Media không chờ DB để ngừng forwarding. Khi ghi lịch sử cleanup thất bại, live quyền vẫn đóng; bounded retry và startup reconciliation sửa row do server này sở hữu. Room bị lỗi admission/DB không nhận thêm người cho tới khi trạng thái được xác nhận.
- Mốc 1 chưa resume membership tự động. Client reconnect phải authenticate lại và JOIN với request mới nếu phiên join cũ đã kết thúc; không tự khởi động capture. Mốc 3 mới thêm backoff/resume/ICE restart.

### Migration đề xuất để tránh hai lỗi khó thấy

Bốn bảng hiện có đủ đường create/join/leave cơ bản. Cần một migration tăng dần, review riêng trước khi dùng production, cho hai bảng phụ:

1. `MMR_Phong_Van_Hanh`: `Ma_Phong` PK/FK, `Room_Opaque_ID` unique, `Instance_ID`, `Server_Epoch`, `Ngay_Tao`. Marker tạo cùng transaction với phòng. Instance ID là cấu hình server ổn định; epoch đổi mỗi lần boot. Startup chỉ đóng row marker của **instance này** từ epoch cũ và membership liên quan, không UPDATE toàn bộ phòng đang mở sẵn trên Railway. MVP chỉ cho một backend của instance chạy; chưa hỗ trợ nhiều server điều khiển cùng DB.
2. `MMR_Yeu_Cau_Phong`: composite PK `(Ma_Phien, Request_ID)`, `Operation`, `Body_HMAC`, `Result_Code`, optional `Ma_Phong`/`Ma_Thanh_Vien_Phong`, `Participant_Opaque_ID`, `Ngay_Tao`, `Het_Han_Luc`. Request ID random 128 bit; body HMAC trên payload canonical dùng key server ổn định, không lưu PIN/token hoặc JSON request thô. Receipt và side effect cùng transaction. Exact replay trả cùng kết quả; reuse ID với body khác trả `REQUEST_ID_REUSED`. Replay sau restart cho phòng đã đóng trả trạng thái đóng, không tạo phòng/member mới. Giữ receipt ít nhất đến khi phiên login hết hạn/thu hồi cộng grace cleanup; **không prune receipt của phiên còn hợp lệ** vì điều đó cho retry cũ tạo side effect lần nữa. Sau expiry validator từ chối phiên trước dispatch. Giới hạn mutation receipt/phiên và rate để bound tăng trưởng DB; client không tự đổi ID để retry mutation chưa biết kết quả.

Migration không tự chạy khi server boot. Preflight thiếu bảng thì fail bằng mã `SCHEMA_NOT_READY`; không tiếp tục production với replay cache giả. H2 test DDL cô lập; bản migration MySQL review trước, không tự apply Railway. In-memory replay cache hữu hạn chỉ tối ưu đọc receipt, không thay persistence.

## DTO và signaling v1 đề xuất

Envelope JSON UTF-8, length-prefix 4 byte big-endian riêng cho signaling:

```java
record SignalEnvelope(int version, String type, String requestId,
    String serverEpoch, String connectionId, long generation,
    String roomId, String participantId, Long revision, JsonObject body) {}
record MediaState(boolean micRequested, boolean cameraRequested, boolean shareRequested,
    boolean audioPublishing, boolean videoPublishing, boolean sharePublishing) {}
record ParticipantView(String participantId, String displayName, RoomRole role,
    MediaState mediaState) {}
record RoomSnapshot(String roomId, String joinCode, String name, RoomStatus status,
    boolean locked, int capacity, String hostParticipantId, long revision,
    List<ParticipantView> participants) {}
record RoomPreview(String roomId, String joinCode, String name, RoomStatus status,
    boolean locked, int capacity, int participantCount) {}
record SignalError(String code, boolean retryable, Long retryAfterMillis) {}
```

`RoomRole=HOST|MEMBER`, `RoomStatus=READY|ACTIVE|CLOSED`; DB enum mapping chỉ ở repository. Room/participant/track/transport IDs là random opaque server cấp, account/session/history numeric ID không xuất hiện trong signaling. `roomId` khác join code; hostParticipantId null khi READY. Request ID 128 bit không chứa identity. Event không có requestId; revision tăng mỗi thay đổi phòng. Client phát hiện revision gap thì GET_ROOM để lấy snapshot, không đoán membership.

| Type | Body request hoặc event/response |
|---|---|
| SERVER_WELCOME | `{serverEpoch,connectionId,generation,protocolVersion,maxParticipants,maxFrameBytes}` |
| AUTHENTICATE / AUTHENTICATED | `{token}` / `{expiresAt,selfDisplayName}`; connection identity lưu server-side |
| CREATE_ROOM / ROOM_CREATED | `{name,pin,capacity,locked}` / `{room:RoomSnapshot}` |
| LOOKUP_ROOM / ROOM_PREVIEW | `{joinCode,pin}` / `{room:RoomPreview}`; chuẩn bị pre-join, chưa tạo membership; không gửi participant list |
| GET_ROOM / ROOM_SNAPSHOT | `{roomId}` / `{room:RoomSnapshot}`; membership cần thiết để xem danh sách người |
| JOIN_ROOM / ROOM_JOINED | `{joinCode,pin,micRequested,cameraRequested}` / `{selfParticipantId,room:RoomSnapshot}` |
| LEAVE_ROOM / ROOM_LEFT | `{}` / `{reason}`; room/participant lấy từ connection đã bind |
| PARTICIPANT_JOINED / PARTICIPANT_LEFT | `{participant:ParticipantView}` / `{participantId,reason}` |
| SET_MEDIA_STATE / PARTICIPANT_STATE | `{micRequested,cameraRequested,shareRequested}` / `{participantId,mediaState}`; active publishing chỉ từ engine |
| SET_ROOM_LOCK / ROOM_STATE | `{locked}` / `{locked,status}`; quyền host |
| HOST_STOP_PUBLISH / PUBLISH_STOPPED | `{targetParticipantId,kind}` / `{participantId,kind,reason}`; chỉ ngừng, không bật thiết bị |
| KICK_PARTICIPANT / ROOM_CLOSED | `{targetParticipantId}` / `{reason}`; server kiểm cùng phòng/quyền |
| PUBLISH / UNPUBLISH | `{kind,localTrackId}` / `{trackId}`; IDs được bind với owner/generation; không nhận codec/SSRC do client tự cấp làm quyền |
| SUBSCRIBE / UNSUBSCRIBE | `{trackId}`; target track phải cùng room, sống, thuộc participant khác |
| SDP_OFFER / SDP_ANSWER | `{transportId,negotiationId,sdp}`; chỉ forward sau authorization; raw SDP không log |
| ICE_CANDIDATE | `{transportId,negotiationId,mid,mLineIndex,candidate}`; null candidate là end-of-candidates; bounded pre-SDP queue |
| HEARTBEAT / HEARTBEAT_ACK | `{sequence}` / `{sequence}`; timeout trên monotonic clock |
| ERROR / SESSION_ENDED | `SignalError` / `{reason}`; fixed code, không dump exception |

Mốc 1 định nghĩa và test validation của nhóm media, nhưng chỉ bật production nhóm SDP/publish/subscription khi engine gate và adapter đã sẵn sàng. Không báo đã có cuộc gọi từ việc ACK media intent. Host stop là revoke publication; UI client tự dừng capture khi nhận event. Server không thể bật camera/mic từ xa. Participant chỉ publish lại sau thao tác bật của chính họ theo policy room.

Server xác minh epoch/connection/generation từ trạng thái socket, participant đang bind, membership và tất cả tham chiếu track/target. Fields do client gửi không tạo quyền. Reader reject version/type/field không hợp lệ; response error mapping cố định. Sai/hết hạn/thu hồi token trước authenticate dùng `SESSION_INVALID`; trong session đã biết có thể gửi `SESSION_EXPIRED`/`SESSION_REVOKED`.

Đề xuất limit cấu hình ban đầu: frame 128 KiB; authenticated rate 20 message/s, burst 40 và 1 MiB/s; outbound tối đa 128 message hoặc 512 KiB; tối đa 64 pending request/client; unauthenticated deadline/handshake 5 s; heartbeat 10 s, timeout 30 s. Limit SDP/ICE phải được test với SDP thực tế. Bounded queue đầy đóng connection với `BACKPRESSURE` để client resync snapshot; không âm thầm làm mất mutation event. Không tăng giới hạn auth 16 KiB.

## Contract với service Go/Pion

Java giữ session/account/room authority và Railway credentials. Go chỉ nhận quyền từ `MediaControlClient` qua kênh service TLS có hostname/certificate verification và credential service riêng trong config ignored; credential DB và token login không đi Go. Bind service control loopback mặc định; media advertise/UDP tách riêng. Go không tự cấp membership từ SDP hay request client.

Control lease tối đa 5 s gồm `{leaseId,serverEpoch,roomId,participantId,generation,transportId,direction,allowedPublishKinds,allowedTrackIds,expiresAt}`. Renew chỉ sau validation; expiry được đổi sang deadline monotonic. Commands có `controlRequestId` và media process epoch: `AUTHORIZE_TRANSPORT`, `APPLY_OFFER`, `ADD_ICE`, `REVOKE_TRANSPORT`, `REVOKE_PARTICIPANT`, `CLOSE_ROOM`; events `ANSWER_READY`, `ICE_READY`, `TRACK_PUBLISHED`, `TRACK_UNPUBLISHED`, `TRANSPORT_STATE`, `REVOKED_ACK`. Callback cũ của generation/process epoch bị loại. Go kiểm room/owner/lease bằng registry RAM trên routing, không query DB; expiry/revoke xóa subscription/queue/cache trước ack.

Một publish PC và một subscribe PC/client theo kiến trúc đề xuất. SDP negotiationId phân biệt offer cycle; client publisher là offerer trên publish PC, SFU là offerer trên subscribe PC để giảm collision. Queue ICE trước remote description theo transport/negotiation, có timeout/byte/count limit; chỉ một pending offer mỗi transport. Chính sách mở rộng/glare/ICE restart chi tiết được kiểm thử ở Mốc 2–3, không ACK vô điều kiện trong Mốc 1.

## Test Mốc 1 sẽ thực hiện sau gate

- H2 cô lập + injectable Clock: token đúng/sai/expired/revoked/locked account, biên expiry UTC, logout notify chỉ sau commit, DB failure fail closed; auth test/wire cũ giữ pass.
- H2 repository: salted PIN, mã collision, create rollback, vai trò host do server, lock, wrong PIN, live duplicate account, nhiều lần rejoin tạo history riêng.
- Barrier/race: N joins tranh ghế cuối chỉ một admission; join/logout và join/close không để quyền stale; SQL failure không ghost participant; không giữ room lock khi Go chậm.
- Durable receipt: response bị mất + retry/disconnect/restart không thêm room/member; body khác cùng request ID bị từ chối; không prune receipt khi phiên còn sống và phiên hết hạn không dispatch lại. Startup chỉ sửa marker của instance, giữ nguyên unrelated rooms/history.
- TLS hai client: membership/revision nhất quán, cross-room ID/track/host target bị từ chối; untrusted cert/wrong hostname, malformed/oversized frame, unauthenticated timeout, rate/queue overload, stale generation và shutdown sạch.
- Fake media authority có bounded scheduler cho lease/revoke logic; media E2E thật là test riêng, fake không thay bằng chứng SFU. Đo revocation deadline và cleanup task/socket/thread; logger không chứa token/PIN/SDP/ICE/user identity.

Chưa có test production Mốc 1 được chạy từ tài liệu này. Không đổi trạng thái nghiệm thu Mốc 0–4 và không thêm dependency/API giả trong code.
