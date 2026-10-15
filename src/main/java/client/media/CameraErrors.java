package client.media;

import common.signaling.SignalException;
import java.util.Set;
import java.util.concurrent.*;

/** Classify locally; never expose or log raw native error text or device descriptors. */
public final class CameraErrors {
    private CameraErrors() { }
    public static String code(Throwable error) {
        while (error.getCause() != null && (error instanceof CompletionException || error instanceof ExecutionException || error instanceof java.io.UncheckedIOException)) error = error.getCause();
        if (error instanceof TimeoutException) return "CAMERA_CATALOG_TIMEOUT";
        String code = error instanceof SignalException signal ? signal.code() : error.getMessage();
        if (code != null && Set.of("CAMERA_PERMISSION_REQUIRED", "CAMERA_FORMAT_UNAVAILABLE", "CAMERA_NOT_FOUND", "CAMERA_NO_FRAMES",
                "CAMERA_CATALOG_TIMEOUT", "VIDEO_OFFER_FAILED", "VIDEO_DESCRIPTION_FAILED", "ICE_TIMEOUT", "SOURCE_UNAVAILABLE", "CONNECTION_CLOSED", "MEDIA_NOT_READY").contains(code)) return code;
        String detail = code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
        if (detail.contains("access denied") || detail.contains("permission") || detail.contains("0x80070005")) return "CAMERA_ACCESS_DENIED";
        if (detail.contains("busy") || detail.contains("in use") || detail.contains("being used")) return "CAMERA_DEVICE_BUSY";
        return "CAMERA_OPEN_FAILED";
    }
    public static String message(Throwable error) {
        return switch (code(error)) {
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
