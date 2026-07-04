package io.softa.starter.metadata.scanner.annotation.inference;

import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link JsonValueResolver}. Covers the three resolution paths:
 * {@code @JsonValue} on a field, {@code @JsonValue} on a method, and the
 * {@code enum.name()} fallback.
 */
class JsonValueResolverTest {

    enum WithFieldAnnotation {
        GOLD("g"), SILVER("s");

        @JsonValue
        private final String code;

        WithFieldAnnotation(String code) {
            this.code = code;
        }
    }

    enum WithMethodAnnotation {
        GOLD, SILVER;

        @JsonValue
        public String code() {
            return name().toLowerCase();
        }
    }

    enum WithoutAnnotation {
        GOLD, SILVER
    }

    enum WithNullJsonValue {
        BLANK("");

        @SuppressWarnings("unused")
        @JsonValue
        private final String code;

        WithNullJsonValue(String code) {
            this.code = code;
        }
    }

    @Test
    void resolves_fromFieldAnnotation() {
        assertEquals("g", JsonValueResolver.resolveItemCode(WithFieldAnnotation.GOLD));
        assertEquals("s", JsonValueResolver.resolveItemCode(WithFieldAnnotation.SILVER));
    }

    @Test
    void resolves_fromMethodAnnotation() {
        assertEquals("gold", JsonValueResolver.resolveItemCode(WithMethodAnnotation.GOLD));
        assertEquals("silver", JsonValueResolver.resolveItemCode(WithMethodAnnotation.SILVER));
    }

    @Test
    void fallsBack_toEnumName_whenAnnotationAbsent() {
        assertEquals("GOLD", JsonValueResolver.resolveItemCode(WithoutAnnotation.GOLD));
    }

    @Test
    void emptyJsonValue_isPreserved_notFallback() {
        // The @JsonValue field IS present and holds "". The resolver returns
        // that value as-is (toString) — Jackson would do the same.
        assertEquals("", JsonValueResolver.resolveItemCode(WithNullJsonValue.BLANK));
    }

    @Test
    void nullEnum_raises() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonValueResolver.resolveItemCode(null));
    }
}
