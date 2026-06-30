package io.softa.starter.user.filter;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Data;

/**
 * Configuration binding for {@link PermissionInterceptor}'s whitelists.
 *
 * <p>{@code @ConfigurationProperties} (not {@code @Value}) because we need
 * to bind YAML list syntax:
 * <pre>
 * permission:
 *   public-uri-patterns:
 *     - /UserAccount/login
 *     - /oauth/**
 * </pre>
 * {@code @Value} with {@code List<String>} only handles comma-separated
 * strings — YAML lists silently produce an empty list, which is the bug
 * that let unprivileged users hit {@code /me/uiContext} as "Endpoint
 * not registered" even with the bypass pattern in place.
 */
@Data
@ConfigurationProperties(prefix = "permission")
public class PermissionInterceptorProperties {

    /** Truly public endpoints — no authentication required at all
     *  (login / oauth callback / health). */
    private List<String> publicUriPatterns = List.of();

    /** Authenticated-bypass — caller must be logged in, but the endpoint
     *  is exempt from permission lookup. Self-service endpoints every
     *  authenticated user needs unconditionally: {@code /me/**},
     *  {@code /UserProfile/getMyUserInfo}, {@code /UserAccount/logout},
     *  {@code /MetaModel/**} etc. */
    private List<String> authenticatedBypassPatterns = List.of();
}
