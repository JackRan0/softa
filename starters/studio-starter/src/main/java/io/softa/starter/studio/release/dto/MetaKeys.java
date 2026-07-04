package io.softa.starter.studio.release.dto;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.entity.SysOptionSet;

/**
 * The business-key column names of the five swept meta-models, derived once from the {@code Sys*} getters
 * (a rename breaks compilation, not silently mis-keys). These camelCase strings are the join/locate keys
 * the differ, merger, import path, connectors and change-reports all pair rows by — previously each
 * re-derived its own private copy. Single source here so every lane keys by the identical column name.
 */
public final class MetaKeys {

    private MetaKeys() {
    }

    public static final String MODEL_NAME = LambdaUtils.getAttributeName(SysModel::getModelName);
    public static final String FIELD_NAME = LambdaUtils.getAttributeName(SysField::getFieldName);
    public static final String INDEX_NAME = LambdaUtils.getAttributeName(SysModelIndex::getIndexName);
    public static final String OPTION_SET_CODE = LambdaUtils.getAttributeName(SysOptionSet::getOptionSetCode);
    public static final String ITEM_CODE = LambdaUtils.getAttributeName(SysOptionItem::getItemCode);
}
