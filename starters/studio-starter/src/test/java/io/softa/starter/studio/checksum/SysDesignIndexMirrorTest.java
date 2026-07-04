package io.softa.starter.studio.checksum;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.studio.meta.entity.DesignModelIndex;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural mirror guard for the index {@code message} attribute.
 *
 * <p>{@code INDEX_ATTRS} is derived from {@code SysModelIndex} alone, so a value-only golden
 * fixture is one-directional: if {@code message} were added to {@code DesignModelIndex} but
 * forgotten on {@code SysModelIndex}, the attribute would be projected away on BOTH sides and
 * the cross-lane equality would still pass while studio messages silently never shipped. This
 * test closes both directions by asserting each entity declares a {@code @Field}-annotated
 * {@code message} field of identical Java type.
 */
class SysDesignIndexMirrorTest {

    @Test
    void bothLanesDeclareMessageFieldOfSameType() {
        Field sys = fieldOf(SysModelIndex.class, "message");
        Field design = fieldOf(DesignModelIndex.class, "message");
        assertNotNull(sys.getAnnotation(io.softa.framework.orm.annotation.Field.class),
                "SysModelIndex.message must carry @Field");
        assertNotNull(design.getAnnotation(io.softa.framework.orm.annotation.Field.class),
                "DesignModelIndex.message must carry @Field");
        assertEquals(sys.getType(), design.getType(),
                "message field type must match across the runtime and studio lanes");
        assertEquals(String.class, sys.getType());
    }

    private static Field fieldOf(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(type.getSimpleName() + " must declare a '" + name + "' field", e);
        }
    }
}
