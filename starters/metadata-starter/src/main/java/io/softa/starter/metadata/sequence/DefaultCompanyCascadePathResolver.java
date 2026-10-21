package io.softa.starter.metadata.sequence;

import java.util.Optional;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * The framework's answer, asked last: the company model is its own company, and any other model
 * belongs to the company its company-axis field ({@link ModelConstant#COMPANY_FIELD}) names.
 *
 * <p>A model whose company-axis field is {@code dynamic} has no column for it — it is joined at query
 * time from another relation — so the field's value is not on the record being inserted. Its cascade
 * path is the answer instead: the first hop is read from the record and the rest is looked up.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultCompanyCascadePathResolver implements CompanyCascadePathResolver {

    @Override
    public Optional<String> resolve(String modelName) {
        if (modelName == null || !ModelManager.existModel(modelName)) {
            return Optional.empty();
        }
        if (ModelConstant.COMPANY_MODEL.equals(modelName)) {
            return Optional.of(SELF_PATH);
        }
        MetaField company = ModelManager.getModelFieldOrNull(modelName, ModelConstant.COMPANY_FIELD);
        if (company == null) {
            return Optional.empty();
        }
        return Optional.of(company.isDynamicCascadedField() ? company.getCascadedField() : ModelConstant.COMPANY_FIELD);
    }
}
