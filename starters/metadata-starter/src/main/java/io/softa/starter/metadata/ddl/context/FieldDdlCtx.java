package io.softa.starter.metadata.ddl.context;

import lombok.Data;

import io.softa.framework.orm.enums.FieldType;

/**
 * Field-level DDL context passed to templates.
 */
@Data
public class FieldDdlCtx {
    private String fieldName;
    private String columnName;
    private String oldColumnName;
    private boolean renamed;
    private String label;
    private String description;
    private FieldType fieldType;
    // The physical FK type/length for a TO_ONE relation is pre-resolved upstream into
    // relatedFieldType (+ length/scale) and folded into fieldType by the context builders,
    // so no relation-identity context is needed here at render time.
    private String dbType;
    private String typeDeclaration;
    private Integer length;
    private Integer scale;
    private boolean required;
    private boolean autoIncrement;
    /** The declared default as a raw VALUE (never SQL) — see {@link #defaultValueLiteral}. */
    private String defaultValue;
    /**
     * The {@code defaultValue} rendered as a SQL literal for the DEFAULT clause
     * (quoted/escaped for text types, validated for numeric/boolean, expression
     * keywords passed through). Derived by the dialect at render time
     * ({@code DefaultValueLiterals}); templates interpolate this, never the raw value.
     */
    private String defaultValueLiteral;
}
