# Mốc 0: kiểm chứng đường RTP qua Pion

Đây là service kiểm chứng một publisher → SFU → một subscriber cùng máy. Backend
auth/phòng và giao diện vẫn ở Java. Service không có codec encoder/decoder, không
thay thế phần xác thực/signaling production và không công bố hỗ trợ cuộc họp thật.

Dependency cố định: `github.com/pion/webrtc/v4 v4.2.23` (MIT), Go >= 1.24.0.
Các dependency trực tiếp và gián tiếp được cố định trong `go.mod`/`go.sum`.
Chạy trực tiếp trên Windows; Docker không cần thiết.

## Chạy

Dùng `scripts/test-sfu-probe.ps1` từ workspace. Script tạo secret ngẫu nhiên riêng
cho bài thử, chạy service và Java harness với nguồn tự tạo; không dùng Railway,
camera, microphone hoặc speaker thật. Để build/test service riêng:

```powershell
cd C:\MMT\MiniMeetingRoom\media\probe
go mod download
go test ./...
go build -o ..\..\target\media-probe.exe .
```

Chạy executable cần `MMR_MEDIA_PROBE_SECRET` gồm 32–128 ký tự và tùy chọn
`MMR_MEDIA_PROBE_PORT` (mặc định 8765). HTTP chỉ bind `127.0.0.1`; Pion tạo
candidate UDP IPv4 host của máy và loopback, không dùng STUN/TURN. Native libwebrtc
desktop bỏ loopback theo mặc định nên cần host candidate dù cả ba process cùng máy.
Không dùng các endpoint HTTP
này cho Internet hoặc đưa token đăng nhập production vào service.

## HTTP contract

Mọi endpoint yêu cầu header `X-MMR-Probe-Key` khớp secret trong môi trường.
Secret không nằm trong URL hoặc log. Không có CORS. Request JSON tối đa 128 KiB,
header tối đa 4 KiB, read timeout 5 giây; ICE gather deadline 8 giây.

| Endpoint | Nội dung |
| --- | --- |
| `GET /health` | `{ "ready": true }` khi process sẵn sàng. |
| `POST /publish` | `{ "sdp": "offer" }` → `{ "sdp": "gathered answer" }`. Một ingress PC. |
| `POST /subscribe` | Cùng schema, sau publish; offer recv-only Opus/VP8. Một egress PC riêng. |
| `GET /stats` | Cumulative counters và payload hashes; không có SDP/ICE/IP/user ID. |
| `DELETE /session` | Stop ingress, drain queues hữu hạn, close egress, join workers; trả final stats. Idempotent. |
| `POST /shutdown` | Close session và dừng HTTP/process sạch. |

Lỗi chỉ trả `{ "code": "FIXED_CODE" }`. Stats cuối được giữ cho tới publish mới.
Hai outbound track được tạo trước negotiation để subscriber không cần chờ
publisher bắt đầu capture. `publisher_connected` và `subscriber_connected` phải
đúng trước khi Java bắt đầu đẩy nguồn media deterministic.
Các trường `publisher_connection_state`/`subscriber_connection_state` là giá trị
enum số của Pion; `publisher_ice_candidates`/`subscriber_ice_candidates` đếm local
candidate tạo được, không chứa địa chỉ hay SDP.

## Ranh giới SFU/thư viện

Pion xử lý ICE, fingerprint verification, DTLS, SRTP/SRTCP riêng cho mỗi PC,
negotiation, RTP binding và default RTCP interceptors. Mã dự án quản lý hai PC,
route audio/video, queue hữu hạn riêng cho mỗi route, forwarding, lifecycle,
feedback PLI/FIR về publisher và số liệu/hash kiểm chứng.

`TrackRemote.ReadRTP()` nhận RTP đã bỏ bảo vệ SRTP nhưng payload vẫn mã hóa codec.
`TrackLocalStaticRTP.WriteRTP()` chuyển cùng codec payload qua transport đích,
rewrite SSRC/PT theo binding đích và áp dụng SRTP mới. Sequence/timestamp/marker
được giữ cho đúng một nguồn xuyên suốt phiên. Ingress extensions, CSRC và padding
không sao chép sang transport khác. Không gửi nguyên gói SRTP sang subscriber,
không dùng media sample/raw PCM/I420 trong service, không decode/encode lại.

RTCP không được sao chép mù giữa hai PC. Default Pion interceptors xử lý NACK và
SR/RR của từng transport; các RTCP reader được drain. PLI/FIR từ subscriber được
chuyển thành PLI với SSRC publisher, tối đa một lần/500 ms; subscribe/queue drop
cũng yêu cầu keyframe. Không register RTX, simulcast/SVC hoặc cơ chế congestion
control đầy đủ. Probe chưa có multi-source switching, reordering buffer hoặc
rewrite sequence khi nguồn/lớp thay đổi.

Mỗi route có tối đa 256 RTP packet, 2 MiB gồm packet header/payload và tuổi 750 ms.
Publisher không chờ subscriber: queue đầy bỏ packet mới; packet quá tuổi bỏ tại
writer. Audio và video có queue/writer độc lập. Khi video drop, yêu cầu keyframe.
Queue tuổi/byte chỉ giới hạn queue tự viết; không phải cam kết toàn bộ memory của
Pion/interceptors hoặc của hệ điều hành đã được đo.

## Số liệu và bằng chứng

`tracks.audio` và `tracks.video` gồm `ingress_packets`, `ingress_payload_bytes`,
`forwarded_packets`, `forwarded_payload_bytes`, `queue_packets`, `queue_bytes`,
`queue_drops`, `expired_drops`, `read_errors`, `write_errors`, `pli_requests`,
`ingress_payload_sha256` và `forwarded_payload_sha256`. Bytes chỉ tính codec payload
RTP (không header/SRTP). Hash là SHA-256 streaming của mỗi payload theo thứ tự,
trước mỗi payload có unsigned 64-bit big endian length. Hash chỉ phục vụ assertion
với media tự tạo; Java không đưa hash vào diagnostics log.

Một bài thử PASS cần cả hai loại media được nhận/giải mã ở Java subscriber,
ingress/forwarded packets và bytes > 0, các số liệu/hash khớp sau drain, drop/error
bằng 0, `cleanup_timed_out=false`, `peer_close_errors=0`, `closed=true`, active PC/queue bằng 0 sau DELETE và process
shutdown sạch. Write thành công ở SFU riêng lẻ không chứng minh client đã nhận.

Unit tests kiểm tra packet payload ownership, header stripping, sequence wrap,
reorder/duplicate giữ nguyên, queue packet/byte bounds và HTTP auth/size/cleanup.
Chúng không thay Java ↔ Pion ↔ Java integration test thật. Mốc 0 chưa gồm năng lực
2/3/5/10 client, long call, reconnect, Internet, thiết bị thật hoặc production
session validation. Không suy ra FPS decode/freeze của SFU vì SFU không decode.

Nguồn chính thức:

- [Pion v4.2.23](https://github.com/pion/webrtc/releases/tag/v4.2.23)
- [ReadRTP](https://github.com/pion/webrtc/blob/v4.2.23/track_remote.go)
- [WriteRTP và SSRC/PT bindings](https://github.com/pion/webrtc/blob/v4.2.23/track_local_static.go)
- [Default interceptors](https://github.com/pion/webrtc/blob/v4.2.23/interceptor.go)
