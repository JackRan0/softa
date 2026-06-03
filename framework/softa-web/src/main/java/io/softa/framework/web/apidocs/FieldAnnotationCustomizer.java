package io.softa.framework.web.apidocs;

import java.lang.annotation.Annotation;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.PropertyCustomizer;

import io.softa.framework.orm.annotation.Field;

/**
 * Bridges {@code @Field.label / description / readonly / length} onto the
 * generated OpenAPI property schema. Class-level {@code @Model} metadata is
 * handled by {@link ModelAnnotationConverter}.
 *
 * <p>Existing {@code title} / {@code description} (e.g. from a user-written
 * {@code @Schema}) is preserved — this customizer only fills blanks.
 */
public class FieldAnnotationCustomizer implements PropertyCustomizer {

    @Override
    @SuppressWarnings("rawtypes")
    public Schema customize(Schema property, AnnotatedType type) {
        if (property == null || type == null || type.getCtxAnnotations() == null) {
            return property;
        }
        for (Annotation a : type.getCtxAnnotations()) {
            if (a instanceof Field f) {
                apply(property, f);
            }
        }
        return property;
    }

    @SuppressWarnings("rawtypes")
    private static void apply(Schema property, Field f) {
        if (isBlank(property.getTitle()) && !f.label().isEmpty()) {
            property.setTitle(f.label());
        }
        if (isBlank(property.getDescription()) && !f.description().isEmpty()) {
            property.setDescription(f.description());
        }
        if (f.readonly()) {
            property.setReadOnly(true);
        }
        if (f.length() > 0 && "string".equals(property.getType()) && property.getMaxLength() == null) {
            property.setMaxLength(f.length());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
