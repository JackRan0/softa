package io.softa.starter.cron.scheduler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.starter.cron.entity.SysCron;
import io.softa.starter.cron.message.CronTaskProducer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for how {@link CronScheduler} follows this node's leadership. A node that loses the lease and later wins it back
 * must schedule every job again: it then holds and renews the lease, so no other node can take over a schedule it failed to restore.
 * The scheduler is a real executor, so one that has been shut down rejects new tasks exactly as it does in production.
 */
class CronSchedulerLeadershipTest {

    /** The tasks handed to the executor, in the order they were scheduled */
    private final List<Runnable> scheduledRuns = new CopyOnWriteArrayList<>();
    private ScheduledThreadPoolExecutor scheduler;
    private CronTaskProducer cronTaskProducer;
    private CronScheduler cronScheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                ScheduledFuture<?> scheduledTask = super.schedule(command, delay, unit);
                scheduledRuns.add(command);
                return scheduledTask;
            }
        };
        SchedulerPool schedulerPool = mock(SchedulerPool.class);
        when(schedulerPool.getScheduler()).thenReturn(scheduler);
        JdbcService<?> jdbcService = mock(JdbcService.class);
        // Yearly, so that no run fires on its own while a test is looking at the executor
        when(jdbcService.selectMetaEntityList(eq(SysCron.class), any())).thenReturn(List.of(
                activeCron(6L, "RecomputeSnapshotStats", "0 0 2 1 1 ?"),
                activeCron(13L, "FinalizeEmployeeAttendance", "0 0 3 1 1 ?")));
        cronTaskProducer = mock(CronTaskProducer.class);

        cronScheduler = new CronScheduler();
        ReflectionTestUtils.setField(cronScheduler, "schedulerPool", schedulerPool);
        ReflectionTestUtils.setField(cronScheduler, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(cronScheduler, "cronTaskProducer", cronTaskProducer);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    /**
     * Losing the leadership cancels every job but keeps the scheduler, so winning it back schedules every job again.
     * A scheduler shut down on the loss would reject each of them, leaving the node holding the lease with nothing scheduled.
     */
    @Test
    void winningTheLeadershipBackSchedulesEveryJobAgain() {
        LeaderElectionRunner leaderElectionRunner = new LeaderElectionRunner();
        ReflectionTestUtils.setField(leaderElectionRunner, "cronScheduler", cronScheduler);
        leaderElectionRunner.setLeader(true);
        assertEquals(2, pendingRuns());

        leaderElectionRunner.setLeader(false);
        assertEquals(0, pendingRuns());
        assertFalse(scheduler.isShutdown());

        leaderElectionRunner.setLeader(true);
        assertEquals(2, pendingRuns());
    }

    /**
     * An election round that runs into a Redis outage steps the leader down without throwing — scheduleAtFixedRate would suppress
     * every later round once one throws — so the node stays in the election and wins the lease back once Redis recovers.
     */
    @Test
    void redisOutageStepsTheLeaderDownWithoutEndingTheElection() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisConnectionFailureException outage = new RedisConnectionFailureException("Unable to connect to Redis");
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true).thenThrow(outage).thenReturn(true);
        when(valueOperations.get(anyString())).thenThrow(outage);
        LeaderElectionRunner leaderElectionRunner = new LeaderElectionRunner();
        ReflectionTestUtils.setField(leaderElectionRunner, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(leaderElectionRunner, "cronScheduler", cronScheduler);

        // This node acquires the lease
        leaderElectionRunner.runElectionRound();
        assertEquals(2, pendingRuns());

        // Redis is down: the round fails, and so does releasing the lease on the way out
        assertDoesNotThrow(leaderElectionRunner::runElectionRound);
        assertEquals(0, pendingRuns());

        // Redis is back and the old lease has expired
        leaderElectionRunner.runElectionRound();
        assertEquals(2, pendingRuns());
    }

    /**
     * A second start() replaces the jobs the first one registered, instead of scheduling every job a second time.
     */
    @Test
    void startingTwiceSchedulesEachJobOnce() {
        cronScheduler.start();
        cronScheduler.start();

        assertEquals(2, pendingRuns());
    }

    /**
     * A run that was already firing when stop() cancelled its job still sends its trigger, but must not schedule its next run:
     * the job would otherwise keep firing on a node that has lost the leadership, alongside the new leader.
     */
    @Test
    void runFiringAcrossStopDoesNotScheduleItsNextRun() {
        cronScheduler.start();
        Runnable firingRun = scheduledRuns.getFirst();

        cronScheduler.stop();
        // Stands in for a run the executor had already started: stop() can only interrupt it, which the send may well ignore
        firingRun.run();

        verify(cronTaskProducer).sendCronTask(any());
        assertEquals(0, pendingRuns());
    }

    /** Tasks the executor still holds that have not been cancelled, one per scheduled job */
    private long pendingRuns() {
        return scheduler.getQueue().stream().filter(task -> !((Future<?>) task).isCancelled()).count();
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
