package common.signaling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Independent signaling framing. AuthWire remains at its existing 16 KiB limit. */
public final class SignalWire {
    public static final int DEFAULT_MAX_BYTES = 128 * 1024;
    private static final Gson JSON = new Gson();
    private static final Set<String> FIELDS =
            Set.of(
                    "version",
                    "type",
                    "requestId",
                    "serverEpoch",
                    "connectionId",
                    "generation",
                    "roomId",
                    "participantId",
                    "revision",
                    "body");

    private SignalWire() {}

    public static byte[] encode(SignalMessage message, int maximum) throws IOException {
        checkLimit(maximum);
        byte[] bytes = JSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IOException("FRAME_LIMIT");
        return bytes;
    }

    public static void write(DataOutputStream output, SignalMessage message, int maximum)
            throws IOException {
        writeEncoded(output, encode(message, maximum));
    }

    public static void writeEncoded(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    public static SignalMessage read(DataInputStream input, int maximum) throws IOException {
        checkLimit(maximum);
        int length = input.readInt();
        if (length < 2 || length > maximum) throw new IOException("FRAME_LIMIT");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            // Bound recursive JSON parsing; SDP strings are not interpreted as JSON nesting.
            int depth = 0;
            boolean quoted = false, escaped = false;
            for (char value : text.toCharArray()) {
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (value == '\\') escaped = true;
                    else if (value == '"') quoted = false;
                } else if (value == '"') quoted = true;
                else if ((value == '{' || value == '[') && ++depth > 32)
                    throw new IOException("FRAME_DEPTH");
                else if (value == '}' || value == ']') depth--;
            }
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT
                    || !object.has("body")
                    || !object.get("body").isJsonObject()
                    || object.keySet().stream().anyMatch(key -> !FIELDS.contains(key)))
                throw new IOException("FRAME_JSON");
            for (String field : Set.of("version", "generation")) {
                if (!object.has(field)
                        || !object.get(field).isJsonPrimitive()
                        || !object.get(field).getAsJsonPrimitive().isNumber()
                        || object.get(field).getAsBigDecimal().longValueExact() < 0)
                    throw new IOException("FRAME_JSON");
            }
            if (object.get("version").getAsBigDecimal().intValueExact() != 1)
                throw new IOException("FRAME_VERSION");
            if (object.has("revision")
                    && !object.get("revision").isJsonNull()
                    && (!object.get("revision").isJsonPrimitive()
                            || !object.get("revision").getAsJsonPrimitive().isNumber()
                            || object.get("revision").getAsBigDecimal().longValueExact() < 0))
                throw new IOException("FRAME_JSON");
            for (String field :
                    Set.of(
                            "type",
                            "requestId",
                            "serverEpoch",
                            "connectionId",
                            "roomId",
                            "participantId")) {
                if (object.has(field)
                        && !object.get(field).isJsonNull()
                        && (!object.get(field).isJsonPrimitive()
                                || !object.get(field).getAsJsonPrimitive().isString()))
                    throw new IOException("FRAME_JSON");
            }
            return JSON.fromJson(object, SignalMessage.class);
        } catch (RuntimeException | java.nio.charset.CharacterCodingException failure) {
            throw new IOException("FRAME_JSON");
        }
    }

    private static void checkLimit(int maximum) {
        if (maximum < 16 * 1024 || maximum > 1024 * 1024)
            throw new IllegalArgumentException("FRAME_LIMIT_CONFIGURATION");
    }
}
