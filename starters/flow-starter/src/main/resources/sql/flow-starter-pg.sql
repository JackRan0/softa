-- ============================================================
-- flow-starter DDL — PostgreSQL. GENERATED from the @Model annotations,
-- do not hand-edit. The MySQL counterpart (flow-starter.sql) is the
-- hand-written original and is intentionally left untouched.
--
-- Source of truth is the entity model, never this file. To regenerate, render the
-- @Model/@Field/@Index annotations through the PostgreSQL DDL dialect:
--   ClasspathScannerSupport -> AnnotationParser.parse
--     -> ReferenceColumnResolver.stampSysFields   (TO_ONE FKs mirror the referenced id)
--     -> SysDdlContextBuilder.forCreate
--     -> DdlDialectFactory.create(DatabaseType.POSTGRESQL, BuiltinDdlMetadataResolver.INSTANCE)
-- See apps/demo-app/src/test/java/io/softa/app/metadata/MetadataBaselineDdlGeneratorTest
-- for the same chain against MySQL. CREATE TABLE / CREATE INDEX are patched to
-- IF NOT EXISTS after rendering (the template stays untouched -- it also drives
-- runtime auto-DDL).
--
-- Not read at boot when scanner-scope is non-empty — the annotation
-- lane creates these tables itself. This file exists for runtimes
-- with an empty scanner-scope (production) and for DBA review.
-- ============================================================

