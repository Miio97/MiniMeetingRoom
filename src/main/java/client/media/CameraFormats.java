package client.media;

import com.google.gson.*;

import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.*;

import java.io.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Some virtual-camera drivers block GetStreamCaps. Query in a bounded child, never in the call JVM.
 */
public final class CameraFormats {
    private static final String PREFIX = "MMR_CAMERA_FORMATS:";

    private CameraFormats() {}

    public static List<VideoCaptureCapability> read(VideoDevice device) throws Exception {
        Set<String> entries =
                new LinkedHashSet<>(
                        Arrays.asList(
                                System.getProperty("java.class.path")
                                        .split(java.io.File.pathSeparator)));
        for (Class<?> type : List.of(CameraFormats.class, MediaDevices.class, Gson.class)) {
            var location = type.getProtectionDomain().getCodeSource().getLocation();
            entries.add(Path.of(location.toURI()).toString());
        }
        for (ClassLoader loader = CameraFormats.class.getClassLoader();
                loader != null;
                loader = loader.getParent())
            if (loader instanceof URLClassLoader urls)
                for (var url : urls.getURLs())
                    if (url.getProtocol().equals("file"))
                        entries.add(Path.of(url.toURI()).toString());
        String executable =
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? "javaw.exe"
                        : "java";
        ProcessBuilder builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                        "-cp",
                        String.join(java.io.File.pathSeparator, entries),
                        CameraFormats.class.getName());
        builder.environment()
                .keySet()
                .removeIf(
                        key ->
                                key.startsWith("MMR_DB_")
                                        || key.startsWith("MMR_AUTH_")
                                        || key.startsWith("MMR_TEST_"));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        var responseBytes = new java.util.concurrent.CompletableFuture<byte[]>();
        Thread.ofVirtual()
                .name("camera-formats-output")
                .start(
                        () -> {
                            try (var input = child.getInputStream()) {
                                responseBytes.complete(input.readNBytes(65537));
                            } catch (IOException failure) {
                                responseBytes.completeExceptionally(failure);
                            }
                        });
        try {
            JsonObject request = new JsonObject();
            request.addProperty("descriptor", device.getDescriptor());
            try (var output = child.getOutputStream()) {
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!child.waitFor(8, TimeUnit.SECONDS))
                throw new IllegalStateException("CAMERA_CATALOG_TIMEOUT");
            byte[] bytes = responseBytes.get(2, TimeUnit.SECONDS);
            if (bytes.length > 65536) throw new IllegalStateException("CAMERA_FORMAT_UNAVAILABLE");
            String response =
                    new String(bytes, StandardCharsets.UTF_8)
                            .lines()
                            .filter(line -> line.startsWith(PREFIX))
                            .reduce((before, after) -> after)
                            .orElseThrow(
                                    () -> new IllegalStateException("CAMERA_FORMAT_UNAVAILABLE"));
            JsonObject value =
                    JsonParser.parseString(response.substring(PREFIX.length()).strip())
                            .getAsJsonObject();
            if (!value.has("formats")) throw new IllegalStateException("CAMERA_FORMAT_UNAVAILABLE");
            List<VideoCaptureCapability> formats = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray("formats")) {
                JsonObject mode = element.getAsJsonObject();
                formats.add(
                        new VideoCaptureCapability(
                                mode.get("width").getAsInt(),
                                mode.get("height").getAsInt(),
                                mode.get("fps").getAsInt()));
                if (formats.size() >= 256) break;
            }
            return List.copyOf(formats);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            child.waitFor(2, TimeUnit.SECONDS);
        }
    }

    /** Internal metadata helper: no camera start, no PeerConnection, no login or database. */
    public static void main(String[] args) {
        JsonObject result = new JsonObject();
        try {
            byte[] bytes = System.in.readNBytes(16385);
            if (bytes.length > 16384) throw new IllegalStateException("LIMIT");
            String descriptor =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject()
                            .get("descriptor")
                            .getAsString();
            VideoDevice device =
                    MediaDevices.getVideoCaptureDevices().stream()
                            .filter(item -> item.getDescriptor().equals(descriptor))
                            .findFirst()
                            .orElseThrow();
            JsonArray formats = new JsonArray();
            for (VideoCaptureCapability capability :
                    MediaDevices.getVideoCaptureCapabilities(device).stream().limit(256).toList()) {
                JsonObject mode = new JsonObject();
                mode.addProperty("width", capability.width);
                mode.addProperty("height", capability.height);
                mode.addProperty("fps", capability.frameRate);
                formats.add(mode);
            }
            result.add("formats", formats);
        } catch (Throwable failure) {
            result.addProperty("error", "CAMERA_FORMAT_UNAVAILABLE");
        }
        System.out.println(PREFIX + result);
        System.out.flush();
        System.exit(0);
    }
}
