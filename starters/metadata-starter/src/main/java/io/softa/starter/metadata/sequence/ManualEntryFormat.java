package io.softa.starter.metadata.sequence;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** How a code typed by hand into a sequence-numbered field is checked — one of four, never combined. */
@Getter
@AllArgsConstructor
public enum ManualEntryFormat {
    /** No format check; only the fixed rules (no spaces, unique ignoring case). */
    ANY("Any"),
    /** The shape the template generates: the record's company abbreviation, the literals, and as many digits. */
    SAME_AS_AUTO_GENERATION("SameAsAutoGeneration"),
    /** A length range and/or a character set. */
    LENGTH_AND_CHARACTERS("LengthAndCharacters"),
    /** A regular expression, with the message shown when it does not match. */
    PATTERN("Pattern"),
    ;

    @JsonValue
    private final String code;
}
