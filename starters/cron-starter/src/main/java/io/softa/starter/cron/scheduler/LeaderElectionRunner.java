package io.softa.starter.cron.scheduler;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.SystemException;

/**
 * Scheduler leader election based on Redis.
 * The leader node renews, and the candidate node tries to acquire the lease.
 * Suitable for scheduling task, which can tolerate repeated scheduling in a short time under
 * extreme circumstances, and a short time when no leader is generated during the lease period.
 */
@Slf4j
@Component
public class LeaderElectionRunner implements CommandLineRunner {
    private static final String LEADER_KEY = "cron:scheduler:leader";
    private static final String LEADER_UUID = UUID.randomUUID().toString();

    /** Leader lease duration in seconds */
    private static final int LEADER_EXPIRE_SECONDS = 60;
    /** Leader renewal interval, half of the lease duration, in seconds */
    private static final int LEADER_RENEW_INTERVAL = LEADER_EXPIRE_SECONDS / 2;
    /** Leader renewal executor, single thread */
    private final ScheduledExecutorService leaderRenewalExecutor = Executors.newScheduledThreadPool(1);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CronScheduler cronScheduler;

    /** Switched by the election task and by quitElection(), which run on different threads */
    private final AtomicBoolean isLeader = new AtomicBoolean(false);

    /**
     * Try to acquire the Leader lock, renew once every half of the lease duration.
     */
    @Override
    public void run(String @NonNull ... args) {
        // Try to acquire the lock or renew once during each lease period
        leaderRenewalExecutor.scheduleAtFixedRate(this::runElectionRound, 0, LEADER_RENEW_INTERVAL, TimeUnit.SECONDS);
    }

    /**
     * Run one election round: acquire the lease, or renew it while this node is the leader.
     * A failure steps this node down rather than escaping: scheduleAtFixedRate silently suppresses every later round once one throws,
     * which would leave a leader scheduling without renewing its lease, and any node out of the election for good.
     */
    void runElectionRound() {
        try {
            if (tryAcquireLeaderLease()) {
                setLeader(true);
            } else if (isLeader.get()) {
                renewLeaderLease();
            }
        } catch (Exception e) {
            log.error("Cron job scheduler Leader election failed, an exception occurred:", e);
            terminateLeadership();
        }
    }

    /**
     * Set Leader status
     *
     * @param isLeader Leader status
     */
    public void setLeader(boolean isLeader) {
        if (this.isLeader.compareAndSet(!isLeader, isLeader)) {
            onLeaderChange(isLeader);
        }
    }

    /**
     * Leader status change triggers scheduler change
     *
     * @param isLeader Leader status
     */
    public void onLeaderChange(boolean isLeader) {
        if (isLeader) {
            log.info("Leader elected, starting the scheduler.");
            try {
                // Start all active cron jobs
                cronScheduler.start();
            } catch (Exception e) {
                throw new SystemException("Scheduler startup failed: {0}", e.getMessage(), e);
            }
        } else {
            // Cancel all scheduling tasks when no longer the leader, keeping the scheduler for when this node wins the leadership back
            cronScheduler.stop();
        }
    }

    /**
     * Try to acquire the Leader lock with the lease duration.
     *
     * @return true if the Leader lock is acquired
     */
    private boolean tryAcquireLeaderLease() {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LEADER_KEY, LEADER_UUID, LEADER_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Renew the `Leader` lease.
     * Check if the current `Leader` is itself before renewing
     */
    private void renewLeaderLease() {
        String currentLeaderUUID = redisTemplate.opsForValue().get(LEADER_KEY);
        if (LEADER_UUID.equals(currentLeaderUUID)) {
            // If the `Leader` is itself, renew the lease
            redisTemplate.expire(LEADER_KEY, LEADER_EXPIRE_SECONDS, TimeUnit.SECONDS);
        } else {
            // If the `Leader` is not itself, release the lease
            terminateLeadership();
        }
    }

    /**
     * Terminate the `Leader` lease.
     * Steps down even when the lease cannot be released, e.g. while Redis is unreachable: the lease then expires on its own.
     */
    private void terminateLeadership() {
        try {
            String currentLeaderUUID = redisTemplate.opsForValue().get(LEADER_KEY);
            if (LEADER_UUID.equals(currentLeaderUUID)) {
                redisTemplate.delete(LEADER_KEY);
            }
        } catch (Exception e) {
            log.warn("Failed to release the cron job scheduler Leader lease, it expires within {}s: {}", LEADER_EXPIRE_SECONDS, e.getMessage());
        }
        setLeader(false);
    }

    /**
     * Quit from the `Leader` election
     */
    @PreDestroy
    public void quitElection() {
        leaderRenewalExecutor.shutdown();
        terminateLeadership();
    }
}
