package io.softa.starter.metadata.checksum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysOptionSet;

/**
 * Assembles per-meta-table rows into business aggregates and reduces each to its
 * {@link AggregateChecksum}, keyed by business key ({@code modelName} / {@code optionSetCode}).
 *
 * <p>The one map-based assembler shared by both lanes — the runtime (code-linked:
 * {@code child.modelName → parent.modelName}) and the studio diff (id-linked:
 * {@code child.modelId → parent.id}, rename-stable). The link fields are passed in so
 * neither lane duplicates the grouping; the output is always keyed by the business key,
 * making the two sides directly comparable.
 *
 * <p>Rows are camelCase attribute-keyed maps (as returned by {@code modelService.searchList}
 * / the runtime export), matching the checksum allow-lists. Pure / stateless.
 */
public final class AggregateChecksumIndex {

    private AggregateChecksumIndex() {
    }

    private static final String MODEL_NAME = LambdaUtils.getAttributeName(SysModel::getModelName);
    private static final String OPTION_SET_CODE = LambdaUtils.getAttributeName(SysOptionSet::getOptionSetCode);

    /**
     * Model aggregate checksums keyed by {@code modelName}.
     *
     * @param childLink  the field on a child row that points at its parent (lane-specific)
     * @param parentLink the field on the model row that the child's {@code childLink} matches
     */
    public static Map<String, String> models(List<Map<String, Object>> modelRows,
                                              List<Map<String, Object>> fieldRows,
                                              List<Map<String, Object>> indexRows,
                                              String childLink, String parentLink) {
        Map<Object, List<Map<String, Object>>> fieldsByParent = groupBy(fieldRows, childLink);
        Map<Object, List<Map<String, Object>>> indexesByParent = groupBy(indexRows, childLink);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> model : modelRows) {
            Object parent = model.get(parentLink);
            out.put(str(model.get(MODEL_NAME)), AggregateChecksum.ofModel(model,
                    fieldsByParent.getOrDefault(parent, List.of()),
                    indexesByParent.getOrDefault(parent, List.of())));
        }
        return out;
    }

    /** OptionSet aggregate checksums keyed by {@code optionSetCode}. */
    public static Map<String, String> optionSets(List<Map<String, Object>> setRows,
                                                  List<Map<String, Object>> itemRows,
                                                  String childLink, String parentLink) {
        Map<Object, List<Map<String, Object>>> itemsByParent = groupBy(itemRows, childLink);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> set : setRows) {
            Object parent = set.get(parentLink);
            out.put(str(set.get(OPTION_SET_CODE)), AggregateChecksum.ofOptionSet(set,
                    itemsByParent.getOrDefault(parent, List.of())));
        }
        return out;
    }

    private static Map<Object, List<Map<String, Object>>> groupBy(List<Map<String, Object>> rows, String key) {
        if (rows == null) {
            return Map.of();
        }
        return rows.stream()
                .filter(r -> r.get(key) != null)   // a null-link child cannot belong to an aggregate
                .collect(Collectors.groupingBy(r -> r.get(key)));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
