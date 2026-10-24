package client.media;

import dev.onvoid.webrtc.media.video.VideoDevice;

import java.util.List;
import java.util.Locale;

/**
 * No default video API in the pinned SDK: prefer saved choice, built-in/physical, then fallback.
 */
public final class CameraSelection {
    private CameraSelection() {}

    public static List<VideoDevice> physicalDevices(List<VideoDevice> devices) {
        return devices.stream()
                .filter(java.util.Objects::nonNull)
                .filter(
                        device ->
                                !virtual(device.getName().toLowerCase(Locale.ROOT))
                                        && !virtual(
                                                device.getDescriptor().toLowerCase(Locale.ROOT)))
                .limit(64)
                .toList();
    }

    public static VideoDevice choose(List<VideoDevice> devices, String preferred) {
        if (devices.isEmpty()) return null;
        if (preferred != null && !preferred.isEmpty())
            for (VideoDevice device : devices)
                if (preferred.equals(device.getDescriptor())) return device;
        for (VideoDevice device : devices) {
            String name = device.getName().toLowerCase(Locale.ROOT);
            if (virtual(name)) continue;
            if (name.contains("integrated")
                    || name.contains("built-in")
                    || name.contains("internal")
                    || name.contains("laptop")
                    || name.contains("truevision")
                    || name.contains("facetime")
                    || name.contains("easycamera")
                    || name.contains("user facing")) return device;
        }
        for (VideoDevice device : devices) {
            String name = device.getName().toLowerCase(Locale.ROOT);
            if (!virtual(name)
                    && (name.contains("webcam")
                            || name.contains("uvc")
                            || name.contains("usb")
                            || name.contains("camera"))) return device;
        }
        return devices.getFirst();
    }

    private static boolean virtual(String name) {
        return name.contains("virtual")
                || name.contains("bytecast")
                || name.contains("obs-camera")
                || name.contains("manycam")
                || name.contains("snap camera")
                || name.contains("droidcam")
                || name.contains("iriun")
                || name.contains("xsplit")
                || name.contains("mmhmm")
                || name.contains("nvidia broadcast")
                || name.contains("@device:sw:");
    }
}
