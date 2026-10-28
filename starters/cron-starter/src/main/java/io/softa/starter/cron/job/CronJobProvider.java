package io.softa.starter.cron.job;

import java.util.List;

/**
 * Supplies cron jobs whose schedule lives with the data they run for rather than in {@code SysCron} rows, e.g. the
 * flows a cron trigger starts. Register an implementation as a Spring bean.
 * <p>
 * The leader asks every provider when it starts scheduling and again every
 * {@code CronScheduler.PROVIDED_JOBS_REFRESH_SECONDS}: a job a provider adds is scheduled, one it no longer returns
 * is cancelled, and one it keeps returning keeps its next run. The call runs on a scheduler thread outside any
 * request, so a provider sets up the context its own reads need.
 */
public interface CronJobProvider {

    /**
     * Every job to schedule now. Throwing leaves the schedule as it is until the next refresh.
     *
     * @return the jobs; an empty list cancels every job of this provider
     */
    List<CronJob> listCronJobs();
}
