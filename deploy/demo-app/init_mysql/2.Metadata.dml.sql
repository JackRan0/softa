-- Residual metadata seed: MODELS DEFINED ONLY IN METADATA (no Java class).
-- Everything that has an @Model-annotated Java class is materialized by the
-- MetadataAnnotationScanner at boot and was removed from this file.
-- These rows ship ownership='STUDIO_MANAGED': platform no-code
-- definitions with no Java source, evolved henceforth via the Studio design
-- workspace + signed envelope. (PLATFORM_DEFAULT is retired on sys_* — V7.)

-- Model headers (reconstructed from HEAD; label_name -> label per current schema)
INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('User Default View', 'SysViewDefault', 'sys_view_default', '', '', '', '', false, '', false,  '', false, false, false, '', '', '', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Model Onchange Event', 'SysModelOnchange', 'sys_model_onchange', '', '', '', '', false, 'DistributedLong', false,  '', false, false, false, '', '', 'modelName,code', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Model Validation', 'SysModelValidation', 'sys_model_validation', '', 'modelName,priority', '', '', false, 'DistributedLong', false,  '', false, false, false, '', '', 'modelName,code', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Tenant Option Set', 'TenantOptionSet', 'tenant_option_set', '', '', 'name', '', false, 'DistributedLong', false,  '', true, false, false, '', '', '', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Tenant Option Set Translation', 'TenantOptionSetTrans', 'tenant_option_set_trans', '', '', '', '', false, '', false,  '', false, false, false, '', '', '', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Tenant Option Items', 'TenantOptionItem', 'tenant_option_item', '', 'optionSetCode,sequence', 'itemCode,label', '', false, 'DistributedLong', false,  '', true, false, false, '', '', '', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Tenant Option Items Translation', 'TenantOptionItemTrans', 'tenant_option_item_trans', '', '', '', '', false, '', false,  '', false, false, false, '', '', '', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Tenant Config', 'TenantConfig', 'tenant_config', '', 'name', 'name', '', false, 'DistributedLong', false,  '', true, false, false, '', '', '', '');

-- Field rows
INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('View ID', 'viewId', 'view_id', 'SysViewDefault', '', 'Long', '', '', '', '', '', '', '', '', "0", 32, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('View Code', 'viewCode', 'view_code', 'SysViewDefault', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Navigation ID', 'navId', 'nav_id', 'SysViewDefault', '', 'Long', '', '', '', '', '', '', '', '', "0", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Model Name', 'modelName', 'model_name', 'SysViewDefault', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created Time', 'createdTime', 'created_time', 'SysViewDefault', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created ID', 'createdId', 'created_id', 'SysViewDefault', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created By', 'createdBy', 'created_by', 'SysViewDefault', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Time', 'updatedTime', 'updated_time', 'SysViewDefault', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated ID', 'updatedId', 'updated_id', 'SysViewDefault', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated By', 'updatedBy', 'updated_by', 'SysViewDefault', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('App ID', 'appId', 'app_id', 'SysModelOnchange', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Name', 'name', 'name', 'SysModelOnchange', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Code', 'code', 'code', 'SysModelOnchange', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Model Name', 'modelName', 'model_name', 'SysModelOnchange', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Onchange Fields', 'onchangeFields', 'onchange_fields', 'SysModelOnchange', '', 'MultiString', '', '', '', '', '', '', '', '', "''", 256, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Expression', 'expression', 'expression', 'SysModelOnchange', '', 'String', '', '', '', '', '', '', '', '', "''", 1000, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Fields', 'updatedFields', 'updated_fields', 'SysModelOnchange', 'Automatic extraction based on the expression', 'MultiString', '', '', '', '', '', '', '', '', "''", 256, 0, false, true, 0, 0, 0, 0, '', true, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Description', 'description', 'description', 'SysModelOnchange', '', 'String', '', '', '', '', '', '', '', '', "''", 256, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created Time', 'createdTime', 'created_time', 'SysModelOnchange', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created ID', 'createdId', 'created_id', 'SysModelOnchange', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created By', 'createdBy', 'created_by', 'SysModelOnchange', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Time', 'updatedTime', 'updated_time', 'SysModelOnchange', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated ID', 'updatedId', 'updated_id', 'SysModelOnchange', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated By', 'updatedBy', 'updated_by', 'SysModelOnchange', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('App ID', 'appId', 'app_id', 'SysModelValidation', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Name', 'name', 'name', 'SysModelValidation', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Code', 'code', 'code', 'SysModelValidation', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Model Name', 'modelName', 'model_name', 'SysModelValidation', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('priority', 'priority', 'priority', 'SysModelValidation', '', 'Integer', '', '', '', '', '', '', '', '', "1", 4, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Expression', 'expression', 'expression', 'SysModelValidation', '', 'String', '', '', '', '', '', '', '', '', "''", 1000, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Exception Message', 'exceptionMsg', 'exception_msg', 'SysModelValidation', '', 'String', '', '', '', '', '', '', '', '', "''", 256, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Validated Fields', 'validatedFields', 'validated_fields', 'SysModelValidation', 'Automatic extraction based on the expression', 'MultiString', '', '', '', '', '', '', '', '', "''", 256, 0, false, true, 0, 0, 0, 0, '', true, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Description', 'description', 'description', 'SysModelValidation', '', 'String', '', '', '', '', '', '', '', '', "''", 256, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created Time', 'createdTime', 'created_time', 'SysModelValidation', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created ID', 'createdId', 'created_id', 'SysModelValidation', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created By', 'createdBy', 'created_by', 'SysModelValidation', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Time', 'updatedTime', 'updated_time', 'SysModelValidation', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated ID', 'updatedId', 'updated_id', 'SysModelValidation', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated By', 'updatedBy', 'updated_by', 'SysModelValidation', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Tenant ID', 'tenantId', 'tenant_id', 'TenantOptionSet', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('App ID', 'appId', 'app_id', 'TenantOptionSet', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Option Set Name', 'name', 'name', 'TenantOptionSet', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 1, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Option Set Code', 'optionSetCode', 'option_set_code', 'TenantOptionSet', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Option Items', 'optionItems', 'option_items', 'TenantOptionSet', '', 'OneToMany', '', 'TenantOptionItem', 'optionSetId', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Description', 'description', 'description', 'TenantOptionSet', '', 'String', '', '', '', '', '', '', '', '', "''", 256, 0, false, 0, 1, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created Time', 'createdTime', 'created_time', 'TenantOptionSet', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created ID', 'createdId', 'created_id', 'TenantOptionSet', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created By', 'createdBy', 'created_by', 'TenantOptionSet', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Time', 'updatedTime', 'updated_time', 'TenantOptionSet', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated ID', 'updatedId', 'updated_id', 'TenantOptionSet', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated By', 'updatedBy', 'updated_by', 'TenantOptionSet', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Active', 'active', 'active', 'TenantOptionSet', '', 'Boolean', '', '', '', '', '', '', '', '', "1", 1, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Language Code', 'languageCode', 'language_code', 'TenantOptionSetTrans', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Row ID', 'rowId', 'row_id', 'TenantOptionSetTrans', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Option Set Name', 'name', 'name', 'TenantOptionSetTrans', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Description', 'description', 'description', 'TenantOptionSetTrans', '', 'String', '', '', '', '', '', '', '', '', "", 256, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created Time', 'createdTime', 'created_time', 'TenantOptionSetTrans', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created By', 'createdBy', 'created_by', 'TenantOptionSetTrans', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created ID', 'createdId', 'created_id', 'TenantOptionSetTrans', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Time', 'updatedTime', 'updated_time', 'TenantOptionSetTrans', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated ID', 'updatedId', 'updated_id', 'TenantOptionSetTrans', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated By', 'updatedBy', 'updated_by', 'TenantOptionSetTrans', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Tenant ID', 'tenantId', 'tenant_id', 'TenantOptionItem', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('App ID', 'appId', 'app_id', 'TenantOptionItem', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Option Set ID', 'optionSetId', 'option_set_id', 'TenantOptionItem', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Option Set Code', 'optionSetCode', 'option_set_code', 'TenantOptionItem', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Parent Item ID', 'parentItemId', 'parent_item_id', 'TenantOptionItem', '', 'ManyToOne', '', 'TenantOptionItem', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Sequence', 'sequence', 'sequence', 'TenantOptionItem', '', 'Integer', '', '', '', '', '', '', '', '', "1", 11, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Item Code', 'itemCode', 'item_code', 'TenantOptionItem', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Label', 'label', 'label', 'TenantOptionItem', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 1, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Item Tone', 'itemTone', 'item_tone', 'TenantOptionItem', '', 'Option', 'OptionItemTone', '', '', '', '', '', '', '', "''", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Item Icon', 'itemIcon', 'item_icon', 'TenantOptionItem', '', 'Option', 'OptionItemIcon', '', '', '', '', '', '', '', "''", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Description', 'description', 'description', 'TenantOptionItem', '', 'String', '', '', '', '', '', '', '', '', "''", 256, 0, false, 0, 1, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created Time', 'createdTime', 'created_time', 'TenantOptionItem', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created ID', 'createdId', 'created_id', 'TenantOptionItem', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created By', 'createdBy', 'created_by', 'TenantOptionItem', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Time', 'updatedTime', 'updated_time', 'TenantOptionItem', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated ID', 'updatedId', 'updated_id', 'TenantOptionItem', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated By', 'updatedBy', 'updated_by', 'TenantOptionItem', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Active', 'active', 'active', 'TenantOptionItem', '', 'Boolean', '', '', '', '', '', '', '', '', "1", 1, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Language Code', 'languageCode', 'language_code', 'TenantOptionItemTrans', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Row ID', 'rowId', 'row_id', 'TenantOptionItemTrans', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Label', 'label', 'label', 'TenantOptionItemTrans', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Description', 'description', 'description', 'TenantOptionItemTrans', '', 'String', '', '', '', '', '', '', '', '', "", 256, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created Time', 'createdTime', 'created_time', 'TenantOptionItemTrans', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created By', 'createdBy', 'created_by', 'TenantOptionItemTrans', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created ID', 'createdId', 'created_id', 'TenantOptionItemTrans', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Time', 'updatedTime', 'updated_time', 'TenantOptionItemTrans', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated ID', 'updatedId', 'updated_id', 'TenantOptionItemTrans', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated By', 'updatedBy', 'updated_by', 'TenantOptionItemTrans', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Tenant ID', 'tenantId', 'tenant_id', 'TenantConfig', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('App ID', 'appId', 'app_id', 'TenantConfig', '', 'Long', '', '', '', '', '', '', '', '', "0", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Name', 'name', 'name', 'TenantConfig', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Code', 'code', 'code', 'TenantConfig', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Value', 'value', 'value', 'TenantConfig', '', 'JSON', '', '', '', '', '', '', '', '', "", 0, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Value Data Type', 'valueType', 'value_type', 'TenantConfig', '', 'String', 'DataType', '', '', '', '', '', '', '', "''", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Description', 'description', 'description', 'TenantConfig', '', 'String', '', '', '', '', '', '', '', '', "''", 256, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created Time', 'createdTime', 'created_time', 'TenantConfig', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created ID', 'createdId', 'created_id', 'TenantConfig', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created By', 'createdBy', 'created_by', 'TenantConfig', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Time', 'updatedTime', 'updated_time', 'TenantConfig', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated ID', 'updatedId', 'updated_id', 'TenantConfig', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated By', 'updatedBy', 'updated_by', 'TenantConfig', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Active', 'active', 'active', 'TenantConfig', '', 'Boolean', '', '', '', '', '', '', '', '', "1", 1, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

-- Mark every residual row as PLATFORM_DEFAULT (column default is TENANT).
UPDATE sys_model SET ownership='STUDIO_MANAGED' WHERE model_name IN ('SysModelOnchange', 'SysModelValidation', 'SysViewDefault', 'TenantConfig', 'TenantOptionItem', 'TenantOptionItemTrans', 'TenantOptionSet', 'TenantOptionSetTrans');
UPDATE sys_field SET ownership='STUDIO_MANAGED' WHERE model_name IN ('SysModelOnchange', 'SysModelValidation', 'SysViewDefault', 'TenantConfig', 'TenantOptionItem', 'TenantOptionItemTrans', 'TenantOptionSet', 'TenantOptionSetTrans');
