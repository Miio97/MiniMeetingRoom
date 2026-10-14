package server.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.signaling.SignalException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Server-only loopback control. No login token, PIN or DB credential crosses this boundary. */
public final class VideoEngineClient implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .proxy(new java.net.ProxySelector() {
                public java.util.List<java.net.Proxy> select(URI uri) { return java.util.List.of(java.net.Proxy.NO_PROXY); }
                public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException error) { }
            }).build();
    private final URI base;
    private final String secret;
    private final String instance;
    private final long processId;
    public VideoEngineClient(int port, String secret) throws Exception {
        this.base = URI.create("http://127.0.0.1:" + port); this.secret = secret;
        JsonObject health;
        try { health = request("GET", "/health", null); }
        catch (Exception failure) { client.close(); throw failure; }
        instance = health.get("instance").getAsString(); processId = health.get("pid").getAsLong();
        if (!instance.matches("[a-f0-9]{32}")) throw new SignalException("ENGINE_UNAVAILABLE");
    }
    public long processId() { return processId; }
    public JsonObject control(JsonObject body) throws Exception {
        body = body.deepCopy(); body.addProperty("instance", instance);
        return request("POST", "/v1/control", body);
    }
    private JsonObject request(String method, String path, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(12))
                .header("X-MMR-Media-Key", secret).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var reply = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var input = reply.body()) {
            byte[] bytes = input.readNBytes(128 * 1024 + 1);
            if (bytes.length > 128 * 1024) throw new SignalException("MEDIA_CONTROL_LIMIT");
            JsonObject result = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (reply.statusCode() != 200) {
                String code = result.has("code") ? result.get("code").getAsString() : "MEDIA_CONTROL_FAILED";
                if (!java.util.Set.of("MEDIA_BUSY", "ENGINE_CHANGED", "LEASE_EXPIRED", "ALREADY_PUBLISHING", "ALREADY_SUBSCRIBED",
                        "SOURCE_UNAVAILABLE", "REQUEST_CONFLICT", "OFFER_REJECTED", "ICE_TIMEOUT", "MEDIA_CLOSED").contains(code)) code = "MEDIA_CONTROL_FAILED";
                throw new SignalException(code);
            }
            return result;
        }
    }
    @Override public void close() { client.close(); }
    public void shutdown() { try { request("POST", "/shutdown", new JsonObject()); } catch (Exception ignored) { } }
    @Override public String toString() { return "VideoEngineClient[redacted]"; }
}
