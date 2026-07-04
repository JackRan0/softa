package io.softa.starter.metadata.scanner.annotation.inference;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Small reflection helpers for resolving generic Java type shapes, shared by the
 * annotation parser (FieldType inference) and the drift checker's relation-shape
 * lint so the {@code List<X>} element-type resolution lives in exactly one place.
 *
 * <p>Pure POJO — no Spring dependency.
 */
public final class ReflectionTypes {

    private ReflectionTypes() {}

    /**
     * The element type {@code X} of a {@code List<X>} field, or {@code null} if
     * the field is not a {@code List} or the element type is not a resolvable
     * {@code Class} (e.g. a wildcard or type variable).
     */
    public static Class<?> listElementType(Field field) {
        if (!List.class.isAssignableFrom(field.getType())) {
            return null;
        }
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType pt)) {
            return null;
        }
        Type[] args = pt.getActualTypeArguments();
        if (args.length != 1) {
            return null;
        }
        if (args[0] instanceof Class<?> c) {
            return c;
        }
        return null;
    }
}
