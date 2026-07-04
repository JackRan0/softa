package io.softa.framework.orm.enums;

/**
 * Data-ownership tier tag — which channel controls a row and how it evolves.
 *
 * <p><b>Currently unused.</b> The metadata catalog ({@code sys_*}) previously
 * carried this tag to partition code-authored rows from Studio no-code rows;
 * that partition has been retired — the annotation and Studio lanes now
 * reconcile the same rows purely by business key (modelName / fieldName /
 * optionSetCode / itemCode + {@code renamedFrom}), so no {@code sys_*} column
 * records ownership.
 *
 * <p>The enum is retained for future business-data scenarios that ship
 * platform-defaults a tenant may customize (e.g. system roles, workflow
 * templates, email templates, default categories).
 */
public enum Ownership {

    /** Platform-version-controlled; materialized from code annotations. */
    PLATFORM_MAINTAINED,

    /** Platform no-code definition, version-managed by Studio. */
    STUDIO_MANAGED,

    /** Platform-provided default that tenants may override. */
    PLATFORM_DEFAULT,

    /** Tenant-owned data. */
    TENANT
}
