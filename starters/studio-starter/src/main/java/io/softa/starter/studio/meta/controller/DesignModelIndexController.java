package io.softa.starter.studio.meta.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.softa.starter.studio.meta.entity.DesignModelIndex;
import io.softa.starter.studio.meta.service.DesignModelIndexService;

/**
 * DesignModelIndex Model Controller
 */
@Tag(name = "DesignModelIndex")
@RestController
@RequestMapping("/DesignModelIndex")
public class DesignModelIndexController extends AbstractDesignWriteController<DesignModelIndexService, DesignModelIndex> {

    @Override
    protected String modelName() {
        return "DesignModelIndex";
    }
}