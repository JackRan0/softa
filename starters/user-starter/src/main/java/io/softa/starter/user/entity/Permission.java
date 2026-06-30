package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * Permission entry — a single business capability (button / API action) under a navigation.
 * Default standard CRUD permissions (list / detail / create / update / ...) are
 * derived per navigation; custom actions declare their own permission rows with
 * explicit endpoints.
 */
@Data
@Schema(name = "Permission")
@EqualsAndHashCode(callSuper = true)
public class Permission extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Permission ID (e.g. core-hr.employee.employee.transfer)")
    private String id;

    @Schema(description = "Navigation ID this permission belongs to (FK navigation.id)")
    private String navigationId;

    @Schema(description = "Display name shown in admin Wizard (e.g. 'Transfer Employee')")
    private String name;

    @Schema(description = "Explicit endpoint list for non-conventional URLs; null means EndpointIndex derives by convention. Format: ['POST /api/<Model>/<action>', ...]")
    private JsonNode endpoints;

    @Schema(description = "Optional description")
    private String description;
}
