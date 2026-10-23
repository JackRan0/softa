package io.softa.starter.metadata.sequence;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules a hand-typed code must pass, worded as the user reads them. */
class ManualCodeRulesTest {

    private static final String LABEL = "Employee Code";

    private static ManualEntryRule sameAsAuto() {
        return new ManualEntryRule(ManualEntryFormat.SAME_AS_AUTO_GENERATION, null, null, null, null, null);
    }

    private static ManualEntryRule length(Integer min, Integer max, AllowedCharacters chars) {
        return new ManualEntryRule(ManualEntryFormat.LENGTH_AND_CHARACTERS, min, max, chars, null, null);
    }

    @Test
    void spacesInsideACodeAreRefused() {
        assertThat(ManualCodeRules.spaces(LABEL, "ZK 123456")).isEqualTo("Employee Code cannot contain spaces.");
        assertThat(ManualCodeRules.spaces(LABEL, "ZK123456")).isNull();
    }

    @Test
    void anyChecksNothing() {
        ManualEntryRule any = new ManualEntryRule(ManualEntryFormat.ANY, 6, 10, null, null, null);
        assertThat(ManualCodeRules.format(LABEL, "x", any, "{seq:7}", null)).isNull();
        assertThat(ManualCodeRules.format(LABEL, "x", null, "{seq:7}", null)).isNull();
    }

    @Test
    void sameAsAutoGenerationTakesTheCompanyAbbreviationAndTheDigitCount() {
        String template = "{companyAbbr}{seq:7}";
        assertThat(ManualCodeRules.format(LABEL, "ZK1234567", sameAsAuto(), template, "ZK")).isNull();
        assertThat(ManualCodeRules.format(LABEL, "ZK12345", sameAsAuto(), template, "ZK"))
                .isEqualTo("Employee Code must be ZK + 7 digits.");
        assertThat(ManualCodeRules.format(LABEL, "HF1234567", sameAsAuto(), template, "ZK"))
                .isEqualTo("Employee Code must be ZK + 7 digits.");
        assertThat(ManualCodeRules.format(LABEL, "zk12345", sameAsAuto(), template, "ZK"))
                .isEqualTo("Employee Code must be ZK + 7 digits.");
    }

    @Test
    void sameAsAutoGenerationWithoutAnAbbreviationIsDigitsOnly() {
        assertThat(ManualCodeRules.format(LABEL, "1234567", sameAsAuto(), "{companyAbbr}{seq:7}", null)).isNull();
        assertThat(ManualCodeRules.format(LABEL, "zk12345", sameAsAuto(), "{companyAbbr}{seq:7}", " "))
                .isEqualTo("Employee Code must be 7 digits.");
    }

    @Test
    void sameAsAutoGenerationKeepsLiteralsAndMergesDigitRuns() {
        String template = "EMP-{yyyy}{MM}-{seq:4}";
        assertThat(ManualCodeRules.format(LABEL, "EMP-202610-0042", sameAsAuto(), template, null)).isNull();
        assertThat(ManualCodeRules.format(LABEL, "EMP-2026-0042", sameAsAuto(), template, null))
                .isEqualTo("Employee Code must be EMP- + 6 digits + - + 4 digits.");
    }

    @Test
    void lengthIsCheckedBeforeCharacters() {
        ManualEntryRule rule = length(6, 10, AllowedCharacters.LETTERS_NUMBERS_DASH_UNDERSCORE);
        assertThat(ManualCodeRules.format(LABEL, "ZK12345", rule, null, null)).isNull();
        assertThat(ManualCodeRules.format(LABEL, "ZK1234567-SG01", rule, null, null))
                .isEqualTo("Employee Code must be 6–10 characters.");
        assertThat(ManualCodeRules.format(LABEL, "ZK#12345", rule, null, null))
                .isEqualTo("Employee Code can only contain letters, numbers, - and _.");
    }

