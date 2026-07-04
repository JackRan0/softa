package io.softa.starter.studio.release.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.softa.starter.metadata.checksum.AggregateChecksum;
import io.softa.starter.metadata.dto.MetaTable;

/**
 * Aggregate-root-grouped before/after view of a flat {@link RowChangeDTO} change set:
 * "which aggregate root, which field/attr/option changed, before → after". A <b>derived, on-demand
 * projection</b> — the persisted flat change set stays the single source of truth — same philosophy as
 * {@link DesignMetaTables#group}, but grouped by <b>aggregate root</b> (a Model + its fields/indexes;
 * an OptionSet + its items) instead of by table.
 *
 * <p>Drives the change-report read API for all four activities (PUBLISH / IMPORT / REVERSE / MERGE —
 * whose change sets are uniform {@code List<RowChangeDTO>}) and the runtime-drift
 * preview.
 *
 * <p>The before/after attribute set is the <b>same checksum allow-list</b> the differ uses
 * ({@link AggregateChecksum#MODEL_ATTRS} etc. — keys + data, id/appCode/active/deleted
 * excluded), so the report shows exactly the attributes that count as a change and never drifts from a
 * parallel list. CREATE → {@code before=null}; DELETE → {@code after=null}; UPDATE → only the changed
 * columns (the differ already filtered them into {@code previousValuesForChangedFields}).
 */
public record AggregateChangeReport(List<AggregateChange> aggregates) {

    /** One aggregate root (Model or OptionSet) and the changes to it and its children. */
    public record AggregateChange(
            MetaTable aggregateKind,        // MODEL or OPTION_SET
            String businessKey,             // modelName / optionSetCode (the identity; no logicalId)
            RowChangeOp op,                 // root row op; null when only children changed
            List<AttrChange> attrChanges,   // root-row attribute deltas
            List<ChildChange> children) {
    }

    /** One child (field / index / option-item) change within an aggregate. */
    public record ChildChange(
            MetaTable childKind,            // FIELD / INDEX / OPTION_ITEM
            String businessKey,             // fieldName / indexName / itemCode
            RowChangeOp op,
            List<AttrChange> attrChanges) {
    }

    /** One attribute's before → after (CREATE: before=null; DELETE: after=null). */
    public record AttrChange(String attr, Object before, Object after) {
    }

    // Business-key / parent-key column names, derived once in MetaKeys from the Sys* getters (single source —
    // a rename breaks compilation, never silently mis-keys; same approach as DesignAggregateDiffer).
    private static final String MODEL_NAME = MetaKeys.MODEL_NAME;
    private static final String OPTION_SET_CODE = MetaKeys.OPTION_SET_CODE;
    private static final String FIELD_NAME = MetaKeys.FIELD_NAME;
    private static final String INDEX_NAME = MetaKeys.INDEX_NAME;
    private static final String ITEM_CODE = MetaKeys.ITEM_CODE;

    /**
     * Project a flat change set into aggregate-root groups. Root rows (MODEL / OPTION_SET) seed an
     * aggregate; child rows (FIELD / INDEX / OPTION_ITEM) attach to their parent aggregate located by the
     * parent business key carried in the child row, creating an aggregate entry with a {@code null} root
     * op when only the children changed. First-seen order is preserved.
     */
    public static AggregateChangeReport from(List<RowChangeDTO> changes) {
        Map<AggKey, Group> groups = new LinkedHashMap<>();
        for (RowChangeDTO row : changes) {
            MetaTable table = row.getTable();
            MetaTable parent = parentKind(table);
            if (parent == null) {                                     // a root row
                String key = str(row.getFullRow().get(businessKeyAttr(table)));
                groups.computeIfAbsent(new AggKey(table, key), k -> new Group(table, key)).root = row;
            } else {                                                  // a child row
                String parentKey = str(row.getFullRow().get(parentKeyAttr(table)));
                groups.computeIfAbsent(new AggKey(parent, parentKey), k -> new Group(parent, parentKey))
                        .children.add(row);
            }
        }
        List<AggregateChange> out = new ArrayList<>(groups.size());
        for (Group g : groups.values()) {
            out.add(g.build());
        }
        return new AggregateChangeReport(out);
    }

