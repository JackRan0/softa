package io.softa.framework.base.config;

import java.net.URI;
import java.util.List;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Data
@Configuration
@ConfigurationProperties(prefix = "system")
@Validated
public class SystemConfig {

    private String name;

    /**
     * Stable application code identifying which app this runtime hosts.
     * The cross-system join key: stamped server-side onto
     * metadata catalog rows ({@code sys_*.app_code}) and verified against
     * {@code DesignApp.appCode} on every signed studio call, so an envelope
     * can never land on the wrong app.
     * <p>
     * Optional at the base layer (apps without metadata-starter need no
     * identity); metadata-starter asserts it non-blank at boot.
     */
    private String appCode;

    private String defaultLanguage;

    private boolean enableAuth;

    private boolean enableChangeLog;

    private boolean enableMultiTenancy;

    private boolean enableInsertId;

    /**
     * Enable log request body in exception handler, default is true
     */
    private boolean enableLogRequestBody = true;

    // Multiple origins are allowed, cannot be set to "*"
    // For example: - http://localhost:3000/
    private List<String> allowedOrigins;

    /**
     * Public origin of the site as the browser sees it — scheme + host [+ port], no path —
     * i.e. where the frontend pages live. Every user-facing deep link (invitation / join,
     * set-password, document signing, …) is built on it, so it is REQUIRED wherever such links
     * are produced; {@link #resolveSiteUrl()} fails fast when it is missing.
     * <p>
     * Not inferred from the servlet container: the app normally sits behind a gateway, under a
     * different hostname and scheme than it uses locally, and link-building code does not always
     * run inside an HTTP request.
     */
    private String siteUrl;

    /**
     * Public origin of this application's API — only for a split deployment where the API is
     * served from a different origin than the site (e.g. {@code https://api.example.com}).
     * Optional: blank means same-origin, and {@link #resolveApiUrl()} falls back to
     * {@link #siteUrl}. Never includes the servlet context path; {@link #getApiRootUrl()}
     * appends that from {@code server.servlet.context-path}.
     */
    private String apiUrl;

    /**
     * Pulled from {@code server.servlet.context-path} so {@link #getApiRootUrl()}
     * can compose the full API root. Not a {@code system.*} property — bound via
     * {@code @Value} rather than {@code @ConfigurationProperties}.
     */
    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${debug:false}")
    private boolean debug;

    // Singleton instance
    public static SystemConfig env;

    @PostConstruct
    public void init() {
        env = this;
    }

    /**
     * {@link #siteUrl} without a trailing slash. Fails fast when unset: a link built on a missing
     * origin would be silently wrong, which is worse than a clear error at the point of use.
     */
    public String resolveSiteUrl() {
        if (!StringUtils.hasText(siteUrl)) {
            throw new IllegalStateException(
                    "system.site-url is not configured; every user-facing link is built on it");
        }
        return stripTrailingSlash(siteUrl);
    }

    /**
     * The API's public origin without a trailing slash: {@link #apiUrl} when set, otherwise
     * {@link #siteUrl} (same-origin deployment). Fails fast when neither is configured.
     */
    public String resolveApiUrl() {
        if (StringUtils.hasText(apiUrl)) {
            return stripTrailingSlash(apiUrl);
        }
        if (!StringUtils.hasText(siteUrl)) {
            throw new IllegalStateException(
                    "neither system.api-url nor system.site-url is configured; the API's public origin is unknown");
        }
        return stripTrailingSlash(siteUrl);
    }

    /**
     * Externally-visible API root — {@link #resolveApiUrl()} concatenated with the servlet
     * context path. Use this (not the raw origin) when advertising callback / webhook URLs
     * that point back at one of this app's REST endpoints.
     */
    public String getApiRootUrl() {
        String origin = resolveApiUrl();
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return origin;
        }
        String path = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        return origin + stripTrailingSlash(path);
    }

    private static String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    @AssertTrue(message = "system.app-code must match [a-z][a-z0-9-]{0,63}, e.g. demo-app")
    public boolean isAppCodeValid() {
        if (!StringUtils.hasText(appCode)) {
            return true; // optional in base; metadata-starter enforces presence
        }
        return appCode.matches("[a-z][a-z0-9-]{0,63}");
    }

    @AssertTrue(message = "system.site-url must be an absolute http(s) origin without path, e.g. https://hcm.example.com")
    public boolean isSiteUrlValid() {
        return isBlankOrOrigin(siteUrl);
    }

    @AssertTrue(message = "system.api-url must be an absolute http(s) origin without path, e.g. https://api.example.com")
    public boolean isApiUrlValid() {
        return isBlankOrOrigin(apiUrl);
    }

    /** Blank passes (both origins are optional at the base layer); a value must be scheme + host [+ port] only. */
    private static boolean isBlankOrOrigin(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String path = uri.getPath();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && StringUtils.hasText(uri.getHost())
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (path == null || path.isBlank() || "/".equals(path));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
