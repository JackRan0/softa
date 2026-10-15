package io.softa.starter.user.entity;

import java.io.Serial;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * How one person last arranged one list: its columns, and the fields of its Dynamic Export.
 *
 * <p>One row per person, tenant and list. Held on the server rather than in the browser because it
 * belongs to the account: a person who signs in on another machine expects the list they arranged.
 *
 * <p>Read and written only through {@code ListViewPreferenceController}'s own endpoints, which bind
 * the row to the caller. The model has no employee or department anchor, so the generic CRUD
 * endpoints would answer an ordinary role with nothing; and they are not registered to any
 * permission, so they stay closed to everyone but the admins' bypass.
 *
 * <p>The field lists hold field names and are never authority: they are filtered against what the
 * reader may read every time they are served, so a field whose sensitive set is withdrawn drops out
 * of the arrangement rather than coming back as a column or an export column.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "List View Preference",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true,
        description = "A person's own arrangement of a list: columns and Dynamic Export fields.")
@Index(indexName = "uk_list_view_preference_user_view", fields = {"tenantId", "userId", "viewKey"},
        unique = true, message = "This list already has a saved arrangement.")
public class ListViewPreference extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    /** The account the arrangement belongs to; always the caller's own. */
    @Field(label = "User ID", required = true)
    private Long userId;

    /** The list: view kind, model, route and tab, as the frontend keys its view state. */
    @Field(label = "View Key", required = true, length = 255)
    private String viewKey;

    /** The model the list shows — what the field names are checked against. */
    @Field(label = "Model", required = true, length = 64)
    private String modelName;

    /** Column ids in display order, as a JSON array. Empty means the list's own order. */
    @Field(label = "Column Order", fieldType = FieldType.TEXT)
    private String columnOrder;

    /** Column ids the person hid, as a JSON array. */
    @Field(label = "Hidden Columns", fieldType = FieldType.TEXT)
    private String hiddenColumns;

    /** How many leading columns are frozen. */
    @Field(label = "Freeze Column Index")
    private Integer freezeColumnIndex;

    /** Dynamic Export fields in export order, as a JSON array. */
    @Field(label = "Export Fields", fieldType = FieldType.TEXT)
    private String exportFields;

    /** Dynamic Export fields of each object hanging off the record, as a JSON object. */
    @Field(label = "Export Object Fields", fieldType = FieldType.TEXT)
    private String exportObjectFields;

    /**
     * The list's other view state, as a JSON object — whether its side tree is collapsed, and
     * whatever the list remembers next. Holds no field names: those live in the columns above, where
     * the server can check them against what the reader may read.
     */
    @Field(label = "View Settings", fieldType = FieldType.TEXT)
    private String viewSettings;
}
