package io.softa.starter.message.dlq.enums;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DeadLetterStatusTest {

    @Test
    void pendingCode() {
        Assertions.assertEquals("Pending", DeadLetterStatus.PENDING.getCode());
    }

    @Test
    void resolvedCode() {
        Assertions.assertEquals("Resolved", DeadLetterStatus.RESOLVED.getCode());
    }

    @Test
    void discardedCode() {
        Assertions.assertEquals("Discarded", DeadLetterStatus.DISCARDED.getCode());
    }

    @Test
    void valueOfResolvesCorrectly() {
        Assertions.assertEquals(DeadLetterStatus.PENDING, DeadLetterStatus.valueOf("PENDING"));
        Assertions.assertEquals(DeadLetterStatus.RESOLVED, DeadLetterStatus.valueOf("RESOLVED"));
        Assertions.assertEquals(DeadLetterStatus.DISCARDED, DeadLetterStatus.valueOf("DISCARDED"));
    }

    @Test
    void valueOfInvalidThrowsException() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> DeadLetterStatus.valueOf("UNKNOWN"));
    }

    @Test
    void allValuesHaveDistinctCode() {
        Assertions.assertEquals(3, DeadLetterStatus.values().length);
        Assertions.assertNotEquals(DeadLetterStatus.PENDING.getCode(), DeadLetterStatus.RESOLVED.getCode());
        Assertions.assertNotEquals(DeadLetterStatus.RESOLVED.getCode(), DeadLetterStatus.DISCARDED.getCode());
        Assertions.assertNotEquals(DeadLetterStatus.PENDING.getCode(), DeadLetterStatus.DISCARDED.getCode());
    }
}
