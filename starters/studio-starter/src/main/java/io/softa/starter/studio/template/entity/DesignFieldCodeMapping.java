package io.softa.starter.studio.template.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * DesignFieldCodeMapping Model
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG)
@EqualsAndHashCode(callSuper = true)
public class DesignFieldCodeMapping extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true)
    private DesignCodeLang codeLang;

    @Field(required = true)
    private FieldType fieldType;

    @Field(required = true)
    private String propertyType;

    @Field(length = 256)
    private String description;

    @Field
    private Boolean deleted;
}
