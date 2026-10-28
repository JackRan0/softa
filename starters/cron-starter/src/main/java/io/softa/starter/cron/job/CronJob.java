package io.softa.starter.cron.job;

import io.softa.starter.cron.enums.TenantJobMode;

/**
 * A job the cron scheduler runs without a {@code SysCron} row: a {@link CronJobProvider} supplies it from the
 * data it schedules, so the schedule has a single source. The leader schedules it like a row and publishes a
 * {@code CronTaskMessage} named after it on every execution, with no {@code cronId}.
 * <p>
 * A job is a value: one whose expression or tenant changes is another job, and the scheduler replaces the old
 * one with it.
 *
 * @param name           cron name of its trigger messages, what consumers dispatch on; unique among the provided jobs
 * @param cronExpression Quartz cron expression, as on a {@code SysCron} row
 * @param tenantJobMode  tenant dispatch, as on a {@code SysCron} row; null for a single trigger
 * @param tenantId       with no dispatch mode, the tenant whose context the trigger carries; null for none
 */
public record CronJob(String name, String cronExpression, TenantJobMode tenantJobMode, Long tenantId) {

    public CronJob {
        if (tenantJobMode != null && tenantId != null) {
            throw new IllegalArgumentException("Cron job " + name + " names a tenant and a tenant dispatch mode");
        }
    }

    /**
     * A job of one tenant: each execution is a single trigger in that tenant's context, sent only while the tenant
     * is active.
     */
    public static CronJob ofTenant(String name, String cronExpression, Long tenantId) {
        return new CronJob(name, cronExpression, null, tenantId);
    }

    /** A job of every tenant: each execution is one trigger per active tenant, as on a {@code PerTenant} row. */
    public static CronJob perTenant(String name, String cronExpression) {
        return new CronJob(name, cronExpression, TenantJobMode.PER_TENANT, null);
    }

    /** A job of no tenant, for a deployment without tenants: each execution is a single trigger with no tenant. */
    public static CronJob of(String name, String cronExpression) {
        return new CronJob(name, cronExpression, null, null);
    }
}
