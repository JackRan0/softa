package io.softa.starter.user.controller;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.UserRef;
import io.softa.starter.user.service.EmployeeRelationsService;
import io.softa.starter.user.util.ModelRefIds;

/**
 * Returns a list of {@code UserRef} rows for the admin Add-Members /
 * AssignRoles dialogs. Each row carries the UserAccount auth identity
 * (nickname / username / email / mobile / status) plus its HCM-side
 * identity (employeeId / departmentId / legalEntityId) when the user is
 * linked to an Employee.
 *
 * <h3>Why this endpoint exists</h3>
 * The dialogs previously read UserAccount directly via the generic
 * {@code /UserAccount/searchList} model endpoint and hardcoded
 * {@code employeeId=null} client-side — every user looked like a "Pure
 * user", so role-compatibility classification was always wrong for users
 * that DID have an Employee row. This endpoint joins the two on the BE
 * with one extra IN query and ships the merged payload.
 *
 * <h3>Cost</h3>
 * Two queries: (1) UserAccount page (cap 1000), (2) Employee where
 * {@code userId IN (...)}. Both indexed; ~10ms total for typical tenants.
 *
 * <h3>Degradation when corehr isn't on the classpath</h3>
 * {@link EmployeeRelationsService} is injected as an {@link ObjectProvider}
 * so this starter still boots in a non-HCM app. Without the bean, all
 * users come back with null HCM context — same effective behaviour as
 * before this endpoint existed.
 */
@Slf4j
@Tag(name = "Admin User Refs")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class UserRefsController {

    /** Max number of UserAccount rows returned. Matches the dialog's
     *  client-side pagination so the BE never silently truncates. If the
     *  dialog adds server-side search/filter, lift this. */
    private static final int USER_PAGE_CAP = 1000;

    private final ModelService<?> modelService;
    private final ObjectProvider<EmployeeRelationsService> employeeRelations;

    @GetMapping("/userRefs")
    @Operation(summary = "List user refs (UserAccount + HCM identity) for admin dialogs")
    public ApiResponse<List<UserRef>> listUserRefs() {
        FlexQuery q = new FlexQuery();
        q.setLimitSize(USER_PAGE_CAP);
        // Pull only the columns the dialog actually needs — and crucially
        // skip the framework-side entity mapping by reading raw rows as
        // Map. The full-entity overload runs BeanTool's reflective
        // conversion across every column (createdId / updatedId being
        // 17-digit snowflake Longs), which we hit a JsonNode/Long
        // conversion bug on in this codebase. Map rows sidestep that
        // path entirely.
        q.setFields(List.of(
                "id", "nickname", "username", "email", "mobile",
                "status", "createdTime", "updatedTime"));
        List<Map<String, Object>> users = modelService.searchList("UserAccount", q);

        // Pull HCM context in a single IN query — empty/missing → null
        // employee fields, which classifies the user as "Pure" downstream.
        Map<Long, EmployeeRelationsService.UserHcmContext> ctxByUser = loadHcmContext(users);

        List<UserRef> out = new java.util.ArrayList<>(users.size());
        for (Map<String, Object> u : users) {
            Long id = ModelRefIds.extractLongId(u.get("id"));
            EmployeeRelationsService.UserHcmContext ctx = ctxByUser.get(id);
            out.add(new UserRef(
                    id,
                    asString(u.get("nickname")),
                    asString(u.get("username")),
                    asString(u.get("email")),
                    asString(u.get("mobile")),
                    asString(u.get("status")),
                    asString(u.get("createdTime")),
                    asString(u.get("updatedTime")),
                    ctx == null ? null : ctx.employeeId(),
                    ctx == null ? null : ctx.departmentId(),
                    ctx == null ? null : ctx.legalEntityId()));
        }
        return ApiResponse.success(out);
    }

    /** Resolve employeeId / departmentId / legalEntityId for every user
     *  who has an Employee row. Returns empty map when corehr isn't on
     *  the classpath (graceful degradation — caller treats every user as
     *  pure). */
    private Map<Long, EmployeeRelationsService.UserHcmContext> loadHcmContext(
            List<Map<String, Object>> users) {
        EmployeeRelationsService rel = employeeRelations.getIfAvailable();
        if (rel == null || users.isEmpty()) return Map.of();
        Set<Long> userIds = new HashSet<>(users.size());
        for (Map<String, Object> u : users) {
            Long id = ModelRefIds.extractLongId(u.get("id"));
            if (id != null) userIds.add(id);
        }
        if (userIds.isEmpty()) return Map.of();
        List<EmployeeRelationsService.UserHcmContext> rows;
        try {
            rows = rel.findEmployeesByUserIds(userIds);
        } catch (Throwable t) {
            // Don't fail the dialog on a corehr-side hiccup — degrade to
            // "every user is pure" and log loudly so ops can investigate.
            log.warn("UserRefs — findEmployeesByUserIds failed; degrading to no HCM context", t);
            return Map.of();
        }
        Map<Long, EmployeeRelationsService.UserHcmContext> out = new HashMap<>(rows.size());
        for (EmployeeRelationsService.UserHcmContext c : rows) {
            if (c.userId() != null) out.put(c.userId(), c);
        }
        return out;
    }

    /** Map row values stringification — handles String, LocalDateTime (via
     *  toString), and any other type with reasonable {@code toString()}. */
    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

}
