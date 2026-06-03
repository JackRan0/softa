package io.softa.starter.file.excel.export.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.starter.file.dto.ExportResult;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.excel.export.ExcelSheetData;
import io.softa.starter.file.excel.export.ResolvedTemplateSheet;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.ExportDataFetcher;
import io.softa.starter.file.excel.export.support.ExportTemplateResolver;
import io.softa.starter.file.excel.style.CustomExportStyleHandler;

/**
 * Export by template.
 */
@Slf4j
@Component
public class ExportByFieldTemplate implements ExportStrategy {

    @Autowired
    private ExportTemplateResolver exportTemplateResolver;

    @Autowired
    private ExportDataFetcher exportDataFetcher;

    @Autowired
    private ExcelUploadService excelUploadService;

    /**
     * Export data by exportTemplate configured exported fields.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param exportTemplate exportTemplate object
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    public ExportResult export(ExportTemplate exportTemplate, FlexQuery flexQuery) {
        String fileName = exportTemplate.getFileName();
        String sheetName = exportTemplate.getSheetName();
        ResolvedTemplateSheet resolvedTemplateSheet = exportTemplateResolver.resolve(exportTemplate);
        flexQuery.setFields(resolvedTemplateSheet.getFetchFields());
        List<Map<String, Object>> rows = exportDataFetcher.fetchRows(exportTemplate.getModelName(),
                exportTemplate.getCustomHandler(), flexQuery);
        List<List<Object>> rowsTable = exportTemplateResolver.resolveRows(resolvedTemplateSheet, rows);
        ExcelSheetData sheetData = new ExcelSheetData(StringUtils.isNotBlank(sheetName) ? sheetName : fileName,
                resolvedTemplateSheet.getHeaders(), rowsTable, new CustomExportStyleHandler[]{new CustomExportStyleHandler()});
        FileInfo fileInfo = excelUploadService.generateFileAndUpload(exportTemplate.getModelName(), fileName, sheetData);
        return new ExportResult(fileInfo, rowsTable.size());
    }

    @Override
    public ExportMode getMode() {
        return ExportMode.FIELD_TEMPLATE;
    }

    @Override
    public ExportResult export(ExportContext exportContext) {
        return export(exportContext.getExportTemplate(), exportContext.getFlexQuery());
    }

    /**
     * Export multiple sheets of data by dynamic fields and QueryParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param exportTemplates the list of exportTemplates
     * @return fileInfo object with download URL
     */
    public FileInfo exportMultiSheet(String fileName, List<ExportTemplate> exportTemplates) {
        return this.generateFileInfo(fileName, exportTemplates, Map.of());
    }

    public FileInfo dynamicExportMultiSheet(String fileName, List<ExportTemplate> exportTemplates, Map<Long, Filters> dynamicTemplateMap) {
        return this.generateFileInfo(fileName, exportTemplates, dynamicTemplateMap);
    }

    private FileInfo generateFileInfo(String fileName, List<ExportTemplate> exportTemplates, Map<Long, Filters> dynamicTemplateMap) {
        List<ExcelSheetData> sheetDataList = new ArrayList<>();
        for (ExportTemplate exportTemplate : exportTemplates) {
            ResolvedTemplateSheet resolvedTemplateSheet = exportTemplateResolver.resolve(exportTemplate);
            FlexQuery flexQuery;
            if (dynamicTemplateMap.isEmpty()) {
                flexQuery = new FlexQuery(exportTemplate.getFilters(), exportTemplate.getOrders());
            } else {
                flexQuery = new FlexQuery(exportTemplate.getFilters(), exportTemplate.getOrders());
                flexQuery.setFilters(Filters.and(flexQuery.getFilters(), dynamicTemplateMap.get(exportTemplate.getId())));
            }
            flexQuery.setConvertType(ConvertType.DISPLAY);
            flexQuery.setFields(resolvedTemplateSheet.getFetchFields());
            List<Map<String, Object>> rows = exportDataFetcher.fetchRows(exportTemplate.getModelName(),
                    exportTemplate.getCustomHandler(), flexQuery);
            List<List<Object>> rowsTable = exportTemplateResolver.resolveRows(resolvedTemplateSheet, rows);
            String sheetName = StringUtils.isNotBlank(exportTemplate.getSheetName()) ? exportTemplate.getSheetName() : fileName;
            sheetDataList.add(new ExcelSheetData(sheetName, resolvedTemplateSheet.getHeaders(), rowsTable,
                    new CustomExportStyleHandler[]{new CustomExportStyleHandler()}));
        }
        return excelUploadService.generateFileAndUpload("", fileName, sheetDataList);
    }

}
