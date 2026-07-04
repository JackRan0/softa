package io.softa.starter.studio.release.ddl;

import java.util.List;

import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.studio.release.dto.RowChangeDTO;

/**
 * Renders rename-aware DDL (table structure + indexes) from a flat list of metadata
 * {@link RowChangeDTO row changes}. The flat list is regrouped per meta-table internally via
 * {@link io.softa.starter.studio.release.dto.DesignMetaTables#group}; a row's {@code op} drives
 * create / alter / drop and its {@code previousValuesForChangedFields} the changed-column gate.
 *
 * <p>A TO_ONE foreign key's physical column type is resolved at reconciliation time and stored on the
 * {@code DesignField} ({@code relatedFieldType} + {@code length}/{@code scale}); it flows through the
 * change rows here and is read straight from the field context — no cross-model lookup at render.
 */
public interface MetadataChangeDdlRenderer {

    /**
     * Render structured DDL (table + index) through a caller-supplied dialect — the publish entry point.
     *
     * <p>The {@link DdlDialect} is chosen by the target's
     * {@link io.softa.starter.studio.release.connector.Connector}: a Softa runtime renders on
     * the builtin annotation dialect — identical to the boot scanner — rather than the
     * {@code design_*}-backed Spring registry.
     *
     * @param dialect DDL dialect for the target database flavor
     * @param changes flat list of metadata row changes
     * @return structured DDL result
     */
    DdlRenderResult generateDdlResult(DdlDialect dialect, List<RowChangeDTO> changes);
}
