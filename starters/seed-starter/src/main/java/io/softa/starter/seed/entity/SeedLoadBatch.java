package io.softa.starter.seed.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.seed.enums.SeedLoadStatus;
import io.softa.starter.seed.enums.SeedLoadType;

/**
 * One seed data operation: a platform load, a tenant run, a tenant's setup, or the one-time initialization of
 * an existing environment. What it did file by file is in its {@link SeedLoadRecord}s.
 *
 * <p>Shared (not multi-tenant): written by the platform, about the platform and every tenant.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Seed Load Batch", defaultOrder = "id:desc", copyable = false, enableAuditLog = false)
public class SeedLoadBatch extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true)
    private SeedLoadType type;

    @Field(required = true)
    private SeedLoadStatus status;

    @Field(label = "Tenant IDs", fieldType = FieldType.TEXT,
            description = "The tenants a tenant run was limited to, comma-separated; empty for every tenant")
    private String tenantIds;

    @Field
    private Integer totalTenants;

    @Field
    private Integer finishedTenants;

    @Field
    private Integer failedTenants;

    @Field(description = "Files run, over every scope the batch reached")
    private Integer fileCount;

    @Field(fieldType = FieldType.TEXT)
    private String errorMessage;

    @Field
    private LocalDateTime startTime;

    @Field
    private LocalDateTime endTime;

    @Field(description = "Moved on after each tenant; a running batch that stops moving was interrupted")
    private LocalDateTime lastProgressTime;

    @Field
    private String appVersion;

    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = SeedLoadRecord.class, relatedField = "batchId")
    private List<SeedLoadRecord> records;
}