    @Test
    void lengthWordingFollowsWhichBoundIsSet() {
        assertThat(ManualCodeRules.format(LABEL, "ZK1", length(6, null, null), null, null))
                .isEqualTo("Employee Code must be at least 6 characters.");
        assertThat(ManualCodeRules.format(LABEL, "ZK123456789", length(null, 10, null), null, null))
                .isEqualTo("Employee Code cannot exceed 10 characters.");
        assertThat(ManualCodeRules.format(LABEL, "ZK1", length(null, null, AllowedCharacters.NUMBERS), null, null))
                .isEqualTo("Employee Code can only contain numbers.");
        assertThat(ManualCodeRules.format(LABEL, "ZK-1", length(null, null, AllowedCharacters.LETTERS_NUMBERS), null, null))
                .isEqualTo("Employee Code can only contain letters and numbers.");
    }

    @Test
    void patternShowsItsOwnMessage() {
        ManualEntryRule rule = new ManualEntryRule(ManualEntryFormat.PATTERN, null, null, null,
                "^[A-Z]{2,3}[0-9]{4,7}$", "Use 2–3 capital letters followed by 4–7 digits.");
        assertThat(ManualCodeRules.format(LABEL, "ZK12345", rule, null, null)).isNull();
        assertThat(ManualCodeRules.format(LABEL, "zk12345", rule, null, null))
                .isEqualTo("Use 2–3 capital letters followed by 4–7 digits.");
    }

    @Test
    void aRuleIsCheckedOnlyForItsActiveFormat() {
        assertThat(ManualCodeRules.ruleErrors(length(null, null, null)).values())
                .containsExactly("Set at least one of Min Length, Max Length or Allowed Characters.");
        assertThat(ManualCodeRules.ruleErrors(length(10, 6, null)).values())
                .containsExactly("Min Length cannot be greater than Max Length.");
        assertThat(ManualCodeRules.ruleErrors(length(0, 33, null))).containsKeys("minLength", "maxLength");
        assertThat(ManualCodeRules.ruleErrors(new ManualEntryRule(ManualEntryFormat.PATTERN, null, null, null, "[A-Z", null)))
                .containsEntry("pattern", "Pattern is not a valid regular expression.")
                .containsEntry("errorMessage", "Error Message is required.");
        assertThat(ManualCodeRules.ruleErrors(new ManualEntryRule(ManualEntryFormat.PATTERN, null, null, null, "^[A-Z ]+$", "Letters only")))
                .containsExactly(java.util.Map.entry("pattern", "Pattern cannot match spaces: codes never contain them."));
        assertThat(ManualCodeRules.ruleErrors(new ManualEntryRule(ManualEntryFormat.PATTERN, null, null, null, "^[A-Z]+$", "Letters only")))
                .isEmpty();
        // Settings of an inactive format are kept, not judged.
        assertThat(ManualCodeRules.ruleErrors(new ManualEntryRule(ManualEntryFormat.ANY, 10, 6, null, "[A-Z", null))).isEmpty();
    }

    @Test
    void aPatternThatSpellsOutASpaceIsRefused() {
        for (String pattern : List.of("^[A-Z ]+$", "^[A-Z\tB]+$", "^[A-Z\\s]+$", "\\h", "\\v", "\\t", "A\\x20B",
                "A\\x{20}B", "A\\u0020B", "A\\040B", "\\p{Space}", "\\p{Blank}", "\\p{javaWhitespace}",
                "\\p{IsWhite_Space}", "\\p{Zs}", "\\Q \\E", "\\\\\\s")) {
            assertThat(ManualCodeRules.spellsOutSpace(pattern)).as(pattern).isTrue();
        }
    }

    @Test
    void aPatternThatOnlyHappensToAllowOneIsNot() {
        for (String pattern : List.of("^[A-Z]{2,3}[0-9]{4,7}$", ".+", "\\W", "\\D", "[^A-Z]", "\\S",
                "\\P{Space}", "\\\\s", "A\\x41B", "A\\u0041B", "\\d{3}")) {
            assertThat(ManualCodeRules.spellsOutSpace(pattern)).as(pattern).isFalse();
        }
    }
}
