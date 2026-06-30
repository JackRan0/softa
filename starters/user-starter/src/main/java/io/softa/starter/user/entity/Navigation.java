package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.user.enums.NavigationType;

/**
 * Navigation node — sidebar / route entry registered by dev's manifest.
 * Forms a tree via parentId; type ∈ GROUP / MENU / BUTTON / TAB.
 * Loaded from data-system/navigation.json at startup.
 */
@Data
@Schema(name = "Navigation")
@EqualsAndHashCode(callSuper = true)
public class Navigation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Navigation ID, business-named (e.g. core-hr.employee.employee)")
    private String id;

    @Schema(description = "Parent navigation ID; null for top-level nodes")
    private String parentId;

    @Schema(description = "Display name (e.g. 'Employees')")
    private String name;

    @Schema(description = "Frontend route (Next.js dynamic segments allowed). Null for GROUP / TAB / pure-container MENU")
    private String route;

    @Schema(description = "Node type: GROUP / MENU / BUTTON / TAB")
    private NavigationType type;

    @Schema(description = "Primary model code (PascalCase, matches sys_model.model_name). Null for GROUP and pure-container MENU")
    private String model;

    @Schema(description = "Sort order within siblings")
    private Integer sortOrder;
}
