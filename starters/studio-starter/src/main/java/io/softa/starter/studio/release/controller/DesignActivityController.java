package io.softa.starter.studio.release.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.release.dto.AggregateChangeReport;
import io.softa.starter.studio.release.entity.DesignActivity;
import io.softa.starter.studio.release.service.DesignActivityService;
import io.softa.starter.studio.release.service.DesignAppEnvService;

/**
 * DesignActivity Model Controller — the unified studio operation history (PUBLISH / IMPORT
 * / REVERSE / MERGE) that replaces the former DesignDeployment + DesignMerge controllers. CRUD / query comes from
 * {@link EntityController}; retry / cancel are publish-lifecycle operations delegated to
 * {@link DesignAppEnvService}.
 */
@Tag(name = "DesignActivity")
@RestController
@RequestMapping("/DesignActivity")
public class DesignActivityController extends EntityController<DesignActivityService, DesignActivity, Long> {

    @Autowired
    private DesignAppEnvService appEnvService;

    @Operation(description = "Retry a FAILED publish activity by re-publishing its env.")
    @PostMapping(value = "/retry")
    @Parameter(name = "id", description = "Activity ID")
    public ApiResponse<Void> retry(@RequestParam Long id) {
        appEnvService.retryPublish(id);
        return ApiResponse.success();
    }

    @Operation(description = "Cancel a stuck (RUNNING) publish activity and release the env mutex. "
            + "No automatic rollback — runtime changes already applied stay applied.")
    @PostMapping(value = "/cancel")
    @Parameter(name = "id", description = "Activity ID")
    public ApiResponse<Void> cancel(@RequestParam Long id) {
        appEnvService.cancelPublish(id);
        return ApiResponse.success();
    }

    @Operation(description = "Restore the env to this activity's captured design (overwrite design from its "
            + "snapshot, then publish to converge the runtime). Any succeeded activity that captured a "
            + "snapshot applies (PUBLISH / MERGE / IMPORT / REVERSE).")
    @PostMapping(value = "/restore")
    @Parameter(name = "id", description = "Activity ID")
    public ApiResponse<Void> restore(@RequestParam Long id) {
        appEnvService.restore(id);
        return ApiResponse.success();
    }

    @Operation(description = "Aggregate-root-grouped before/after view of this activity's change set "
            + "(which aggregate root, which field/attr/option changed, before → after).")
    @GetMapping(value = "/changeReport")
    @Parameter(name = "id", description = "Activity ID")
    public ApiResponse<AggregateChangeReport> changeReport(@RequestParam Long id) {
        return ApiResponse.success(service.changeReport(id));
    }
}
