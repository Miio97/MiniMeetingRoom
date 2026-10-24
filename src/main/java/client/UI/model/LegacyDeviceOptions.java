package client.UI.model;

import java.util.List;

/** Labels for the explicitly simulated device controls of pre-WebRTC rooms. */
public final class LegacyDeviceOptions {
    private LegacyDeviceOptions() {}

    public static List<String> cameras() {
        return List.of("Camera tích hợp (demo)", "USB Webcam (demo)");
    }

    public static List<String> microphones() {
        return List.of("Microphone mặc định (demo)", "Headset Microphone (demo)");
    }

    public static List<String> speakers() {
        return List.of("Loa máy tính (demo)", "Tai nghe (demo)");
    }
}
