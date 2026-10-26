package io.softa.starter.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The first platform super-administrator, created at startup by {@code PlatformAdminBootstrap}.
 *
 * <p>The password belongs in the environment, never in a committed file: reference it, e.g.
 * {@code password: ${PLATFORM_ADMIN_PASSWORD:}}. A credential written into the repository is known to
 * everyone who can read it, in every environment that never changed it — which is exactly the problem this
 * replaces. It must also satisfy the password policy; a weak one fails the boot.
 */
@Data
@Component
@ConfigurationProperties(prefix = "softa.user.platform-admin")
public class PlatformAdminProperties {

    /** Off unless a deployment opts in. */
    private boolean enabled;

    private String email;

    private String password;
}
