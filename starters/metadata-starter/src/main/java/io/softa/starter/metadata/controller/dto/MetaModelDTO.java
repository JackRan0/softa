package io.softa.starter.metadata.controller.dto;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.orm.domain.Orders;

/**
 * MetaModelDTO
 */
@Data
@Schema(name = "MetaModelDTO")
public class MetaModelDTO {
    private String label;
    private String modelName;
    private String description;
    private List<String> displayName;
    private List<String> searchName;
    private Orders defaultOrder;
    private boolean timeline;
    /** Optimistic-locking switch — true ⇒ update submissions must carry the record's version. */
    private boolean versionLock;
    /** Model-level copy switch — false ⇒ hide the Duplicate action and reject copy APIs. */
    private boolean copyable;
    /** Change-log switch — false ⇒ the model keeps no record history, so hide the Audit Log panel. */
    private boolean enableAuditLog;
    /** Read-only projection over a table another model owns — true ⇒ hide create/edit/delete, writes are rejected. */
    private boolean projection;
    private Map<String, MetaFieldDTO> modelFields;
}
