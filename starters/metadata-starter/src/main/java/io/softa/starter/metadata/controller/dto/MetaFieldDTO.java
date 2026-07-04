package io.softa.starter.metadata.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.enums.WidgetType;

/**
 * MetaFieldDTO
 */
@Data
@Schema(name = "MetaFieldDTO")
public class MetaFieldDTO {
    private String label;
    private String fieldName;
    private String modelName;
    private FieldType fieldType;
    private String description;

    private Boolean required;
    private Integer length;
    private Integer scale;
    private Object defaultValue;
    private Boolean readonly;
    private Boolean hidden;
    private Boolean translatable;
    private Boolean copyable;
    private Boolean unsearchable;
    private Boolean computed;
    private Boolean dynamic;
    private Boolean encrypted;

    private String optionSetCode;
    private String relatedModel;
    private String relatedField;
    private String joinModel;
    private String joinLeft;
    private String joinRight;
    private String cascadedField;

    private String filters;
    private MaskingType maskingType;
    private WidgetType widgetType;
    private OnDelete onDelete;
}
