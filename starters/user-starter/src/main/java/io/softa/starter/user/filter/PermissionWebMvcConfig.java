package io.softa.starter.user.filter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import lombok.RequiredArgsConstructor;

/**
 * Registers {@link PermissionInterceptor} with Spring MVC. Without this
 * config the interceptor is a {@code @Component} bean that nothing ever
 * calls — Spring MVC requires an explicit
 * {@code WebMvcConfigurer.addInterceptors} hook to wire a
 * {@link org.springframework.web.servlet.HandlerInterceptor} into the
 * request lifecycle. (This was the gap that let unprivileged users hit
 * mutating endpoints like {@code /api/Role/updateOne} with a 200 — the
 * endpoint-gate interceptor existed in code but wasn't actually on the
 * request path.)
 *
 * <p>{@code addPathPatterns("/**")} — interceptor sees every request; the
 * interceptor itself short-circuits on public URI patterns (via
 * {@code permission.public-uri-patterns} yml) and on SUPER_ADMIN.
 */
@Configuration
@EnableConfigurationProperties(PermissionInterceptorProperties.class)
@RequiredArgsConstructor
public class PermissionWebMvcConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        InterceptorRegistration registration =
                registry.addInterceptor(permissionInterceptor);
        registration.addPathPatterns("/**");
        // Static / framework noise we never want to gate. The interceptor's
        // public-uri-patterns yml handles app-level public endpoints
        // (login / oauth / health), but those run before this exclusion
        // list — exclude framework infrastructure here so we don't pay
        // the lookup cost on them.
        registration.excludePathPatterns(
                "/error",
                "/actuator/**",
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/favicon.ico");
    }
}
