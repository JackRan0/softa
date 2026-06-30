package io.softa.starter.user.service;

import java.util.List;

import io.softa.starter.user.dto.BulkAddResult;
import io.softa.starter.user.dto.UserRolePair;
import io.softa.starter.user.enums.RoleSource;

/**
 * Batch user-role assignment service (entry C: M users × N roles).
 * Partial-success semantics — each pair commits via its own SAVEPOINT so one
 * failure doesn't abort the rest. Frontend pre-filters pairs by classifyRoleForUser
 * + bulk decision, so this service never carries compat semantics.
 *
 * Side effects: triggers PermissionCacheInvalidator.evictBatch(affectedUserIds)
 * and writes a single BULK_USER_ROLE_ADD audit log entry per call.
 *
 * Upper bound: pairs.size() ≤ 1000 (caller responsibility; this service throws
 * BadRequestException when exceeded).
 */
public interface BulkUserRoleService {

    /**
     * @param pairs  user-role pairs to insert
     * @param source either MANUAL (admin UI) or DYNAMIC (job, internal use)
     * @return per-pair add / skip result + summary counts
     */
    BulkAddResult bulkAdd(List<UserRolePair> pairs, RoleSource source);
}
