package io.softa.starter.user;

import io.softa.framework.base.config.SystemConfig;

/**
 * Test-side factory for {@link SystemConfig}. The services under test only ever read the site
 * origin from it, so a bare instance with {@code siteUrl} set is all a unit test needs — no
 * Spring context, and nothing touches the {@code SystemConfig.env} singleton.
 */
public final class TestSystemConfigs {

    private TestSystemConfigs() {
    }

    public static SystemConfig siteAt(String siteUrl) {
        SystemConfig config = new SystemConfig();
        config.setSiteUrl(siteUrl);
        return config;
    }
}
