package io.softa.starter.file.service;

import java.util.List;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.file.dto.ExportTemplateDTO;
import io.softa.starter.file.dto.SheetInfo;

public interface ExportService {

    /**
     * Export data by dynamic fields and ExportParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param modelName the model name to be exported
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    FileInfo dynamicExport(String modelName, FlexQuery flexQuery);

    /**
     * Export multiple sheets of data by dynamic fields and ExportParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param sheetInfoList the list of sheetInfo objects
     * @return fileInfo object with download URL
     */
    FileInfo dynamicExportMultiSheet(String fileName, List<SheetInfo> sheetInfoList);

    /**
     * Export data by exportTemplate configured exported fields.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param exportTemplateId the ID of the export template
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    FileInfo exportByTemplate(Long exportTemplateId, FlexQuery flexQuery);

    /**
     * Export multiple sheets merged to on Excel file by specifying multi export templates.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param ids the list of export template id
     * @return fileInfo object with download URL
     */
    FileInfo exportByMultiTemplate(String fileName, List<Long> ids);

    FileInfo dynamicExportByMultiTemplate(String fileName, List<ExportTemplateDTO> dtoList);

}
