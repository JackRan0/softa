package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * DesignOptionSet Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        activeControl = true,
        copyable = false,   // copy disabled (would clone the per-env business key) — see DesignModel.
        // envId scopes the businessKey (see DesignModel).
        businessKey = {"envId", "optionSetCode"}
)
public class DesignOptionSet extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "App ID")
    private Long appId;

    // Per-env design: envId scopes the row (NOT NULL, V19). Identity = per-env business key
    // (env_id + optionSetCode); no logicalId.
    @Field(label = "Env ID")
    private Long envId;

    @Field(required = true)
    private String label;

    @Field(required = true)
    private String optionSetCode;

    /** Single immediately-prior option-set code for a declared rename; excluded from checksum/diff. */
    @Field
    private String renamedFrom;

    // Studio is id-based (rename-stable): optionSetId stores the parent's id, so renaming the
    // option-set code never orphans items. relatedField points at that id column → join on parent.id.
    @Field(fieldType = FieldType.ONE_TO_MANY,
            relatedModel = DesignOptionItem.class, relatedField = "optionSetId")
    private List<DesignOptionItem> optionItems;

    @Field(length = 256)
    private String description;

    @Field
    private Boolean active;
}
