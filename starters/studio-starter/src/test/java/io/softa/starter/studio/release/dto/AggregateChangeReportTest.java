package io.softa.starter.studio.release.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.studio.release.dto.AggregateChangeReport.AggregateChange;
import io.softa.starter.studio.release.dto.AggregateChangeReport.AttrChange;
import io.softa.starter.studio.release.dto.AggregateChangeReport.ChildChange;

/**
 * {@link AggregateChangeReport} groups a flat {@link RowChangeDTO} change set by aggregate root with
 * per-attribute before/after: UPDATE shows changed cols (before→after), CREATE has
 * {@code before=null}, DELETE has {@code after=null}, and a child-only change yields an aggregate with a
 * {@code null} root op.
 */
class AggregateChangeReportTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static RowChangeDTO change(RowChangeOp op, MetaTable table,
                                       Map<String, Object> fullRow, Map<String, Object> previous) {
        RowChangeDTO c = new RowChangeDTO();
        c.setOp(op);
        c.setTable(table);
        c.setFullRow(fullRow);
        if (previous != null) {
            c.setPreviousValuesForChangedFields(previous);
        }
        return c;
    }

    @Test
    @DisplayName("groups rows by aggregate root with per-attr before/after; child-only change → null root op")
    void projectsAggregateRootsWithBeforeAfter() {
        List<RowChangeDTO> changes = List.of(
                // Customer model UPDATE: label Customer -> Client.
                change(RowChangeOp.UPDATE, MetaTable.MODEL,
                        row("modelName", "Customer", "label", "Client", "tableName", "customer"),
                        row("label", "Customer")),
                // field email CREATE under Customer.
                change(RowChangeOp.CREATE, MetaTable.FIELD,
                        row("modelName", "Customer", "fieldName", "email", "fieldType", "STRING"), null),
                // field old DELETE under Customer.
                change(RowChangeOp.DELETE, MetaTable.FIELD,
                        row("modelName", "Customer", "fieldName", "old", "fieldType", "STRING"), null),
                // option-item UPDATE under set "tier" whose set row itself did NOT change.
                change(RowChangeOp.UPDATE, MetaTable.OPTION_ITEM,
                        row("optionSetCode", "tier", "itemCode", "gold", "label", "Gold"),
                        row("label", "GOLD")));

        AggregateChangeReport report = AggregateChangeReport.from(changes);
        assertEquals(2, report.aggregates().size());

        // --- Customer model aggregate: root UPDATE + two field children.
        AggregateChange customer = aggregate(report, "Customer");
        assertEquals(MetaTable.MODEL, customer.aggregateKind());
        assertEquals(RowChangeOp.UPDATE, customer.op());
        AttrChange label = attr(customer.attrChanges(), "label");
        assertEquals("Customer", label.before());
        assertEquals("Client", label.after());
        assertEquals(2, customer.children().size());

        ChildChange email = child(customer, "email");
        assertEquals(RowChangeOp.CREATE, email.op());
        assertNull(attr(email.attrChanges(), "fieldName").before(), "CREATE has no before");
        assertEquals("email", attr(email.attrChanges(), "fieldName").after());

        ChildChange old = child(customer, "old");
        assertEquals(RowChangeOp.DELETE, old.op());
        assertEquals("old", attr(old.attrChanges(), "fieldName").before());
        assertNull(attr(old.attrChanges(), "fieldName").after(), "DELETE has no after");

        // --- tier option-set aggregate: only a child changed → null root op, empty root attrs.
        AggregateChange tier = aggregate(report, "tier");
        assertEquals(MetaTable.OPTION_SET, tier.aggregateKind());
        assertNull(tier.op(), "root row itself did not change");
        assertTrue(tier.attrChanges().isEmpty());
        assertEquals(1, tier.children().size());
        ChildChange gold = tier.children().getFirst();
        assertEquals(MetaTable.OPTION_ITEM, gold.childKind());
        assertEquals("gold", gold.businessKey());
        assertEquals("GOLD", attr(gold.attrChanges(), "label").before());
        assertEquals("Gold", attr(gold.attrChanges(), "label").after());
    }

    private static AggregateChange aggregate(AggregateChangeReport report, String businessKey) {
        return report.aggregates().stream()
                .filter(a -> businessKey.equals(a.businessKey()))
                .findFirst().orElseThrow();
    }

    private static ChildChange child(AggregateChange aggregate, String businessKey) {
        return aggregate.children().stream()
                .filter(c -> businessKey.equals(c.businessKey()))
                .findFirst().orElseThrow();
    }

    private static AttrChange attr(List<AttrChange> attrs, String name) {
        return attrs.stream()
                .filter(a -> name.equals(a.attr()))
                .findFirst().orElseThrow();
    }
}
