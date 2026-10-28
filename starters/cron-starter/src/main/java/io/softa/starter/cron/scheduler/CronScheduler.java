package io.softa.starter.cron.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.SystemUser;
import io.softa.framework.orm.annotation.SwitchUser;
import io.softa.framework.orm.compute.CronUtils;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.cron.entity.SysCron;
import io.softa.starter.cron.enums.TenantJobMode;
import io.softa.starter.cron.job.CronJob;
import io.softa.starter.cron.job.CronJobProvider;
import io.softa.starter.cron.message.CronTaskProducer;
import io.softa.starter.cron.message.dto.CronTaskMessage;

/**
 * Cron Scheduler
 * Due to the use of leader election, there is only one running scheduler in the entire scheduling system.
 * It runs the {@code SysCron} rows, read when this node starts scheduling, and the jobs of every {@link CronJobProvider},
 * re-read every {@link #PROVIDED_JOBS_REFRESH_SECONDS} while it schedules.
 */
@Slf4j
@Component
public class CronScheduler {

    /** How often the providers are read again, so the jobs they add or drop reach the schedule */
    static final long PROVIDED_JOBS_REFRESH_SECONDS = 30;

    @Autowired
    private SchedulerPool schedulerPool;

    @Autowired
    private JdbcService<?> jdbcService;

    @Autowired
    private CronTaskProducer cronTaskProducer;

    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

    @Autowired(required = false)
    private List<CronJobProvider> cronJobProviders = List.of();

    private final Map<Long, ScheduledFuture<?>> scheduledTasksMap = new ConcurrentHashMap<>();

    /**
     * The provided jobs on the schedule, each with the handle of its next run: null when it has none, for a job whose expression
     * cannot be parsed or has no execution ahead. A run schedules the next one only while its job is still in here.
     * Only read or written while holding this scheduler's lock.
     */
    private final Map<CronJob, ScheduledFuture<?>> providedJobs = new HashMap<>();

    /** The periodic read of the providers, while this node schedules */
    private ScheduledFuture<?> providedJobsRefresh;

    /** Whether this node schedules: from start() to stop(). Only read or written while holding this scheduler's lock. */
    private boolean scheduling;

    /**
     * Bumped by every stop(). Each run carries the generation its job was registered in,
     * so a run that was already firing when stop() cancelled its job does not schedule the next one.
     * Only read or written while holding this scheduler's lock.
     */
    private long generation;

