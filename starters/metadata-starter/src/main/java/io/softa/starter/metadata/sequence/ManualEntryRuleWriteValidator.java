package io.softa.starter.metadata.sequence;

import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;

/**
 * A numbering rule's Manual Entry block is checked when the rule is saved: Length &amp; Characters
 * needs at least one setting and a sane range, Pattern needs a valid expression and a message. Only the
 * active format is judged — the settings of the others are kept, unchecked, for when the user switches
 * back.
 */
@Component
@Order(150)
public class ManualEntryRuleWriteValidator implements ModelWriteValidator {

    static final String FIELD = "manualEntry";

    @Override
    public boolean supports(String modelName) {
        return "SysSequence".equals(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        check(ctx);
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (ctx.patch().containsKey(FIELD)) {
            check(ctx);
        }
    }

    private static void check(WriteContext ctx) {
        ManualEntryRule rule = toRule(ctx.row().get(FIELD));
        Map<String, String> errors = ManualCodeRules.ruleErrors(rule);
        if (!errors.isEmpty()) {
            // One column holds the whole block, so the messages land on it; the first is the one shown.
            ctx.reject(FIELD, errors.values().iterator().next());
        }
    }

    /** The raw value as the request sent it: an object, a JSON string, or already the record. */
    static ManualEntryRule toRule(Object raw) {
        return switch (raw) {
            case null -> null;
            case ManualEntryRule rule -> rule;
            case String json when json.isBlank() -> null;
            case String json -> JsonUtils.stringToObject(json, ManualEntryRule.class);
            default -> JsonUtils.getMapper().convertValue(raw, ManualEntryRule.class);
        };
    }
}
