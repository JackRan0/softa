package io.softa.starter.metadata.scanner.annotation.inference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FieldType;

/**
 * Maps Java type → {@link FieldType}.
 *
 * <p>Three tiers:
 * <ol>
 *   <li><b>Exact</b>: {@code Integer} → INTEGER, {@code BigDecimal} → BIG_DECIMAL,
 *       {@code LocalDate} → DATE, etc.</li>
 *   <li><b>Default-with-constrained-override</b>: {@code String} → STRING; overriding to
 *       OPTION via {@code @Field(fieldType = OPTION)} <b>requires</b> the Java type
 *       to be an enum—{@code optionSetCode} is always derived from the enum class
 *       name, never hand-specified. {@code Long} → LONG; overriding to FILE /
 *       MANY_TO_ONE / ONE_TO_ONE requires {@code @Field(fieldType=..., relatedModel=...)}.</li>
 *   <li><b>Must-specify</b>: cannot infer (raises). E.g. {@code byte[]},
 *       {@code Map&lt;?,?&gt;}, ambiguous {@code List&lt;Long&gt;}.</li>
 * </ol>
 *
 * <p>Pure POJO — no Spring dependency.
 */
public final class TypeInference {

    private TypeInference() {}

    /**
     * Outcome of {@link #infer(Class, Class)}.
     *
     * @param fieldType        inferred {@link FieldType}
     * @param relatedModel     inferred related model name (empty if not applicable)
     * @param optionSetCode    inferred option set code (empty if not applicable)
     */
    public record FieldTypeResolution(
            FieldType fieldType,
            String relatedModel,
            String optionSetCode
    ) {
        public static FieldTypeResolution of(FieldType type) {
            return new FieldTypeResolution(type, "", "");
        }

        public static FieldTypeResolution related(FieldType type, String relatedModel) {
            return new FieldTypeResolution(type, relatedModel, "");
        }

        public static FieldTypeResolution option(FieldType type, String optionSetCode) {
            return new FieldTypeResolution(type, "", optionSetCode);
        }
    }

    /**
     * Infer the FieldType for a Java type. Throws if cannot be inferred—caller
     * should require explicit {@code @Field(fieldType=...)}.
     *
     * @param rawType     the field's Java type (e.g. {@code String.class},
     *                    {@code List.class})
     * @param elementType for parameterized types (e.g. {@code List<X>.class},
     *                    pass {@code X.class}); may be {@code null}
     * @throws IllegalStateException if the type cannot be inferred
     */
    public static FieldTypeResolution infer(Class<?> rawType, Class<?> elementType) {
        // Tier 1: exact mappings (1:1, no override needed)
        FieldType exact = exactMap(rawType);
        if (exact != null) {
            return FieldTypeResolution.of(exact);
        }

        // Tier 2a: String default → STRING (override to OPTION explicit)
        if (rawType == String.class) {
            return FieldTypeResolution.of(FieldType.STRING);
        }

        // Tier 2b: Long default → LONG (override to FILE / *_TO_ONE explicit)
        if (rawType == Long.class || rawType == long.class) {
            return FieldTypeResolution.of(FieldType.LONG);
        }

        // Tier 1 cont: any Enum subclass → OPTION + derive optionSetCode
        if (rawType.isEnum()) {
            return FieldTypeResolution.option(FieldType.OPTION, rawType.getSimpleName());
        }

        // Tier 1 cont: POJO with @Model → MANY_TO_ONE + derive relatedModel
        if (rawType.isAnnotationPresent(Model.class)) {
            return FieldTypeResolution.related(FieldType.MANY_TO_ONE, rawType.getSimpleName());
        }

        // Tier 1 cont: List<X> with X inferable
        if (List.class.isAssignableFrom(rawType)) {
            if (elementType == null) {
                throw new IllegalStateException(
                        "Cannot infer FieldType for raw List (no element type); "
                                + "specify explicit @Field(fieldType=...)");
            }
            if (elementType == String.class) {
                return FieldTypeResolution.of(FieldType.MULTI_STRING);
            }
            if (elementType.isEnum()) {
                return FieldTypeResolution.option(FieldType.MULTI_OPTION,
                        elementType.getSimpleName());
            }
            if (elementType.isAnnotationPresent(Model.class)) {
                return FieldTypeResolution.related(FieldType.ONE_TO_MANY,
                        elementType.getSimpleName());
            }
            // Ambiguous: List<Long> → MULTI_FILE or MANY_TO_MANY? Must specify
            throw new IllegalStateException(
                    "Cannot infer FieldType for List<" + elementType.getName()
                            + ">; specify explicit @Field(fieldType=...)");
        }

        // Tier 3: must specify
        throw new IllegalStateException(
                "Cannot infer FieldType for Java type " + rawType.getName()
                        + "; specify explicit @Field(fieldType=...)");
    }

    private static FieldType exactMap(Class<?> javaType) {
        if (javaType == Integer.class || javaType == int.class) return FieldType.INTEGER;
        if (javaType == Double.class || javaType == double.class) return FieldType.DOUBLE;
        if (javaType == BigDecimal.class) return FieldType.BIG_DECIMAL;
        if (javaType == Boolean.class || javaType == boolean.class) return FieldType.BOOLEAN;
        if (javaType == LocalDate.class) return FieldType.DATE;
        if (javaType == LocalDateTime.class) return FieldType.DATE_TIME;
        if (javaType == LocalTime.class) return FieldType.TIME;
        if (javaType == JsonNode.class) return FieldType.JSON;
        if (javaType == Filters.class) return FieldType.FILTERS;
        if (javaType == Orders.class) return FieldType.ORDERS;
        return null;
    }
}
