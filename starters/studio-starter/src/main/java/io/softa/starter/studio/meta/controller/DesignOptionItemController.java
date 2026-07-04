package io.softa.starter.studio.meta.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.starter.studio.meta.entity.DesignOptionItem;
import io.softa.starter.studio.meta.service.DesignOptionItemService;

/**
 * DesignOptionItem Model Controller
 */
@Tag(name = "DesignOptionItem")
@RestController
@RequestMapping("/DesignOptionItem")
public class DesignOptionItemController extends AbstractDesignWriteController<DesignOptionItemService, DesignOptionItem> {

    @Override
    protected String modelName() {
        return "DesignOptionItem";
    }

    @Override
    protected String renameKeyField() {
        return "itemCode";
    }
}