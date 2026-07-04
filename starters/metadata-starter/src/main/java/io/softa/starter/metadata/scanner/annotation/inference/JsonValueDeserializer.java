package io.softa.starter.metadata.scanner.annotation.inference;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Inverse of {@link JsonValueResolver}: resolves an enum constant from its
 * {@link JsonValue}-annotated field/method value (fallback: match by
 * {@code Enum.name()}).
 *
 * <p>Used by {@code SysJdbcLoader} to map enum-valued columns (e.g.
 * {@code sys_field.field_type = 'String'}) back to enum constants
 * (e.g. {@code FieldType.STRING}).
 *
 * <p>Pure POJO — no Spring dependency.
 */
public final class JsonValueDeserializer {

    private JsonValueDeserializer() {}

    /**
     * Resolve the enum constant of {@code enumClass} whose {@code @JsonValue}
     * matches {@code stringValue}. If no {@code @JsonValue} is declared on the
     * enum, falls back to matching {@code enumConstant.name()}.
     *
     * @return the matching enum constant, or {@code null} if {@code stringValue}
     *         is null/blank or no match is found
     */
    public static <E extends Enum<E>> E fromString(Class<E> enumClass, String stringValue) {
        if (stringValue == null || stringValue.isEmpty()) {
            return null;
        }
        E[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            return null;
        }

        Field jsonValueField = findJsonValueField(enumClass);
        if (jsonValueField != null) {
            jsonValueField.setAccessible(true);
            for (E constant : constants) {
                try {
                    Object v = jsonValueField.get(constant);
                    if (v != null && stringValue.equals(v.toString())) {
                        return constant;
                    }
                } catch (IllegalAccessException ignored) {
                    // try methods next
                }
            }
        }

        Method jsonValueMethod = findJsonValueMethod(enumClass);
        if (jsonValueMethod != null) {
            jsonValueMethod.setAccessible(true);
            for (E constant : constants) {
                try {
                    Object v = jsonValueMethod.invoke(constant);
                    if (v != null && stringValue.equals(v.toString())) {
                        return constant;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // fall through to name match
                }
            }
        }

        // Fallback: match by Enum.name() — covers enums without @JsonValue,
        // and is also useful for legacy rows that stored enum names rather
        // than @JsonValue-strings.
        for (E constant : constants) {
            if (constant.name().equals(stringValue)) {
                return constant;
            }
        }
        return null;
    }

    private static Field findJsonValueField(Class<?> enumClass) {
        for (Field f : enumClass.getDeclaredFields()) {
            if (f.isAnnotationPresent(JsonValue.class)) {
                return f;
            }
        }
        return null;
    }

    private static Method findJsonValueMethod(Class<?> enumClass) {
        for (Method m : enumClass.getDeclaredMethods()) {
            if (m.isAnnotationPresent(JsonValue.class) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }
}
