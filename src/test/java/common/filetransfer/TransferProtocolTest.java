package common.filetransfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferProtocolTest {
    @Test void fragmentedAndCoalescedFramesRetainBoundariesAndUnicode() throws Exception {
        byte[] bytes = new byte[65536]; Arrays.fill(bytes, (byte) 0xC7);
        UUID id = UUID.randomUUID();
        var original = new TransferProtocol.Frame(TransferProtocol.Type.UPLOAD_CHUNK, id,
                TransferProtocol.json(Map.of("offset", 17, "name", "Đồ án 日本語.exe")), bytes);
        var buffer = new ByteArrayOutputStream();
        var output = new DataOutputStream(buffer);
        TransferProtocol.write(output, original); TransferProtocol.write(output, original);
        var input = new DataInputStream(new FilterInputStream(new ByteArrayInputStream(buffer.toByteArray())) {
            @Override public int read(byte[] target, int offset, int length) throws IOException {
                return super.read(target, offset, Math.min(3, length));
            }
        });
        for (int i = 0; i < 2; i++) {
            var frame = TransferProtocol.read(input);
            assertEquals(id, frame.transferId()); assertEquals(original.header(), frame.header());
            assertArrayEquals(bytes, frame.payload());
        }
        assertEquals(-1, input.read());
    }
    @Test void lengthsAndTypesRejectedBeforeAllocationOrBodyRead() throws Exception {
        for (int[] lengths : new int[][]{{-1, 0}, {Integer.MAX_VALUE, 0}, {2, -1}, {2, Integer.MAX_VALUE}, {0, 0}}) {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(TransferProtocol.MAGIC); out.writeShort(1); out.writeShort(3);
            out.writeInt(lengths[0]); out.writeInt(lengths[1]);
            assertEquals("INVALID_FRAME", assertThrows(TransferException.class,
                    () -> TransferProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))).code());
        }
        var out = new DataOutputStream(new ByteArrayOutputStream());
        assertThrows(TransferException.class, () -> TransferProtocol.write(out,
                new TransferProtocol.Frame(TransferProtocol.Type.UPLOAD_INIT, UUID.randomUUID(),
                        TransferProtocol.json(Map.of()), new byte[10])));
    }
    @Test void malformedNestedOrNonObjectJsonIsContained() throws Exception {
        for (String header : new String[]{"[]", "null", "\"x\"", "{'token':'x'}", "{roomId:1}", "{} {}", "{".repeat(100), "[".repeat(20) + "]".repeat(20)}) {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            byte[] content = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(TransferProtocol.MAGIC); out.writeShort(1); out.writeShort(1);
            out.writeInt(content.length); out.writeInt(0); out.writeLong(0); out.writeLong(0); out.write(content);
            assertThrows(IOException.class, () -> TransferProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        }
    }
}
