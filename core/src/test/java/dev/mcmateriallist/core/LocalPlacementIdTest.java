package dev.mcmateriallist.core;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalPlacementIdTest {
    @Test void generatedIdsAreDifferent() {
        assertNotEquals(LocalPlacementId.create(), LocalPlacementId.create());
    }
    @Test void parseRestoresTheSameIdentity() {
        String text = "01234567-89ab-4cde-8f01-23456789abcd";
        assertEquals(new LocalPlacementId(UUID.fromString(text)), LocalPlacementId.parse(text));
        assertEquals(text, LocalPlacementId.parse(text).toString());
    }
    @Test void rejectsNull() {
        assertThrows(NullPointerException.class, () -> new LocalPlacementId(null));
        assertThrows(NullPointerException.class, () -> LocalPlacementId.parse(null));
    }
    @Test void rejectsMalformedAndNonCanonicalIds() {
        for (String value : new String[]{"", "not-an-id", "1-1-1-1-1", "01234567-89ab-4cde-8f01-23456789abcd "}) {
            assertThrows(IllegalArgumentException.class, () -> LocalPlacementId.parse(value));
        }
    }
}
