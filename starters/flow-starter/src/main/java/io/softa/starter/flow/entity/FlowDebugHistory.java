package io.softa.starter.flow.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Persistent debug execution history.
 * Stores a snapshot of the trigger event and full node trace for
 * each flow execution when debug mode is enabled.
 */
@Data
@Model(
    multiTenant = true,
    idStrategy = IdStrategy.DISTRIBUTED_LONG,
    copyable = false
)
@Index(fields = {"tenantId", "flowId"})
@Index(fields = {"tenantId", "instanceId"})
@EqualsAndHashCode(callSuper = true)
public class FlowDebugHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID", required = true)
    private Long tenantId;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = FlowDesign.class, label = "Flow")
    private String flowId;

    @Field
    private Integer flowRevision;

    @Field(label = "Instance ID")
    private String instanceId;

    @Field
    private FlowExecutionStatus status;

    @Field(label = "Initiator ID")
    private String initiatorId;

    @Field(label = "Parent Instance ID")
    private String parentInstanceId;

    @Field
    private LocalDateTime startTime;

    @Field
    private LocalDateTime endTime;

    @Field(label = "Duration (ms)")
    private Long durationMs;

    @Field(fieldType = FieldType.TEXT, description = "Trigger event message (JSON)")
    private String eventMessage;

    @Field(fieldType = FieldType.TEXT, description = "Full node execution trace (JSON)")
    private String nodeTrace;

    @Field(fieldType = FieldType.TEXT, description = "Final variables snapshot (JSON)")
    private String finalVariables;

    @Field(length = 2000, description = "Error message if execution failed")
    private String errorMessage;
}
