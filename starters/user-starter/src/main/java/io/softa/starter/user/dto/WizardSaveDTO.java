package io.softa.starter.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * Role wizard save body — packs the three tables touched by a single role
 * create / edit into one request payload so the backend can persist them
 * inside one transaction.
 *
 * <p>Submitted by the FE Role Wizard (basic info + permission matrix +
 * member assignment) at:
 * <ul>
 *   <li>{@code POST /admin/role/wizard}     — RoleController.createWithWizard</li>
 *   <li>{@code PUT  /admin/role/{id}/wizard} — RoleController.saveWizard</li>
 * </ul>
 *
 * <p>Fields stay as {@link JsonNode} (not strongly-typed entities) because:
 * <ol>
 *   <li>{@code roleNavigations[]} rows carry three nested JSON columns
 *       (permissionIds / dataScopes / sensitiveFieldSetIds) that the
 *       receiving aspect copies verbatim — turning them into strongly-typed
 *       lists would force a redundant re-serialization.</li>
 *   <li>{@code userIds[]} elements arrive as either JSON strings (FE
 *       convention) or numbers (test fixtures); the {@code parseLong}
 *       helper inside the controller tolerates both, but a typed
 *       {@code List<Long>} would reject the string variant.</li>
 *   <li>Runtime Jackson on the starter classpath lacks the
 *       {@code treeToValue(JsonNode, Class)} overload our usual helper
 *       relies on (NoSuchMethodError at call time), so we lean on manual
 *       per-field extraction inside the controller.</li>
 * </ol>
 *
 * <p>Each field maps 1:1 to a backing table:
 * <ul>
 *   <li>{@link #roleUpdate}       → {@code role} (basic columns + dynamicFilter)</li>
 *   <li>{@link #roleNavigations}  → {@code role_navigation} (replaces all rows for this role)</li>
 *   <li>{@link #userIds}          → {@code user_role_rel} with {@code source=MANUAL}
 *       (DYNAMIC rows are rebuilt by DynamicRoleSyncJob in the same tx)</li>
 * </ul>
 */
@Schema(description = "Wizard save body — Role basics + role_navigation set + manual member user ids")
public record WizardSaveDTO(
        @Schema(description = "Role basic info update (name / description / active / dynamicFilter)")
        JsonNode roleUpdate,

        @Schema(description = "Full role_navigation rows for this role (replaces existing)")
        JsonNode roleNavigations,

        @Schema(description = "User account ids to assign with source=MANUAL (replaces existing MANUAL rows; DYNAMIC rows untouched)")
        JsonNode userIds
) {
}
