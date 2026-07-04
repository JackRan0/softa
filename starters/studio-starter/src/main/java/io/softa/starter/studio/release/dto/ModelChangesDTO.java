package io.softa.starter.studio.release.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A per-meta-table, op-bucketed <b>derived view</b> of the flat {@link RowChangeDTO} diff — created /
 * updated / deleted rows for one {@code Design*} model. Not the source of truth and never held as
 * state: it is materialized on demand by {@link DesignMetaTables#group} for the consumers that render
 * or write per table (the DDL renderer, env↔env merge, drift report). The flat
 * {@code List<RowChangeDTO>} stays canonical.
 */
@Data
@NoArgsConstructor
public class ModelChangesDTO {

    private String modelName;
    private List<RowChangeDTO> createdRows = new ArrayList<>();
    private List<RowChangeDTO> updatedRows = new ArrayList<>();
    private List<RowChangeDTO> deletedRows = new ArrayList<>();

    public ModelChangesDTO(String modelName) {
        this.modelName = modelName;
    }

    public void addCreatedRow(RowChangeDTO rowChangeDTO) {
        createdRows.add(rowChangeDTO);
    }

    public void addUpdatedRow(RowChangeDTO rowChangeDTO) {
        updatedRows.add(rowChangeDTO);
    }

    public void addDeletedRow(RowChangeDTO rowChangeDTO) {
        deletedRows.add(rowChangeDTO);
    }

}