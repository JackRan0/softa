package io.softa.starter.studio.release.service;

import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.release.dto.AggregateChangeReport;
import io.softa.starter.studio.release.entity.DesignActivity;
import io.softa.starter.studio.release.enums.DesignActivityKind;

/**
 * DesignActivity Model Service Interface — records the unified studio operation audit
 * (PUBLISH / IMPORT / REVERSE / MERGE) that replaces DesignDeployment + DesignMerge.
 * <p>
 * Studio operations are synchronous: callers {@link #start} a {@code RUNNING} activity, run the work
 * inline, then {@link #succeed} or {@link #fail} it. {@link #snapshot} captures the post-operation
 * design for {@code restore}.
 */
public interface DesignActivityService extends EntityService<DesignActivity, Long> {

    /** Open a {@code RUNNING} activity record for a synchronous operation. {@code sourceEnvId} is MERGE-only. */
    DesignActivity start(Long appId, Long envId, DesignActivityKind kind, Long sourceEnvId, Long operatorId);

    /** Mark the activity {@code SUCCESS}, attaching the applied change set, audit detail, and snapshot link. */
    void succeed(Long activityId, JsonNode changeSet, JsonNode detail, Long snapshotId);

    /** Mark the activity {@code FAILURE} with the error message. */
    void fail(Long activityId, String errorMessage);

    /** Mark the activity {@code CANCELED} (operator action) with a note. */
    void cancel(Long activityId, String note);

    /** Persist a {@link io.softa.starter.studio.release.entity.DesignSnapshot} for the activity; returns its id. */
    Long snapshot(Long activityId, JsonNode content);

    /**
     * Aggregate-root-grouped before/after view of this activity's change set — "which
     * aggregate root, which field/attr/option changed, before → after". Derived on demand from the
     * persisted flat {@code changeSet} (uniform {@code List<RowChangeDTO>} across all four kinds); an
     * empty report when the activity carries no change set.
     */
    AggregateChangeReport changeReport(Long activityId);
}
