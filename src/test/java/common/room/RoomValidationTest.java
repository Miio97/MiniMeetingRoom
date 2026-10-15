package common.room;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomValidationTest {
    @Test
    void joinAcceptsLegacyAndModernCodesWithWhitespaceAndCaseNormalization() {
        for (String code : new String[]{"MMR-0123", "MMR-AB123DEF", "MMR-23456789ABCDEFGH", "  mmr-abcd  "}) {
            assertNull(RoomValidation.codeError(code), code);
        }
    }

    @Test
    void joinRejectsNumericReferencesAndMalformedCodesWithCodeOnlyMessages() {
        for (String code : new String[]{null, "", " ", "1", " 123456 ", "9223372036854775807",
                "MMR-ABC", "MMR-12345678901234567", "MMR-AB_CD", "MMR-AB CD", "' OR '1'='1"}) {
            String error = RoomValidation.codeError(code);
            assertNotNull(error, code);
            assertFalse(error.contains("ID"));
            assertTrue(error.contains("mã phòng"));
        }
    }

    @Test
    void internalReferencesRemainCompatibleForRoomOperations() {
        assertNull(RoomValidation.identifierError("1"));
        assertNull(RoomValidation.identifierError("9223372036854775807"));
        assertNull(RoomValidation.identifierError("MMR-ABCD"));
        assertNotNull(RoomValidation.identifierError("0"));
        assertNotNull(RoomValidation.identifierError("9223372036854775808"));
        assertNotNull(RoomValidation.identifierError(null));
        assertFalse(RoomValidation.identifierError("invalid").contains("ID"));
    }
}
