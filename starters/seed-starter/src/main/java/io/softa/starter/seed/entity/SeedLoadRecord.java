package io.softa.starter.seed.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.seed.enums.SeedRecordStatus;

/**
 * One run of one seed file in one scope — the platform, or a tenant. Whether a file runs again is decided from
 * these: a scope runs a file it has no successful record of, and an overwriting file again once its content
 * changed since the last one.
 *
 * <p>Shared (not multi-tenant): {@code tenantId} names the tenant the file ran in, empty for the platform.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Seed Load Record", copyable = false, enableAuditLog = false)
@Index(fields = {"tenantId", "fileName"})
@Index(fields = {"batchId"})
public class SeedLoadRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Batch", fieldType = FieldType.MANY_TO_ONE, relatedModel = SeedLoadBatch.class)
    private Long batchId;

    @Field(label = "Tenant ID", description = "The tenant the file ran in; empty for the platform")
    private Long tenantId;

    @Field(length = 128)
    private String tenantName;

    @Field(required = true, length = 128)
    private String fileName;

    @Field(description = "SHA-256 of the file as it ran")
    private String checksum;

    @Field(description = "When the build that ran it was built, if known. An older build does not load the "
            + "file over it: that is a rollback putting older content over newer")
    private LocalDateTime buildTime;

    @Field(required = true)
    private SeedRecordStatus status;

    @Field(fieldType = FieldType.TEXT)
    private String errorMessage;

    @Field
    private LocalDateTime startTime;

    @Field
    private LocalDateTime endTime;
}
