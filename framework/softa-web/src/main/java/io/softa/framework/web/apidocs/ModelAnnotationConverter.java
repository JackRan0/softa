package io.softa.framework.web.apidocs;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Iterator;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;

import io.softa.framework.orm.annotation.Model;

/**
 * Bridges {@code @Model.label / description} onto the generated OpenAPI
 * schema at the class level. Field-level metadata is handled by
 * {@link FieldAnnotationCustomizer}.
 *
 * <p>Existing {@code title} / {@code description} (e.g. from a user-written
 * {@code @Schema}) is preserved — this converter only fills blanks.
 */
public class ModelAnnotationConverter implements ModelConverter {

    @Override
    @SuppressWarnings("rawtypes")
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema<?> schema = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (schema == null) return null;
        if (schema.get$ref() != null) return schema;

        Class<?> rawClass = extractRawClass(type.getType());
        if (rawClass == null) return schema;

        Model model = rawClass.getAnnotation(Model.class);
        if (model == null) return schema;

        if (isBlank(schema.getTitle()) && !model.label().isEmpty()) {
            schema.setTitle(model.label());
        }
        if (isBlank(schema.getDescription()) && !model.description().isEmpty()) {
            schema.setDescription(model.description());
        }
        return schema;
    }

    private static Class<?> extractRawClass(Type t) {
        return switch (t) {
            case Class<?> c -> c;
            case JavaType jt -> jt.getRawClass();
            case ParameterizedType pt when pt.getRawType() instanceof Class<?> c -> c;
            case null, default -> null;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
