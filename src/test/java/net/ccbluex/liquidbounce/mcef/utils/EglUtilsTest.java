package net.ccbluex.liquidbounce.mcef.utils;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EglUtilsTest {
    private IntBuffer direct(int... values) {
        IntBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        buffer.put(values).flip();
        return buffer;
    }

    @Test
    void acceptsTerminatedAttributesWithoutChangingPosition() {
        IntBuffer values = direct(0x3057, 640, 0x3056, 480, 0x3038);
        assertDoesNotThrow(() -> EglUtils.validateAttributes(values));
        assertEquals(0, values.position());
    }

    @Test
    void rejectsUnsafeNativeAttributeLists() {
        assertThrows(IllegalArgumentException.class, () -> EglUtils.validateAttributes(IntBuffer.wrap(new int[]{0x3038})));
        assertThrows(IllegalArgumentException.class, () -> EglUtils.validateAttributes(direct()));
        assertThrows(IllegalArgumentException.class, () -> EglUtils.validateAttributes(direct(0x3057, 640)));
        assertThrows(IllegalArgumentException.class, () -> EglUtils.validateAttributes(direct(0x3057, 640, 0)));
    }
}
