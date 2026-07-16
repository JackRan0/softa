-- Residual option-set seed: OPTION SETS WITH NO @OptionSet JAVA ENUM
-- (no-code vocabularies referenced by metadata-only models / Studio UI).
-- Everything that has an @OptionSet-annotated enum is materialized by the
-- MetadataAnnotationScanner at boot (dev) or platform Plan/Apply (prod) and is
-- NOT seeded here. Per P0-6 the engine-fixed vocabularies
-- ActionGetDataType + BooleanValue became @OptionSet enums and were removed.
-- These rows ship ownership='STUDIO_MANAGED': platform no-code
-- definitions, evolved henceforth via the Studio design workspace + signed
-- envelope. (PLATFORM_DEFAULT is retired on sys_* — V7.)

-- Option-set headers
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('FlowNotifyType','Flow Notify Type','');
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('TriggerEventType','Trigger Event Type','');
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('FlowLayoutType','Flow Layout Type','');
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('FlowStatus','Flow Status','');
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('FlowType','Flow Type','');
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('ActionMessageType','Action Message Type','');
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('ActionExceptionSignal','Action Exception Signal','');
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('ActionExceptionType','Action Exception Type','Check the exception condition and throw an exception.');
INSERT INTO sys_option_set(option_set_code,label,description) VALUES('DataType','Data Type','Java data type of the field value');

-- Item rows
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNotifyType','ExceptionNotify','Exception Notify',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNotifyType','StatusChanged','Status Changed',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNotifyType','RemindNotify','Remind Notify',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowNotifyType','MessageNotify','Message Notify',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','SubflowEvent','Subflow Event',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','UpdateEvent','Update Event',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','CreateOrUpdate','Create or Update Event',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','DeleteEvent','Delete Event',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','ChangedEvent','Changed Event(C/U/D)',5,'','','','Create/Update/Delete');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','ButtonEvent','Button Event',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','OnchangeEvent','Onchange Event',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','ApiEvent','API Event',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('TriggerEventType','CronEvent','Cron Event',9,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowLayoutType','HorizontalAutomatic','Horizontal Automatic',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowLayoutType','ManualLayout','Manual Layout',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowStatus','Running','Running',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowStatus','Approving','Approving',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowStatus','Failed','Failed',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowStatus','Completed','Completed',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowType','FormFlow','Form Flow',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowType','ValidationFlow','Validation Flow',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowType','OnchangeFlow','Onchange Flow',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('FlowType','AgentFlow','Agent Flow',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionMessageType','Email','Email',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionMessageType','InAppMessage','In-app Message',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionMessageType','InstantMessage','Instant Message',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionSignal','EndFlow','End Current Flow',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionSignal','EndLoopNode','End Current Loop Node',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionSignal','ThrowException','Throw Exception',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionSignal','LogError','Log Error',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionType','ResultIsEmptyOrZero','Result Is Empty Or Zero',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionType','ResultIsNotEmpty','Result Is Not Empty',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionType','ResultIsFalse','Result Is False',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('ActionExceptionType','ResultIsTrue','Result Is True',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','MultiString','MultiString',10,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Integer','Integer',2,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Long','Long',3,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Double','Double',4,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','BigDecimal','BigDecimal',5,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Boolean','Boolean',6,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Date','Date',7,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','Datetime','Datetime',8,'','','','');
INSERT INTO sys_option_item(option_set_code,item_code,label,sequence,parent_item_code,item_tone,item_icon,description) VALUES('DataType','JSON','JSON',9,'','','','');

-- Stamp the residual no-code vocabularies STUDIO_MANAGED. The column
-- default is already STUDIO_MANAGED; this is explicit + self-documenting and
-- keeps the seed correct even if the default changes.
UPDATE sys_option_set SET ownership='STUDIO_MANAGED' WHERE option_set_code IN ('ActionExceptionSignal', 'ActionExceptionType', 'ActionMessageType', 'DataType', 'FlowLayoutType', 'FlowNotifyType', 'FlowStatus', 'FlowType', 'TriggerEventType');
UPDATE sys_option_item SET ownership='STUDIO_MANAGED' WHERE option_set_code IN ('ActionExceptionSignal', 'ActionExceptionType', 'ActionMessageType', 'DataType', 'FlowLayoutType', 'FlowNotifyType', 'FlowStatus', 'FlowType', 'TriggerEventType');
