package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.utils.ReflectTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Child rows created together with their parent reach the child model's create already linked.
 *
 * <p>The child's own create runs its field processors on those rows — a sequence among them — and some
 * of them read the link: a code prefixed with the company abbreviation finds the company of a child
 * through its parent ({@code customerId.companyId}), starting from the parent id on the row. So the id
 * has to be on the row when the child create is called, not written back afterwards. The parent was
 * inserted a moment earlier in the same transaction, which is what lets that lookup find it.
 */
class OneToManyCreateLinksParentTest {

    private static final String MAIN = "Customer";
    private static final String RELATED = "CustomerAddress";
    private static final String FIELD = "addresses";
    private static final String BACK_REF = "customerId";

    private static MetaField oneToMany() {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(MAIN);
        when(field.getFieldName()).thenReturn(FIELD);
        when(field.getRelatedModel()).thenReturn(RELATED);
        when(field.getRelatedField()).thenReturn(BACK_REF);
        return field;
    }

    private static Map<String, Object> parent(long id, String... lines) {
        List<Map<String, Object>> children = new ArrayList<>();
        for (String line : lines) {
            children.add(new HashMap<>(Map.of("line1", line)));
        }
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put(FIELD, children);
        return row;
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachChildCarriesItsParentIdWhenCreated() {
        try (MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class)) {
            reflect.when(() -> ReflectTool.createList(eq(RELATED), anyList())).thenReturn(List.of());

            new OneToManyProcessor(oneToMany(), AccessType.CREATE, null)
                    .batchProcessInputRows(new ArrayList<>(List.of(parent(7L, "a", "b"), parent(8L, "c"))));

            ArgumentCaptor<List<Map<String, Object>>> created = ArgumentCaptor.forClass(List.class);
            reflect.verify(() -> ReflectTool.createList(eq(RELATED), created.capture()));
            assertThat(created.getValue())
                    .extracting(row -> row.get("line1"), row -> row.get(BACK_REF))
                    .containsExactly(
                            tuple("a", 7L),
                            tuple("b", 7L),
                            tuple("c", 8L));
        }
    }
}
