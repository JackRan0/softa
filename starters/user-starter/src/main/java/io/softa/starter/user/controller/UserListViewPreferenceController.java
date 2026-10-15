package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.UserListViewPreferenceDTO;
import io.softa.starter.user.service.UserListViewPreferenceService;

/**
 * Self-service endpoints for a person's own list view arrangements, and the only way to them.
 *
 * <p>Deliberately not an {@code EntityController}: the typed generic endpoints it would add serve
 * any row, and an admin's permission bypass would let one read and overwrite another person's
 * arrangement. A host lists these three under {@code permission.authenticated-bypass-patterns};
 * each acts on the caller only.
 */
@Tag(name = "UserListViewPreference")
@RestController
@RequestMapping("/UserListViewPreference")
@RequiredArgsConstructor
public class UserListViewPreferenceController {

    private final UserListViewPreferenceService service;

    @Operation(summary = "getMyPreference",
            description = "The caller's arrangement of a list, without fields they may no longer read.")
    @GetMapping("/getMyPreference")
    public ApiResponse<UserListViewPreferenceDTO> getMyPreference(@RequestParam String viewStateKey) {
        return ApiResponse.success(service.getMine(viewStateKey));
    }

    @Operation(summary = "saveMyPreference",
            description = "Save the given parts of the caller's arrangement of a list; null parts are kept.")
    @PostMapping("/saveMyPreference")
    public ApiResponse<UserListViewPreferenceDTO> saveMyPreference(@RequestBody UserListViewPreferenceDTO preference) {
        return ApiResponse.success(service.saveMine(preference));
    }

    @Operation(summary = "resetMyPreference",
            description = "Forget the caller's columns, export fields, view settings, or all three (part omitted).")
    @PostMapping("/resetMyPreference")
    public ApiResponse<Boolean> resetMyPreference(@RequestParam String viewStateKey,
                                                  @RequestParam(required = false) String part) {
        service.resetMine(viewStateKey, part);
        return ApiResponse.success(true);
    }
}
