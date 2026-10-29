package io.softa.starter.cron.scheduler;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.starter.cron.entity.SysCron;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CronScheduler#start()}'s per-row isolation. start() runs once per leader election,
 * so an exception escaping its registration loop leaves every remaining job unscheduled and makes {@code LeaderElectionRunner} release the lease and retry 
 * ———the whole cluster then holds no schedule at all, however many of the rows were fine.
 */
class CronSchedulerStartTest {

    private JdbcService<?> jdbcService;
    private ScheduledExecutorService scheduler;
    private CronScheduler cronScheduler;

    @BeforeEach
    void setUp() {
        jdbcService = mock(JdbcService.class);
        scheduler = mock(ScheduledExecutorService.class);
        // A handle for every run, as a real executor hands back
        doReturn(mock(ScheduledFuture.class)).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        SchedulerPool schedulerPool = mock(SchedulerPool.class);
        when(schedulerPool.getScheduler()).thenReturn(scheduler);

        cronScheduler = new CronScheduler();
        ReflectionTestUtils.setField(cronScheduler, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(cronScheduler, "schedulerPool", schedulerPool);
    }

    /**
     * A row whose cron expression cannot be parsed is skipped, and the jobs on either side of it are still scheduled.
     */
    @Test
    void startSkipsTheUnregistrableRowAndSchedulesTheRest() {
        when(jdbcService.selectMetaEntityList(eq(SysCron.class), any())).thenReturn(List.of(
                activeCron(6L, "RecomputeSnapshotStats", "0 0 * * * ?"),
                // Quartz's seventh field is the year, which does not accept `?`
                activeCron(11L, "ExpireDueBuckets", "0 0 1 * * * ?"),
                activeCron(13L, "FinalizeEmployeeAttendance", "0 0 2 * * ?")));

        assertDoesNotThrow(() -> cronScheduler.start());

        verify(scheduler, times(2)).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    /**
     * Inactive rows are still left alone — the guard only decides which rows are attempted, not whether a failure propagates.
     */
    @Test
    void startIgnoresInactiveRows() {
        SysCron inactive = activeCron(12L, "CalEmployeeTimeSheet", "0 0 3 * * ?");
        inactive.setActive(false);
        when(jdbcService.selectMetaEntityList(eq(SysCron.class), any())).thenReturn(List.of(inactive));
        
        cronScheduler.start();
        
        verify(scheduler, times(0)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    private static SysCron activeCron(Long id, String name, String cronExpression) {
        SysCron sysCron = new SysCron();
        sysCron.setId(id);
        sysCron.setName(name);
        sysCron.setCronExpression(cronExpression);
        sysCron.setLimitExecution(false);
        sysCron.setActive(true);
        return sysCron;
    }
}
