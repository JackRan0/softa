package io.softa.starter.tenant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Attributes of the platform tenant, read once: when {@code TenantInfoService.ensurePlatformTenant} has to
 * create it.
 *
 * <p>Country and timezone have no default, on purpose. Every tenant must carry both, and which country the
 * operator's own workspace sits in is the application's decision — a framework default would be a guess
 * that nobody notices is wrong. Left unset, creating the platform tenant fails and names the property.
 */
@Data
@Component
@ConfigurationProperties(prefix = "softa.tenant.platform")
public class PlatformTenantProperties {

    private String name = "Platform";

    private String code = "platform";

    /** ISO 3166-1 alpha-2 country code, e.g. {@code SG}. Required to create the platform tenant. */
    private String defaultCountry;

    /** A {@code Timezone} code, e.g. {@code UTC+08:00}. Required to create the platform tenant. */
    private String defaultTimezone;

    /** A {@code Language} code, e.g. {@code en-US}. Optional. */
    private String defaultLanguage;
}
