-- Widen the `description` columns on the metadata catalog from VARCHAR(256) to VARCHAR(512):
-- the 8 catalog tables (sys_*/design_* model / field / option_set / option_item) plus their
-- 8 *_trans i18n twins. Matches the entity declarations (@Field(length = 512)) and the new
-- parse-time guard (AnnotationParser.DESCRIPTION_MAX_LENGTH = 512) that rejects oversized
-- descriptions at boot instead of surfacing a SQL error mid-reconciliation.
--
-- The catalog is itself annotation-managed, so a dev scanner-scope auto-applies the widening
-- (MODIFY COLUMN); this script converges existing environments (prod / scanner off). Fresh
-- installs never run it — deploy/{demo,mini}-app/init_mysql/1.Metadata.ddl.sql already carries
-- the 512 end state.
--
-- The UPDATE at the end is required alongside the DDL: the app-level string-length check
-- (StringProcessor reads metaField.length, loaded from sys_field rows) would otherwise keep
-- rejecting >256 descriptions even after the physical columns are widened.
--
-- Ordering: no strict constraint relative to booting the new binary (boot reads tolerate the
-- old width); run it before publishing metadata that carries >256-char descriptions.
-- Deployments without studio-starter have no design_* tables — skip that section.
-- MySQL utf8mb4 VARCHAR(256→512) keeps the 2-byte length prefix: in-place, instant.

-- sys_* catalog
ALTER TABLE sys_model
    MODIFY COLUMN description VARCHAR(512) DEFAULT '' COMMENT 'Description';
ALTER TABLE sys_field
    MODIFY COLUMN description VARCHAR(512) DEFAULT '' COMMENT 'Description';
ALTER TABLE sys_option_set
    MODIFY COLUMN description VARCHAR(512) DEFAULT '' COMMENT 'Description';
ALTER TABLE sys_option_item
    MODIFY COLUMN description VARCHAR(512) DEFAULT '' COMMENT 'Description';
ALTER TABLE sys_model_trans
    MODIFY COLUMN description VARCHAR(512) COMMENT 'Description';
ALTER TABLE sys_field_trans
    MODIFY COLUMN description VARCHAR(512) COMMENT 'Description';
ALTER TABLE sys_option_set_trans
    MODIFY COLUMN description VARCHAR(512) COMMENT 'Description';
ALTER TABLE sys_option_item_trans
    MODIFY COLUMN description VARCHAR(512) COMMENT 'Description';

-- design_* studio mirror (skip when studio-starter is not deployed)
ALTER TABLE design_model
    MODIFY COLUMN description VARCHAR(512) DEFAULT '' COMMENT 'Description';
ALTER TABLE design_field
    MODIFY COLUMN description VARCHAR(512) DEFAULT '' COMMENT 'Description';
ALTER TABLE design_option_set
    MODIFY COLUMN description VARCHAR(512) DEFAULT '' COMMENT 'Description';
ALTER TABLE design_option_item
    MODIFY COLUMN description VARCHAR(512) DEFAULT '' COMMENT 'Description';
ALTER TABLE design_model_trans
    MODIFY COLUMN description VARCHAR(512) COMMENT 'Description';
ALTER TABLE design_field_trans
    MODIFY COLUMN description VARCHAR(512) COMMENT 'Description';
ALTER TABLE design_option_set_trans
    MODIFY COLUMN description VARCHAR(512) COMMENT 'Description';
ALTER TABLE design_option_item_trans
    MODIFY COLUMN description VARCHAR(512) COMMENT 'Description';

-- Self-describing catalog rows: widen the runtime length metadata the app-level
-- check reads (models absent from this deployment simply match no rows).
UPDATE sys_field
   SET length = 512
 WHERE field_name = 'description'
   AND length = 256
   AND model_name IN (
        'SysModel', 'SysField', 'SysOptionSet', 'SysOptionItem',
        'SysModelTrans', 'SysFieldTrans', 'SysOptionSetTrans', 'SysOptionItemTrans',
        'DesignModel', 'DesignField', 'DesignOptionSet', 'DesignOptionItem',
        'DesignModelTrans', 'DesignFieldTrans', 'DesignOptionSetTrans', 'DesignOptionItemTrans'
   );
