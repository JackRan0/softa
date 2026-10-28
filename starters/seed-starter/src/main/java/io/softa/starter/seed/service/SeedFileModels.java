package io.softa.starter.seed.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.FileUtils;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.service.impl.PreDataFormatParser;

/**
 * The models a seed file writes: the models it lists, and the models of the rows nested in them through their
 * one-to-many fields, as the loader walks them.
 */
final class SeedFileModels {

    private SeedFileModels() {
    }

    /** Read the file from the running build and name every model it writes. */
    static Set<String> of(SeedFile file) {
        Set<String> models = new LinkedHashSet<>();
        new PreDataFormatParser()
                .parse(FileUtils.getFileObjectByPath(file.level().dataDir(), file.file()))
                .forEach((model, data) -> collect(model, data, models));
        return models;
    }

    private static void collect(String model, Object data, Set<String> models) {
        models.add(model);
        if (!ModelManager.existModel(model)) {
            // The loader refuses the file; here it is enough that it names the model.
            return;
        }
        for (Object row : data instanceof List<?> rows ? rows : List.of(data)) {
            if (!(row instanceof Map<?, ?> fields)) {
                continue;
            }
            fields.forEach((field, value) -> {
                String fieldName = String.valueOf(field);
                if (value == null || !ModelManager.existField(model, fieldName)) {
                    return;
                }
                MetaField metaField = ModelManager.getModelField(model, fieldName);
                if (FieldType.ONE_TO_MANY.equals(metaField.getFieldType()) && metaField.getRelatedModel() != null) {
                    collect(metaField.getRelatedModel(), value, models);
                }
            });
        }
    }
}
