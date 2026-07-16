package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * SysOptionSetTrans — i18n translations of {@link SysOptionSet} display attributes.
 *
 * <p>Self-described via {@code @Model} + per-field {@code @Field}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "System Option Set Translation",
        businessKey = {"rowId", "languageCode"},
        description = "Translations for sys_option_set"
)
public class SysOptionSetTrans extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true)
    private String languageCode;

    @Field(label = "Row ID", required = true)
    private Long rowId;

    @Field
    private String label;

    @Field(length = 512)
    private String description;
}
