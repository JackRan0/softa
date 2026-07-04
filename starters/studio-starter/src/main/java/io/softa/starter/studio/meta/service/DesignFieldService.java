package io.softa.starter.studio.meta.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.studio.meta.entity.DesignField;

/**
 * DesignField Model Service Interface.
 */
public interface DesignFieldService extends EntityService<DesignField, Long> {

    /**
     * Apply a {@code DesignFieldDomain} to a field as a <b>one-time template</b>: copy the
     * domain's {@code fieldType} / {@code length} / {@code scale} / {@code defaultValue} / {@code widgetType}
     * onto the field and record {@code domainId} (design-time provenance). NOT a live binding — the field is
     * freely editable afterwards and a later domain change does not propagate (re-apply to re-template).
     * {@code domainId} is design-only (never checksummed nor shipped), so the converge engine is unaffected.
     *
     * @param fieldId  the field to fill
     * @param domainId the domain to apply
     * @return the updated field
     */
    DesignField applyDomain(Long fieldId, Long domainId);
}
