package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.starter.user.dto.ListViewPreferenceDTO;
import io.softa.starter.user.entity.ListViewPreference;
import io.softa.starter.user.service.ListViewPreferenceService;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;

/**
 * Self-service endpoints for a person's own list arrangements. Open to every signed-in account
 * (listed under {@code permission.authenticated-bypass-patterns}); each acts on the caller only.
 */
@Tag(name = "ListViewPreference")
@RestController
@RequestMapping("/ListViewPreference")
public class ListViewPreferenceController
        extends EntityController<ListViewPreferenceService, ListViewPreference, Long> {

    @Operation(summary = "getMyPreference",
            description = "The caller's arrangement of a list, without fields they may no longer read.")
    @GetMapping("/getMyPreference")
    public ApiResponse<ListViewPreferenceDTO> getMyPreference(@RequestParam String viewKey) {
        return ApiResponse.success(service.getMine(viewKey));
    }

    @Operation(summary = "saveMyPreference",
            description = "Save the given parts of the caller's arrangement of a list; null parts are kept.")
    @PostMapping("/saveMyPreference")
    public ApiResponse<ListViewPreferenceDTO> saveMyPreference(@RequestBody ListViewPreferenceDTO preference) {
        return ApiResponse.success(service.saveMine(preference));
    }

    @Operation(summary = "resetMyPreference",
            description = "Forget the caller's columns, export fields, view settings, or all three (part omitted) for a list.")
    @PostMapping("/resetMyPreference")
    public ApiResponse<Boolean> resetMyPreference(@RequestParam String viewKey,
                                                  @RequestParam(required = false) String part) {
        service.resetMine(viewKey, part);
        return ApiResponse.success(true);
    }
}
