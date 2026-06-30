package io.softa.starter.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * UserAccount + HCM identity tuple — response shape for
 * {@code GET /admin/userRefs}, consumed by admin dialogs that need to
 * render a user picker with both account fields (nickname / username /
 * email / mobile / status) and the linked employee's HCM identity
 * (employeeId / departmentId / legalEntityId) so dynamic-scope
 * compatibility can be classified per user.
 *
 * <p>{@code employeeId} / {@code departmentId} / {@code legalEntityId}
 * are null when the UserAccount has no linked Employee row — these are
 * "pure users" (e.g. system accounts, integration users). Dialogs
 * classify them as compatible with ALL / CUSTOM scopes only.
 *
 * <p>Timestamp fields are ISO strings to keep the wire shape independent
 * of the FE's date library.
 */
@Schema(description = "UserAccount + HCM identity for admin dialogs")
public record UserRef(
        @Schema(description = "UserAccount.id")
        Long id,

        @Schema(description = "Display nickname")
        String nickname,

        @Schema(description = "Login username")
        String username,

        @Schema(description = "Email address")
        String email,

        @Schema(description = "Mobile number")
        String mobile,

        @Schema(description = "AccountStatus code (Active / Disabled / ...)")
        String status,

        @Schema(description = "Created timestamp (ISO string)")
        String createdTime,

        @Schema(description = "Updated timestamp (ISO string)")
        String updatedTime,

        @Schema(description = "Linked Employee.id, or null when user is not bound to any Employee row")
        Long employeeId,

        @Schema(description = "Employee.departmentId for the linked employee (null if unbound)")
        Long departmentId,

        @Schema(description = "Employee.legalEntityId for the linked employee (null if unbound)")
        Long legalEntityId
) {
}
