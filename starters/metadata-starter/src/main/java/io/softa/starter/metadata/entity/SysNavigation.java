package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * SysNavigation Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "System Navigation")
public class SysNavigation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String appCode;

    @Field(required = true)
    private String name;

    @Field(required = true)
    private String type;

    @Field(required = true)
    private String code;

    @Field(length = 256)
    private String modelName;

    @Field(label = "Parent ID")
    private Long parentId;

    @Field(length = 256)
    private String description;

    @Field(length = 256)
    private String filter;
}
