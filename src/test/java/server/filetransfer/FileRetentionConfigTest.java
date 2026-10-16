package server.filetransfer;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileRetentionConfigTest {
    @Test void defaultsToTwentyMinutesAndMinutesOverrideLegacyDays() {
        assertEquals(Duration.ofMinutes(20), FileTransferServer.retentionPeriod(null, null));
        assertEquals(Duration.ofMinutes(20), FileTransferServer.retentionPeriod("20", "0"));
        assertEquals(Duration.ZERO, FileTransferServer.retentionPeriod("0", "1"));
        assertEquals(Duration.ofDays(1), FileTransferServer.retentionPeriod(null, "1"));
        assertEquals(Duration.ZERO, FileTransferServer.retentionPeriod(null, "0"));
    }

    @Test void rejectsInvalidRetentionInsteadOfSilentlyChangingDeletionPolicy() {
        for (String invalid : new String[]{"-1", "abc", "52560001", "99999999999999999999999"})
            assertThrows(IllegalArgumentException.class, () -> FileTransferServer.retentionPeriod(invalid, null));
        assertThrows(IllegalArgumentException.class, () -> FileTransferServer.retentionPeriod(null, "36501"));
    }
}
