package io.softa.starter.tenant.controller;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.tenant.entity.TenantSubscriptionPeriod;
import io.softa.starter.tenant.enums.SubscriptionPeriodType;
import io.softa.starter.tenant.service.TenantSubscriptionPeriodService;

/**
 * Shadows <b>every</b> generic write endpoint of {@code /TenantSubscriptionPeriod} so nothing reaches the
 * table without the guards and the projection refresh.
 *
 * <h3>Why all of them, explicitly</h3>
 * The framework's generic controller is mapped at {@code /{modelName}}, so declaring the model is enough
 * for sixteen write endpoints to exist. And {@code ModelServiceImpl} does not route through a model's
 * {@code EntityService}, so overriding the service is not enough either — a generic call would bypass it.
 * Anything left unshadowed could:
 *
 * <ul>
 *   <li>skip the projection refresh, leaving the subscription row disagreeing with its periods and
 *       {@code currentPeriodId} pointing at a deleted row;</li>
 *   <li>record a <b>second floor period</b>, putting the tenant's baseline entitlement in two places.</li>
 * </ul>
 *
 * <p>Overlap is no longer among the reasons: it is accepted now, because every tenant owns an open-ended
 * base period that every sale overlaps. The projection resolves the ambiguity by plan tier instead.</p>
 *
 * This codebase has already been bitten twice by the same shape: {@code TenantStatus} being editable
 * through the generic {@code updateOne} (so suspending a tenant did not evict its caches and did not take
 * effect), and the {@code user_info} cache being bypassed by {@code updateByFilter}, which does not go
 * through {@code updateOne}. Both times the typed method did the right thing and a generic door did not
 * use it.
 *
 * <p>Endpoints that cannot be expressed through the guarded service are rejected rather than quietly
 * approximated. Bulk create / update / copy each bypass the projection refresh and the floor-cardinality
 * check; ops enters periods one at a time, so the capability is not worth the failure mode.
 *
 * <p>Reads are deliberately <b>not</b> shadowed — the generic {@code searchList} / {@code getById} are
 * exactly what the UI needs.
 */
@Tag(name = "TenantSubscriptionPeriod")
@RestController
@RequestMapping("/TenantSubscriptionPeriod")
public class TenantSubscriptionPeriodController {

    /** Bulk paths bypass the projection refresh and the floor-cardinality check; ops records one at a time. */
    private static final String BULK_REJECTED =
            "Subscription periods must be recorded one at a time so each is validated and the projection refreshed.";
    private static final String COPY_REJECTED =
            "A subscription period cannot be copied — record the new period with its own dates instead.";

    private final TenantSubscriptionPeriodService periodService;

    public TenantSubscriptionPeriodController(TenantSubscriptionPeriodService periodService) {
        this.periodService = periodService;
    }

    @Operation(summary = "Record a subscription period — validated, then the projection is refreshed")
    @PostMapping("/createOne")
    public ApiResponse<Long> createOne(@RequestBody TenantSubscriptionPeriod period) {
        return ApiResponse.success(periodService.createOne(period));
    }

    @Operation(summary = "Record a subscription period and return it")
    @PostMapping("/createOneAndFetch")
    public ApiResponse<TenantSubscriptionPeriod> createOneAndFetch(
            @RequestBody TenantSubscriptionPeriod period) {
        Long id = periodService.createOne(period);
        return ApiResponse.success(periodService.getById(id).orElse(null));
    }

    @Operation(summary = "Change a subscription period — re-validated, then the projection is refreshed")
    @PostMapping("/updateOne")
    public ApiResponse<Boolean> updateOne(@RequestBody TenantSubscriptionPeriod period) {
        return ApiResponse.success(periodService.updateOne(period));
    }

    @Operation(summary = "Change a subscription period and return it")
    @PostMapping("/updateOneAndFetch")
    public ApiResponse<TenantSubscriptionPeriod> updateOneAndFetch(
            @RequestBody TenantSubscriptionPeriod period) {
        periodService.updateOne(period);
        return ApiResponse.success(periodService.getById(period.getId()).orElse(null));
    }

    @Operation(summary = "Delete a subscription period — the projection is refreshed afterwards")
    @PostMapping("/deleteById")
    public ApiResponse<Boolean> deleteById(@RequestParam Long id) {
        return ApiResponse.success(periodService.deleteById(id));
    }

    @Operation(summary = "Delete subscription periods — the projections are refreshed afterwards")
    @PostMapping("/deleteByIds")
    public ApiResponse<Boolean> deleteByIds(@RequestBody List<Long> ids) {
        Assert.notEmpty(ids, "Ids to delete cannot be empty.");
        return ApiResponse.success(periodService.deleteByIds(ids));
    }

    @Operation(summary = "Move to another plan today, keeping the paid-through date")
    @PostMapping("/changePlanNow")
    public ApiResponse<Long> changePlanNow(@RequestBody Map<String, Object> body) {
        Long subscriptionId = IdUtils.convertIdToLong(body.get("subscriptionId"));
        String planId = body.get("planId") == null ? null : String.valueOf(body.get("planId"));
        SubscriptionPeriodType type = body.get("periodType") == null
                ? SubscriptionPeriodType.PAID
                : SubscriptionPeriodType.valueOf(String.valueOf(body.get("periodType")).toUpperCase());
        return ApiResponse.success(periodService.changePlanNow(subscriptionId, planId, type));
    }

    // ─── Rejected generic write paths ───
    // Each of these exists automatically because the model does. Left open they would bypass the guards
    // and the projection refresh; there is no test that can catch an endpoint nobody remembered.

    @PostMapping("/createList")
    public ApiResponse<Void> createList(@RequestBody Object ignored) {
        throw new BusinessException(BULK_REJECTED);
    }

    @PostMapping("/createListAndFetch")
    public ApiResponse<Void> createListAndFetch(@RequestBody Object ignored) {
        throw new BusinessException(BULK_REJECTED);
    }

    @PostMapping("/updateList")
    public ApiResponse<Void> updateList(@RequestBody Object ignored) {
        throw new BusinessException(BULK_REJECTED);
    }

    @PostMapping("/updateListAndFetch")
    public ApiResponse<Void> updateListAndFetch(@RequestBody Object ignored) {
        throw new BusinessException(BULK_REJECTED);
    }

    @PostMapping("/updateByFilter")
    public ApiResponse<Void> updateByFilter(@RequestBody Object ignored) {
        // The one that bypassed the user_info cache eviction: it does not go through updateOne, so no
        // per-row validation or refresh would run.
        throw new BusinessException(BULK_REJECTED);
    }

    @PostMapping("/copyById")
    public ApiResponse<Void> copyById(@RequestParam(required = false) Long id) {
        throw new BusinessException(COPY_REJECTED);
    }

    @PostMapping("/copyByIdAndFetch")
    public ApiResponse<Void> copyByIdAndFetch(@RequestParam(required = false) Long id) {
        throw new BusinessException(COPY_REJECTED);
    }

    @PostMapping("/copyByIds")
    public ApiResponse<Void> copyByIds(@RequestBody Object ignored) {
        throw new BusinessException(COPY_REJECTED);
    }

    @PostMapping("/copyByIdsAndFetch")
    public ApiResponse<Void> copyByIdsAndFetch(@RequestBody Object ignored) {
        throw new BusinessException(COPY_REJECTED);
    }

    @PostMapping("/deleteBySliceId")
    public ApiResponse<Void> deleteBySliceId(@RequestParam(required = false) Long sliceId) {
        throw new BusinessException("Subscription periods are not a timeline model — delete by id.");
    }
}
