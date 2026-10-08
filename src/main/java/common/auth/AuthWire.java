package common.auth;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Four-byte big-endian length followed by one UTF-8 JSON object. */
public final class AuthWire {
    public static final int MAX_FRAME_BYTES = 16 * 1024;
    private static final Gson JSON = new Gson();

    private AuthWire() { }

    public static void write(DataOutputStream output, Object message) throws IOException {
        byte[] bytes = JSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) {
            throw new IOException("Kích thước thông điệp không hợp lệ.");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    public static <T> T read(DataInputStream input, Class<T> type) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Kích thước thông điệp không hợp lệ.");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            T message = JSON.fromJson(reader, type);
            if (message == null || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Thông điệp JSON không hợp lệ.");
            }
            return message;
        } catch (JsonParseException | IllegalStateException | CharacterCodingException e) {
            throw new IOException("Thông điệp JSON không hợp lệ.", e);
        }
    }
}
