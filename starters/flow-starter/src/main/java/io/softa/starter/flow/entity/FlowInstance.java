package io.softa.starter.flow.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Persistent flow execution instance entity.
 * Complex nested runtime state fields are stored as JSON strings.
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    versionLock = true,
    copyable = false
)
@Index(indexName = "uk_instance_id", fields = {"instanceId"}, unique = true)
@Index(indexName = "idx_tenant_flow_status", fields = {"tenantId", "flowId", "status"})
@Index(indexName = "idx_tenant_status_fire", fields = {"tenantId", "status", "nextFireAt"})
@Index(indexName = "idx_tenant_initiator_status", fields = {"tenantId", "initiatorId", "status"})
@Index(indexName = "idx_tenant_model_row", fields = {"tenantId", "modelName", "rowId"})
@Index(indexName = "uk_flow_instance_open_record", fields = {"tenantId", "modelName", "openRecordId"}, unique = true,
       message = "This record is already in approval.")
@EqualsAndHashCode(callSuper = true)
public class FlowInstance extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(description = "Optimistic-lock version for runtime state updates", required = true, defaultValue = "1")
    private Integer version;

    @Field(label = "Instance ID", required = true, description = "Runtime instance id (UUID)")
    private String instanceId;

    @Field(label = "Bundle ID", description = "Exact FlowBundle.id this instance was created from; "
            + "required to resolve the definition for approval/resume actions after a DB reload")
    private Long bundleId;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = FlowDesign.class, label = "Flow", description = "The flow this instance runs (stable flow handle)")
    private String flowId;

    @Field(cascadedField = "flowId.flowName", dynamic = true, readonly = true)
    private String flowName;

    @Field(description = "Published flow revision")
    private Integer flowRevision;

    @Field(length = 200, description = "Instance title")
    private String title;

    @Field(length = 100, description = "Related model name")
    private String modelName;

    @Field(label = "Row ID", description = "Related row data ID")
    private String rowId;

    @Field(label = "Initiator ID", description = "Flow initiator id")
    private String initiatorId;

    @Field(description = "Whether the instance is its record's approval: started by submitting the record, so its "
            + "result is written back to it")
    private Boolean recordApproval;

    @Field(label = "Open Record ID", description = "The record's id while this record approval is unfinished, empty "
            + "otherwise. Unique with the model in a tenant: a record has at most one approval in progress")
    private String openRecordId;

    @Field(required = true, description = "Execution status")
    private FlowExecutionStatus status;

    @Field(length = 2000, description = "Error message when execution fails")
    private String errorMessage;

    @Field(label = "Failed Node ID", length = 100, description = "Node where execution failed (set when status = Failed)")
    private String failedNodeId;

    @Field(fieldType = FieldType.TEXT, description = "Immutable trigger payload (JSON)")
    private String inputPayload;

    @Field(fieldType = FieldType.TEXT, description = "Execution variables (JSON)")
    private String variables;

    @Field(fieldType = FieldType.TEXT, description = "Active timer/async waits (JSON array); pending approvals are tracked separately")
    private String waitTokens;

    @Field(description = "Earliest due time across timer waits (denormalized from waitTokens for the sweep index)")
    private LocalDateTime nextFireAt;

    @Field(label = "Completed Node IDs", fieldType = FieldType.TEXT, description = "Completed node ids (JSON array)")
    private String completedNodeIds;

    @Field(fieldType = FieldType.TEXT, description = "Pending approvals (JSON array)")
    private String pendingApprovals;

    @Field(fieldType = FieldType.TEXT, description = "Parallel join arrival counts (JSON map)")
    private String joinArrivalCounts;

    @Field(fieldType = FieldType.TEXT, description = "Return data from ReturnValue nodes (JSON)")
    private String returnData;
}
