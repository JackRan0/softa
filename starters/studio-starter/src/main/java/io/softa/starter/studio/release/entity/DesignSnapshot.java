package io.softa.starter.studio.release.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * DesignSnapshot — the full per-env design set ({@code DesignRows}) captured after a
 * {@link DesignActivity}, one-to-(zero-or-)one with its activity. Lets
 * {@code restore(activityId)} replay a prior design state back onto the env. A plain audit record
 * (no per-env converge identity) — just a distributed surrogate id + the {@code activityId} link.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        copyable = false
)
@Index(indexName = "uk_design_snapshot_activity", fields = {"activityId"}, unique = true)
public class DesignSnapshot extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Activity ID", required = true, description = "The DesignActivity that produced this snapshot")
    private Long activityId;

    @Field(description = "Full per-env design set (DesignRows) captured after the activity")
    private JsonNode content;
}
