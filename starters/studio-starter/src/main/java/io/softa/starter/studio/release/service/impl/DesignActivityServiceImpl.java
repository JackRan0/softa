package io.softa.starter.studio.release.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.dto.AggregateChangeReport;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.entity.DesignActivity;
import io.softa.starter.studio.release.entity.DesignSnapshot;
import io.softa.starter.studio.release.enums.DesignActivityKind;
import io.softa.starter.studio.release.enums.DesignActivityStatus;
import io.softa.starter.studio.release.service.DesignActivityService;
import io.softa.starter.studio.release.service.DesignSnapshotService;

/**
 * DesignActivity Model Service Implementation.
 */
@Service
public class DesignActivityServiceImpl extends EntityServiceImpl<DesignActivity, Long> implements DesignActivityService {

    /** Matches the {@code errorMessage} column width (DesignActivity.errorMessage, length = 20000). */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 20000;

    /** The persisted {@code changeSet} shape — uniform across PUBLISH / IMPORT / REVERSE / MERGE. */
    private static final TypeReference<List<RowChangeDTO>> CHANGE_SET_TYPE = new TypeReference<>() {
    };

    private final DesignSnapshotService snapshotService;

    public DesignActivityServiceImpl(DesignSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Override
    public DesignActivity start(Long appId, Long envId, DesignActivityKind kind, Long sourceEnvId, Long operatorId) {
        DesignActivity activity = new DesignActivity();
        activity.setAppId(appId);
        activity.setEnvId(envId);
        activity.setKind(kind);
        activity.setSourceEnvId(sourceEnvId);
        activity.setOperatorId(operatorId);
        activity.setStatus(DesignActivityStatus.RUNNING);
        activity.setStartedTime(LocalDateTime.now());
        activity.setId(this.createOne(activity));
        return activity;
    }

    @Override
    public void succeed(Long activityId, JsonNode changeSet, JsonNode detail, Long snapshotId) {
        DesignActivity activity = load(activityId);
        activity.setStatus(DesignActivityStatus.SUCCESS);
        activity.setChangeSet(changeSet);
        activity.setDetail(detail);
        activity.setSnapshotId(snapshotId);
        activity.setFinishedTime(LocalDateTime.now());
        this.updateOne(activity);
    }

    @Override
    public void fail(Long activityId, String errorMessage) {
        DesignActivity activity = load(activityId);
        activity.setStatus(DesignActivityStatus.FAILURE);
        activity.setErrorMessage(truncate(errorMessage));
        activity.setFinishedTime(LocalDateTime.now());
        this.updateOne(activity);
    }

    @Override
    public void cancel(Long activityId, String note) {
        DesignActivity activity = load(activityId);
        activity.setStatus(DesignActivityStatus.CANCELED);
        activity.setErrorMessage(truncate(note));
        activity.setFinishedTime(LocalDateTime.now());
        this.updateOne(activity);
    }

    @Override
    public Long snapshot(Long activityId, JsonNode content) {
        DesignSnapshot snapshot = new DesignSnapshot();
        snapshot.setActivityId(activityId);
        snapshot.setContent(content);
        return snapshotService.createOne(snapshot);
    }

    @Override
    public AggregateChangeReport changeReport(Long activityId) {
        JsonNode changeSet = load(activityId).getChangeSet();
        if (changeSet == null || changeSet.isNull()) {
            return new AggregateChangeReport(List.of());
        }
        return AggregateChangeReport.from(JsonUtils.jsonNodeToObject(changeSet, CHANGE_SET_TYPE));
    }

    private DesignActivity load(Long activityId) {
        return this.getById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("DesignActivity {0} does not exist!", activityId));
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ERROR_MESSAGE_MAX_LENGTH ? text : text.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }
}
