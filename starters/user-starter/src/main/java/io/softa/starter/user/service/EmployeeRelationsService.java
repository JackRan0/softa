package io.softa.starter.user.service;

import java.util.Collection;
import java.util.List;

import io.softa.framework.orm.annotation.SkipPermissionCheck;

/**
 * User ↔ Employee identity bridge — translates between user-starter's
 * {@code userId} and the HR module's {@code employeeId}. Used by
 * user-starter services that need to react to HR domain events:
 * <ul>
 *   <li>{@code PermissionCacheInvalidatorImpl} — turns an
 *       {@code EmployeeChangedEvent} into the cache key
 *       {@code perm:{tenantId}:user:{userId}}.</li>
 *   <li>{@code DynamicRoleSyncJobImpl} — same, for incremental dynamic-role
 *       resync.</li>
 *   <li>{@code UserRefsController} — enriches the FE Add-Members dialog
 *       with HCM identity per UserAccount.</li>
 * </ul>
 *
 * <p>Loading the full HR identity ({@code empId + deptId + companyId +
 * managedDeptIds}, packaged as {@code EmpInfo}) for
 * {@link io.softa.starter.user.dto.Principal} enrichment is no longer in
 * this SPI's contract — it's handled by the HR module's
 * {@link io.softa.starter.user.service.PrincipalEnrichmentContributor}
 * implementation.
 *
 * <p>Lives in {@code user-starter} as an optional bridge interface — when
 * the HR module isn't on the classpath, {@code ObjectProvider} resolves to
 * empty and the consuming services degrade gracefully (TTL-based cache
 * eviction instead of event-driven, no FE enrichment).
 */
public interface EmployeeRelationsService {

    /**
     * Reverse lookup: given an Employee row's id, return the linked
     * {@code userId} and {@code tenantId}, or null when no Employee row
     * matches (or it has no linked user). Used by
     * {@code PermissionCacheInvalidatorImpl} to translate
     * {@code EmployeeChangedEvent} (which only carries {@code employeeId})
     * into the cache key {@code perm:{tenantId}:user:{userId}}.
     */
    @SkipPermissionCheck
    UserHandle findUserByEmployeeId(Long employeeId);

    /**
     * Batch reverse lookup: for each {@code userId} whose UserAccount is
     * linked to an Employee row, return the HCM-side identity (employeeId
     * + departmentId + legalEntityId) for that user. UserAccounts without
     * a linked Employee are simply absent from the result list — callers
     * treat them as "pure user".
     *
     * <p>Used by {@code /admin/userRefs} to enrich the Add-Members
     * dialog so it can correctly classify role compatibility per user
     * (dynamic scopes need a real employeeId). One SQL with {@code
     * employee.user_id IN (...)} — the dialog's UserAccount page is
     * capped at 1000, well under any DB's IN limit, so no chunking
     * is required at this layer.
     *
     * @param userIds caller's user id set (deduped client-side; null/empty
     *                returns an empty list)
     */
    @SkipPermissionCheck
    List<UserHcmContext> findEmployeesByUserIds(Collection<Long> userIds);

    /** Minimal tuple — kept inline so callers don't need to know about
     *  corehr's Employee entity. */
    record UserHandle(Long userId, Long tenantId) {
    }

    /** HCM-side identity of a UserAccount — populated only when the user
     *  is linked to an Employee row. {@code employeeId} is always set
     *  (it's the lookup result); department / legalEntity may be null if
     *  the Employee row doesn't carry them. */
    record UserHcmContext(Long userId, Long employeeId, Long departmentId, Long legalEntityId) {
    }
}
