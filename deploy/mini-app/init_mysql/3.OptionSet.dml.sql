-- Residual option-set seed: OPTION SETS WITH NO @OptionSet JAVA ENUM
-- (no-code vocabularies referenced by metadata-only models / Studio UI).
-- Everything that has an @OptionSet-annotated enum is materialized by the
-- MetadataAnnotationScanner at boot (dev) or platform Plan/Apply (prod) and is
-- NOT seeded here. Per P0-6 the engine-fixed BooleanValue became an
-- @OptionSet enum (io.softa.framework.orm.enums.BooleanValue) and was removed.
-- These rows ship ownership='STUDIO_MANAGED': platform no-code
-- definitions, evolved via the Studio design workspace + signed envelope.
-- (PLATFORM_DEFAULT is retired on sys_* — V7.)

-- Option-set headers
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('DataType','Data Type','Java data type of the field value');

-- Item rows
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','MultiString','MultiString',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Integer','Integer',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Long','Long',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Double','Double',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','BigDecimal','BigDecimal',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Boolean','Boolean',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Date','Date',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Datetime','Datetime',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','JSON','JSON',9,'','','','');

-- Stamp the residual no-code vocabulary STUDIO_MANAGED. The column
-- default is already STUDIO_MANAGED; this is explicit + self-documenting.
UPDATE sys_option_set SET ownership='STUDIO_MANAGED' WHERE option_set_code IN ('DataType');
UPDATE sys_option_item SET ownership='STUDIO_MANAGED' WHERE option_set_code IN ('DataType');
