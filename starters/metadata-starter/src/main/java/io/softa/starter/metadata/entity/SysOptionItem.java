package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.base.enums.OptionItemIcon;
import io.softa.framework.base.enums.OptionItemTone;
import io.softa.framework.orm.enums.FieldType;

/**
 * SysOptionItem — metadata catalog row describing an OptionSet member.
 *
 * <p>Self-described via {@code @Model} + per-field {@code @Field}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "System Option Item",
        activeControl = true,
        businessKey = {"optionSetCode", "itemCode"},
        description = "Metadata catalog of option items"
)
public class SysOptionItem extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String appCode;

    // The owning option set's business code — a plain attribute (half of businessKey) and the column
    // the post-scan populator joins on to resolve optionSetId.
    @Field(required = true)
    private String optionSetCode;

    // Surrogate FK to the owning option set. relatedField defaults to id (BIGINT). Nullable
    // and EXCLUDED from the scanner diff: resolved post-scan from optionSetCode — see SysReferenceSql.
    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = SysOptionSet.class)
    private Long optionSetId;

    @Field
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

    @Field(length = 512)
    private String description;

    @Field
    private Boolean active;
}
