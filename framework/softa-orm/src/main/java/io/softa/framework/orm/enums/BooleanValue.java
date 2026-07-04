package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;

/**
 * The {@code "true"} / {@code "false"} option vocabulary for rendering boolean
 * fields as a two-item option set.
 *
 * <p>Code-fixed: {@code BooleanExpandProcessor} keys option-item lookups on
 * {@code Boolean.toString(value)} — i.e. the literal strings {@code "true"} /
 * {@code "false"} — and the Excel import {@code BooleanHandler} expects the same
 * two codes. The engine therefore depends on these exact item codes existing, so
 * the enum is the source of truth.
 *
 * <p>Lives in {@code softa-orm} (next to {@link FieldType} and its primary
 * consumer {@code BooleanExpandProcessor}), not flow-starter — boolean rendering
 * is a framework concern, not a flow one. Materializing it from code also repairs
 * the legacy seed, which shipped only {@code false -> No} (the {@code true} item
 * the engine needs was missing).
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Boolean Option")
public enum BooleanValue {

    @OptionItem(label = "Yes")
    TRUE("true"),

    @OptionItem(label = "No")
    FALSE("false"),
    ;

    @JsonValue
    private final String code;
}
