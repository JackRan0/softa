package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * DesignOptionItemTrans Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Design Option Items Translation")
public class DesignOptionItemTrans extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true)
    private String languageCode;

    @Field(label = "Row ID")
    private Long rowId;

    @Field
    private String label;

    @Field(length = 512)
    private String description;
}
