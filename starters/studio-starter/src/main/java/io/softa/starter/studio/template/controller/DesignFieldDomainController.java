package io.softa.starter.studio.template.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.studio.template.entity.DesignFieldDomain;
import io.softa.starter.studio.template.service.DesignFieldDomainService;

/**
 * DesignFieldDomain Model Controller (renamed from DesignFieldTypeDefaultController).
 */
@Tag(name = "DesignFieldDomain")
@RestController
@RequestMapping("/DesignFieldDomain")
public class DesignFieldDomainController
        extends EntityController<DesignFieldDomainService, DesignFieldDomain, Long> {

}
