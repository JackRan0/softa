package io.softa.starter.studio.template.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * DesignCodeTemplate Model
 */
@Data
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG)
@EqualsAndHashCode(callSuper = true)
public class DesignCodeTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private DesignCodeLang codeLang;

    @Field
    private Integer sequence;

    @Field
    private String subDirectory;

    @Field
    private String fileName;

    @Field(length = 20000)
    private String templateContent;

    @Field(length = 256)
    private String description;

    @Field
    private Boolean deleted;
}
