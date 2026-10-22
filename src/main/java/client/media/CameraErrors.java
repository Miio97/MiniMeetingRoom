package client.media;

import common.signaling.SignalException;
import java.util.Set;
import java.util.concurrent.*;

/** Classify locally; never expose or log raw native error text or device descriptors. */
public final class CameraErrors {
    /** An untyped native failure must be attributed to the operation that opened the device. */
    public enum AudioOperation { UNKNOWN, CAPTURE, PLAYBACK, CLEANUP }

    private CameraErrors() { }
    public static Throwable unwrap(Throwable error) {
        while (error.getCause() != null && (error instanceof CompletionException || error instanceof ExecutionException || error instanceof java.io.UncheckedIOException)) error = error.getCause();
        return error;
    }
    public static String code(Throwable error) {
        error = unwrap(error);
        if (error instanceof TimeoutException) return "CAMERA_CATALOG_TIMEOUT";
        String code = error instanceof SignalException signal ? signal.code() : error.getMessage();
        if (code != null && Set.of("CAMERA_PERMISSION_REQUIRED", "CAMERA_FORMAT_UNAVAILABLE", "CAMERA_NOT_FOUND", "CAMERA_NO_FRAMES",
                "MICROPHONE_PERMISSION_REQUIRED", "MICROPHONE_OPEN_FAILED", "AUDIO_OUTPUT_FAILED", "AUDIO_DEVICE_FAILED", "AUDIO_CONNECTION_FAILED", "SCREEN_CONNECTION_FAILED", "ALREADY_PUBLISHING", "VIDEO_BUSY", "MEDIA_BUSY", "VIDEO_VIEWPORT_LIMIT", "MEDIA_CLOSED", "ENGINE_CHANGED", "LEASE_EXPIRED", "AUDIO_CLEANUP_FAILED", "SHARE_IN_USE", "SCREEN_NO_FRAMES", "SCREEN_CAPTURE_FAILED", "CAMERA_CATALOG_TIMEOUT", "VIDEO_OFFER_FAILED", "VIDEO_DESCRIPTION_FAILED", "ICE_TIMEOUT", "SOURCE_UNAVAILABLE", "CONNECTION_CLOSED", "MEDIA_NOT_READY").contains(code)) return code;
        String detail = code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
        if (detail.contains("access denied") || detail.contains("permission") || detail.contains("0x80070005")) return "CAMERA_ACCESS_DENIED";
        if (detail.contains("busy") || detail.contains("in use") || detail.contains("being used")) return "CAMERA_DEVICE_BUSY";
        return "CAMERA_OPEN_FAILED";
    }
    public static String audioCode(Throwable error) {
        return audioCode(error, AudioOperation.UNKNOWN);
    }
    public static String audioCode(Throwable error, AudioOperation operation) {
        error = unwrap(error);
        if (error instanceof InterruptedException || error instanceof CancellationException) return "CONNECTION_CLOSED";
        if (error instanceof TimeoutException) return "AUDIO_CONNECTION_FAILED";
        String code = code(error);
        if (code.equals("VIDEO_OFFER_FAILED") || code.equals("VIDEO_DESCRIPTION_FAILED") || code.equals("ICE_TIMEOUT")) return "AUDIO_CONNECTION_FAILED";
        if (!code.startsWith("CAMERA_")) return code;
        // A server rejection is not evidence of a local microphone/speaker failure.
        // Unknown protocol codes remain connection failures instead of passing through device classification.
        if (error instanceof SignalException) return "AUDIO_CONNECTION_FAILED";
        if (operation == AudioOperation.CLEANUP) return "AUDIO_CLEANUP_FAILED";

        // These strings are examined locally only. The original device/native message is never displayed or logged.
        String detail = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (detail.contains("playout") || detail.contains("render") || detail.contains("speaker")) return "AUDIO_OUTPUT_FAILED";
        if (detail.contains("recording") || detail.contains("microphone") || detail.contains("capture")) return "MICROPHONE_OPEN_FAILED";
        return switch (operation) {
            case CAPTURE -> "MICROPHONE_OPEN_FAILED";
            case PLAYBACK -> "AUDIO_OUTPUT_FAILED";
            case CLEANUP -> "AUDIO_CLEANUP_FAILED";
            case UNKNOWN -> "AUDIO_DEVICE_FAILED";
        };
    }
    public static String audioMessage(Throwable error) { return message(new SignalException(audioCode(error))); }
    public static String audioMessage(Throwable error, AudioOperation operation) { return message(new SignalException(audioCode(error, operation))); }
    public static String screenCode(Throwable error) { String code=code(error); return code.equals("VIDEO_OFFER_FAILED") || code.equals("VIDEO_DESCRIPTION_FAILED") || code.equals("ICE_TIMEOUT") ? "SCREEN_CONNECTION_FAILED" : code.startsWith("CAMERA_") ? "SCREEN_CAPTURE_FAILED" : code; }
    public static String screenMessage(Throwable error) { return message(new SignalException(screenCode(error))); }
    public static String message(Throwable error) {
        return switch (code(error)) {
            case "AUDIO_CONNECTION_FAILED" -> "Không kết nối được âm thanh. Kiểm tra mạng rồi chủ động bật lại mic.";
            case "SCREEN_CONNECTION_FAILED" -> "Không kết nối được nguồn chia sẻ. Kiểm tra mạng và chọn lại nguồn.";
            case "ALREADY_PUBLISHING", "VIDEO_BUSY" -> "Đang thiết lập video. Vui lòng thử lại sau giây lát.";
            case "MEDIA_BUSY" -> "Máy chủ đang hết tài nguyên media. Vui lòng thử lại sau giây lát.";
            case "VIDEO_VIEWPORT_LIMIT" -> "Đã đạt giới hạn video đang nhận. Hãy đổi trang hoặc bỏ ghim video.";
            case "MEDIA_CLOSED", "ENGINE_CHANGED", "LEASE_EXPIRED" -> "Kết nối media đã dừng. Hãy chủ động bật lại sau khi kết nối phòng ổn định.";
            case "MICROPHONE_PERMISSION_REQUIRED" -> "Nhấn Bật mic và cho phép sử dụng microphone.";
            case "AUDIO_OUTPUT_FAILED" -> "Không mở được loa. Kiểm tra thiết bị phát âm thanh trong Windows và chọn lại Loa trong Cài đặt.";
            case "MICROPHONE_OPEN_FAILED" -> "Không mở được microphone. Kiểm tra thiết bị đã chọn và quyền Microphone trong Windows Settings → Privacy & security.";
            case "AUDIO_DEVICE_FAILED" -> "Không khởi tạo được thiết bị âm thanh. Chọn lại microphone hoặc loa trong Cài đặt rồi thử lại.";
            case "AUDIO_CLEANUP_FAILED" -> "Chưa dừng được thiết bị âm thanh. Đóng ứng dụng rồi mở lại trước khi thử tiếp.";
            case "SHARE_IN_USE" -> "Đã có người đang chia sẻ màn hình trong phòng.";
            case "SCREEN_NO_FRAMES", "SCREEN_CAPTURE_FAILED" -> "Không nhận được hình màn hình. Kiểm tra quyền Windows, khôi phục cửa sổ đang thu nhỏ hoặc chọn lại nguồn chia sẻ.";
            case "CAMERA_PERMISSION_REQUIRED" -> "Nhấn Bật camera và cho phép để tiếp tục.";
            case "CAMERA_ACCESS_DENIED" -> "Windows đang chặn camera. Kiểm tra quyền camera dành cho desktop apps.";
            case "CAMERA_DEVICE_BUSY" -> "Camera đang được ứng dụng hoặc cửa sổ khác sử dụng. Tắt camera ở cửa sổ đó hoặc chọn thiết bị khác.";
            case "CAMERA_FORMAT_UNAVAILABLE" -> "Camera này không có định dạng video được hỗ trợ. Chọn camera khác (ưu tiên camera màu thay cho camera IR).";
            case "CAMERA_NOT_FOUND" -> "Không có camera khả dụng trên máy. Bạn vẫn có thể nhận video.";
            case "CAMERA_NO_FRAMES" -> "Camera không trả về hình ảnh. Kiểm tra quyền Windows, thiết bị bị chiếm dụng hoặc chọn camera khác.";
            case "CAMERA_CATALOG_TIMEOUT" -> "Đọc camera quá thời hạn. Kiểm tra kết nối thiết bị hoặc chọn camera khác; video nhận vẫn tiếp tục.";
            case "VIDEO_OFFER_FAILED", "VIDEO_DESCRIPTION_FAILED", "ICE_TIMEOUT" -> "Không thiết lập được kết nối video với server. Kiểm tra kết nối và cấu hình mạng.";
            case "SOURCE_UNAVAILABLE" -> "Nguồn video đã dừng hoặc thay đổi; đang chờ nguồn mới.";
            case "CONNECTION_CLOSED" -> "Kết nối phòng đã kết thúc.";
            case "MEDIA_NOT_READY" -> "Server chưa sẵn sàng cho video. Khởi động server từ Main.java trong package server.";
            default -> "Không mở được camera. Kiểm tra quyền Windows, camera bị chiếm dụng và chọn đúng thiết bị trong Cài đặt.";
        };
    }
}
