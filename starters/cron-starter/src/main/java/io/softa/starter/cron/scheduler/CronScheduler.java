package io.softa.starter.cron.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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
import io.softa.starter.cron.message.CronTaskProducer;
import io.softa.starter.cron.message.dto.CronTaskMessage;

/**
 * Cron Scheduler
 * Due to the use of leader election, there is only one running scheduler in the entire scheduling system.
 */
@Slf4j
@Component
public class CronScheduler {

    @Autowired
    private SchedulerPool schedulerPool;

    @Autowired
    private JdbcService<?> jdbcService;

    @Autowired
    private CronTaskProducer cronTaskProducer;

    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

    private final Map<Long, ScheduledFuture<?>> scheduledTasksMap = new ConcurrentHashMap<>();

    /**
     * Bumped by every stop(). Each run carries the generation its job was registered in,
     * so a run that was already firing when stop() cancelled its job does not schedule the next one.
     * Only read or written while holding this scheduler's lock.
     */
    private long generation;

    /**
     * Start all active cron jobs, replacing whatever is already registered, so calling it again never schedules a job twice.
     * A job that cannot be registered — an unparsable cron expression, most often — is logged and skipped, so the remaining jobs still get scheduled.
     */
    @SwitchUser(SystemUser.CRON_USER)
    public void start() {
        List<SysCron> sysCronList = jdbcService.selectMetaEntityList(SysCron.class, null);
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
        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        if (!Boolean.TRUE.equals(sysCron.getLimitExecution())) {
            // Cron with unlimited execution times
            scheduleRepeatedTask(sysCron, executionTime, generation);
        } else if (sysCron.getRemainingCount() > 0) {
            // Cron with limited execution times, and there are remaining execution times.
            AtomicInteger remainingCount = new AtomicInteger(sysCron.getRemainingCount());
            scheduleLimitedTask(sysCron, executionTime, remainingCount, generation);
        }
    }

    /**
     * Schedule a task repeatedly.
     * Send an asynchronous execution message in the task, and then reschedule.
     *
     * @param sysCron Cron job object
     * @param executionTime Execution time manager of the cron expression
     * @param generation Generation the job was registered in
     */
    private void scheduleRepeatedTask(SysCron sysCron, ExecutionTime executionTime, long generation) {
        Runnable task = () -> {
            sendToMessageQueue(sysCron);
            // Reschedule
            scheduleRepeatedTask(sysCron, executionTime, generation);
        };
        scheduleTask(sysCron, executionTime, generation, task);
    }

    /**
     * Schedule a task with limited execution times.
     * Send an asynchronous execution message in the task, and then reschedule.
     *
     * @param sysCron Cron job object
     * @param executionTime Execution time manager of the cron expression
     * @param remainingCount Remaining execution times
     * @param generation Generation the job was registered in
     */
    private void scheduleLimitedTask(SysCron sysCron, ExecutionTime executionTime, AtomicInteger remainingCount, long generation) {
        Runnable task = () -> {
            // Check the remaining execution times
            if (remainingCount.get() <= 0) {
                return;
            }
            sendToMessageQueue(sysCron);
            remainingCount.decrementAndGet();
            // Reschedule
            scheduleLimitedTask(sysCron, executionTime, remainingCount, generation);
        };
        scheduleTask(sysCron, executionTime, generation, task);
    }

    /**
     * Schedule a task once based on the cron expression.
     * Calculate the next execution time based on the time manager,
     * calculate the delay time, and execute the scheduled task.
     *
     * @param sysCron Cron job object
     * @param executionTime Execution time manager of the cron expression
     * @param generation Generation the job was registered in
     * @param task Scheduled task
     */
    private synchronized void scheduleTask(SysCron sysCron, ExecutionTime executionTime, long generation, Runnable task) {
        // The job was stopped while this run was firing, so the run must not schedule the next one
        if (generation != this.generation) {
            return;
        }
        ZonedDateTime now = LocalDateTime.now().atZone(ZoneId.systemDefault());
        ZonedDateTime nextExecutionTime = executionTime.nextExecution(now).orElse(null);
        if (nextExecutionTime == null) {
            return;
        }
        // Calculate the time difference between the next execution time and the current time
        // as the delay time of the scheduled task
        long delayMs = Duration.between(now, nextExecutionTime).toMillis();
        ScheduledFuture<?> scheduledTask = schedulerPool.getScheduler().schedule(task, delayMs, TimeUnit.MILLISECONDS);
        scheduledTasksMap.put(sysCron.getId(), scheduledTask);
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
        if (TenantJobMode.PER_TENANT.equals(sysCron.getTenantJobMode())) {
            if (tenantInfoService == null) {
                log.error("TenantInfoService is not available for PER_TENANT cron job: {}", sysCron.getName());
                return;
            }
            List<Long> tenantIds = tenantInfoService.getActiveTenantIds();
            for (Long tenantId : tenantIds) {
                sendCronMessage(sysCron, tenantId, false);
            }
        } else if (TenantJobMode.CROSS_TENANT.equals(sysCron.getTenantJobMode())) {
            sendCronMessage(sysCron, null, true);
        } else {
            sendCronMessage(sysCron, null, false);
        }
    }

    private void sendCronMessage(SysCron sysCron, Long tenantId, boolean crossTenant) {
        Context ctx = ContextHolder.cloneContext();
        if (tenantId != null) {
            ctx.setTenantId(tenantId);
        }
        ctx.setCrossTenant(crossTenant);
        CronTaskMessage message = new CronTaskMessage();
        message.setCronId(sysCron.getId());
        message.setCronName(sysCron.getName());
        message.setTriggerTime(LocalDateTime.now());
        message.setLastExecTime(sysCron.getLastExecTime());
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
        // Cancel all tasks
        for (Long cronId : scheduledTasksMap.keySet()) {
            cancelTask(cronId);
        }
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
}
