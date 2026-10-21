package io.softa.starter.metadata.sequence;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** The character sets a hand-typed code may be limited to; anything else goes through a pattern. */
@Getter
@AllArgsConstructor
public enum AllowedCharacters {
    LETTERS_NUMBERS_DASH_UNDERSCORE("LettersNumbersDashUnderscore", Pattern.compile("[A-Za-z0-9_-]*"),
            "letters, numbers, - and _"),
    LETTERS_NUMBERS("LettersNumbers", Pattern.compile("[A-Za-z0-9]*"), "letters and numbers"),
    NUMBERS("Numbers", Pattern.compile("[0-9]*"), "numbers"),
    ;

    @JsonValue
    private final String code;
    private final Pattern pattern;
    /** Completes "{label} can only contain …". */
    private final String wording;
}
