package io.softa.starter.user.dto;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * A list arrangement as the frontend reads and writes it.
 *
 * <p>On save each part is optional: a null part is left as stored, so the Columns dialog and the
 * export dialog can each save their own half without carrying the other's.
 */
@Data
public class ListViewPreferenceDTO {

    private String viewKey;

    private String modelName;

    private List<String> columnOrder;

    private List<String> hiddenColumns;

    private Integer freezeColumnIndex;

    private List<String> exportFields;

    /** Keyed by the collection field on the list's model; values are fields of its related model. */
    private Map<String, List<String>> exportObjectFields;

    /**
     * The list's other view state, such as {@code sidePanelCollapsed}. On save it is merged key by
     * key into what is stored — a key sent as null is removed — so one setting can be saved without
     * carrying the others.
     */
    private Map<String, Object> viewSettings;
}
