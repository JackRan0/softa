package io.softa.starter.metadata.scanner.checker;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import io.softa.framework.orm.annotation.Model;
import io.softa.starter.metadata.scanner.annotation.inference.ReflectionTypes;

/**
 * Surfaces declared fields that <em>look like</em> a relation but carry no
 * {@code @Field} annotation. The {@code AnnotationParser} silently drops
 * un-annotated fields from the metadata surface, so a forgotten {@code @Field}
 * on a relation field produces no error and no column — the relation just
 * vanishes. This lint WARNs on that class of mistake.
 *
 * <p>Extracted from {@code MetadataAnnotationChecker} so the checker stays
 * focused on drift detection; the {@code List<X>} element-type reflection is
 * shared with the annotation parser via {@link ReflectionTypes}.
 *
 * <p>A field is treated as relation-shaped when its Java type is a
 * {@link Model}-annotated POJO, {@code List<X>} with {@code X} a {@link Model}
 * class, or {@code List<Long>} (the common foreign-key-id collection shape).
 * Static / synthetic fields and any field already carrying {@code @Field} are
 * skipped.
 */
@Slf4j
@Component
public class RelationShapeLinter {

    /** WARN for each declared relation-shaped field on the given models that lacks {@code @Field}. */
    public void warnUnannotatedRelations(Set<Class<?>> modelClasses) {
        for (Class<?> clazz : modelClasses) {
            // Only inspect fields the model itself declares; inherited structural
            // fields (AuditableModel / TimelineModel) are annotated on the base
            // class and are not relations.
            for (Field f : clazz.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) || f.isSynthetic()) {
                    continue;
                }
                if (f.isAnnotationPresent(io.softa.framework.orm.annotation.Field.class)) {
                    continue;
                }
                if (isRelationShaped(f)) {
                    log.warn("Model {}.{} looks like a relation but has no @Field — "
                            + "annotate it (e.g. ONE_TO_MANY/MANY_TO_ONE) or confirm it "
                            + "is intentionally transient",
                            clazz.getSimpleName(), f.getName());
                }
            }
        }
    }

    private static boolean isRelationShaped(Field f) {
        Class<?> rawType = f.getType();
        if (rawType.isAnnotationPresent(Model.class)) {
            return true;
        }
        if (!List.class.isAssignableFrom(rawType)) {
            return false;
        }
        Class<?> element = ReflectionTypes.listElementType(f);
        if (element == null) {
            return false;
        }
        return element == Long.class || element.isAnnotationPresent(Model.class);
    }
}
