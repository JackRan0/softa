package io.softa.starter.message.dlq.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.dlq.enums.DeadLetterStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import tools.jackson.databind.JsonNode;

import java.io.Serial;

/**
 * Dead Letter Message Model
 */
@Data
@Schema(name = "DeadLetterMessage")
@EqualsAndHashCode(callSuper = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DeadLetterMessage extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Source Tenant Id")
    private Long sourceTenantId;

    @Schema(description = "Original Topic")
    private String originalTopic;

    @Schema(description = "DLQ Topic")
    private String dlqTopic;

    @Schema(description = "Subscription Name")
    private String subscriptionName;

    @Schema(description = "Event Type")
    private String eventType;

    @Schema(description = "Event Id")
    private Long eventId;

    @Schema(description = "Payload")
    private JsonNode payload;

    @Schema(description = "Status")
    private DeadLetterStatus status;

    @Schema(description = "Last Error Msg")
    private String lastErrorMsg;

    @Schema(description = "Resolved Remark")
    private String resolvedRemark;
}
