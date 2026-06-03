package io.softa.starter.file.excel.imports.handler;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.OptionManager;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * OptionHandler
 * Compatible with the label and itemCode of OptionItem.
 */
public class OptionHandler extends BaseImportHandler {

    public OptionHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        super(metaField, importFieldDTO);
    }

    /**
     * Handle the option value
     * @param value The option value
     * @return The option itemCode
     */
    public Object handleValue(Object value) {
        if (value instanceof String optionStr && StringUtils.isNotBlank(optionStr)) {
            optionStr = optionStr.trim();
            String optionSetCode = metaField.getOptionSetCode();
            if (OptionManager.existsItemCode(optionSetCode, optionStr)) {
                return optionStr;
            }
            // Treat the option string as label
            String optionItemCode = OptionManager.getItemCodeByLabel(optionSetCode, optionStr);
            if (optionItemCode == null) {
                throw new ValidationException("The option field `{0}` does not exist item `{1}`", label, optionStr);
            }
            return optionItemCode;
        } else {
            return value;
        }
    }

}
