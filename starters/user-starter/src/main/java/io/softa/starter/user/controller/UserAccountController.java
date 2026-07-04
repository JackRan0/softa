package io.softa.starter.user.controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.DataMask;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.utils.CookieUtils;
import io.softa.starter.user.dto.ChangePasswordDTO;
import io.softa.starter.user.dto.UnlockAccountDTO;
import io.softa.starter.user.dto.UnlockAccountsDTO;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.PermissionCacheInvalidator;
import io.softa.starter.user.service.UserAccountService;

/**
 * UserAccount Controller
 */
@Tag(name = "UserAccount Controller")
@RestController
@RequestMapping("/UserAccount")
public class UserAccountController extends EntityController<UserAccountService, UserAccount, Long> {

    private static final Logger log = LoggerFactory.getLogger(UserAccountController.class);

    private static final String MODEL = "UserAccount";
    private static final String ROLES_FIELD = "roles";

    @Autowired
    private CacheService cacheService;

    @Autowired
    private ModelService<Long> modelService;

    @Autowired
    private PermissionCacheInvalidator permissionCacheInvalidator;

    /**
     * Typed shadow of the generic {@code /UserAccount/updateOne}. The
     * {@code roles} ManyToMany cascades into {@code user_role_rel} through the
     * generic ORM write, which does NOT publish {@code UserRoleRelChangedEvent}
     * — so a role change made by editing this form would otherwise leave the
     * user's cached PermissionInfo stale until the 1h TTL. Body mirrors
     * {@code ModelController.updateOne} (so non-roles updates are unchanged);
     * we additionally evict this user when the payload touched roles. Spring
     * routes here over the templated {@code /{modelName}/updateOne} (literal
     * path is more specific).
     */
    @Operation(summary = "Update a UserAccount — evicts the user's cached permissions when roles change")
    @PostMapping("/updateOne")
    @DataMask
    public ApiResponse<Boolean> updateOne(@RequestBody Map<String, Object> row) {
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        IdUtils.formatMapId(MODEL, row);
        boolean ok = modelService.updateOne(MODEL, row);
        evictIfRolesTouched(row);
        return ApiResponse.success(ok);
    }

    @Operation(summary = "Update a UserAccount and fetch — evicts the user's cached permissions when roles change")
    @PostMapping("/updateOneAndFetch")
    @DataMask
    public ApiResponse<Map<String, Object>> updateOneAndFetch(@RequestBody Map<String, Object> row) {
        Assert.notEmpty(row, "The data to be updated cannot be empty!");
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        IdUtils.formatMapId(MODEL, row);
        Map<String, Object> result = modelService.updateOneAndFetch(MODEL, row, ConvertType.REFERENCE);
        evictIfRolesTouched(row);
        return ApiResponse.success(result);
    }

    /**
     * A UserAccount write only affects that one user's PermissionInfo, so evict
     * exactly that user when the payload carried the {@code roles} field. No-op
     * for non-roles updates (pure pass-through, matching the generic endpoint).
     * Runs after the update call returns (its own transaction has committed),
     * so there's no pre-commit stale-reload race.
     */
    private void evictIfRolesTouched(Map<String, Object> row) {
        if (!row.containsKey(ROLES_FIELD)) return;
        Object idObj = row.get("id");
        Long userId = idObj instanceof Number n ? n.longValue()
                : idObj != null ? Long.valueOf(idObj.toString()) : null;
        if (userId == null) return;
        Long tenantId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getTenantId();
        permissionCacheInvalidator.evictBatch(tenantId, Set.of(userId));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = CookieUtils.getCookie(request, BaseConstant.SESSION_ID);
        cacheService.clear(RedisConstant.SESSION + sessionId);
        CookieUtils.clearCookie(response, BaseConstant.SESSION_ID);
        return ApiResponse.success();
    }

    @Operation(summary = "Lock User Account")
    @PostMapping("/lockAccount")
    public ApiResponse<Void> lockAccount(@RequestParam @NotNull Long id) {
        validateNotSelf(id, "lock");
        service.lockAccount(id);
        return ApiResponse.success();
    }

