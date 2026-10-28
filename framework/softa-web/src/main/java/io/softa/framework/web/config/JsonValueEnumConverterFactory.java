package io.softa.framework.web.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

import io.softa.framework.base.utils.JsonUtils;

/**
 * Binds request parameters (query, path, header) to enums by the value JSON uses for them, so
 * {@code ?scenario=Approval} reaches {@code FlowScenario.APPROVAL} just as {@code "scenario": "Approval"} does in a
 * request body.
 * <p>
 * That value is the one Jackson writes: the {@code @JsonValue} field when the enum declares one, the constant name
 * otherwise. Only that value is accepted, matched exactly. Spring's built-in factory matches the constant name
 * instead, a spelling that no response, option item or request body ever shows for an enum with {@code @JsonValue}.
 * <p>
 * When this conversion fails, Spring MVC's {@code TypeConverterDelegate} still looks the text up as a constant name,
 * so {@code ?scenario=PROCESS} keeps binding. That is Spring's fallback, not part of the API.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class JsonValueEnumConverterFactory implements ConverterFactory<String, Enum> {

    @Override
    public <T extends Enum> Converter<String, T> getConverter(Class<T> targetType) {
        // A constant with a body is an anonymous subclass of its enum.
        Class<?> enumType = targetType.isEnum() ? targetType : targetType.getSuperclass();
        return source -> {
            String value = source.trim();
            if (value.isEmpty()) {
                return null;
            }
            List<String> accepted = new ArrayList<>();
            for (Object constant : enumType.getEnumConstants()) {
                String wireValue = JsonUtils.getMapper().convertValue(constant, String.class);
                if (value.equals(wireValue)) {
                    return (T) constant;
                }
                accepted.add(wireValue);
            }
            throw new IllegalArgumentException("'" + value + "' is not a " + enumType.getSimpleName()
                    + " value; accepted: " + String.join(", ", accepted));
        };
    }
}
