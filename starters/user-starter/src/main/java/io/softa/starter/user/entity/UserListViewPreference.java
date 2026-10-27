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
import io.softa.framework.orm.enums.OnDelete;

/**
 * How one person last arranged one list view — a table, card or board: its columns, the fields of
 * its Dynamic Export, and its other view state such as whether its side tree is collapsed.
 *
 * <p>One row per account and list. Held on the server rather than in the browser because it belongs
 * to the person, not the machine. The account is per tenant, so the row is too.
 *
 * <p>Read and written only through {@code UserListViewPreferenceController}, which binds every call to
 * the caller. The model exposes no generic endpoints, and has no employee or department anchor a role
 * could scope it by.
 *
 * <p>The field lists hold field names and are never authority: they are filtered against what the
 * reader may read every time they are served or saved, so a field whose sensitive set is withdrawn
 * drops out of the arrangement rather than coming back as a column or an export column.
 *
 * <p>A row, for illustration:
 * <pre>
 * viewStateKey         table|Order|/sales/order|          (view kind | model | route | tab)
 * modelName            Order
 * columns              [{"id":"orderNo","frozen":true},{"id":"customer"},{"id":"amount"},
 *                       {"id":"customerId.creditLimit"}]
 * exportFields         ["orderNo","customer","amount"]
 * exportObjectFields   {"lines":["product","quantity","price"]}   (keyed by the collection field)
 * viewSettings         {"sidePanelCollapsed":true}
 * columnWidths         {"orderNo":12.5,"customer":24}            (percent of the table's width)
 * </pre>
 * A part left null is not arranged, and the list uses its own default for it; a row whose parts are
 * all null is deleted.
 *
 * <p>The view state key is opaque here — compared, never parsed. The client builds it as
 * {@code <view kind>|<model>|<route>|<tab>}: the kind of view ({@code table}, {@code card},
 * {@code board}), the model it lists, the page's path exactly as the browser has it, and the active
 * tab of a tabbed page (empty when the page has none, so the key then ends in {@code |}). Each
 * distinct key is a list of its own, with an arrangement of its own:
 * <pre>
 * table|Order|/sales/order|             the order list, a page without tabs
 * table|Order|/sales/order|pending      the same page's "pending" tab
 * table|Order|/sales/order|shipped      its "shipped" tab — another row
 * card|Order|/sales/order|              the same orders shown as cards — another row
 * board|Task|/projects/task|            a kanban board of tasks
 * table|OrderLine|/sales/order/42|      a list inside the page of order 42; the path carries the id,
 *                                       so the page of order 43 keeps an arrangement of its own
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "User List View Preference",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true,
        description = "A person's own arrangement of a list view: columns, Dynamic Export fields, view state.")
@Index(indexName = "uk_user_list_view_preference_user_view", fields = {"tenantId", "userId", "viewStateKey"},
        unique = true, message = "This list already has a saved arrangement.")
public class UserListViewPreference extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    /** The account the arrangement belongs to; always the caller's own. Goes with the account. */
    @Field(label = "User", fieldType = FieldType.MANY_TO_ONE, relatedModel = UserAccount.class,
            required = true, onDelete = OnDelete.CASCADE)
    private Long userId;

    /** The list view, as the frontend keys its view state: view kind, model, route and tab. */
    @Field(label = "View State Key", required = true, length = 255)
    private String viewStateKey;

    /** The model the list shows — what the field names are checked against. */
    @Field(label = "Model", required = true, length = 64)
    private String modelName;

    /**
     * The columns the person shows, left to right, as a JSON array of objects: {@code id}, and
     * {@code frozen} only when true. Null means not arranged: the list shows its own columns.
     *
     * <p>Once saved it is the list's columns for this person, whatever the page declares later: a
     * column the page adds is not shown until they tick it, and one it stops declaring stays while
     * the field does. Freezing is marked on the columns rather than counted, so a frozen column that
     * goes does not pull the next one in.
     */
    @Field(label = "Columns", fieldType = FieldType.TEXT)
    private String columns;

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

    /**
     * The widths the person dragged columns to, as a JSON object of column id to a share of the
     * table's width in percent. Kept apart from the columns: a list whose columns are not arranged
     * can still have its widths. Column ids, so filtered as the columns are.
     */
    @Field(label = "Column Widths", fieldType = FieldType.TEXT)
    private String columnWidths;
}
