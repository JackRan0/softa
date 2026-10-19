package io.softa.starter.user.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * A list view arrangement as the frontend reads and writes it.
 *
 * <p>On save each part is optional: a null part is left as stored, so the Columns dialog, the export
 * dialog and the side tree each save their own part without carrying the others'.
 */
@Data
public class UserListViewPreferenceDTO {

    private String viewStateKey;

    private String modelName;

    /**
     * The columns the person shows, left to right. Once saved it is the list's columns for them: a
     * column the page adds later is not shown until they tick it, and one the page stops declaring
     * stays while the field does.
     */
    private List<Column> columns;

    private List<String> exportFields;

    /** Keyed by the collection field on the list's model; values are fields of its related model. */
    private Map<String, List<String>> exportObjectFields;

    /**
     * The list's other view state, such as {@code sidePanelCollapsed}. On save it is merged key by
     * key into what is stored — a key sent as null is removed — so one setting can be saved without
     * carrying the others.
     */
    private Map<String, Object> viewSettings;

    /** One shown column. */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Column {

        /** A field of the list's model ({@code code}), one level into a one-to-one record
         *  ({@code employeeProfileId.passType}), or a column of the list's own such as a pivot column. */
        private String id;

        /** True when frozen, otherwise absent. The list freezes up to the last frozen column it still
         *  shows, so a frozen column that goes does not pull the next one in. */
        private Boolean frozen;
    }
}
