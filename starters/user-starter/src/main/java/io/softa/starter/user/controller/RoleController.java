package io.softa.starter.user.controller;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.WizardSaveDTO;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.RoleSource;
import io.softa.starter.user.service.DynamicRoleSyncJob;
import io.softa.starter.user.service.RoleDataScopeService;
import io.softa.starter.user.service.RoleNavigationService;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.util.JsonArrayUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Role controller — mapped under {@code /Role} to share the generic model
 * path namespace. Hosts the wizard create/update ({@code /Role/wizard},
 * {@code /Role/{id}/wizard}), the status toggle ({@code /Role/{id}/active}),
 * and the typed DELETE endpoints ({@code /Role/deleteById(s)}) that route
 * through {@link RoleService} so the system-role guard + cache eviction run —
 * the generic {@code /{modelName}} delete path skips both. Reads and generic
 * writes not declared here fall through to the generic {@code ModelController};
 * the literal {@code /Role/*} mappings here are more specific and win.
 */
@Tag(name = "Role")
@RestController
@RequestMapping("/Role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final RoleNavigationService roleNavigationService;
    private final RoleDataScopeService roleDataScopeService;
    private final RoleSensitiveFieldSetService roleSensitiveFieldSetService;
    private final UserRoleRelService userRoleRelService;
    private final DynamicRoleSyncJob dynamicRoleSyncJob;
    /** Used for explicit-null column updates that bypass the entity-based
     *  {@code ignoreNull=true} semantics (e.g. wizard "Clear" on
     *  dynamicFilter). The map-based updateOne writes whatever keys are
     *  present in the map, null values included. */
    private final ModelService<?> modelService;

    @Operation(summary = "Delete a role by id — typed (system-role guard + cache eviction); "
            + "shadows the generic /Role/deleteById which skips both")
    @PostMapping("/deleteById")
    public ApiResponse<Boolean> deleteById(@RequestParam Long id) {
        return ApiResponse.success(roleService.deleteById(id));
    }

    @Operation(summary = "Delete roles by ids — typed (system-role guard + cache eviction); "
            + "shadows the generic /Role/deleteByIds which skips both")
    @PostMapping("/deleteByIds")
    public ApiResponse<Boolean> deleteByIds(@RequestParam List<Long> ids) {
        return ApiResponse.success(roleService.deleteByIds(ids));
    }

    @PostMapping("/wizard")
    @Transactional
    @Operation(summary = "Wizard create — insert Role + role_navigation + user_role rows (Manual + Dynamic) in one transaction; returns new role id")
    public ApiResponse<Long> createWithWizard(@RequestBody @Valid WizardSaveDTO body) {
        Role role = parseRole(body.roleUpdate());
        role.setId(null);
        Long newId = roleService.createOne(role);
        writeRoleNavigations(newId, body.roleNavigations());
        writeRoleDataScopes(newId, body.roleDataScopes());
        writeRoleSensitiveFieldSets(newId, body.roleSensitiveFieldSetIds());
        writeManualUserRoleRels(newId, body.userIds());
        // Inline DYNAMIC sync — same in-transaction guarantee. The cron
        // job covers the in-between drift (employee data changes between
        // role saves) once it's running.
        dynamicRoleSyncJob.syncRole(newId);
        return ApiResponse.success(newId);
    }

    @PutMapping("/{id}/wizard")
    @Transactional
    @Operation(summary = "Wizard update — refresh Role basics, rewrite role_navigation rows and user_role rows (Manual + Dynamic) in one transaction")
    public ApiResponse<Void> saveWizard(@PathVariable Long id, @RequestBody @Valid WizardSaveDTO body) {
        Role role = parseRole(body.roleUpdate());
        role.setId(id);
        roleService.updateOne(role, true);
        // Wizard "Clear" on the dynamic membership rule sends
        // {"dynamicFilter": null} explicitly. The entity-based updateOne
        // above runs with ignoreNull=true (to avoid clobbering fields the
        // wizard payload didn't touch, e.g. code / tenantId), which means
        // it skips null values — including our intended clear. Issue a
        // targeted column-only update through the framework's map-based
        // ModelService.updateOne, which writes whatever keys are in the
        // map regardless of their value, so the SQL becomes a literal
        // `SET dynamic_filter = NULL`.
        if (isExplicitNull(body.roleUpdate(), "dynamicFilter")) {
            Map<String, Object> clearFields = new HashMap<>();
            clearFields.put("id", id);
            clearFields.put("dynamicFilter", null);
            modelService.updateOne("Role", clearFields);
        }
        roleNavigationService.deleteByFilters(new Filters().eq(RoleNavigation::getRoleId, id));
        writeRoleNavigations(id, body.roleNavigations());
        // Rewrite the data-dimension grants (scope + SFS). Same delete-then-
        // insert pattern, each scoped to THIS role's rows only. Whole save is
        // one @Transactional so observers never see the empty intermediate state.
        roleDataScopeService.deleteByFilters(new Filters().eq(RoleDataScope::getRoleId, id));
        writeRoleDataScopes(id, body.roleDataScopes());
        roleSensitiveFieldSetService.deleteByFilters(new Filters().eq(RoleSensitiveFieldSet::getRoleId, id));
        writeRoleSensitiveFieldSets(id, body.roleSensitiveFieldSetIds());
        // Wipe ALL existing user_role rows for this role — both Manual and
        // Dynamic — then rebuild from scratch. By application convention
        // there's at most one row per (user, role): Manual takes precedence,
        // syncRole inserts Dynamic only for users not in the Manual list.
        // Doing a full wipe (instead of delta upsert) keeps the logic
        // trivially correct: no "what about old rows that should be
        // upgraded/downgraded" branches. The whole save is in one
        // @Transactional so external observers never see the empty state.
        userRoleRelService.deleteByFilters(
                new Filters().eq(UserRoleRel::getRoleId, id));
        writeManualUserRoleRels(id, body.userIds());
        dynamicRoleSyncJob.syncRole(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/active")
    @Transactional
    @Operation(summary = "Enable / disable a role. Routes through the typed RoleService so it publishes "
            + "RoleNavigationChangedEvent — every holder's PermissionInfo cache is evicted on commit and "
            + "the status change takes effect immediately. The generic /Role/updateOne path publishes no "
            + "event (holders would stay authorised until the 1h cache TTL), so status MUST be changed here.")
    public ApiResponse<Boolean> setActive(@PathVariable Long id, @RequestBody JsonNode payload) {
        JsonNode active = payload == null ? null : payload.get("active");
        Assert.isTrue(active != null && !active.isNull(), "`active` (boolean) is required");
        Role patch = new Role();
        patch.setId(id);
        patch.setActive(active.asBoolean());
        // ignoreNull=true → writes only `active`, leaving name / code / tenantId
        // untouched. The typed updateOne runs guardSystemMutation (blocks
        // deactivating a system role) and publishRoleGrantChange, whose
        // AFTER_COMMIT listener evicts every holder's PermissionInfo snapshot.
        return ApiResponse.success(roleService.updateOne(patch, true));
    }

    /** True when {@code payload} contains {@code field} as a JSON null
     *  (vs. the field being absent). Used to tell apart "wizard didn't
     *  touch this field" from "wizard explicitly cleared this field" —
     *  the latter needs a column-only update to actually write NULL,
     *  because the entity-based updateOne runs with ignoreNull=true. */
    private static boolean isExplicitNull(JsonNode payload, String field) {
        if (payload == null || !payload.has(field)) return false;
        JsonNode value = payload.get(field);
        return value == null || value.isNull();
    }

    /** Maps the wizard's `roleUpdate` JSON (name / description / active /
     *  dynamicFilter) into a Role entity. We extract each field by hand
     *  instead of going through JsonUtils.jsonNodeToObject(Class<T>) because
     *  the runtime Jackson lacks the `treeToValue(JsonNode, Class)` overload
     *  the helper relies on (NoSuchMethodError on call).
     *
     *  <p>Explicit-null fields (e.g. wizard "Clear" on dynamicFilter) stay
     *  out of the entity here — see {@link #saveWizard} for the targeted
     *  follow-up update that actually writes NULL. */
    private Role parseRole(JsonNode payload) {
        Role role = new Role();
        if (payload == null || payload.isNull()) return role;
        JsonNode name = payload.get("name");
        if (name != null && !name.isNull()) role.setName(name.asString());
        JsonNode description = payload.get("description");
        if (description != null && !description.isNull()) role.setDescription(description.asString());
        JsonNode active = payload.get("active");
        if (active != null && !active.isNull()) role.setActive(active.asBoolean());
        JsonNode dynamicFilter = payload.get("dynamicFilter");
        if (dynamicFilter != null && !dynamicFilter.isNull()) role.setDynamicFilter(dynamicFilter);
        return role;
    }

    /** Inserts one user_role row per id in the wizard payload with source=MANUAL.
     *  Skips when payload is null / not an array / empty. Duplicate ids in the
     *  payload are de-duped (a single (user, role, MANUAL) row is enough).
     *  UserAccount ids arrive as JSON strings (frontend serializes all ids as
     *  string per type convention) but may be numbers in tests — {@link
     *  JsonArrayUtils#toLongList} accepts both. */
    private void writeManualUserRoleRels(Long roleId, JsonNode idsJson) {
        java.util.Set<Long> userIds = new java.util.LinkedHashSet<>(JsonArrayUtils.toLongList(idsJson));
        if (userIds.isEmpty()) return;
        List<UserRoleRel> rows = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            UserRoleRel ur = new UserRoleRel();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            ur.setSource(RoleSource.MANUAL);
            rows.add(ur);
        }
        userRoleRelService.createList(rows);
    }

    /** Writes one role_navigation row per entry in the wizard payload — now
     *  menu + permission only ({navigationId, permissionIds}); data scope and
     *  sensitive-field-set moved to their own tables (see
     *  {@link #writeRoleDataScopes} / {@link #writeRoleSensitiveFieldSets}).
     *  Incoming `id` / `roleId` from the frontend are ignored — id is
     *  auto-assigned, roleId is bound by the caller. */
    private void writeRoleNavigations(Long roleId, JsonNode rowsJson) {
        if (rowsJson == null || !rowsJson.isArray() || rowsJson.isEmpty()) return;
        List<RoleNavigation> rows = new ArrayList<>(rowsJson.size());
        for (JsonNode row : rowsJson) {
            RoleNavigation rn = new RoleNavigation();
            rn.setRoleId(roleId);
            JsonNode navIdNode = row.get("navigationId");
            rn.setNavigationId(navIdNode == null || navIdNode.isNull() ? null : navIdNode.asString());
            rn.setPermissionIds(row.get("permissionIds"));
            rows.add(rn);
        }
        roleNavigationService.createList(rows);
    }

    /** Writes one role_data_scope row per {@code {model, dataScopes}} entry
     *  (one per queryable model). Entries without a model are skipped.
     *  Incoming id/roleId ignored — id auto-assigned, roleId bound by caller. */
    private void writeRoleDataScopes(Long roleId, JsonNode rowsJson) {
        if (rowsJson == null || !rowsJson.isArray() || rowsJson.isEmpty()) return;
        List<RoleDataScope> rows = new ArrayList<>(rowsJson.size());
        for (JsonNode row : rowsJson) {
            JsonNode modelNode = row.get("model");
            if (modelNode == null || modelNode.isNull() || modelNode.asString().isBlank()) continue;
            RoleDataScope rds = new RoleDataScope();
            rds.setRoleId(roleId);
            rds.setModel(modelNode.asString());
            rds.setDataScopes(row.get("dataScopes"));
            rows.add(rds);
        }
        if (!rows.isEmpty()) roleDataScopeService.createList(rows);
    }

    /** Writes one role_sensitive_field_set row per granted setId (de-duped).
     *  Payload is a flat JSON string array (role-wide) — each SFS carries its
     *  own canonical model, so no model is stored here. */
    private void writeRoleSensitiveFieldSets(Long roleId, JsonNode idsJson) {
        java.util.Set<String> setIds = new java.util.LinkedHashSet<>(JsonArrayUtils.toStringList(idsJson));
        if (setIds.isEmpty()) return;
        List<RoleSensitiveFieldSet> rows = new ArrayList<>(setIds.size());
        for (String sid : setIds) {
            if (sid == null || sid.isBlank()) continue;
            RoleSensitiveFieldSet r = new RoleSensitiveFieldSet();
            r.setRoleId(roleId);
            r.setSensitiveFieldSetId(sid);
            rows.add(r);
        }
        if (!rows.isEmpty()) roleSensitiveFieldSetService.createList(rows);
    }

}
