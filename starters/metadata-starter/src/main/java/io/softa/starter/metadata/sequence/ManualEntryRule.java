package io.softa.starter.metadata.sequence;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.softa.framework.orm.dto.DTOFieldObject;

/**
 * How codes typed by hand are checked for one sequence — the Manual Entry block of a numbering rule.
 *
 * <p>Stored as one JSON column on the sequence, so the settings of every format survive a switch to
 * another: only {@link #format} decides which of them apply. A sequence with no rule checks as
 * {@link ManualEntryFormat#ANY}. Generated codes are never checked against it.
 *
 * @param format            which check applies
 * @param minLength         {@code LENGTH_AND_CHARACTERS}: shortest allowed, 1–32
 * @param maxLength         {@code LENGTH_AND_CHARACTERS}: longest allowed, 1–32
 * @param allowedCharacters {@code LENGTH_AND_CHARACTERS}: the character set, null for any
 * @param pattern           {@code PATTERN}: a regular expression the whole code must match
 * @param errorMessage      {@code PATTERN}: shown as is when the code does not match
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManualEntryRule(
        ManualEntryFormat format,
        Integer minLength,
        Integer maxLength,
        AllowedCharacters allowedCharacters,
        String pattern,
        String errorMessage) implements DTOFieldObject {

    /** The format that applies, {@code ANY} when none is set. */
    public ManualEntryFormat effectiveFormat() {
        return format == null ? ManualEntryFormat.ANY : format;
    }
}
