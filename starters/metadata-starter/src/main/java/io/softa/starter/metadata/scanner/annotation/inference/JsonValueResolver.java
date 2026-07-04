package io.softa.starter.metadata.scanner.annotation.inference;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Resolves the {@code itemCode} for an {@link Enum} constant by reading the
 * value of the field (or method) annotated with {@link JsonValue} on the enum
 * class.
 *
 * <p>If no {@code @JsonValue} is present, falls back to
 * {@code enumConstant.name()}.
 *
 * <p>itemCode is derived from the enum's {@code @JsonValue} (not from the
 * Java constant name). Aligns with Jackson serialization, so itemCode in
 * {@code sys_option_item} matches the value that crosses HTTP / JSON
 * boundaries.
 *
 * <p>Pure POJO — no Spring dependency.
 */
public final class JsonValueResolver {

    private JsonValueResolver() {}

    /**
     * Resolve the itemCode for the given enum constant.
     *
     * @return the @JsonValue field/method's value as String, or
     *         {@code enumConstant.name()} if no @JsonValue is present
     */
    public static String resolveItemCode(Enum<?> enumConstant) {
        if (enumConstant == null) {
            throw new IllegalArgumentException("enumConstant must not be null");
        }
        Class<?> enumClass = enumConstant.getDeclaringClass();

        // Try @JsonValue on fields (typical: @JsonValue private final String code;)
        for (Field field : enumClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(JsonValue.class)) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(enumConstant);
                    return value != null ? value.toString() : enumConstant.name();
                } catch (IllegalAccessException ignored) {
                    // fall through to method scan
                }
            }
        }

        // Try @JsonValue on methods (alternative: @JsonValue public String getCode())
        for (Method method : enumClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(JsonValue.class)
                    && method.getParameterCount() == 0) {
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(enumConstant);
                    return value != null ? value.toString() : enumConstant.name();
                } catch (ReflectiveOperationException ignored) {
                    // fall through to fallback
                }
            }
        }

        // Fallback: enum constant name
        return enumConstant.name();
    }
}
