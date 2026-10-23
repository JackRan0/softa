package io.softa.starter.file.excel.imports.handler;

import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.meta.MetaField;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.excel.imports.RequiredOnCreateResolver;

/**
 * BaseImportHandler
 */
public abstract class BaseImportHandler {

    protected final MetaField metaField;
    protected final String modelName;
    protected final String fieldName;
    protected final String label;
    protected final ImportFieldDTO importFieldDTO;

    /**
     * The row map key this handler reads and writes. Defaults to the field name, which is also the
     * imported column key for a direct field. A nested OneToOne sub-field is keyed by its dotted
     * path instead (e.g. {@code employeeProfileId.gender}), because at this stage the row still
     * carries the flat imported columns — they are folded into the nested value object later, by
     * {@code RelationLookupResolver}.
     */
    protected String rowKey;

    public BaseImportHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        this.metaField = metaField;
        this.modelName = metaField.getModelName();
        this.fieldName = metaField.getFieldName();
        // The column's own header, not the field's label. They differ exactly where it matters most:
        // a dotted column is handled by its ROOT field's metadata, so every required cell under one
        // sub-record reported the same name — five blank columns, five times "The field `Employee
        // Profile` is required", and nothing saying which five. The header is the text in the
        // spreadsheet the reader is looking at.
        this.label = StringUtils.defaultIfBlank(importFieldDTO.getHeader(), metaField.getLabel());
        this.importFieldDTO = importFieldDTO;
        this.rowKey = metaField.getFieldName();
    }

    /**
     * Point this handler at a different row key — used for nested OneToOne sub-fields, whose
     * metadata lives on the related model while the imported column is keyed by the dotted path.
     *
     * @param rowKey the row map key to read and write
     * @return this handler
     */
    public BaseImportHandler rowKey(String rowKey) {
        this.rowKey = rowKey;
        return this;
    }

    /**
     * Handle the rows with skipException support.
     * When skipException=true, catch ValidationException and set the failed reason to the row.
     * When skipException=false, let the ValidationException propagate to trigger a transaction rollback.
     *
     * @param rows The rows
     * @param skipException Whether to skip exceptions
     */
    public void handleRows(List<Map<String, Object>> rows, boolean skipException) {
        rows.forEach(row -> {
            if (skipException) {
                try {
                    handleRow(row);
                } catch (ValidationException e) {
                    String failedReason = "";
                    if (row.get(FileConstant.FAILED_REASON) != null) {
                        failedReason = row.get(FileConstant.FAILED_REASON) + "; ";
                    }
                    failedReason += e.getMessage();
                    row.put(FileConstant.FAILED_REASON, failedReason);
                }
            } else {
                handleRow(row);
            }
        });
    }

    /**
     * Handle the row
     * Properties of the row will be modified:
     *      - Check if the field is required
     *      - Set the default value if the field is empty and the default value is set
     *      - Remove the field if the field is empty and ignoreEmpty is true
     *
     * @param row The row
     */
    public void handleRow(Map<String, Object> row) {
        Object value = row.get(rowKey);
        boolean isEmpty = valueIsEmpty(value);
        if (isEmpty) {
            // Default BEFORE required. A field with a template default can never be missing — the
            // default is what a blank cell means — so requiredness is only ever a question for fields
            // with no default. The old order raised on required-and-defaulted fields, and that pair is
            // not a misconfiguration anyone has to write: the handler factory promotes the MODEL's
            // required onto the template field, so a metadata-required column with a template default
            // (the per-country Country column, stamped SG or NZ) rejected the very blank the default
            // exists for.
            if (importFieldDTO.getDefaultValue() != null) {
                row.put(rowKey, importFieldDTO.getDefaultValue());
            } else if (Boolean.TRUE.equals(importFieldDTO.getIgnoreEmpty())) {
                // A dropped blank keeps the stored value on an update, so a required column may be
                // left blank on a row that updates. Which kind of row this is is not known yet: the
                // blank is noted, and RequiredOnCreateResolver fails the row only if it creates.
                if (Boolean.TRUE.equals(importFieldDTO.getRequired())) {
                    RequiredOnCreateResolver.record(row, label);
                }
                row.remove(rowKey);
            } else {
                checkRequired();
            }
        } else {
            row.put(rowKey, handleValue(value));
        }
    }

    /**
     * Handle the value of the field
     *
     * @param value The value
     * @return The handled value
     */
    public Object handleValue(Object value) {
        // handle value
        return value;
    }

    /**
     * Check whether the value is empty
     *
     * @param value The value
     * @return Whether the value is empty
     */
    public boolean valueIsEmpty(Object value) {
        return value == null || (value instanceof String valueStr && StringUtils.isBlank(valueStr));
    }

    /**
     * Check required
     */
    public void checkRequired() {
        if (Boolean.TRUE.equals(importFieldDTO.getRequired())) {
            throw new ValidationException("The field `{0}` is required", label);
        }
    }

}
