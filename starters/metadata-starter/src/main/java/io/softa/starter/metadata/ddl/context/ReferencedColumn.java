package io.softa.starter.metadata.ddl.context;

import io.softa.framework.orm.enums.FieldType;

/**
 * The physical shape of a column another column references — the minimal slice
 * ({@code fieldType} / {@code length} / {@code scale}) a TO_ONE foreign key needs
 * to mirror so it renders the same SQL type as the column it points at.
 *
 * <p>Lane-agnostic: the annotation lane builds it from {@code SysField}, the studio
 * lane from {@code DesignField}, and both feed {@link io.softa.starter.metadata.ddl.ReferenceColumnResolver}.
 */
public record ReferencedColumn(FieldType fieldType, Integer length, Integer scale) {}
