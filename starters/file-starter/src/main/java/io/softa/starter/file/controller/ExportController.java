package io.softa.starter.file.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.file.service.ExportService;
import io.softa.starter.file.vo.ExportParams;

/**
 * ExportController
 */
@Tag(name = "Data Export")
@RestController
@RequestMapping("/export")
public class ExportController {

    @Autowired
    private ExportService exportService;

    /**
     * Export data by dynamic fields and ExportParams, without export template.
     * The convertType is set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param modelName the model name to be exported
     * @param exportParams the export parameters of the data to be exported
     * @return fileInfo object with download URL
     */
    @Operation(description = "Export data by dynamic fields and ExportParams, without export template.")
    @PostMapping(value = "/dynamicExport")
    public ApiResponse<FileInfo> dynamicExport(@RequestParam String modelName,
                                               @RequestBody ExportParams exportParams) {
        FlexQuery flexQuery = ExportParams.convertParamsToFlexQuery(exportParams);
        return ApiResponse.success(exportService.dynamicExport(modelName, flexQuery));
    }

    /**
     * Export data by exportTemplate configured exported fields or a custom file template.
     * The custom file template is a template file that contains the variables to be filled in.
     * The convertType is set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param exportTemplateId The ID of the export template
     * @param exportParams the export parameters of the data to be exported
     * @return fileInfo object with download URL
     */
    @Operation(description = "Export data by exportTemplate configured exported fields or a custom file template.")
    @PostMapping(value = "/exportByTemplate")
    @Parameter(name = "exportTemplateId", description = "The id of the ExportTemplate.")
    public ApiResponse<FileInfo> exportByTemplate(@RequestParam Long exportTemplateId,
                                                  @RequestBody ExportParams exportParams) {
        FlexQuery flexQuery = ExportParams.convertParamsToFlexQuery(exportParams);
        return ApiResponse.success(exportService.exportByTemplate(exportTemplateId, flexQuery));
    }

}
