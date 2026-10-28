package io.softa.starter.cron.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.cronutils.model.time.ExecutionTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.compute.CronUtils;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.cron.job.CronJob;
import io.softa.starter.cron.job.CronJobProvider;
import io.softa.starter.cron.message.CronTaskProducer;
import io.softa.starter.cron.message.dto.CronTaskMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the jobs a {@link CronJobProvider} supplies: the leader schedules them beside the {@code SysCron} rows and keeps them in
 * line with the provider at every refresh, so a job that is added or dropped reaches the schedule without a new leader election.
 * The scheduler is a real executor; every expression is yearly, so that no run fires on its own while a test is looking at it.
 */
class CronSchedulerProvidedJobsTest {

    private static final CronJob FLOW_A = CronJob.ofTenant("flow:11", "0 0 2 1 1 ?", 7L);
    private static final CronJob FLOW_B = CronJob.ofTenant("flow:12", "0 0 3 1 1 ?", 7L);
    private static final CronJob FLOW_C = CronJob.perTenant("flow:13", "0 0 4 1 1 ?");

    /** The job runs handed to the executor, in the order they were scheduled */
    private final List<ScheduledRun> scheduledRuns = new CopyOnWriteArrayList<>();
    /** What the provider returns next; null makes it fail */
    private List<CronJob> providedJobs;
    private ScheduledThreadPoolExecutor scheduler;
    private CronTaskProducer cronTaskProducer;
    private TenantInfoService tenantInfoService;
    private CronScheduler cronScheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                ScheduledFuture<?> scheduledTask = super.schedule(command, delay, unit);
                scheduledRuns.add(new ScheduledRun(command, scheduledTask, unit.toMillis(delay)));
                return scheduledTask;
            }
        };
        SchedulerPool schedulerPool = mock(SchedulerPool.class);
        when(schedulerPool.getScheduler()).thenReturn(scheduler);
        JdbcService<?> jdbcService = mock(JdbcService.class);
        when(jdbcService.selectMetaEntityList(any(), any())).thenReturn(List.of());
        cronTaskProducer = mock(CronTaskProducer.class);
        tenantInfoService = mock(TenantInfoService.class);
        CronJobProvider provider = () -> {
            if (providedJobs == null) {
                throw new IllegalStateException("database unavailable");
            }
            return providedJobs;
        };

        cronScheduler = new CronScheduler();
        ReflectionTestUtils.setField(cronScheduler, "schedulerPool", schedulerPool);
        ReflectionTestUtils.setField(cronScheduler, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(cronScheduler, "cronTaskProducer", cronTaskProducer);
        ReflectionTestUtils.setField(cronScheduler, "tenantInfoService", tenantInfoService);
        ReflectionTestUtils.setField(cronScheduler, "cronJobProviders", List.of(provider));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /**
     * Starting to schedule registers every provided job at the next execution of its own expression, and the periodic refresh with them.
     */
    @Test
    void startSchedulesEachProvidedJobOnItsOwnExpression() {
        providedJobs = List.of(FLOW_A, FLOW_B);

        cronScheduler.start();

        assertEquals(2, scheduledRuns.size());
        assertDueAtNextExecutionOf(FLOW_A, scheduledRuns.get(0));
        assertDueAtNextExecutionOf(FLOW_B, scheduledRuns.get(1));
        // The refresh is the one periodic task besides the runs
        assertEquals(3, scheduler.getQueue().size());
    }

    /**
     * A refresh cancels the job the provider dropped, schedules the one it added, and leaves the one it kept with its next run.
     */
    @Test
    void refreshFollowsTheProvider() {
        providedJobs = List.of(FLOW_A, FLOW_B);
        cronScheduler.start();
        ScheduledRun runOfA = scheduledRuns.get(0);
        ScheduledRun runOfB = scheduledRuns.get(1);

        providedJobs = List.of(FLOW_B, FLOW_C);
        cronScheduler.refreshProvidedJobs();

        assertTrue(runOfA.future().isCancelled());
        assertFalse(runOfB.future().isCancelled());
        assertEquals(3, scheduledRuns.size());
        assertDueAtNextExecutionOf(FLOW_C, scheduledRuns.get(2));
    }

    /**
     * A job whose expression changed is another job: the old schedule is cancelled and the new one registered.
     */
    @Test
    void changedExpressionReplacesTheJob() {
        providedJobs = List.of(FLOW_A);
        cronScheduler.start();
        CronJob rescheduled = CronJob.ofTenant(FLOW_A.name(), "0 30 2 1 1 ?", FLOW_A.tenantId());

        providedJobs = List.of(rescheduled);
        cronScheduler.refreshProvidedJobs();

        assertTrue(scheduledRuns.get(0).future().isCancelled());
        assertEquals(2, scheduledRuns.size());
        assertDueAtNextExecutionOf(rescheduled, scheduledRuns.get(1));
    }

    /**
     * A run that was already firing when the refresh dropped its job still sends its trigger, but must not schedule its next run.
     */
    @Test
    void runOfADroppedJobDoesNotScheduleItsNextRun() {
        providedJobs = List.of(FLOW_A);
        cronScheduler.start();
        Runnable firingRun = scheduledRuns.getFirst().command();
        when(tenantInfoService.isTenantActive(7L)).thenReturn(true);

        providedJobs = List.of();
        cronScheduler.refreshProvidedJobs();
        firingRun.run();

        verify(cronTaskProducer).sendCronTask(any());
        assertEquals(1, scheduledRuns.size());
    }

    /**
     * A run sends its trigger and then schedules the run after it.
     */
    @Test
    void runSchedulesTheNextOne() {
        providedJobs = List.of(FLOW_A);
        cronScheduler.start();
        when(tenantInfoService.isTenantActive(7L)).thenReturn(true);

        scheduledRuns.getFirst().command().run();

        verify(cronTaskProducer).sendCronTask(any());
        assertEquals(2, scheduledRuns.size());
    }

    /**
     * A provider that cannot be read leaves the schedule as it is, and a later refresh that reads it changes nothing it kept.
     */
    @Test
    void failingProviderLeavesTheScheduleAsItIs() {
        providedJobs = List.of(FLOW_A);
        cronScheduler.start();

        providedJobs = null;
        cronScheduler.refreshProvidedJobs();
        providedJobs = List.of(FLOW_A);
        cronScheduler.refreshProvidedJobs();

        assertFalse(scheduledRuns.getFirst().future().isCancelled());
        assertEquals(1, scheduledRuns.size());
    }

    /**
     * A provider that cannot be read when this node wins the leadership does not keep the rows from being scheduled,
     * and its jobs follow at the next refresh.
     */
    @Test
    void failingProviderAtStartLeavesItsJobsToTheNextRefresh() {
        providedJobs = null;
        cronScheduler.start();
        assertTrue(scheduledRuns.isEmpty());

        providedJobs = List.of(FLOW_A);
        cronScheduler.refreshProvidedJobs();

        assertEquals(1, scheduledRuns.size());
    }

    /**
     * A node that has stopped scheduling — it lost the leadership — neither runs its provided jobs nor schedules them again on a refresh.
     */
    @Test
    void stopCancelsTheProvidedJobsAndTheirRefresh() {
        providedJobs = List.of(FLOW_A);
        cronScheduler.start();

        cronScheduler.stop();
        cronScheduler.refreshProvidedJobs();

        assertTrue(scheduledRuns.getFirst().future().isCancelled());
        assertEquals(1, scheduledRuns.size());
        assertTrue(scheduler.getQueue().stream().allMatch(task -> ((ScheduledFuture<?>) task).isCancelled()));
    }

    /**
     * A job whose expression cannot be parsed is skipped without holding up the others, at the start and at every refresh.
     */
    @Test
    void unparsableJobDoesNotHoldUpTheOthers() {
        CronJob broken = CronJob.ofTenant("flow:14", "0 0 * * *", 7L);
        providedJobs = List.of(broken, FLOW_A);

        cronScheduler.start();
        cronScheduler.refreshProvidedJobs();

        assertEquals(1, scheduledRuns.size());
        assertDueAtNextExecutionOf(FLOW_A, scheduledRuns.getFirst());
    }

    /**
     * The trigger of a job of one tenant carries no cron id and runs in that tenant's context, and only while the tenant is active.
     */
    @Test
    void jobOfOneTenantSendsInThatTenantWhileItIsActive() {
        when(tenantInfoService.isTenantActive(7L)).thenReturn(true, false);

        cronScheduler.sendToMessageQueue(FLOW_A);
        cronScheduler.sendToMessageQueue(FLOW_A);

        ArgumentCaptor<CronTaskMessage> sent = ArgumentCaptor.forClass(CronTaskMessage.class);
        verify(cronTaskProducer, times(1)).sendCronTask(sent.capture());
        CronTaskMessage message = sent.getValue();
        assertNull(message.getCronId());
        assertEquals("flow:11", message.getCronName());
        assertEquals(7L, message.getContext().getTenantId());
        assertFalse(message.getContext().isCrossTenant());
    }

    /**
     * The trigger of a job of every tenant is sent once per active tenant, each in that tenant's context.
     */
    @Test
    void perTenantJobSendsOncePerActiveTenant() {
        when(tenantInfoService.getActiveTenantIds()).thenReturn(List.of(7L, 9L));

        cronScheduler.sendToMessageQueue(FLOW_C);

        ArgumentCaptor<CronTaskMessage> sent = ArgumentCaptor.forClass(CronTaskMessage.class);
        verify(cronTaskProducer, times(2)).sendCronTask(sent.capture());
        assertEquals(List.of(7L, 9L), sent.getAllValues().stream().map(m -> m.getContext().getTenantId()).toList());
        assertTrue(sent.getAllValues().stream().allMatch(m -> "flow:13".equals(m.getCronName()) && m.getCronId() == null));
    }

    /**
     * A run woken a little before its execution time — its delay is cut to whole milliseconds, and the wall clock may drift from the
     * executor's — is handed the execution after it, not that same execution a second time.
     */
    @Test
    void runWokenBeforeItsExecutionIsHandedTheNextOne() {
        ExecutionTime everyMinute = ExecutionTime.forCron(CronUtils.getCron("everyMinute", "0 * * ? * *"));
        ZonedDateTime noon = LocalDateTime.of(2026, 10, 28, 12, 0).atZone(ZoneId.systemDefault());
        MovableClock clock = new MovableClock(noon.minusNanos(400_000).toInstant());
        CronScheduler.Executions executions = new CronScheduler.Executions(everyMinute, clock);

        // Registered 0.4ms before noon: the first run is due at noon, a delay cut to 0ms
        assertEquals(0L, executions.delayToNext());
        // That run wakes at the same instant, still before noon
        assertEquals(60_000L, executions.delayToNext());
        // The next one wakes a little late, which needs nothing special
        clock.instant = noon.plusMinutes(1).plusNanos(400_000).toInstant();
        assertEquals(59_999L, executions.delayToNext());
    }

    private static void assertDueAtNextExecutionOf(CronJob job, ScheduledRun run) {
        ZonedDateTime next = ExecutionTime.forCron(CronUtils.getCron(job.name(), job.cronExpression()))
                .nextExecution(ZonedDateTime.now()).orElseThrow();
        long expectedDelayMs = next.toInstant().toEpochMilli() - System.currentTimeMillis();
        // Scheduled a moment ago, so the delay it got is at most a little longer than the one left now
        assertTrue(Math.abs(run.delayMs() - expectedDelayMs) < 5_000,
                () -> job + " is due in " + run.delayMs() + "ms, its next execution in " + expectedDelayMs + "ms");
    }

    private record ScheduledRun(Runnable command, ScheduledFuture<?> future, long delayMs) {}

    /** A clock a test moves by hand */
    private static final class MovableClock extends Clock {

        private Instant instant;

        private MovableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
