package common.filetransfer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * MMRF v1, big endian: magic u32, version u16, type u16, header i32, payload i32, UUID 128 bits,
 * UTF-8 JSON object, raw binary payload. Lengths are validated BEFORE allocation; readFully handles
 * fragmented TCP reads.
 */
public final class TransferProtocol {
    public static final int MAGIC = 0x4d4d5246;
    public static final int VERSION = 1;
    public static final int MAX_HEADER = 16 * 1024;
    public static final int MAX_PAYLOAD = 256 * 1024;
    public static final Gson GSON = new Gson();

    public enum Type {
        UPLOAD_INIT(1),
        UPLOAD_ACCEPT(2),
        UPLOAD_CHUNK(3),
        UPLOAD_COMPLETE(4),
        UPLOAD_FAILED(5),
        DOWNLOAD_REQUEST(6),
        DOWNLOAD_ACCEPT(7),
        DOWNLOAD_CHUNK(8),
        DOWNLOAD_COMPLETE(9),
        DOWNLOAD_FAILED(10),
        FILE_AVAILABLE(11),
        TRANSFER_CANCEL(12),
        TRANSFER_STATUS(13),
        ERROR(14),
        ROOM_CREATE(15),
        ROOM_JOIN(16),
        ROOM_LEAVE(17),
        ROOM_RESULT(18),
        FILE_LIST(19);
        private final int code;

        Type(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Type from(int code) throws TransferException {
            for (Type type : values()) if (type.code == code) return type;
            throw invalid();
        }
    }

    public record Frame(Type type, UUID transferId, JsonObject header, byte[] payload) {
        // A record's default toString would reveal the token in its header.
        @Override
        public String toString() {
            return "Frame[type=" + type + ", transferId=" + transferId + "]";
        }
    }

    private TransferProtocol() {}

    public static JsonObject json(Object value) {
        return GSON.toJsonTree(value).getAsJsonObject();
    }

    public static Frame read(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC || input.readUnsignedShort() != VERSION) throw invalid();
        Type type = Type.from(input.readUnsignedShort());
        int headerLength = input.readInt();
        int payloadLength = input.readInt();
        lengths(type, headerLength, payloadLength);
        UUID id = new UUID(input.readLong(), input.readLong());
        byte[] header = new byte[headerLength];
        input.readFully(header);
        JsonObject object;
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(header))
                            .toString();
            // Bound JSON nesting before Gson parses an untrusted header.
            int depth = 0;
            boolean quoted = false;
            boolean escape = false;
            for (char c : text.toCharArray()) {
                if (quoted) {
                    if (escape) escape = false;
                    else if (c == '\\') escape = true;
                    else if (c == '"') quoted = false;
                } else if (c == '"') quoted = true;
                else if (c == '{' || c == '[') {
                    if (++depth > 16) throw invalid();
                } else if (c == '}' || c == ']') depth--;
            }
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);
                object = GSON.fromJson(reader, JsonObject.class);
                if (object == null || reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            }
        } catch (RuntimeException e) {
            throw invalid();
        }
        byte[] payload = new byte[payloadLength];
        input.readFully(payload);
        return new Frame(type, id, object, payload);
    }

    public static void write(DataOutputStream output, Frame frame) throws IOException {
        byte[] header = GSON.toJson(frame.header()).getBytes(StandardCharsets.UTF_8);
        byte[] payload = frame.payload() == null ? new byte[0] : frame.payload();
        lengths(frame.type(), header.length, payload.length);
        output.writeInt(MAGIC);
        output.writeShort(VERSION);
        output.writeShort(frame.type().code());
        output.writeInt(header.length);
        output.writeInt(payload.length);
        output.writeLong(frame.transferId().getMostSignificantBits());
        output.writeLong(frame.transferId().getLeastSignificantBits());
        output.write(header);
        output.write(payload);
        output.flush();
    }

    private static void lengths(Type type, int header, int payload) throws TransferException {
        if (header < 2
                || header > MAX_HEADER
                || payload < 0
                || payload > MAX_PAYLOAD
                || (payload != 0 && type != Type.UPLOAD_CHUNK && type != Type.DOWNLOAD_CHUNK))
            throw invalid();
    }

    private static TransferException invalid() {
        return new TransferException("INVALID_FRAME", "Khung dữ liệu không hợp lệ.");
    }
}
