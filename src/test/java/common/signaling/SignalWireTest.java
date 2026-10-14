package common.signaling;

import com.google.gson.JsonObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignalWireTest {
    @Test void signalingHasItsOwnBoundedFrameAndPreservesLongGeneration() throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("sdp", "x".repeat(20000));
        SignalMessage message = new SignalMessage(1, "SDP_OFFER", "req-1", "epoch-1", "conn-1",
                Long.MAX_VALUE, "room-1", null, 123L, body);
        var bytes = new ByteArrayOutputStream();
        SignalWire.write(new DataOutputStream(bytes), message, SignalWire.DEFAULT_MAX_BYTES);
        assertEquals(message, SignalWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), SignalWire.DEFAULT_MAX_BYTES));
        assertThrows(IOException.class, () -> SignalWire.encode(message, 16384));
    }

    @Test void rejectsCoercedNumbersOverflowAndUnexpectedFields() {
        for (String json : new String[]{
            "{\"version\":4294967297,\"type\":\"HEARTBEAT\",\"generation\":1,\"body\":{}}",
            "{\"version\":1,\"type\":\"HEARTBEAT\",\"generation\":1.5,\"body\":{}}",
            "{\"version\":1,\"type\":\"HEARTBEAT\",\"generation\":\"1\",\"body\":{}}",
            "{\"version\":1,\"type\":\"HEARTBEAT\",\"generation\":1,\"roomId\":3,\"body\":{}}",
            "{\"version\":1,\"type\":\"HEARTBEAT\",\"generation\":1,\"unknown\":true,\"body\":{}}",
            "{\"version\":1,\"type\":\"HEARTBEAT\",\"generation\":1,\"body\":{}}{}"
        }) assertThrows(IOException.class, () -> read(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void rejectsInvalidUtf8TruncatedOversizedAndDeepFrames() throws Exception {
        assertThrows(IOException.class, () -> read(new byte[]{(byte) 0xc3, (byte) 0x28}));
        assertThrows(IOException.class, () -> read(("[".repeat(33) + "]".repeat(33)).getBytes(StandardCharsets.UTF_8)));
        var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
        output.writeInt(SignalWire.DEFAULT_MAX_BYTES + 1);
        assertThrows(IOException.class, () -> SignalWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), SignalWire.DEFAULT_MAX_BYTES));
        bytes.reset(); output.writeInt(100); output.writeByte(1);
        assertThrows(EOFException.class, () -> SignalWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), SignalWire.DEFAULT_MAX_BYTES));
    }
    private SignalMessage read(byte[] json) throws Exception {
        var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
        output.writeInt(json.length); output.write(json);
        return SignalWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), SignalWire.DEFAULT_MAX_BYTES);
    }
}
