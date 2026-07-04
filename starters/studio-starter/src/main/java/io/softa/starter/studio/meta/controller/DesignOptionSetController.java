package io.softa.starter.studio.meta.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.starter.studio.meta.entity.DesignOptionSet;
import io.softa.starter.studio.meta.service.DesignOptionSetService;

/**
 * DesignOptionSet Model Controller
 */
@Tag(name = "DesignOptionSet")
@RestController
@RequestMapping("/DesignOptionSet")
public class DesignOptionSetController extends AbstractDesignWriteController<DesignOptionSetService, DesignOptionSet> {

    @Override
    protected String modelName() {
        return "DesignOptionSet";
    }

    @Override
    protected String renameKeyField() {
        return "optionSetCode";
    }
}