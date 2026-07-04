package io.softa.starter.metadata.ddl;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultValueLiterals} — the type-aware rendering of a
 * declared {@code defaultValue} into the DEFAULT clause.
 */
class DefaultValueLiteralsTest {

    @Test
    void blankRendersNull() {
        assertNull(DefaultValueLiterals.render(FieldType.STRING, null, "c"));
        assertNull(DefaultValueLiterals.render(FieldType.STRING, "  ", "c"));
    }

    @Test
    void stringValueIsQuoted() {
        assertEquals("'ACTIVE'", DefaultValueLiterals.render(FieldType.STRING, "ACTIVE", "status"));
        assertEquals("'draft'", DefaultValueLiterals.render(FieldType.OPTION, "draft", "state"));
    }

    @Test
    void embeddedQuoteIsDoubled() {
        assertEquals("'O''Brien'", DefaultValueLiterals.render(FieldType.STRING, "O'Brien", "name"));
    }

    @Test
    void numericValuePassesThrough() {
        assertEquals("-1", DefaultValueLiterals.render(FieldType.INTEGER, "-1", "remaining"));
        assertEquals("3.14", DefaultValueLiterals.render(FieldType.BIG_DECIMAL, "3.14", "rate"));
    }

    @Test
    void nonNumericOnNumericTypeFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DefaultValueLiterals.render(FieldType.LONG, "abc", "cnt"));
        assertTrue(ex.getMessage().contains("cnt"), ex.getMessage());
    }

    @Test
    void booleanNormalizesToKeywords() {
        assertEquals("TRUE", DefaultValueLiterals.render(FieldType.BOOLEAN, "true", "copyable"));
        assertEquals("TRUE", DefaultValueLiterals.render(FieldType.BOOLEAN, "1", "copyable"));
        assertEquals("FALSE", DefaultValueLiterals.render(FieldType.BOOLEAN, "False", "copyable"));
        assertEquals("FALSE", DefaultValueLiterals.render(FieldType.BOOLEAN, "0", "copyable"));
        assertThrows(IllegalStateException.class,
                () -> DefaultValueLiterals.render(FieldType.BOOLEAN, "yes", "copyable"));
    }

    @Test
    void expressionKeywordsPassUnquoted() {
        assertEquals("CURRENT_TIMESTAMP",
                DefaultValueLiterals.render(FieldType.DATE_TIME, "current_timestamp", "created"));
        assertEquals("CURRENT_DATE",
                DefaultValueLiterals.render(FieldType.DATE, "CURRENT_DATE", "start_date"));
    }

    @Test
    void dateValueIsQuoted() {
        assertEquals("'2026-01-01'", DefaultValueLiterals.render(FieldType.DATE, "2026-01-01", "start_date"));
    }
}
