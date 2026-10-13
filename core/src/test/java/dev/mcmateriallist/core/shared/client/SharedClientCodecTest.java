package dev.mcmateriallist.core.shared.client;

import dev.mcmateriallist.core.LocalPlacementId;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedClientCodecTest {
    @Test void malformedCountsFlagsTruncationAndTrailingBytesAreRejectedBeforeAllocation() {
        var empty = SharedClientArchive.empty(LocalPlacementId.create());
        byte[] valid = SharedClientCodec.encode(empty);
        assertEquals(empty, SharedClientCodec.decode(valid));
        byte[] hugeCount = valid.clone();
        ByteBuffer.wrap(hugeCount, hugeCount.length - Integer.BYTES, Integer.BYTES).putInt(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> SharedClientCodec.decode(hugeCount));
        byte[] invalidBoolean = valid.clone(); invalidBoolean[Long.BYTES + 16] = 2;
        assertThrows(IllegalArgumentException.class, () -> SharedClientCodec.decode(invalidBoolean));
        assertThrows(IllegalArgumentException.class, () -> SharedClientCodec.decode(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> SharedClientCodec.decode(Arrays.copyOf(valid, valid.length + 1)));
    }
}