    /**
     * Start all active cron jobs and the provided ones, replacing whatever is already registered, so calling it again never schedules a job twice.
     * A job that cannot be registered — an unparsable cron expression, most often — is logged and skipped, so the remaining jobs still get scheduled.
     * Providers that cannot be read leave their jobs to the next refresh.
     */
    @SwitchUser(SystemUser.CRON_USER)
    public void start() {
        List<SysCron> sysCronList = jdbcService.selectMetaEntityList(SysCron.class, null);
        List<CronJob> providedJobList = listProvidedJobs();
        synchronized (this) {
            stop();
            for (SysCron sysCron : sysCronList) {
                if (Boolean.FALSE.equals(sysCron.getActive())) {
                    continue;
                }
                // start() runs once per leader election, so an exception escaping this loop would leave every remaining job unscheduled and cost this node its leadership.
                try {
                    registerCron(sysCron);
                } catch (Exception e) {
                    log.error("Failed to register cron job {}({}), skipped: {}", sysCron.getName(), sysCron.getCronExpression(), e.getMessage(), e);
                }
            }
            if (providedJobList != null) {
                applyProvidedJobs(providedJobList);
            }
            scheduling = true;
            if (!cronJobProviders.isEmpty()) {
                providedJobsRefresh = schedulerPool.getScheduler().scheduleWithFixedDelay(this::refreshProvidedJobs,
                        PROVIDED_JOBS_REFRESH_SECONDS, PROVIDED_JOBS_REFRESH_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Register a cron job.
     * An active cron job is scheduled in limited execution times or repeatedly.
     *
     * @param sysCron Cron job object
     */
    public synchronized void registerCron(SysCron sysCron) {
        Cron cron = CronUtils.getCron(sysCron.getName(), sysCron.getCronExpression());
        Executions executions = new Executions(ExecutionTime.forCron(cron));
        if (!Boolean.TRUE.equals(sysCron.getLimitExecution())) {
            // Cron with unlimited execution times
            scheduleRepeatedTask(sysCron, executions, generation);
        } else if (sysCron.getRemainingCount() > 0) {
            // Cron with limited execution times, and there are remaining execution times.
            AtomicInteger remainingCount = new AtomicInteger(sysCron.getRemainingCount());
            scheduleLimitedTask(sysCron, executions, remainingCount, generation);
        }
    }

    /**
     * Schedule a task repeatedly.
     * Send an asynchronous execution message in the task, and then reschedule.
     *
     * @param sysCron Cron job object
     * @param executions Execution times of the cron expression
     * @param generation Generation the job was registered in
     */
    private void scheduleRepeatedTask(SysCron sysCron, Executions executions, long generation) {
        Runnable task = () -> {
            sendToMessageQueue(sysCron);
            // Reschedule
            scheduleRepeatedTask(sysCron, executions, generation);
        };
        scheduleTask(sysCron, executions, generation, task);
    }

    /**
     * Schedule a task with limited execution times.
     * Send an asynchronous execution message in the task, and then reschedule.
     *
     * @param sysCron Cron job object
     * @param executions Execution times of the cron expression
     * @param remainingCount Remaining execution times
     * @param generation Generation the job was registered in
     */
    private void scheduleLimitedTask(SysCron sysCron, Executions executions, AtomicInteger remainingCount, long generation) {
        Runnable task = () -> {
            // Check the remaining execution times
            if (remainingCount.get() <= 0) {
                return;
            }
            sendToMessageQueue(sysCron);
            remainingCount.decrementAndGet();
            // Reschedule
            scheduleLimitedTask(sysCron, executions, remainingCount, generation);
        };
        scheduleTask(sysCron, executions, generation, task);
    }

    /**
     * Schedule a task once based on the cron expression, at its next execution time.
     *
     * @param sysCron Cron job object
     * @param executions Execution times of the cron expression
     * @param generation Generation the job was registered in
     * @param task Scheduled task
     */
    private synchronized void scheduleTask(SysCron sysCron, Executions executions, long generation, Runnable task) {
        // The job was stopped while this run was firing, so the run must not schedule the next one
        if (generation != this.generation) {
            return;
        }
        ScheduledFuture<?> scheduledTask = scheduleNextRun(executions, task);
        if (scheduledTask != null) {
            scheduledTasksMap.put(sysCron.getId(), scheduledTask);
        }
    }

    /**
     * Read the providers again and bring the provided jobs on the schedule in line with them.
     * Does nothing unless this node schedules; a provider that cannot be read leaves the schedule as it is until the next refresh.
     * Never throws: scheduleWithFixedDelay silently suppresses every later refresh once one throws.
     */
    void refreshProvidedJobs() {
        try {
            long registeredGeneration;
            synchronized (this) {
                if (!scheduling) {
                    return;
                }
                registeredGeneration = generation;
            }
            // Read outside the lock, so that a slow provider does not hold up the runs that reschedule meanwhile
            List<CronJob> providedJobList = listProvidedJobs();
            synchronized (this) {
                // Stopped, or started anew, while the providers were read
                if (providedJobList != null && scheduling && registeredGeneration == generation) {
                    applyProvidedJobs(providedJobList);
                }
            }
        } catch (Exception e) {
            log.error("Failed to refresh the provided cron jobs, retrying in {}s: {}", PROVIDED_JOBS_REFRESH_SECONDS, e.getMessage(), e);
        }
    }

    /**
     * The jobs of every provider, or null when one of them cannot be read: the schedule then stays as it is.
     * Never throws, as start() relies on.
     */
    private List<CronJob> listProvidedJobs() {
        List<CronJob> jobs = new ArrayList<>();
        for (CronJobProvider provider : cronJobProviders) {
            try {
                jobs.addAll(provider.listCronJobs());
            } catch (Exception e) {
                log.error("Failed to read the cron jobs of {}, the schedule is left as it is: {}",
                        provider.getClass().getSimpleName(), e.getMessage(), e);
                return null;
            }
        }
        return jobs;
    }

    /**
     * Make the provided jobs on the schedule the given ones: a job no longer provided is cancelled, a new one is registered,
     * and one still provided keeps its next run.
     * Called holding this scheduler's lock.
     */
    private void applyProvidedJobs(List<CronJob> jobs) {
        Set<CronJob> provided = new LinkedHashSet<>(jobs);
        Iterator<Map.Entry<CronJob, ScheduledFuture<?>>> scheduledJobs = providedJobs.entrySet().iterator();
        while (scheduledJobs.hasNext()) {
            Map.Entry<CronJob, ScheduledFuture<?>> scheduledJob = scheduledJobs.next();
            if (!provided.contains(scheduledJob.getKey())) {
                // Removed before it is cancelled, so that a run of it firing right now does not schedule the next one
                scheduledJobs.remove();
                if (scheduledJob.getValue() != null) {
                    scheduledJob.getValue().cancel(true);
                }
            }
        }
        for (CronJob job : provided) {
            if (!providedJobs.containsKey(job)) {
                registerProvidedJob(job);
            }
        }
    }

    /**
     * Register a provided job. One that cannot be registered is logged and kept without a run:
     * the same job fails the same way at every refresh, and the provider replaces it once it changes.
     * Called holding this scheduler's lock.
     */
    private void registerProvidedJob(CronJob job) {
        providedJobs.put(job, null);
        try {
            Cron cron = CronUtils.getCron(job.name(), job.cronExpression());
            scheduleProvidedRun(job, new Executions(ExecutionTime.forCron(cron)), generation);
        } catch (Exception e) {
            log.error("Failed to register cron job {}({}), skipped: {}", job.name(), job.cronExpression(), e.getMessage(), e);
        }
    }

    /**
     * Schedule the next run of a provided job.
     * A run that was already firing when its job was cancelled — this node stopped scheduling, or the provider dropped the job — schedules nothing.
     * A run whose trigger cannot be sent still schedules the next one.
     *
     * @param job Provided job
     * @param executions Execution times of its cron expression
     * @param generation Generation the job was registered in
     */
    private synchronized void scheduleProvidedRun(CronJob job, Executions executions, long generation) {
        if (generation != this.generation || !providedJobs.containsKey(job)) {
            return;
        }
        providedJobs.put(job, scheduleNextRun(executions, () -> {
            try {
                sendToMessageQueue(job);
            } catch (Exception e) {
                log.error("Cron job {} failed to send its trigger: {}", job.name(), e.getMessage(), e);
            }
            scheduleProvidedRun(job, executions, generation);
        }));
    }

    /**
     * Schedule the task at the next execution time.
     *
     * @param executions Execution times of the cron expression
     * @param task Scheduled task
     * @return Handle of the scheduled task, or null when the expression has no execution ahead
     */
    private ScheduledFuture<?> scheduleNextRun(Executions executions, Runnable task) {
        Long delayMs = executions.delayToNext();
        if (delayMs == null) {
            return null;
        }
        return schedulerPool.getScheduler().schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Send the scheduled task message.
     * When tenantMode is PER_TENANT, sends one message per active tenant for distributed parallel execution.
     * When tenantMode is CROSS_TENANT, sends a single message with crossTenant=true in context.
     * Otherwise, sends a single message with the current context.
     *
     * @param sysCron Scheduled task object
     */
    public void sendToMessageQueue(SysCron sysCron) {
        dispatch(sysCron.getId(), sysCron.getName(), sysCron.getLastExecTime(), sysCron.getTenantJobMode(), null);
    }

    /**
     * Send the messages of a provided job, which carry no cron id: one per active tenant for a PER_TENANT job,
     * a single one for a job of one tenant — in that tenant's context, and only while the tenant is active — and a single one otherwise.
     *
     * @param job Provided job
     */
    void sendToMessageQueue(CronJob job) {
        dispatch(null, job.name(), null, job.tenantJobMode(), job.tenantId());
    }

    private void dispatch(Long cronId, String cronName, LocalDateTime lastExecTime, TenantJobMode tenantJobMode, Long tenantId) {
        if (TenantJobMode.PER_TENANT.equals(tenantJobMode)) {
            if (tenantInfoService == null) {
                log.error("TenantInfoService is not available for PER_TENANT cron job: {}", cronName);
                return;
            }
            List<Long> tenantIds = tenantInfoService.getActiveTenantIds();
            for (Long activeTenantId : tenantIds) {
                sendCronMessage(cronId, cronName, lastExecTime, activeTenantId, false);
            }
        } else if (TenantJobMode.CROSS_TENANT.equals(tenantJobMode)) {
            sendCronMessage(cronId, cronName, lastExecTime, null, true);
        } else if (tenantId != null && tenantInfoService != null && !tenantInfoService.isTenantActive(tenantId)) {
            log.debug("Cron job {} skipped: its tenant {} is not active", cronName, tenantId);
        } else {
            sendCronMessage(cronId, cronName, lastExecTime, tenantId, false);
        }
    }

    private void sendCronMessage(Long cronId, String cronName, LocalDateTime lastExecTime, Long tenantId, boolean crossTenant) {
        Context ctx = ContextHolder.cloneContext();
        if (tenantId != null) {
            ctx.setTenantId(tenantId);
        }
        ctx.setCrossTenant(crossTenant);
        CronTaskMessage message = new CronTaskMessage();
        message.setCronId(cronId);
        message.setCronName(cronName);
        message.setTriggerTime(LocalDateTime.now());
        message.setLastExecTime(lastExecTime);
        message.setContext(ctx);
        cronTaskProducer.sendCronTask(message);
    }

    /**
     * Cancel a scheduling task.
     * If the task is being scheduled, close it directly and clear the cache.
     *
     * @param cronId Task ID
     */
    public void cancelTask(Long cronId) {
        ScheduledFuture<?> scheduledTask = scheduledTasksMap.get(cronId);
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            scheduledTasksMap.remove(cronId);
        }
    }

    /**
     * Cancel a list of scheduling tasks.
     *
     * @param cronIds Task ID list
     */
    public void cancelTaskList(List<Long> cronIds) {
        if (CollectionUtils.isEmpty(cronIds)) {
            return;
        }
        for (Long cronId : cronIds) {
            cancelTask(cronId);
        }
    }

    /**
     * Cancel all scheduling tasks but keep the scheduler, so that a later start() can schedule them again.
     * Called when this node loses the leadership, which it may win back.
     */
    public synchronized void stop() {
        generation++;
        scheduling = false;
        if (providedJobsRefresh != null) {
            providedJobsRefresh.cancel(false);
            providedJobsRefresh = null;
        }
        // Cancel all tasks
        for (Long cronId : scheduledTasksMap.keySet()) {
            cancelTask(cronId);
        }
        for (ScheduledFuture<?> scheduledJob : providedJobs.values()) {
            if (scheduledJob != null) {
                scheduledJob.cancel(true);
            }
        }
        providedJobs.clear();
    }

    /**
     * Cancel all scheduling tasks and shutdown the scheduler.
     * Only for application shutdown: a scheduler shut down here rejects every later task, so losing the leadership calls stop() instead.
     */
    @PreDestroy
    public synchronized void shutdown() {
        stop();
        // Shutdown the scheduler
        schedulerPool.getScheduler().shutdown();
    }

    /**
     * The execution times of one registered job, handed out in order: each one after the previous and after now.
     * A run can wake a little before its execution time — its delay is cut to whole milliseconds, and the wall clock may drift
     * from the scheduler's — and computing the next execution from now alone would then hand it that same execution again.
     * Only used holding the scheduler's lock.
     */
    static final class Executions {

        private final ExecutionTime executionTime;
        private final Clock clock;
        private ZonedDateTime previous;

        Executions(ExecutionTime executionTime) {
            this(executionTime, Clock.systemDefaultZone());
        }

        Executions(ExecutionTime executionTime, Clock clock) {
            this.executionTime = executionTime;
            this.clock = clock;
        }

        /**
         * Milliseconds from now to the next execution, which becomes the previous one.
         *
         * @return the delay, or null when the expression has no execution ahead
         */
        Long delayToNext() {
            ZonedDateTime now = ZonedDateTime.now(clock);
            ZonedDateTime after = previous != null && previous.isAfter(now) ? previous : now;
            ZonedDateTime next = executionTime.nextExecution(after).orElse(null);
            if (next == null) {
                return null;
            }
            previous = next;
            return Duration.between(now, next).toMillis();
        }
    }
}
