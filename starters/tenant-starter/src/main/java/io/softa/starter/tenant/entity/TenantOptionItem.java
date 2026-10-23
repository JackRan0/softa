package io.softa.starter.tenant.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.OptionItemIcon;
import io.softa.framework.base.enums.OptionItemTone;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * A member of a tenant-authored {@link TenantOptionSet} — the tenant-scoped counterpart of
 * {@code SysOptionItem}.
 * <p>
 * Carries both the owning set's business code ({@code optionSetCode}, half of the business key) and
 * the surrogate FK ({@code optionSetId}). Writers set the FK; the code is a stored cascade of it,
 * filled by the framework on every write, so a form that picks the set — or a copy of an existing
 * item — never has to know the code, and the two can never disagree. {@code parentItemId} is the
 * self-reference behind hierarchical option sets.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Tenant Option Item",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true,
        activeControl = true,
        businessKey = {"optionSetCode", "itemCode"},
        displayName = {"label"},
        searchName = {"itemCode", "label"},
        defaultOrder = {"optionSetCode:asc", "sequence:asc"},
        description = "Members of tenant-authored option sets"
)
@Index(indexName = "uk_tenant_option_item", fields = {"tenantId", "optionSetCode", "itemCode"},
        unique = true, message = "This item code is already used in the option set.")
public class TenantOptionItem extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "Owning tenant. Auto-stamped and isolated by the ORM on every write / read.")
    private Long tenantId;

    @Field(cascadedField = "optionSetId.optionSetCode",
            description = "Owning option set's business code, mirrored from optionSetId on every write")
    private String optionSetCode;

    @Field(label = "Option Set ID", fieldType = FieldType.MANY_TO_ONE,
            relatedModel = TenantOptionSet.class, required = true)
    private Long optionSetId;

    @Field(label = "Parent Item ID", fieldType = FieldType.MANY_TO_ONE,
            relatedModel = TenantOptionItem.class,
            description = "Parent item for hierarchical option sets; null at the top level")
    private Long parentItemId;

    @Field(description = "Display order within the option set")
    private Integer sequence;

    @Field(required = true, description = "Stored value, unique within the option set")
    private String itemCode;

    @Field(required = true)
    private String label;

    @Field
    private OptionItemTone itemTone;

    @Field
    private OptionItemIcon itemIcon;

    @Field(length = 512)
    private String description;

    @Field
    private Boolean active;
}
