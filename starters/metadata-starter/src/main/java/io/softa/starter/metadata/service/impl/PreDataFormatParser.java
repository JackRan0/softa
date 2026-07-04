package io.softa.starter.metadata.service.impl;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FileObject;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.meta.ModelManager;

/**
 * Parses a predefined-data file into an ordered {@code modelName -> data} map,
 * decoupling file-format concerns (JSON / CSV / XML) from the predefined-data
 * loading logic in {@link SysPreDataServiceImpl}.
 *
 * <p>Each entry value is either a single {@code Map<String, Object>} row or a
 * {@code List<Map<String, Object>>} of rows — the two shapes the loader accepts.
 * A {@link LinkedHashMap} preserves declaration order so dependent rows are
 * loaded after the rows they reference.
 *
 * <p>Stateless and Spring-free (instantiated directly, like
 * {@code AnnotationParser} / {@code DiffEngine}). It reads {@link ModelManager}
 * statically only to type CSV string cells against the target model's field
 * types — JSON already carries typed values.
 */
public final class PreDataFormatParser {

    /**
     * Parse a predefined-data file into an ordered {@code modelName -> Map|List<Map>}
     * structure. Blank content yields an empty map (nothing to load).
     *
     * @param fileObject file content + name + type
     * @return ordered model-to-data map
     */
    public LinkedHashMap<String, Object> parse(FileObject fileObject) {
        if (StringUtils.isBlank(fileObject.getContent())) {
            return new LinkedHashMap<>();
        }
        FileType fileType = fileObject.getFileType();
        if (FileType.JSON.equals(fileType)) {
            return parseJson(fileObject.getContent());
        } else if (FileType.CSV.equals(fileType)) {
            return parseCsv(fileObject);
        } else if (FileType.XML.equals(fileType)) {
            return parseXml(fileObject.getContent());
        }
        throw new IllegalArgumentException("Unsupported file type for predefined data: {0}", fileType);
    }

    /**
     * JSON supports a two-layer model nest, mapped into a {@link LinkedHashMap}
     * to preserve declaration order. Data under each model is either a single
     * {@code Map} or a {@code List<Map>}:
     * <ul>
     *     <li>{ model1: {field1: value1, ...}, model2: {...}, ... }</li>
     *     <li>{ model1: [{field1: value1}, {...}], model2: {...}, ... }</li>
     * </ul>
     *
     * @param content JSON string data content
     */
    private LinkedHashMap<String, Object> parseJson(String content) {
        return JsonUtils.stringToObject(content, new TypeReference<>() {
        });
    }

    /**
     * CSV holds a single model's rows; the model name is the file-name stem
     * (the part before the first {@code .}). The first line is the header naming
     * the fields. Each cell is typed against the model's field type: the {@code id}
     * and ManyToOne / OneToOne columns keep their String preId, every other field
     * is converted from its string form.
     *
     * @param fileObject CSV file (name supplies the model, content the rows)
     * @return single-entry map: {modelName -> List<rowMap>}
     */
    private LinkedHashMap<String, Object> parseCsv(FileObject fileObject) {
        String fileName = fileObject.getFileName();
        String modelName = fileName.substring(0, fileName.indexOf('.')).trim();
        Assert.isTrue(ModelManager.existModel(modelName),
                "Model {0} specified in the fileName `{1}` does not exist!", modelName, fileName);

        // Parse the CSV content, automatically detecting and skipping the header line.
        CSVFormat csvFormat = CSVFormat.Builder.create()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get();
        CSVParser parser;
        try {
            parser = csvFormat.parse(new StringReader(fileObject.getContent()));
        } catch (IOException e) {
            throw new SystemException("Failed to parse the CSV content: {0}", e.getMessage());
        }
        Map<String, Integer> headerMap = parser.getHeaderMap();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CSVRecord record : parser) {
            Map<String, Object> rowData = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> header : headerMap.entrySet()) {
                String fieldName = header.getKey().trim();
                Assert.notBlank(fieldName, "The field name in the CSV header cannot be empty!");
                String stringValue = record.get(header.getValue()).trim();
                FieldType fieldType = ModelManager.getModelField(modelName, fieldName).getFieldType();
                if (ModelConstant.ID.equals(fieldName) || FieldType.TO_ONE_TYPES.contains(fieldType)) {
                    // Retain the preId of ID, ManyToOne, and OneToOne fields as String values.
                    rowData.put(fieldName, stringValue);
                } else {
                    rowData.put(fieldName, FieldType.convertStringToFieldValue(fieldType, stringValue));
                }
            }
            rows.add(rowData);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put(modelName, rows);
        return result;
    }

    /**
     * XML predefined-data parsing is not yet supported. It fails fast rather than
     * silently loading nothing, so an XML file is never mistaken for a no-op.
     */
    private LinkedHashMap<String, Object> parseXml(String content) {
        throw new IllegalArgumentException("XML predefined-data format is not yet supported.");
    }
}
