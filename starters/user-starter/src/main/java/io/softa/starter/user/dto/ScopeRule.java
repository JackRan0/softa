package io.softa.starter.user.dto;

import java.util.Iterator;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

import io.softa.starter.user.enums.ScopeType;

/**
 * One row of role_navigation.data_scopes JSON array.
 *
 * scopeExpr semantics depend on scopeType:
 *   - ALL / SELF / DIRECT_REPORTS:   null
 *   - DEPT_SUBTREE:                  required, {"deptId": "..."}
 *   - LEGAL_ENTITY:                  optional, {"legalEntityId": "..."} (null = dynamic from ec)
 *   - MANAGED_DEPARTMENTS:           optional, {"deptIds": ["..."]} (null = dynamic from empInfo.managedDeptIds)
 *   - CUSTOM:                        required FilterCondition; values may reference $principal.xxx
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Data scope rule")
public class ScopeRule {

    @Schema(description = "Scope type")
    private ScopeType scopeType;

    @Schema(description = "Scope expression; semantics depend on scopeType (see class doc)")
    private JsonNode scopeExpr;

    /**
     * Convenience accessor: pulls a top-level field name from scopeExpr (object form).
     */
    public JsonNode scopeExprField(String fieldName) {
        if (scopeExpr == null || !scopeExpr.isObject()) return null;
        return scopeExpr.get(fieldName);
    }

    /**
     * Extract all $principal.xxx field references inside this rule's scopeExpr.
     * Returns an empty list when scopeExpr is null or contains no refs.
     * Used by SCOPE_REQUIREMENTS analysis (CUSTOM static vs dynamic classification).
     */
    public List<String> extractPrincipalRefs() {
        if (scopeExpr == null) return List.of();
        java.util.List<String> refs = new java.util.ArrayList<>();
        walkValueNodes(scopeExpr, value -> {
            if (value.isString()) {
                String s = value.asString();
                if (s.startsWith("$principal.")) {
                    refs.add(s.substring("$principal.".length()));
                }
            }
        });
        return refs;
    }

    private static void walkValueNodes(JsonNode node, java.util.function.Consumer<JsonNode> visitor) {
        if (node == null) return;
        if (node.isValueNode()) {
            visitor.accept(node);
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) walkValueNodes(child, visitor);
            return;
        }
        if (node.isObject()) {
            Iterator<String> names = node.propertyNames().iterator();
            while (names.hasNext()) walkValueNodes(node.get(names.next()), visitor);
        }
    }
}