-- FlowApprovalRecord
/* Create table for model: Flow Approval Record */
CREATE TABLE IF NOT EXISTS flow_approval_record (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    instance_id VARCHAR(64) NOT NULL DEFAULT '',
    flow_id VARCHAR(24),
    flow_revision INT,
    node_id VARCHAR(100),
    node_label VARCHAR(200),
    cycle_number INT,
    task_id BIGINT,
    sequence INT NOT NULL,
    action VARCHAR(64),
    actor_id VARCHAR(64),
    target_actor_id VARCHAR(64),
    add_sign_position VARCHAR(64),
    comment VARCHAR(2000),
    status_before VARCHAR(64),
    status_after VARCHAR(64),
    approved_actors VARCHAR(4000),
    rejected_actors VARCHAR(4000),
    event_time TIMESTAMP,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN flow_approval_record.instance_id IS 'Runtime instance id';
COMMENT ON COLUMN flow_approval_record.flow_revision IS 'Published flow revision';
COMMENT ON COLUMN flow_approval_record.node_id IS 'Primary node id';
COMMENT ON COLUMN flow_approval_record.node_label IS 'Primary node label';
COMMENT ON COLUMN flow_approval_record.cycle_number IS 'Approval task cycle number associated with the record when applicable';
COMMENT ON COLUMN flow_approval_record.task_id IS 'Task id when the record is attached to one task row';
COMMENT ON COLUMN flow_approval_record.sequence IS 'Monotonic runtime sequence within one instance';
COMMENT ON COLUMN flow_approval_record.action IS 'Action type';
COMMENT ON COLUMN flow_approval_record.actor_id IS 'Operator actor id';
COMMENT ON COLUMN flow_approval_record.target_actor_id IS 'Target actor id';
COMMENT ON COLUMN flow_approval_record.add_sign_position IS 'Add-sign position when the record represents an add-sign action';
COMMENT ON COLUMN flow_approval_record.comment IS 'Comment';
COMMENT ON COLUMN flow_approval_record.status_before IS 'Status before action';
COMMENT ON COLUMN flow_approval_record.status_after IS 'Status after action';
COMMENT ON COLUMN flow_approval_record.approved_actors IS 'Actors who had already approved';
COMMENT ON COLUMN flow_approval_record.rejected_actors IS 'Actors who had already rejected';
COMMENT ON COLUMN flow_approval_record.event_time IS 'Recorded time';
CREATE UNIQUE INDEX IF NOT EXISTS uk_flow_approval_record_instance_id_sequence ON flow_approval_record (instance_id, sequence);
CREATE INDEX IF NOT EXISTS idx_flow_approval_record_tenant_id_instance_id ON flow_approval_record (tenant_id, instance_id);
CREATE INDEX IF NOT EXISTS idx_tenant_actor_event ON flow_approval_record (tenant_id, actor_id, event_time);
CREATE INDEX IF NOT EXISTS idx_tenant_target_event ON flow_approval_record (tenant_id, target_actor_id, event_time);

-- FlowApprovalTask
/* Create table for model: Flow Approval Task */
CREATE TABLE IF NOT EXISTS flow_approval_task (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    instance_id VARCHAR(64) NOT NULL DEFAULT '',
    flow_id VARCHAR(24),
    flow_revision INT,
    node_id VARCHAR(100) NOT NULL DEFAULT '',
    node_label VARCHAR(200),
    cycle_number INT,
    actor_id VARCHAR(64) NOT NULL DEFAULT '',
    status VARCHAR(64) NOT NULL DEFAULT '',
    task_type VARCHAR(64),
    action VARCHAR(64),
    comment VARCHAR(2000),
    dynamic_approvers BOOLEAN,
    approval_mode VARCHAR(64),
    required_approval_count INT,
    total_approver_count INT,
    reject_mode VARCHAR(64),
    required_reject_count INT,
    candidate_actors VARCHAR(4000),
    approved_actors VARCHAR(4000),
    rejected_actors VARCHAR(4000),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    due_time TIMESTAMP,
    remind_count INT,
    urgency VARCHAR(30),
    batch_id BIGINT,
    form_snapshot TEXT,
    closed_by_actor_id VARCHAR(64),
    blocked BOOLEAN,
    blocked_by_actor_id VARCHAR(64),
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN flow_approval_task.tenant_id IS 'Tenant ID';
COMMENT ON COLUMN flow_approval_task.instance_id IS 'Runtime instance id';
COMMENT ON COLUMN flow_approval_task.flow_revision IS 'Published flow revision';
COMMENT ON COLUMN flow_approval_task.node_id IS 'Approval node id';
COMMENT ON COLUMN flow_approval_task.node_label IS 'Approval node label';
COMMENT ON COLUMN flow_approval_task.cycle_number IS 'Approval task cycle number for repeated visits to the same node';
COMMENT ON COLUMN flow_approval_task.actor_id IS 'Assigned actor id';
COMMENT ON COLUMN flow_approval_task.status IS 'Task status';
COMMENT ON COLUMN flow_approval_task.task_type IS 'Task type';
COMMENT ON COLUMN flow_approval_task.action IS 'Latest action that changed this task';
COMMENT ON COLUMN flow_approval_task.comment IS 'Latest action comment';
COMMENT ON COLUMN flow_approval_task.dynamic_approvers IS 'Whether approvers were resolved dynamically';
COMMENT ON COLUMN flow_approval_task.approval_mode IS 'Approval mode snapshot';
COMMENT ON COLUMN flow_approval_task.required_approval_count IS 'Required approval count snapshot';
COMMENT ON COLUMN flow_approval_task.total_approver_count IS 'Total approver count snapshot';
COMMENT ON COLUMN flow_approval_task.reject_mode IS 'Reject mode snapshot';
COMMENT ON COLUMN flow_approval_task.required_reject_count IS 'Required reject count snapshot';
COMMENT ON COLUMN flow_approval_task.candidate_actors IS 'Candidate actor ids for the node';
COMMENT ON COLUMN flow_approval_task.approved_actors IS 'Actors who already approved when this projection was synced';
COMMENT ON COLUMN flow_approval_task.rejected_actors IS 'Actors who already rejected when this projection was synced';
COMMENT ON COLUMN flow_approval_task.start_time IS 'Task opened time';
COMMENT ON COLUMN flow_approval_task.end_time IS 'Task closed time';
COMMENT ON COLUMN flow_approval_task.due_time IS 'Task due time for timeout handling';
COMMENT ON COLUMN flow_approval_task.remind_count IS 'Remind count for overdue notifications';
COMMENT ON COLUMN flow_approval_task.urgency IS 'Urgency level';
COMMENT ON COLUMN flow_approval_task.batch_id IS 'Batch ID for batch approval operations';
COMMENT ON COLUMN flow_approval_task.form_snapshot IS 'Form data snapshot at the time the task was created (JSON)';
COMMENT ON COLUMN flow_approval_task.closed_by_actor_id IS 'Actor who closed this task when available';
COMMENT ON COLUMN flow_approval_task.blocked IS 'Whether the task is blocked by an add-sign-before prerequisite';
COMMENT ON COLUMN flow_approval_task.blocked_by_actor_id IS 'Actor who must act before this blocked task can proceed';
CREATE INDEX IF NOT EXISTS idx_tenant_instance_node ON flow_approval_task (tenant_id, instance_id, node_id);
CREATE INDEX IF NOT EXISTS idx_tenant_actor_status_start ON flow_approval_task (tenant_id, actor_id, status, start_time);
CREATE INDEX IF NOT EXISTS idx_tenant_actor_status_end ON flow_approval_task (tenant_id, actor_id, status, end_time);

-- FlowBundle
/* Create table for model: Flow Bundle */
CREATE TABLE IF NOT EXISTS flow_bundle (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    flow_name VARCHAR(100),
    revision INT NOT NULL,
    scenario VARCHAR(64),
    sync BOOLEAN,
    rollback_on_fail BOOLEAN,
    cron_expression VARCHAR(64),
    flow_id VARCHAR(24),
    compiled_json TEXT,
    design_json TEXT,
    compiled_at TIMESTAMP,
    published_at TIMESTAMP,
    change_description VARCHAR(500),
    active BOOLEAN,
    debug BOOLEAN,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN flow_bundle.version IS 'Optimistic-lock version; serializes concurrent publish/activate so a design cannot end up with two active revisions';
COMMENT ON COLUMN flow_bundle.revision IS 'Published revision number';
COMMENT ON COLUMN flow_bundle.scenario IS 'Execution scenario';
COMMENT ON COLUMN flow_bundle.sync IS 'Whether the flow executes synchronously';
COMMENT ON COLUMN flow_bundle.rollback_on_fail IS 'Whether to roll back on failure';
COMMENT ON COLUMN flow_bundle.cron_expression IS 'Schedule of a Cron-triggered revision, copied from its trigger so that the cron scheduler lists the scheduled flows without reading their compiled JSON; empty for other triggers';
COMMENT ON COLUMN flow_bundle.flow_id IS 'The flow this revision belongs to';
COMMENT ON COLUMN flow_bundle.compiled_json IS 'Compiled flow definition (JSON)';
COMMENT ON COLUMN flow_bundle.design_json IS 'Design flow definition at publish time (auto-converted by ORM)';
COMMENT ON COLUMN flow_bundle.compiled_at IS 'Compile timestamp';
COMMENT ON COLUMN flow_bundle.published_at IS 'Published timestamp';
COMMENT ON COLUMN flow_bundle.change_description IS 'Change description for this revision';
COMMENT ON COLUMN flow_bundle.active IS 'Whether this is the currently effective revision (one per design)';
COMMENT ON COLUMN flow_bundle.debug IS 'Debug-run bundle: compiled from an unpublished draft, never active, hidden from revision lists, purged by the maintenance job';
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_flow_revision ON flow_bundle (tenant_id, flow_id, revision);
CREATE INDEX IF NOT EXISTS idx_flow_bundle_active_cron_expression ON flow_bundle (active, cron_expression);

-- FlowCcConfig
/* Create table for model: Flow CC Config */
CREATE TABLE IF NOT EXISTS flow_cc_config (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    flow_id VARCHAR(24),
    node_id VARCHAR(100),
    cc_timing VARCHAR(64),
    cc_name VARCHAR(100),
    recipient_type VARCHAR(30),
    recipient_config VARCHAR(64),
    cc_condition VARCHAR(1000),
    create_read_task BOOLEAN,
    send_notification BOOLEAN,
    message_template VARCHAR(500),
    active BOOLEAN,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN flow_cc_config.node_id IS 'Node id (null for flow-level CC)';
COMMENT ON COLUMN flow_cc_config.cc_timing IS 'CC timing: OnSubmit, OnApprove, OnReject, OnComplete';
COMMENT ON COLUMN flow_cc_config.cc_name IS 'Human-readable CC rule name';
COMMENT ON COLUMN flow_cc_config.recipient_type IS 'Recipient type: USER, ROLE, DEPT, INITIATOR, EXPRESSION';
COMMENT ON COLUMN flow_cc_config.recipient_config IS 'Recipient configuration (JSON): user IDs, role IDs, expression, etc.';
COMMENT ON COLUMN flow_cc_config.cc_condition IS 'Optional condition expression that must evaluate to true for CC to fire';
COMMENT ON COLUMN flow_cc_config.create_read_task IS 'Whether to create CC read tasks for recipients';
COMMENT ON COLUMN flow_cc_config.send_notification IS 'Whether to send notification to recipients';
COMMENT ON COLUMN flow_cc_config.message_template IS 'Optional message template for the notification';
COMMENT ON COLUMN flow_cc_config.active IS 'Whether this CC rule is active';
CREATE INDEX IF NOT EXISTS idx_tenant_flow_active ON flow_cc_config (tenant_id, flow_id, active);

-- FlowDebugHistory
/* Create table for model: Flow Debug History */
CREATE TABLE IF NOT EXISTS flow_debug_history (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    flow_id VARCHAR(24),
    flow_revision INT,
    instance_id VARCHAR(64),
    status VARCHAR(64),
    initiator_id VARCHAR(64),
    parent_instance_id VARCHAR(64),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_ms BIGINT,
    event_message TEXT,
    node_trace TEXT,
    final_variables TEXT,
    error_message VARCHAR(2000),
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN flow_debug_history.event_message IS 'Trigger event message (JSON)';
COMMENT ON COLUMN flow_debug_history.node_trace IS 'Full node execution trace (JSON)';
COMMENT ON COLUMN flow_debug_history.final_variables IS 'Final variables snapshot (JSON)';
COMMENT ON COLUMN flow_debug_history.error_message IS 'Error message if execution failed';
CREATE INDEX IF NOT EXISTS idx_flow_debug_history_tenant_id_flow_id ON flow_debug_history (tenant_id, flow_id);
CREATE INDEX IF NOT EXISTS idx_flow_debug_history_tenant_id_instance_id ON flow_debug_history (tenant_id, instance_id);

-- FlowDelegation
/* Create table for model: Flow Delegation */
CREATE TABLE IF NOT EXISTS flow_delegation (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    delegator_id VARCHAR(64) NOT NULL DEFAULT '',
    delegate_id VARCHAR(64) NOT NULL DEFAULT '',
    reason VARCHAR(500),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    active BOOLEAN,
    scope VARCHAR(30),
    flow_id VARCHAR(24),
    node_id VARCHAR(100),
    auto_expire BOOLEAN,
    delegated_task_count INT,
    last_delegation_time TIMESTAMP,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN flow_delegation.delegator_id IS 'Delegator actor id';
COMMENT ON COLUMN flow_delegation.delegate_id IS 'Delegate actor id';
COMMENT ON COLUMN flow_delegation.start_time IS 'Delegation start time';
COMMENT ON COLUMN flow_delegation.end_time IS 'Delegation end time';
COMMENT ON COLUMN flow_delegation.active IS 'Whether the rule is active';
COMMENT ON COLUMN flow_delegation.scope IS 'Delegation scope: All, Flow or Node';
COMMENT ON COLUMN flow_delegation.flow_id IS 'The flow when the scope is flow- or node-specific';
COMMENT ON COLUMN flow_delegation.node_id IS 'Node id when the scope is node-specific';
COMMENT ON COLUMN flow_delegation.auto_expire IS 'Auto expire at end time';
COMMENT ON COLUMN flow_delegation.delegated_task_count IS 'Delegated task count';
COMMENT ON COLUMN flow_delegation.last_delegation_time IS 'Last delegated time';
CREATE INDEX IF NOT EXISTS idx_tenant_delegator ON flow_delegation (tenant_id, delegator_id);
CREATE INDEX IF NOT EXISTS idx_tenant_delegate_active ON flow_delegation (tenant_id, delegate_id, active);

-- FlowDesign
/* Create table for model: Flow Design */
CREATE TABLE IF NOT EXISTS flow_design (
    id VARCHAR(24) NOT NULL DEFAULT '',
    tenant_id BIGINT NOT NULL,
    flow_name VARCHAR(100) NOT NULL DEFAULT '',
    scenario VARCHAR(64),
    version INT DEFAULT 0,
    design_json TEXT,
    published_revision INT,
    published_checksum VARCHAR(64),
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN flow_design.flow_name IS 'Flow name, unique within the tenant';
COMMENT ON COLUMN flow_design.scenario IS 'Flow scenario (denormalised for list views)';
COMMENT ON COLUMN flow_design.version IS 'Optimistic-lock version; the editor echoes the loaded value on save and a mismatch is rejected as a version conflict';
COMMENT ON COLUMN flow_design.design_json IS 'Full design definition (stored as JSON, auto-converted by ORM)';
COMMENT ON COLUMN flow_design.published_revision IS 'Revision of the most recent successful publish (null = never published)';
COMMENT ON COLUMN flow_design.published_checksum IS 'SHA-256 of designJson at the most recent successful publish; compared against the current draft to derive the editor''s dirty flag';
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_flow_name ON flow_design (tenant_id, flow_name);
CREATE INDEX IF NOT EXISTS idx_tenant_scenario ON flow_design (tenant_id, scenario);

-- FlowEvent
/* Create table for model: Flow Event */
CREATE TABLE IF NOT EXISTS flow_event (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    trigger_type VARCHAR(50),
    source_model VARCHAR(100),
    source_row_id VARCHAR(64),
    actor_id VARCHAR(64),
    flow_id VARCHAR(24),
    flow_revision INT,
    instance_id VARCHAR(64),
    success BOOLEAN,
    error_message VARCHAR(2000),
    fire_method VARCHAR(50),
    event_time TIMESTAMP,
    parameters TEXT,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN flow_event.trigger_type IS 'Trigger type discriminator string (e.g., EntityChange, Api, Cron)';
COMMENT ON COLUMN flow_event.source_model IS 'Source model when the trigger is entity-related';
COMMENT ON COLUMN flow_event.source_row_id IS 'Source row id when the trigger is entity-related';
COMMENT ON COLUMN flow_event.actor_id IS 'Actor who triggered the event';
COMMENT ON COLUMN flow_event.flow_id IS 'The matched and started flow';
COMMENT ON COLUMN flow_event.flow_revision IS 'Flow revision that was started';
COMMENT ON COLUMN flow_event.instance_id IS 'Runtime instance id of the started flow';
COMMENT ON COLUMN flow_event.success IS 'Whether the flow was started successfully';
COMMENT ON COLUMN flow_event.error_message IS 'Error message when the flow failed to start';
COMMENT ON COLUMN flow_event.fire_method IS 'Trigger fire method: fire, fireSyncOnly, fireAsyncOnly, fireForPurpose';
COMMENT ON COLUMN flow_event.event_time IS 'Event timestamp';
COMMENT ON COLUMN flow_event.parameters IS 'Trigger parameters (JSON)';
CREATE INDEX IF NOT EXISTS idx_flow_event_tenant_id_flow_id ON flow_event (tenant_id, flow_id);
CREATE INDEX IF NOT EXISTS idx_flow_event_tenant_id_instance_id ON flow_event (tenant_id, instance_id);
CREATE INDEX IF NOT EXISTS idx_tenant_source ON flow_event (tenant_id, source_model, source_row_id);

-- FlowExecutionTrace
/* Create table for model: Flow Execution Trace */
CREATE TABLE IF NOT EXISTS flow_execution_trace (
    id BIGINT NOT NULL,
    tenant_id BIGINT,
    instance_id VARCHAR(64) NOT NULL DEFAULT '',
    sequence INT NOT NULL,
    flow_id VARCHAR(24),
    node_id VARCHAR(100),
    flow_node_type VARCHAR(64),
    event_type VARCHAR(64) NOT NULL DEFAULT '',
    event_time TIMESTAMP,
    message VARCHAR(2000),
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_flow_execution_trace_instance_id_sequence ON flow_execution_trace (instance_id, sequence);
CREATE INDEX IF NOT EXISTS idx_flow_execution_trace_tenant_id_instance_id ON flow_execution_trace (tenant_id, instance_id);

-- FlowInstance
/* Create table for model: Flow Instance */
CREATE TABLE IF NOT EXISTS flow_instance (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    instance_id VARCHAR(64) NOT NULL DEFAULT '',
    bundle_id BIGINT,
    flow_id VARCHAR(24),
    flow_revision INT,
    title VARCHAR(200),
    model_name VARCHAR(100),
    row_id VARCHAR(64),
    initiator_id VARCHAR(64),
    record_approval BOOLEAN,
    open_record_id VARCHAR(64),
    status VARCHAR(64) NOT NULL DEFAULT '',
    error_message VARCHAR(2000),
    failed_node_id VARCHAR(100),
    input_payload TEXT,
    variables TEXT,
    wait_tokens TEXT,
    next_fire_at TIMESTAMP,
    completed_node_ids TEXT,
    pending_approvals TEXT,
    join_arrival_counts TEXT,
    return_data TEXT,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN flow_instance.version IS 'Optimistic-lock version for runtime state updates';
COMMENT ON COLUMN flow_instance.instance_id IS 'Runtime instance id (UUID)';
COMMENT ON COLUMN flow_instance.bundle_id IS 'Exact FlowBundle.id this instance was created from; required to resolve the definition for approval/resume actions after a DB reload';
COMMENT ON COLUMN flow_instance.flow_id IS 'The flow this instance runs (stable flow handle)';
COMMENT ON COLUMN flow_instance.flow_revision IS 'Published flow revision';
COMMENT ON COLUMN flow_instance.title IS 'Instance title';
COMMENT ON COLUMN flow_instance.model_name IS 'Related model name';
COMMENT ON COLUMN flow_instance.row_id IS 'Related row data ID';
COMMENT ON COLUMN flow_instance.initiator_id IS 'Flow initiator id';
COMMENT ON COLUMN flow_instance.record_approval IS 'Whether the instance is its record''s approval: started by submitting the record, so its result is written back to it';
COMMENT ON COLUMN flow_instance.open_record_id IS 'The record''s id while this record approval is unfinished, empty otherwise. Unique with the model in a tenant: a record has at most one approval in progress';
COMMENT ON COLUMN flow_instance.status IS 'Execution status';
COMMENT ON COLUMN flow_instance.error_message IS 'Error message when execution fails';
COMMENT ON COLUMN flow_instance.failed_node_id IS 'Node where execution failed (set when status = Failed)';
COMMENT ON COLUMN flow_instance.input_payload IS 'Immutable trigger payload (JSON)';
COMMENT ON COLUMN flow_instance.variables IS 'Execution variables (JSON)';
COMMENT ON COLUMN flow_instance.wait_tokens IS 'Active timer/async waits (JSON array); pending approvals are tracked separately';
COMMENT ON COLUMN flow_instance.next_fire_at IS 'Earliest due time across timer waits (denormalized from waitTokens for the sweep index)';
COMMENT ON COLUMN flow_instance.completed_node_ids IS 'Completed node ids (JSON array)';
COMMENT ON COLUMN flow_instance.pending_approvals IS 'Pending approvals (JSON array)';
COMMENT ON COLUMN flow_instance.join_arrival_counts IS 'Parallel join arrival counts (JSON map)';
COMMENT ON COLUMN flow_instance.return_data IS 'Return data from ReturnValue nodes (JSON)';
CREATE UNIQUE INDEX IF NOT EXISTS uk_instance_id ON flow_instance (instance_id);
CREATE INDEX IF NOT EXISTS idx_tenant_flow_status ON flow_instance (tenant_id, flow_id, status);
CREATE INDEX IF NOT EXISTS idx_tenant_status_fire ON flow_instance (tenant_id, status, next_fire_at);
CREATE INDEX IF NOT EXISTS idx_tenant_initiator_status ON flow_instance (tenant_id, initiator_id, status);
CREATE INDEX IF NOT EXISTS idx_tenant_model_row ON flow_instance (tenant_id, model_name, row_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_flow_instance_open_record ON flow_instance (tenant_id, model_name, open_record_id);

-- FlowParallelBranch
/* Create table for model: Flow Parallel Branch */
CREATE TABLE IF NOT EXISTS flow_parallel_branch (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    instance_id VARCHAR(64) NOT NULL DEFAULT '',
    fork_node_id VARCHAR(100) NOT NULL DEFAULT '',
    branch_node_id VARCHAR(100) NOT NULL DEFAULT '',
    branch_name VARCHAR(200),
    status VARCHAR(64),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_ms BIGINT,
    error_message VARCHAR(2000),
    result TEXT,
    created_time TIMESTAMP,
    created_id BIGINT,
    created_by VARCHAR(64),
    updated_time TIMESTAMP,
    updated_id BIGINT,
    updated_by VARCHAR(64),
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_tenant_instance_fork ON flow_parallel_branch (tenant_id, instance_id, fork_node_id);