    @Operation(summary = "Unlock User Account")
    @PostMapping("/unlockAccount")
    public ApiResponse<Void> unlockAccount(@RequestParam @NotNull Long id,
                                           @RequestBody UnlockAccountDTO unlockAccountDTO) {
        validateNotSelf(id, "unlock");
        service.unlockAccount(id, unlockAccountDTO.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "Batch Unlock User Accounts")
    @PostMapping("/unlockAccounts")
    public ApiResponse<Void> unlockAccounts(@RequestBody @Valid UnlockAccountsDTO unlockAccountsDTO) {
        List<Long> userIds = unlockAccountsDTO.getIds();
        Long currentUserId = ContextHolder.getContext().getUserId();
        if (currentUserId != null && userIds.contains(currentUserId)) {
            throw new BusinessException("You cannot unlock your own account.");
        }
        service.unlockAccounts(userIds, unlockAccountsDTO.getReason());
        return ApiResponse.success();
    }

    private void validateNotSelf(Long userId, String action) {
        Long currentUserId = ContextHolder.getContext().getUserId();
        if (currentUserId != null && currentUserId.equals(userId)) {
            throw new BusinessException("You cannot " + action + " your own account.");
        }
    }

    @Operation(summary = "changeMyPassword")
    @PostMapping("/changeMyPassword")
    public ApiResponse<Void> changeMyPassword(@RequestBody @Valid ChangePasswordDTO changePasswordDTO) {
        service.changeMyPassword(changePasswordDTO.getCurrentPassword(), changePasswordDTO.getNewPassword());
        return ApiResponse.success();
    }

    @Operation(summary = "getMyAccount")
    @GetMapping("/getMyAccount")
    public ApiResponse<UserAccount> getMyAccount() {
        Long userId = ContextHolder.getContext().getUserId();
        try {
            Optional<UserAccount> accountOpt = service.getById(userId);

            if (accountOpt.isEmpty()) {
                log.warn("Current user account not found for ID: {}", userId);
                return new ApiResponse<>(ResponseCode.USER_NOT_FOUND.getCode(), "Current user account not found.",
                        null);
            }
            UserAccount account = accountOpt.get();
            // Mask sensitive fields before returning
            account.setPassword(null);
            account.setPasswordSalt(null);
            return ApiResponse.success(account);
        } catch (Exception e) {
            log.error("Error fetching current user account for ID: {}", userId, e);
            return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Failed to retrieve user account.", null);
        }
    }

    @Operation(summary = "saveMyAccount")
    @PostMapping("/saveMyAccount")
    public ApiResponse<Void> saveMyAccount(@RequestBody @Valid UserAccountDTO myAccountDTO) {
        Long currentUserId;
        try {
            currentUserId = ContextHolder.getContext().getUserId();
            if (currentUserId == null) {
                log.warn("Attempt to save current account without authenticated context.");
                return new ApiResponse<>(ResponseCode.UNAUTHORIZED.getCode(), "User not authenticated.", null);
            }
        } catch (Exception e) {
            log.error("Error retrieving user ID from context", e);
            return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Could not determine current user.", null);
        }

        try {
            UserAccount existingAccount = service.getById(currentUserId)
                    .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND,
                            "Current user account not found for update."));

            existingAccount.setNickname(myAccountDTO.getNickname());
            existingAccount.setEmail(myAccountDTO.getEmail());
            existingAccount.setMobile(myAccountDTO.getMobile());

            boolean success = service.updateOne(existingAccount);

            if (success) {
                log.info("User account updated successfully for user ID: {}", currentUserId);
                return ApiResponse.success();
            } else {
                log.error("Failed to update user account for user ID: {}. updateOne returned false.", currentUserId);
                return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Failed to update user account.", null);
            }
        } catch (BusinessException be) {
            log.warn("BusinessException while saving account for user ID {}: {}", currentUserId, be.getMessage());
            return new ApiResponse<>(be.getResponseCode() != null ? be.getResponseCode().getCode()
                    : ResponseCode.BUSINESS_EXCEPTION.getCode(), be.getMessage(), null);
        } catch (Exception e) {
            log.error("Error saving current user account for ID: {}", currentUserId, e);
            return new ApiResponse<>(ResponseCode.ERROR.getCode(), "Failed to save user account.", null);
        }
    }
}