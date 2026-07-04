package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * SysFilter Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "System Filter")
public class SysFilter extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true)
    private String name;

    @Field(length = 256, required = true)
    private Filters filters;

    @Field(required = true)
    private String model;

    @Field(length = 256)
    private String query;
}
