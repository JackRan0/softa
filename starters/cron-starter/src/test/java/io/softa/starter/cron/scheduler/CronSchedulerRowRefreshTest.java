package io.softa.starter.cron.scheduler;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.cronutils.model.time.ExecutionTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.compute.CronUtils;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.cron.entity.SysCron;
import io.softa.starter.cron.enums.TenantJobMode;
import io.softa.starter.cron.message.CronTaskProducer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for how the leader keeps the {@code SysCron} rows on its schedule in line with the table: it reads them again at every
 * refresh, so that a row created, changed, switched off or deleted, on whichever node, reaches the schedule without a new leader election.
 * The scheduler is a real executor; every expression is yearly, so that no run fires on its own while a test is looking at it.
 */
class CronSchedulerRowRefreshTest {

    /** The job runs handed to the executor, in the order they were scheduled */
    private final List<ScheduledRun> scheduledRuns = new CopyOnWriteArrayList<>();
    /** The periodic tasks handed to the executor */
    private final List<Runnable> periodicTasks = new CopyOnWriteArrayList<>();
    /** What the table holds at the next read; null makes the read fail */
    private List<SysCron> rows;
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

            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
                periodicTasks.add(command);
                return super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
            }
        };
        SchedulerPool schedulerPool = mock(SchedulerPool.class);
        when(schedulerPool.getScheduler()).thenReturn(scheduler);
        JdbcService<?> jdbcService = mock(JdbcService.class);
        when(jdbcService.selectMetaEntityList(eq(SysCron.class), any())).thenAnswer(_ -> {
            if (rows == null) {
                throw new IllegalStateException("database unavailable");
            }
            return rows;
        });
        cronTaskProducer = mock(CronTaskProducer.class);
        tenantInfoService = mock(TenantInfoService.class);

        // No provider: the rows are refreshed all the same
        cronScheduler = new CronScheduler();
        ReflectionTestUtils.setField(cronScheduler, "schedulerPool", schedulerPool);
        ReflectionTestUtils.setField(cronScheduler, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(cronScheduler, "cronTaskProducer", cronTaskProducer);
        ReflectionTestUtils.setField(cronScheduler, "tenantInfoService", tenantInfoService);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /**
     * The refresh is scheduled even where no provider is registered, so that a row created after the takeover is scheduled.
     */
    @Test
    void rowsAreRefreshedWithoutAnyProvider() {
        rows = List.of();
        cronScheduler.start();
        assertEquals(1, periodicTasks.size());

        rows = List.of(row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"));
        periodicTasks.getFirst().run();

        assertEquals(1, scheduledRuns.size());
        assertDueAtNextExecutionOf("0 0 2 1 1 ?", scheduledRuns.getFirst());
    }

    /**
     * A refresh cancels the row that was deleted, schedules the one that was created, and leaves the one left as it was with its next run.
     */
    @Test
    void refreshFollowsTheTable() {
        rows = List.of(row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"), row(13L, "FinalizeEmployeeAttendance", "0 0 3 1 1 ?"));
        cronScheduler.start();
        ScheduledRun runOf6 = scheduledRuns.get(0);
        ScheduledRun runOf13 = scheduledRuns.get(1);

        rows = List.of(row(13L, "FinalizeEmployeeAttendance", "0 0 3 1 1 ?"), row(21L, "ExpireDueBuckets", "0 0 4 1 1 ?"));
        cronScheduler.refresh();

        assertTrue(runOf6.future().isCancelled());
        assertFalse(runOf13.future().isCancelled());
        assertEquals(3, scheduledRuns.size());
        assertDueAtNextExecutionOf("0 0 4 1 1 ?", scheduledRuns.get(2));
    }

    /**
     * Only a row with {@code active = false} is left off the schedule: one with no flag at all counts as active.
     */
    @Test
    void onlyARowSwitchedOffIsLeftOut() {
        SysCron unflagged = row(13L, "FinalizeEmployeeAttendance", "0 0 3 1 1 ?");
        unflagged.setActive(null);
        SysCron switchedOff = row(21L, "ExpireDueBuckets", "0 0 4 1 1 ?");
        switchedOff.setActive(false);
        rows = List.of(row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"), unflagged, switchedOff);

        cronScheduler.start();

        assertEquals(2, scheduledRuns.size());
        assertDueAtNextExecutionOf("0 0 2 1 1 ?", scheduledRuns.get(0));
        assertDueAtNextExecutionOf("0 0 3 1 1 ?", scheduledRuns.get(1));
    }

    /**
     * A row switched off is cancelled at the next refresh, and scheduled anew once it is switched on again.
     */
    @Test
    void rowSwitchedOffIsCancelledUntilItIsSwitchedOnAgain() {
        rows = List.of(row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"));
        cronScheduler.start();

        SysCron switchedOff = row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?");
        switchedOff.setActive(false);
        rows = List.of(switchedOff);
        cronScheduler.refresh();
        assertTrue(scheduledRuns.getFirst().future().isCancelled());
        assertEquals(1, scheduledRuns.size());

        rows = List.of(row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"));
        cronScheduler.refresh();

        assertEquals(2, scheduledRuns.size());
        assertDueAtNextExecutionOf("0 0 2 1 1 ?", scheduledRuns.get(1));
    }

    /**
     * A run that was already firing when its row was deleted still sends its trigger, but must not schedule its next run.
     */
    @Test
    void runOfADeletedRowDoesNotScheduleItsNextRun() {
        rows = List.of(row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"));
        cronScheduler.start();
        Runnable firingRun = scheduledRuns.getFirst().command();

        rows = List.of();
        cronScheduler.refresh();
        firingRun.run();

        verify(cronTaskProducer).sendCronTask(any());
        assertEquals(1, scheduledRuns.size());
    }

    /** A change to each field a row's schedule depends on */
    static Stream<Arguments> scheduleChanges() {
        return Stream.of(
                argumentSet("name", (Consumer<SysCron>) row -> row.setName("RecomputeAllSnapshotStats")),
                argumentSet("cronExpression", (Consumer<SysCron>) row -> row.setCronExpression("0 30 2 1 1 ?")),
                argumentSet("limitExecution", (Consumer<SysCron>) row -> row.setLimitExecution(false)),
                argumentSet("remainingCount", (Consumer<SysCron>) row -> row.setRemainingCount(5)),
                argumentSet("tenantJobMode", (Consumer<SysCron>) row -> row.setTenantJobMode(TenantJobMode.CROSS_TENANT)));
    }

    /**
     * A row whose schedule changed is registered again, and a run of the old one that was already firing does not schedule its next run.
     */
    @ParameterizedTest
    @MethodSource("scheduleChanges")
    void rowWhoseScheduleChangedIsRegisteredAgain(Consumer<SysCron> change) {
        rows = List.of(limitedRow(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?", 3));
        cronScheduler.start();
        ScheduledRun firingRun = scheduledRuns.getFirst();

        SysCron changed = limitedRow(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?", 3);
        change.accept(changed);
        rows = List.of(changed);
        cronScheduler.refresh();
        firingRun.command().run();

        assertTrue(firingRun.future().isCancelled());
        assertEquals(2, scheduledRuns.size());
        assertDueAtNextExecutionOf(changed.getCronExpression(), scheduledRuns.get(1));
    }

    /**
     * A row changed in a field its schedule does not depend on keeps its next run.
     */
    @Test
    void rowChangedElsewhereKeepsItsNextRun() {
        rows = List.of(row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"));
        cronScheduler.start();

        SysCron edited = row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?");
        edited.setDescription("Recompute the snapshot statistics");
        edited.setPriority(3);
        edited.setLastExecTime(LocalDateTime.now());
        edited.setUpdatedTime(LocalDateTime.now());
        rows = List.of(edited);
        cronScheduler.refresh();

        assertFalse(scheduledRuns.getFirst().future().isCancelled());
        assertEquals(1, scheduledRuns.size());
    }

    /**
     * A limited row left as it was keeps counting down the executions it has left, though the table still holds the count it started
     * from, and is not registered again once it has none.
     */
    @Test
    void unchangedLimitedRowKeepsTheExecutionsItHasLeft() {
        rows = List.of(limitedRow(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?", 2));
        cronScheduler.start();

        scheduledRuns.get(0).command().run();
        cronScheduler.refresh();
        scheduledRuns.get(1).command().run();
        cronScheduler.refresh();

        verify(cronTaskProducer, times(2)).sendCronTask(any());
        assertEquals(2, scheduledRuns.size());
    }

    /**
     * A run whose trigger cannot be sent, as when the active tenants of a per-tenant row cannot be read, still schedules the next one.
     */
    @Test
    void failedSendStillSchedulesTheNextRun() {
        SysCron perTenant = row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?");
        perTenant.setTenantJobMode(TenantJobMode.PER_TENANT);
        rows = List.of(perTenant);
        when(tenantInfoService.getActiveTenantIds())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(List.of(7L, 9L));
        cronScheduler.start();

        assertDoesNotThrow(() -> scheduledRuns.get(0).command().run());
        assertEquals(2, scheduledRuns.size());
        scheduledRuns.get(1).command().run();

        verify(cronTaskProducer, times(2)).sendCronTask(any());
        assertEquals(3, scheduledRuns.size());
    }

    /**
     * A run of a limited row whose trigger cannot be sent does not count as one of its executions.
     */
    @Test
    void failedSendIsNotCountedAsAnExecution() {
        rows = List.of(limitedRow(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?", 1));
        doThrow(new IllegalStateException("Cron task topic is not configured")).doNothing()
                .when(cronTaskProducer).sendCronTask(any());
        cronScheduler.start();

        scheduledRuns.get(0).command().run();
        scheduledRuns.get(1).command().run();

        verify(cronTaskProducer, times(2)).sendCronTask(any());
        // Its one execution was the second run, which schedules no other
        assertEquals(2, scheduledRuns.size());
    }

    /**
     * Rows that cannot be read leave the schedule as it is, and a later refresh that reads them changes nothing it kept.
     */
    @Test
    void failingReadLeavesTheRowsAsTheyAre() {
        rows = List.of(row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"));
        cronScheduler.start();

        rows = null;
        cronScheduler.refresh();
        rows = List.of(row(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"));
        cronScheduler.refresh();

        assertFalse(scheduledRuns.getFirst().future().isCancelled());
        assertEquals(1, scheduledRuns.size());
    }

    /**
     * A row whose expression cannot be parsed gets no run, and is scheduled once its expression is fixed.
     */
    @Test
    void unparsableRowIsScheduledOnceItIsFixed() {
        // Quartz's seventh field is the year, which does not accept `?`
        rows = List.of(row(11L, "ExpireDueBuckets", "0 0 1 * * * ?"));
        cronScheduler.start();
        cronScheduler.refresh();
        assertTrue(scheduledRuns.isEmpty());

        rows = List.of(row(11L, "ExpireDueBuckets", "0 0 1 1 1 ?"));
        cronScheduler.refresh();

        assertEquals(1, scheduledRuns.size());
        assertDueAtNextExecutionOf("0 0 1 1 1 ?", scheduledRuns.getFirst());
    }

    private static void assertDueAtNextExecutionOf(String cronExpression, ScheduledRun run) {
        ZonedDateTime next = ExecutionTime.forCron(CronUtils.getCron("expected", cronExpression))
                .nextExecution(ZonedDateTime.now()).orElseThrow();
        long expectedDelayMs = next.toInstant().toEpochMilli() - System.currentTimeMillis();
        // Scheduled a moment ago, so the delay it got is at most a little longer than the one left now
        assertTrue(Math.abs(run.delayMs() - expectedDelayMs) < 5_000,
                () -> "due in " + run.delayMs() + "ms, while " + cronExpression + " next runs in " + expectedDelayMs + "ms");
    }

    private static SysCron row(Long id, String name, String cronExpression) {
        SysCron sysCron = new SysCron();
        sysCron.setId(id);
        sysCron.setName(name);
        sysCron.setCronExpression(cronExpression);
        sysCron.setLimitExecution(false);
        sysCron.setActive(true);
        return sysCron;
    }

    private static SysCron limitedRow(Long id, String name, String cronExpression, int remainingCount) {
        SysCron sysCron = row(id, name, cronExpression);
        sysCron.setLimitExecution(true);
        sysCron.setRemainingCount(remainingCount);
        return sysCron;
    }

    private record ScheduledRun(Runnable command, ScheduledFuture<?> future, long delayMs) {}
}
