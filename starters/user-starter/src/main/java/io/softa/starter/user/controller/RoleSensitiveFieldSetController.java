package io.softa.starter.user.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;

/**
 * RoleSensitiveFieldSet model controller. Overrides {@code /deleteById} /
 * {@code /deleteByIds} so they route through the typed
 * {@link RoleSensitiveFieldSetService} (which publishes
 * {@code RoleGrantChangedEvent} for per-role cache eviction) instead of the
 * generic {@code ModelController} path, which skips the event. Other CRUD paths
 * fall through to the generic controller.
 */
@Tag(name = "RoleSensitiveFieldSet")
@RestController
@RequestMapping("/RoleSensitiveFieldSet")
public class RoleSensitiveFieldSetController
        extends EntityController<RoleSensitiveFieldSetService, RoleSensitiveFieldSet, Long> {

    @Operation(summary = "deleteById — routes through the typed service (cache eviction)")
    @PostMapping("/deleteById")
    public ApiResponse<Boolean> deleteById(@RequestParam Long id) {
        return ApiResponse.success(service.deleteById(id));
    }

    @Operation(summary = "deleteByIds — routes through the typed service (cache eviction)")
    @PostMapping("/deleteByIds")
    public ApiResponse<Boolean> deleteByIds(@RequestParam List<Long> ids) {
        return ApiResponse.success(service.deleteByIds(ids));
    }
}
