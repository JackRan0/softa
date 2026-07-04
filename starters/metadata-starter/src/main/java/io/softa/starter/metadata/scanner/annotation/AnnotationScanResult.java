package io.softa.starter.metadata.scanner.annotation;

import java.util.List;

import io.softa.starter.metadata.entity.*;

/**
 * Result of scanning the classpath for {@code @Model} / {@code @Field} /
 * {@code @OptionSet} / {@code @OptionItem} / {@code @Index} annotations.
 *
 * <p>Holds partially-populated Sys* entities (annotation-derived fields only;
 * {@code id} / {@code appId} / {@code modelFields} relations are DB-side and
 * left null).
 *
 * <p>Used as both the "from-code" input to {@code DiffEngine} and (after
 * translation) the "from-db" current state.
 */
public record AnnotationScanResult(
        List<SysModel> models,
        List<SysField> fields,
        List<SysOptionSet> optionSets,
        List<SysOptionItem> optionItems,
        List<SysModelIndex> modelIndexes,
        RenameDeclarations renames
) {

    /**
     * Convenience 5-arg constructor with no rename declarations — for the from-db
     * scan (the catalog carries no rename intent) and pre-{@code renames} call
     * sites. Delegates to the canonical 6-arg form with {@link RenameDeclarations#empty()}.
     */
    public AnnotationScanResult(
            List<SysModel> models,
            List<SysField> fields,
            List<SysOptionSet> optionSets,
            List<SysOptionItem> optionItems,
            List<SysModelIndex> modelIndexes) {
        this(models, fields, optionSets, optionItems, modelIndexes, RenameDeclarations.empty());
    }

    /**
     * Convenience 4-arg constructor with no indexes — preserves test call
     * sites pre-dating the {@code modelIndexes} addition. Delegates to the
     * canonical form with an empty index list + no renames.
     */
    public AnnotationScanResult(
            List<SysModel> models,
            List<SysField> fields,
            List<SysOptionSet> optionSets,
            List<SysOptionItem> optionItems) {
        this(models, fields, optionSets, optionItems, List.of(), RenameDeclarations.empty());
    }

    /** Empty result (used as scan baseline when there are no existing rows). */
    public static AnnotationScanResult empty() {
        return new AnnotationScanResult(List.of(), List.of(), List.of(), List.of(), List.of(),
                RenameDeclarations.empty());
    }

    /** True when no model / field / index / option-set / option-item was scanned. */
    public boolean isEmpty() {
        return models.isEmpty() && fields.isEmpty() && optionSets.isEmpty()
                && optionItems.isEmpty() && modelIndexes.isEmpty();
    }
}
