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
    VALUES('Model Onchange Event', 'SysModelOnchange', 'sys_model_onchange', '', '', '', '', false, 'ExternalID', false,  '', false, false, false, '', '', '', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Model Validation', 'SysModelValidation', 'sys_model_validation', '', 'modelName,priority', '', '', false, 'ExternalID', false,  '', false, false, false, '', '', '', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Registered Client', 'AuthRegisteredClient', 'auth_registered_client', '', '', '', '', false, '', false,  '', false, false, false, '', '', '', '');

INSERT INTO sys_model(label, model_name, table_name, description, default_order, display_name, search_name, timeline, id_strategy, soft_delete, soft_delete_field, active_control, multi_tenant, version_lock, data_source, service_name, business_key, partition_field)
    VALUES('Change Log', 'ChangeLog', 'change_log', '', '', '', '', false, '', false,  '', false, false, false, '', '', '', '');

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
    VALUES('Client Name', 'clientName', 'client_name', 'AuthRegisteredClient', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Client ID', 'clientId', 'client_id', 'AuthRegisteredClient', 'OAuth2 Client ID', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Client Secret', 'clientSecret', 'client_secret', 'AuthRegisteredClient', 'OAuth2 Client Secret, stored in encoded form', 'String', '', '', '', '', '', '', '', '', "''", 256, 0, false, 0, 0, 0, 0, 0, '', 0, 0, 'All', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Expiration Date', 'expiredDate', 'expired_date', 'AuthRegisteredClient', '', 'Date', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Grant Type', 'grantType', 'grant_type', 'AuthRegisteredClient', 'Synchronous upgrades use the API, asynchronous upgrades go through MQ first and then the API, default is synchronous', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Client Description', 'description', 'description', 'AuthRegisteredClient', '', 'String', '', '', '', '', '', '', '', '', "''", 256, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created Time', 'createdTime', 'created_time', 'AuthRegisteredClient', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created ID', 'createdId', 'created_id', 'AuthRegisteredClient', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Created By', 'createdBy', 'created_by', 'AuthRegisteredClient', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated Time', 'updatedTime', 'updated_time', 'AuthRegisteredClient', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated ID', 'updatedId', 'updated_id', 'AuthRegisteredClient', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Updated By', 'updatedBy', 'updated_by', 'AuthRegisteredClient', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Trace ID', 'traceId', 'trace_id', 'ChangeLog', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Model Name', 'model', 'model', 'ChangeLog', '', 'String', '', '', '', '', '', '', '', '', "''", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Row ID', 'rowId', 'row_id', 'ChangeLog', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Access Type', 'accessType', 'access_type', 'ChangeLog', '', 'Option', 'AccessType', '', '', '', '', '', '', '', "", 64, 0, true, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Data Before Change', 'dataBeforeChange', 'data_before_change', 'ChangeLog', '', 'JSON', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Data After Change', 'dataAfterChange', 'data_after_change', 'ChangeLog', '', 'JSON', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Changed ID', 'changedId', 'changed_id', 'ChangeLog', '', 'Long', '', '', '', '', '', '', '', '', "", 32, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Changed By', 'changedBy', 'changed_by', 'ChangeLog', '', 'String', '', '', '', '', '', '', '', '', "", 64, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

INSERT INTO sys_field(label, field_name, column_name, model_name, description, field_type, option_set_code, related_model, related_field, join_model, join_left, join_right, cascaded_field, filters, default_value, length, scale, required, readonly, translatable, non_copyable, unsearchable, computed, expression, dynamic, encrypted, masking_type, widget_type)
    VALUES('Changed Time', 'changedTime', 'changed_time', 'ChangeLog', '', 'DateTime', '', '', '', '', '', '', '', '', "", 0, 0, false, 0, 0, 0, 0, 0, '', 0, 0, '', '');

-- Mark every residual row as PLATFORM_DEFAULT (column default is TENANT).
UPDATE sys_model SET ownership='STUDIO_MANAGED' WHERE model_name IN ('AuthRegisteredClient', 'ChangeLog', 'SysModelOnchange', 'SysModelValidation', 'SysViewDefault');
UPDATE sys_field SET ownership='STUDIO_MANAGED' WHERE model_name IN ('AuthRegisteredClient', 'ChangeLog', 'SysModelOnchange', 'SysModelValidation', 'SysViewDefault');
