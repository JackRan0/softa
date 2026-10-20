package io.softa.starter.metadata.sequence;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.validation.WriteContext;

import static org.assertj.core.api.Assertions.assertThat;

/** A numbering rule's Manual Entry block: stored as JSON with readable codes, judged on save. */
class ManualEntryRuleWriteValidatorTest {

    private final ManualEntryRuleWriteValidator validator = new ManualEntryRuleWriteValidator();

    @Test
    void readsTheBlockAsTheFormSendsIt_andAsItIsStored() {
        Map<String, Object> sent = Map.of("format", "LengthAndCharacters", "minLength", 6,
                "allowedCharacters", "LettersNumbers");
        ManualEntryRule rule = ManualEntryRuleWriteValidator.toRule(sent);
        assertThat(rule).isEqualTo(new ManualEntryRule(ManualEntryFormat.LENGTH_AND_CHARACTERS, 6, null,
                AllowedCharacters.LETTERS_NUMBERS, null, null));

        String json = JsonUtils.objectToString(rule);
        assertThat(json).contains("\"format\":\"LengthAndCharacters\"").doesNotContain("maxLength");
        assertThat(ManualEntryRuleWriteValidator.toRule(json)).isEqualTo(rule);
    }

    @Test
    void aBadBlockIsRejectedOnSave() {
        Map<String, Object> row = new HashMap<>();
        row.put("manualEntry", Map.of("format", "LengthAndCharacters"));
        WriteContext ctx = WriteContext.of("SysSequence", AccessType.CREATE, row, null);
        validator.validateCreate(ctx);
        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void anUntouchedBlockIsNotJudgedOnUpdate() {
        Map<String, Object> patch = new HashMap<>(Map.of("id", 1L, "template", "{seq:7}"));
        Map<String, Object> merged = new HashMap<>(patch);
        merged.put("manualEntry", Map.of("format", "Pattern"));
        WriteContext ctx = WriteContext.of("SysSequence", AccessType.UPDATE, merged, patch, merged);
        validator.validateUpdate(ctx);
        assertThat(ctx.hasErrors()).isFalse();
    }
}
