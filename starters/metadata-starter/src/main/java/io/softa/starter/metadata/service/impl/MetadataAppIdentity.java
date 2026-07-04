package io.softa.starter.metadata.service.impl;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.utils.Assert;

/**
 * This runtime's configured app identity ({@code system.app-code}) — the single source both the metadata
 * export ({@link MetadataServiceImpl}) and apply ({@link MetadataApplyServiceImpl}) lanes scope their
 * {@code sys_*} rows by. The identity is stamped server-side and never trusted from the wire.
 */
final class MetadataAppIdentity {

    /** The {@code appCode} identity column carried by every app-scoped {@code sys_*} row. */
    static final String APP_CODE_FIELD = "appCode";

    private MetadataAppIdentity() {
    }

    /** This runtime's configured {@code system.app-code}; fails fast when the identity is unset. */
    static String configured() {
        Assert.notNull(SystemConfig.env, "System configuration has not been initialized.");
        String appCode = SystemConfig.env.getAppCode();
        Assert.notBlank(appCode, "system.app-code is not configured; metadata APIs require an app identity.");
        return appCode;
    }
}
