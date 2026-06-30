package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * Sensitive field set — a named bundle of fields on one model that require
 * explicit grant to view. Admin grants per (role, navigation) which set ids apply.
 * Naming convention: kebab-case with model prefix (e.g. 'employee-compensation').
 */
@Data
@Schema(name = "SensitiveFieldSet")
@EqualsAndHashCode(callSuper = true)
public class SensitiveFieldSet extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Set ID (e.g. 'employee-compensation')")
    private String id;

    @Schema(description = "Bound model name (MetaModel.modelName, PascalCase). Set only applies to this model")
    private String model;

    @Schema(description = "Display name (e.g. 'Employee Compensation')")
    private String name;

    @Schema(description = "Field codes covered by this set. Must be actual fields on the bound model (validated at startup)")
    private JsonNode fieldCodes;

    /**
     * UI-only hint: extra MetaModel names whose Wizard nav rows should also
     * list this SFS as configurable (in addition to {@link #model}). Pure
     * UX aggregation — does NOT change the mask engine's authority. The
     * canonical {@code model} field still drives Layer C masking, Layer D
     * write-rejection, and the {@code SensitiveFieldSetCache} grouping.
     *
     * <p>Typical use: a SFS bound to {@code EmpBankAccount} can declare
     * {@code attachedTo: ["Employee"]} so admins can check it on the
     * Employee nav row in the role Wizard, without having to drill into a
     * separate EmpBankAccount nav. The mask engine still resolves the SFS
     * via {@code SensitiveFieldSetCache.modelOf(setId)} → "EmpBankAccount",
     * so OneToOne sub-objects under Employee responses ({@code Employee.
     * empBankAcc.*}) get correctly unmasked / masked.
     *
     * <p>JSON array of MetaModel names (PascalCase). Null / empty array →
     * SFS only appears under its own {@link #model} nav row in the Wizard.
     * Validator ⑪ checks every referenced MetaModel exists.
     */
    @Schema(description =
            "Optional extra MetaModel names whose Wizard nav rows should "
                    + "also list this SFS. UI hint only — does not change "
                    + "mask authority (still controlled by `model`).")
    private JsonNode attachedTo;

    @Schema(description = "Optional description")
    private String description;
}
