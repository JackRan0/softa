package io.softa.starter.metadata.ddl;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.ddl.context.ReferencedColumn;
import io.softa.starter.metadata.entity.SysField;

/**
 * Materializes the "FK column = referenced column" invariant at <b>reconciliation time</b>:
 * every TO_ONE foreign key (MANY_TO_ONE / ONE_TO_ONE) physically mirrors the column it references
 * (the {@code relatedField} on {@code relatedModel}, or its {@code id} when blank). The resolved
 * physical type is stamped onto {@code relatedFieldType} and the resolved width onto
 * {@code length}/{@code scale}; the logical {@code fieldType} stays the relation type.
 *
 * <p>Single source of truth for the rule, shared by both lanes: the annotation lane stamps the
 * whole {@code SysField} set here ({@link #stampSysFields}); the studio lane stamps a saved
 * {@code DesignField} via {@link #resolveReferenced} with a query-backed lookup. Because it runs
 * over the full field set each reconciliation, a referenced-column change re-stamps its dependents
 * and the normal diff emits the dependent FKs' {@code MODIFY}.
 */
@Slf4j
public final class ReferenceColumnResolver {

    private ReferenceColumnResolver() {}

    /**
     * Annotation-lane stamp: mirror each TO_ONE FK's referenced column onto its
     * {@code relatedFieldType} / {@code length} / {@code scale}, in place, over the full field set.
     *
     * <p>A single linear pass is sufficient: a {@code relatedField} must be {@code id} or a stored
     * single-column business key (enforced by {@code ModelManager.validateRelationalField}), whose
     * own {@code fieldType} is always a scalar — never another FK — so the lookup (which reads the
     * referenced field's {@code fieldType}, not its {@code relatedFieldType}) is order-independent.
     */
    public static void stampSysFields(List<SysField> fieldsToStamp) {
        stampSysFields(fieldsToStamp, fieldsToStamp);
    }

    /**
     * Stamp {@code fieldsToStamp} while resolving referenced columns against a (possibly wider)
     * {@code referenceUniverse}. Under a partial {@code scanner-scope}, an in-scope FK may reference
     * an out-of-scope model: passing the full platform catalog as the universe (with the in-scope
     * code fields taking precedence — i.e. listed last) lets the FK still mirror its stored column,
     * instead of resetting {@code relatedFieldType} to null and churning a spurious {@code MODIFY}.
     */
    public static void stampSysFields(List<SysField> fieldsToStamp, Collection<SysField> referenceUniverse) {
        if (fieldsToStamp == null || fieldsToStamp.isEmpty()) {
            return;
        }
        Map<String, Map<String, ReferencedColumn>> referenceColumns = new HashMap<>();
        for (SysField field : referenceUniverse) {
            if (field.getModelName() == null || field.getFieldName() == null || field.getFieldType() == null) {
                continue;
            }
            referenceColumns.computeIfAbsent(field.getModelName(), key -> new HashMap<>())
                    .put(field.getFieldName(),
                            new ReferencedColumn(field.getFieldType(), field.getLength(), field.getScale()));
        }
        for (SysField field : fieldsToStamp) {
            ReferencedColumn referenced = resolveReferenced(
                    field.getFieldType(), field.getRelatedModel(), field.getRelatedField(),
                    (model, name) -> {
                        Map<String, ReferencedColumn> columns = referenceColumns.get(model);
                        return columns == null ? null : columns.get(name);
                    },
                    field.getFieldName());
            if (referenced != null) {
                field.setRelatedFieldType(referenced.fieldType());
                field.setLength(referenced.length());
                field.setScale(referenced.scale());
            }
        }
    }

    /**
     * The shared rule. Returns the {@link ReferencedColumn} a TO_ONE FK must mirror — the related
     * model's {@code id} column (TO_ONE relations join on id only, so the FK column mirrors
     * the referenced id: a String code-as-id id → {@code VARCHAR}, a Long surrogate → {@code BIGINT}) —
     * or {@code null} when this field is not a resolvable FK.
     *
     * @param lookup sources the referenced column: {@code (relatedModel, relatedField) -> column}.
     *               Each lane supplies its own backing (annotation: an in-memory map; studio: a
     *               targeted query).
     */
    public static ReferencedColumn resolveReferenced(
            FieldType fieldType, String relatedModel, String relatedField,
            BiFunction<String, String, ReferencedColumn> lookup, String fieldNameForLog) {
        if (fieldType == null || !FieldType.TO_ONE_TYPES.contains(fieldType) || relatedModel == null) {
            return null;
        }
        // A non-id relatedField is rejected at ModelManager.init, so this is always `id`.
        String relatedFieldName = relatedField != null ? relatedField : ModelConstant.ID;
        ReferencedColumn referenced = lookup.apply(relatedModel, relatedFieldName);
        if (referenced == null || referenced.fieldType() == null) {
            log.debug("FK {} -> {}.{} could not resolve the referenced id column; "
                            + "rendering the default relation type until re-stamped.",
                    fieldNameForLog, relatedModel, relatedFieldName);
            return null;
        }
        return referenced;
    }
}
