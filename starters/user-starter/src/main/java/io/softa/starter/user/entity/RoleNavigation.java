package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * RoleNavigation — one row = one navigation's complete grant for a role.
 * Three JSON columns hold the inline membership lists (per design v4 §A.2):
 *   - permissionIds:        Set<String>     (subset of perms under this nav)
 *   - dataScopes:           List<ScopeRule> (OR-combined)
 *   - sensitiveFieldSetIds: Set<String>     (OR-combined)
 *
 * The navigation_id may reference any non-GROUP node (MENU / BUTTON / TAB);
 * Validator ⑩ rejects GROUP and pure-container MENU (nav.model = null).
 */
@Data
@Schema(name = "RoleNavigation")
@EqualsAndHashCode(callSuper = true)
public class RoleNavigation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "Role ID (FK role.id)")
    private Long roleId;

    @Schema(description = "Navigation ID (FK navigation.id). Must reference MENU / BUTTON / TAB only — not GROUP or pure-container MENU")
    private String navigationId;

    @Schema(description = "Granted permission IDs under this nav (string array; default = all, subset = admin-trimmed)")
    private JsonNode permissionIds;

    @Schema(description = "Scope rules (OR-combined). Array of {scopeType, scopeExpr?}")
    private JsonNode dataScopes;

    @Schema(description = "Granted sensitive_field_set IDs under this nav (string array; empty = no sensitive fields visible)")
    private JsonNode sensitiveFieldSetIds;
}
