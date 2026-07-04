package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * RoleSensitiveFieldSet — one row = one sensitive_field_set grant for a role
 * (normalised role ↔ SFS many-to-many).
 *
 * <p>Replaces the per-nav {@code role_navigation.sensitive_field_set_ids}
 * JSON array. Deliberately has NO {@code model} column: each SFS already
 * carries its own canonical model (via {@code SensitiveFieldSetCache.modelOf}),
 * so a grant naturally routes into {@code modelSensitiveFieldSetsMap} keyed by
 * the SFS's own model — including child/sub-object models such as
 * {@code EmpBankAccount} whose fields surface through {@code Employee}.
 *
 * <p>Storing one row per grant (instead of a JSON array) buys per-grant audit
 * (who granted which SFS, when), a real {@code (tenant_id, role_id,
 * sensitive_field_set_id)} uniqueness guarantee, and cheap "which roles hold
 * SFS X" reverse queries.
 */
@Data
@Schema(name = "RoleSensitiveFieldSet")
@EqualsAndHashCode(callSuper = true)
public class RoleSensitiveFieldSet extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "Role ID (FK role.id)")
    private Long roleId;

    @Schema(description = "Granted sensitive_field_set id (FK sensitive_field_set.id). Single value; the SFS carries its own canonical model.")
    private String sensitiveFieldSetId;
}
