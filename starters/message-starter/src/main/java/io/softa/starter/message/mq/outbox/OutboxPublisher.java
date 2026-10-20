package io.softa.starter.message.mq.outbox;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.mq.MqProducer;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.shared.metrics.MessageMetrics;

/**
 * Reads {@link OutboxStatus#NEW} rows from {@code message_outbox} and publishes
 * them to the broker.
 * <p>
 * Concurrency is handled by the framework optimistic lock: each publisher reads
 * a small due batch and claims rows by flipping {@code NEW -> PUBLISHING}. A
 * duplicate publisher that read the same candidate gets a version miss and
 * skips it.
 * <p>
 * Publish outcomes:
 * <ul>
 *   <li><b>Success</b> — CAS the row to {@link OutboxStatus#PUBLISHED}. If the
 *       CAS fails (another instance already published it), we log and move on.</li>
 *   <li><b>Failure</b> — {@code attempts++}, capped at
 *       {@link #MAX_PUBLISH_ATTEMPTS}, exponential back-off on
 *       {@code next_attempt_at}. After the cap the row is moved to
 *       {@link OutboxStatus#DEAD} for manual intervention.</li>
 * </ul>
 * Can be disabled wholesale via {@code softa.message.outbox.enabled=false}
 * (e.g. for read-only replicas).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "softa.message.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_PUBLISH_ATTEMPTS = 10;
    private static final long BACKOFF_BASE_MS = 1_000L;
    private static final long BACKOFF_CAP_MS = 60_000L;
    /** Wall-clock cap for one poll's allOf wait. Stuck broker → next poll re-claims. */
    private static final int BATCH_TIMEOUT_SECONDS = 30;
    /** Release delay when a route drops between scan and dispatch (flap guard). */
    private static final int ROUTE_UNAVAILABLE_RETRY_SECONDS = 30;
    /** Throttle for the stranded-route WARN: one line per window, not one per poll. */
    private static final long STRANDED_WARN_INTERVAL_MS = 300_000L;

    private volatile long strandedWarnedAt;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private MqProducer mqProducer;

    @Autowired(required = false)
    private MessageMetrics metrics;

    /**
     * Claim a batch of due rows through versionLock, then process them outside
     * the scan path.
     * <p>
     * Each claim is dispatched as a non-blocking {@code sendAsync()}, so the
     * whole claim batch is in flight at once instead of paying one broker
     * round-trip per message, as sequential {@code sendAsync().join()} would.
     * The messages are deliberately not batched into one broker entry (see
     * {@code PulsarMqProducer}): the Shared delivery subscriptions hand out
     * whole entries, and one-message entries are what spread a burst across
     * consumers.
     * <p>
     * The aggregate {@code allOf().get(timeout)} bounds wall-clock time per
     * poll; a stuck broker won't pile up scheduler invocations. Rows whose
     * future hasn't completed by then remain {@code Publishing}; the zombie
     * sweeper reopens stale claims.
     */
    @Scheduled(fixedDelayString = "${softa.message.outbox.poll-interval-ms:500}")
    public void pollAndPublish() {
        List<Claim> batch;
        try {
            batch = claimBatch();
        } catch (Exception e) {
            log.error("OutboxPublisher: claim batch failed: {}", e.getMessage(), e);
            return;
        }
        if (batch.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(batch.size());
        for (Claim c : batch) {
            futures.add(dispatchAsync(c));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("OutboxPublisher: batch dispatch did not complete within {}s — "
                    + "in-flight rows stay PUBLISHING until the zombie sweeper reopens them",
                    BATCH_TIMEOUT_SECONDS);
        } catch (Exception e) {
            // Per-claim errors are already handled inside dispatchAsync via .handle();
            // this only catches interruption / unexpected aggregation issues.
            log.warn("OutboxPublisher: batch dispatch wait interrupted: {}", e.getMessage());
        }
    }

    private List<Claim> claimBatch() {
        // Scan only broker-available routes: rows on unconfigured routes must not
        // occupy the id-ordered LIMIT batch, or they starve routable rows behind
        // them (partial topic configuration is an explicitly supported state).
        List<TopicRoute> available = Arrays.stream(TopicRoute.values())
                .filter(mqProducer::isAvailable)
                .toList();
        warnStrandedRoutes(available);
        if (available.isEmpty()) {
            return List.of();
        }

        List<OutboxEntry> candidates = outboxService.findDueNew(BATCH_SIZE, LocalDateTime.now(), available);
        List<Claim> claims = new ArrayList<>(candidates.size());
        for (OutboxEntry row : candidates) {
            long expectedVersion = version(row.getVersion());
            if (!outboxService.markPublishing(row.getId(), expectedVersion)) {
                continue;
            }
            claims.add(new Claim(
                    row.getId(),
                    row.getRoute(),
                    row.getPayload(),
                    expectedVersion + 1,
                    attempts(row.getAttempts())));
        }
        return claims;
    }

    /**
     * Name the routes whose due rows can never be picked up. The scan filter above
     * silently skips unavailable routes — correct for throughput, but without this
     * WARN a down broker leaves send records PENDING with zero log evidence at any
     * level. A route can also be legitimately unconfigured (partial topic
     * configuration is a supported state), so silence alone and noise alone are
     * both wrong: warn only when DUE rows are actually waiting on an unavailable
     * route, and at most once per {@link #STRANDED_WARN_INTERVAL_MS} (the existence
     * probe costs one LIMIT-1 query per window, not per poll).
     */
    private void warnStrandedRoutes(List<TopicRoute> available) {
        long now = System.currentTimeMillis();
        if (now - strandedWarnedAt < STRANDED_WARN_INTERVAL_MS) {
            return;
        }
        List<TopicRoute> unavailable = Arrays.stream(TopicRoute.values())
                .filter(route -> !available.contains(route))
                .toList();
        if (unavailable.isEmpty()) {
            return;
        }
        List<OutboxEntry> stranded = outboxService.findDueNew(1, LocalDateTime.now(), unavailable);
        if (stranded.isEmpty()) {
            return;
        }
        strandedWarnedAt = now;
        log.warn("OutboxPublisher: due outbox rows are waiting on unavailable broker route(s) {} — "
                + "they stay NEW (and their send records PENDING) until the broker / topic "
                + "configuration is restored", unavailable);
    }

    /**
     * Non-blocking dispatch: {@code sendAsync()} returns immediately once the
     * message is in the producer's outbound buffer. Completion (success or
     * failure) is wired to {@link #markPublished} / {@link #markFailure} via
     * {@code .handle()}. Returning the future lets the caller wait for the
     * whole batch in one place ({@link #pollAndPublish}).
     */
    private CompletableFuture<Void> dispatchAsync(Claim c) {
        if (!mqProducer.isAvailable(c.route)) {
            // Race belt: the route was available at scan time but dropped before
            // dispatch. Release with a real delay so the row doesn't spin through
            // every poll while the route flaps.
            outboxService.markNew(c.id, c.version, c.attempts, null,
                    LocalDateTime.now().plusSeconds(ROUTE_UNAVAILABLE_RETRY_SECONDS));
            log.debug("OutboxPublisher: route {} not available, released entry {} to NEW", c.route, c.id);
            return CompletableFuture.completedFuture(null);
        }
        OutboxMessage msg;
        try {
            msg = JsonUtils.stringToObject(c.payload, OutboxMessage.class);
        } catch (Exception e) {
            // Bad payload — record the failure synchronously, don't bother the broker.
            markFailure(c, e);
            return CompletableFuture.completedFuture(null);
        }
        return mqProducer.sendAsync(c.route, msg).handle((v, ex) -> {
            if (ex == null) {
                try {
                    markPublished(c.id, c.version);
                    if (metrics != null) metrics.outboxPublished(c.route.name());
                } catch (Exception persistEx) {
                    log.error("OutboxPublisher: markPublished failed for id={}: {}",
                            c.id, persistEx.getMessage(), persistEx);
                }
            } else {
                Exception cause = (ex instanceof Exception ex2) ? ex2 : new RuntimeException(ex);
                markFailure(c, cause);
            }
            return null;
        });
    }

    private void markPublished(long id, long version) {
        if (!outboxService.markPublished(id, version)) {
            // Someone else already marked it — or it moved off PUBLISHING. Safe to ignore.
            log.debug("OutboxPublisher: CAS miss for entry id={} version={} (already published)",
                    id, version);
        }
    }

    private void markFailure(Claim c, Exception e) {
        int nextAttempts = c.attempts + 1;
        String err = truncate(e.getMessage(), 500);
        if (nextAttempts >= MAX_PUBLISH_ATTEMPTS) {
            outboxService.markDead(c.id, c.version, nextAttempts, err);
            if (metrics != null) metrics.outboxDead(c.route.name());
            log.error("OutboxPublisher: entry id={} moved to DEAD after {} attempts: {}",
                    c.id, nextAttempts, err);
        } else {
            long delay = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * (1L << Math.min(nextAttempts, 6)));
            LocalDateTime nextAttempt = LocalDateTime.now().plusNanos(delay * 1_000_000L);
            outboxService.markNew(c.id, c.version, nextAttempts, err, nextAttempt);
            log.warn("OutboxPublisher: entry id={} failed attempt {}/{} — retry in {}ms: {}",
                    c.id, nextAttempts, MAX_PUBLISH_ATTEMPTS, delay, err);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static long version(Long version) {
        return version != null ? version : 0L;
    }

    private static int attempts(Integer attempts) {
        return attempts != null ? attempts : 0;
    }

    /** Row snapshot held outside the claim transaction for publish work. */
    private record Claim(long id, TopicRoute route, String payload, long version, int attempts) {}
}
