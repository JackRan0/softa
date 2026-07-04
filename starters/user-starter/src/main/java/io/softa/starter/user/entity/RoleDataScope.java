package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * RoleDataScope — one row = a role's row-level data scope for ONE model.
 *
 * <p>Replaces the per-nav {@code role_navigation.data_scopes} column: data
 * scope is now attached to the (role, model) pair, matching how
 * {@code PermissionInfoEnricher} keys {@code modelScopeMap} at runtime.
 * Multiple scope rules for the same model are OR-combined and stored inline
 * in {@link #dataScopes}. {@code (tenant_id, role_id, model)} is unique — the
 * OR-union across the wizard's selected navigations is materialised here at
 * save time instead of being re-aggregated on every enrich.
 *
 * <p>Only "directly queryable" models get a row (the primary model of a
 * granted navigation). Child/related models that merely contribute sensitive
 * fields (e.g. {@code EmpBankAccount} under {@code Employee}) do NOT need a
 * scope row — their field masking is driven by {@link RoleSensitiveFieldSet}.
 */
@Data
@Schema(name = "RoleDataScope")
@EqualsAndHashCode(callSuper = true)
public class RoleDataScope extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "Role ID (FK role.id)")
    private Long roleId;

    @Schema(description = "Queried model name (PascalCase), e.g. Employee / Department. The model whose rows this scope filters.")
    private String model;

    @Schema(description = "Scope rules (OR-combined). Array of {scopeType, scopeExpr?}")
    private JsonNode dataScopes;
}
