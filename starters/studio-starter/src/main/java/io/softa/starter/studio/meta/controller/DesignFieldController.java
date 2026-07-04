package io.softa.starter.studio.meta.controller;

import java.util.Map;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.service.DesignFieldService;
import io.softa.starter.studio.meta.support.DesignFieldRelationStamper;

/**
 * DesignField Model Controller.
 *
 * <p>On top of the per-env identity stamp inherited from {@link AbstractDesignWriteController}, each
 * write row also has the system-computed {@code relatedFieldType} (and mirrored {@code length}/{@code
 * scale}) stamped — so a TO_ONE FK's physical type is materialized at edit time and read straight
 * back by every DDL path. All other model APIs (getById, search, delete, copy, ...) fall through to
 * the generic controller unchanged.
 */
@Tag(name = "DesignField")
@RestController
@RequestMapping("/DesignField")
public class DesignFieldController extends AbstractDesignWriteController<DesignFieldService, DesignField> {

    private static final String MODEL = "DesignField";

    @Autowired
    private DesignFieldRelationStamper relationStamper;

    @Override
    protected String modelName() {
        return MODEL;
    }

    @Override
    protected String renameKeyField() {
        return "fieldName";
    }

    @Override
    protected void onCreate(Map<String, Object> row) {
        super.onCreate(row);
        relationStamper.stamp(row);
    }

    @Override
    protected void onUpdate(Map<String, Object> row) {
        super.onUpdate(row);
        relationStamper.stamp(row);
    }

    /**
     * Apply a {@code DesignFieldDomain} to a field as a one-time template: fill the field's
     * type + defaults from the domain and record {@code domainId} (design-time provenance). The field
     * stays freely editable afterwards (no live binding / propagation).
     */
    @PostMapping("/applyDomain")
    public ApiResponse<DesignField> applyDomain(@RequestParam Long fieldId, @RequestParam Long domainId) {
        return ApiResponse.success(service.applyDomain(fieldId, domainId));
    }
}
