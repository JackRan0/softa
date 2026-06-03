package io.softa.framework.web.apidocs;

import io.swagger.v3.core.converter.ModelConverter;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers SpringDoc / Swagger customizers that surface Softa {@code @Model}
 * and {@code @Field} metadata in the generated OpenAPI document.
 *
 * <p>Picked up by {@code WebAutoConfiguration}'s component scan; gated on
 * {@code ModelConverter} / {@code PropertyCustomizer} being on the classpath so
 * apps that exclude SpringDoc don't fail.
 */
@Configuration
@ConditionalOnClass({ ModelConverter.class, PropertyCustomizer.class })
public class ApiDocsConfig {

    @Bean
    public ModelConverter modelAnnotationConverter() {
        return new ModelAnnotationConverter();
    }

    @Bean
    public PropertyCustomizer fieldAnnotationCustomizer() {
        return new FieldAnnotationCustomizer();
    }
}