    // ----------------------------------------------------------------- internals

    private record AggKey(MetaTable kind, String businessKey) {
    }

    private static final class Group {
        private final MetaTable kind;
        private final String businessKey;
        private RowChangeDTO root;
        private final List<RowChangeDTO> children = new ArrayList<>();

        private Group(MetaTable kind, String businessKey) {
            this.kind = kind;
            this.businessKey = businessKey;
        }

        private AggregateChange build() {
            List<ChildChange> childChanges = new ArrayList<>(children.size());
            for (RowChangeDTO c : children) {
                childChanges.add(new ChildChange(c.getTable(),
                        str(c.getFullRow().get(businessKeyAttr(c.getTable()))),
                        c.getOp(), attrChangesOf(c)));
            }
            return new AggregateChange(kind, businessKey,
                    root == null ? null : root.getOp(),
                    root == null ? List.of() : attrChangesOf(root),
                    childChanges);
        }
    }

    /** Per-attribute before/after for one row, over the table's checksum allow-list. */
    private static List<AttrChange> attrChangesOf(RowChangeDTO row) {
        List<String> allow = allowList(row.getTable());
        Map<String, Object> full = row.getFullRow();
        List<AttrChange> out = new ArrayList<>();
        switch (row.getOp()) {
            case CREATE -> {
                for (String attr : allow) {
                    if (full.containsKey(attr)) {
                        out.add(new AttrChange(attr, null, full.get(attr)));
                    }
                }
            }
            case DELETE -> {
                for (String attr : allow) {
                    if (full.containsKey(attr)) {
                        out.add(new AttrChange(attr, full.get(attr), null));
                    }
                }
            }
            case UPDATE -> {
                // Only the changed columns — the differ already filtered them (allow-listed) into the
                // sparse previousValuesForChangedFields map.
                for (Map.Entry<String, Object> e : row.getPreviousValuesForChangedFields().entrySet()) {
                    out.add(new AttrChange(e.getKey(), e.getValue(), full.get(e.getKey())));
                }
            }
        }
        return out;
    }

    private static List<String> allowList(MetaTable table) {
        return switch (table) {
            case MODEL -> AggregateChecksum.MODEL_ATTRS;
            case FIELD -> AggregateChecksum.FIELD_ATTRS;
            case INDEX -> AggregateChecksum.INDEX_ATTRS;
            case OPTION_SET -> AggregateChecksum.OPTION_SET_ATTRS;
            case OPTION_ITEM -> AggregateChecksum.OPTION_ITEM_ATTRS;
        };
    }

    private static String businessKeyAttr(MetaTable table) {
        return switch (table) {
            case MODEL -> MODEL_NAME;
            case FIELD -> FIELD_NAME;
            case INDEX -> INDEX_NAME;
            case OPTION_SET -> OPTION_SET_CODE;
            case OPTION_ITEM -> ITEM_CODE;
        };
    }

    /** The parent aggregate root kind of a child table, or {@code null} if the table is itself a root. */
    private static MetaTable parentKind(MetaTable table) {
        return switch (table) {
            case FIELD, INDEX -> MetaTable.MODEL;
            case OPTION_ITEM -> MetaTable.OPTION_SET;
            case MODEL, OPTION_SET -> null;
        };
    }

    /** The column in a child row that names its parent aggregate's business key. */
    private static String parentKeyAttr(MetaTable table) {
        return switch (table) {
            case FIELD, INDEX -> MODEL_NAME;
            case OPTION_ITEM -> OPTION_SET_CODE;
            case MODEL, OPTION_SET -> null;
        };
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
