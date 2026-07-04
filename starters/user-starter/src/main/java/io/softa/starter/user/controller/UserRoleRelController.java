package io.softa.starter.user.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.BulkAddRequest;
import io.softa.starter.user.dto.BulkAddResult;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.RoleSource;
import io.softa.starter.user.service.BulkUserRoleService;
import io.softa.starter.user.service.UserRoleRelService;

/**
 * UserRoleRel model controller.
 *
 * <p>Overrides {@code /deleteById} / {@code /deleteByIds} so they route through
 * the typed {@link UserRoleRelService} (system-role last-holder guard + per-user
 * cache eviction via {@code UserRoleRelChangedEvent}) instead of the generic
 * {@code ModelController} path, which skips both. Also hosts the bulk assign
 * ({@code /bulkAdd} — M users × N roles flattened to pairs, partial-success /
 * dedup / cross-tenant validation via {@link BulkUserRoleService}). Reads fall
 * through to the generic controller.
 */
@Tag(name = "UserRoleRel")
@RestController
@RequestMapping("/UserRoleRel")
public class UserRoleRelController
        extends EntityController<UserRoleRelService, UserRoleRel, Long> {

    @Autowired
    private BulkUserRoleService bulkUserRoleService;

    @Operation(summary = "deleteById — typed (last-holder guard + cache eviction)")
    @PostMapping("/deleteById")
    public ApiResponse<Boolean> deleteById(@RequestParam Long id) {
        return ApiResponse.success(service.deleteById(id));
    }

    @Operation(summary = "deleteByIds — typed (last-holder guard + cache eviction)")
    @PostMapping("/deleteByIds")
    public ApiResponse<Boolean> deleteByIds(@RequestParam List<Long> ids) {
        return ApiResponse.success(service.deleteByIds(ids));
    }

    @Operation(summary = "Bulk assign (M users × N roles) — partial-success per pair; "
            + "returns added / skipped with technical reasons")
    @PostMapping("/bulkAdd")
    public ApiResponse<BulkAddResult> bulkAdd(@RequestBody @Valid BulkAddRequest body) {
        RoleSource source = body.source() == null ? RoleSource.MANUAL : body.source();
        return ApiResponse.success(bulkUserRoleService.bulkAdd(body.pairs(), source));
    }
}
