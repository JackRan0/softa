package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.base.enums.OptionItemIcon;
import io.softa.framework.base.enums.OptionItemTone;

/**
 * DesignOptionItem Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Design Option Items",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        // hard delete (no softDelete) — lets the per-env UNIQUE(env_id, …) index work. The
        // plain `active` flag (item enable/disable) is orthogonal and unaffected. See DesignModel.
        copyable = false,   // copy disabled (would clone the per-env business key) — see DesignModel.
        // envId scopes the businessKey (see DesignModel).
        businessKey = {"envId", "optionSetCode", "itemCode"},
        displayName = {"itemCode", "label"},
        defaultOrder = {"optionSetCode", "sequence"}
)
public class DesignOptionItem extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "App ID")
    private Long appId;

    @Field(label = "Option Set ID")
    private Long optionSetId;

    // Per-env design: envId scopes the row (NOT NULL, V19). Identity = per-env business key
    // (env_id + optionSetCode + itemCode); no logicalId.
    @Field(label = "Env ID")
    private Long envId;

    @Field(required = true)
    private String optionSetCode;

    @Field(required = true)
    private Integer sequence;

    @Field(required = true)
    private String itemCode;

    /** Single immediately-prior item code for a declared rename; excluded from checksum/diff. */
    @Field
    private String renamedFrom;

    @Field(required = true)
    private String label;

    @Field
    private String parentItemCode;

    @Field
    private OptionItemTone itemTone;

    @Field
    private OptionItemIcon itemIcon;

    @Field(length = 256)
    private String description;

    @Field
    private Boolean active;
}
