# Flow Starter

`flow-starter` is Softa's metadata-driven flow engine starter. It covers:

- design-time graph documents for a visual editor
- compile-time validation and transformation into runtime bundles
- published bundle revisions by `flowId`
- runtime execution for approval, automation, validation, and compute flows
- persistent projections for instances, approval tasks, approval records, delegations, CC rules, and trigger events
- optional Pulsar-based trigger and async-task integration

This README documents the current backend contract implemented in this module.

## Current Model

### Design, Publish, Runtime

| Layer | Main types | Notes |
|---|---|---|
| Design time | `DesignFlowDefinition`, `FlowGraphDocument`, `FlowGraphNode`, `FlowGraphEdge` | Editor-facing graph DTOs |
| Publish time | `FlowCompiler`, `CompiledFlowDefinition`, `FlowPublishService` | Compile, validate, publish revisions, rollback |
| Runtime | `FlowRuntimeEngine`, `FlowExecutionState` | Execute published bundles, suspend/resume, persist projections |

### Flow Identity And Revisions

- `FlowDesign` is the draft working copy. Its `id` (a distributed string id) is the flow's identity everywhere: bundles, instances, tasks, approval records, events, delegations and CC rules reference it as `flowId`, and lists show the flow's name through that reference.
- `flowName` is unique within a tenant; there is no separate flow code.
- `FlowBundle` is the compiled published snapshot; published revisions are keyed by `flowId`.
- `FlowStartRequest` resolves bundles in this order:
  1. `bundleId` — pin to an exact published revision
  2. `flowId` — start the current active revision
- To start a specific historical revision, look up its `bundleId` via the revisions listing endpoint and pass that as `bundleId`.
- `FlowBundle.designJson` keeps the design snapshot used at publish time so the editor can restore a historical canvas.

### Flow Scenarios

A single `FlowScenario` enum declares what a flow is. It is chosen when the flow is created and fixed
from then on (`FlowDesign.scenario` is readonly on update; the design document follows the column).

| Scenario | Meaning | Triggers it may declare |
|---|---|---|
| `APPROVAL` | A record's approval: started by submitting the record, approval steps, the outcome written back to the record. Persisted instances, kept as the record's approval history | `Submit` |
| `AUTOMATION` | Started by a change, a cron, an explicit start or a parent flow; no approval steps. Persisted runs, purged after their retention period | `EntityChange`, `Cron`, `Api`, `Subflow` |
| `VALIDATION` | Stateless synchronous validation flow — evaluated transiently: no instance row, no trace, no event log | `EntityChange`, `Cron` |
| `COMPUTE` | Stateless synchronous compute/onchange flow — evaluated transiently, same zero-footprint rules | `FieldChange`, `EntityChange`, `Cron` |

The scenario decides the execution strategy: `APPROVAL` / `AUTOMATION` flows go through
`FlowRuntimeEngine.start` (persisted, resumable); `VALIDATION` / `COMPUTE` flows go through `evaluate` —
the same traversal with zero flow footprint. Business writes made by task nodes still join the caller's
transaction, so a failed evaluation rolls them back.

Every flow declares a trigger, and **the trigger is the flow's start contract** — one way in each:

| Trigger | How the flow starts |
|---|---|
| `Submit` | Only by submitting a record (`FlowClient.submitRecord`) — see [Approving Business Records](#approving-business-records) |
| `EntityChange` / `FieldChange` | Only by their events |
| `Cron` | Only on its own schedule — see [Cron-triggered flows](#cron-triggered-flows) |
| `Api` | Only explicitly, by id: `POST /flow/instances/start`, `FlowClient.start` / `publishAndStart` |
| `Subflow` | Only from a parent flow's Subflow node |

An explicit start and a Subflow call both run an Automation flow, so `Api` and `Subflow` belong to
Automation alone. Debug runs of a draft ignore the trigger.

Settings belong to one scenario too: `allowInitiatorWithdraw` and `approverDedup` to Approval flows;
`sync` / `rollbackOnFail` to Automation flows, and a sync flow — which runs inside the writer's
transaction — may not contain a Timer, AsyncTask or Approval node. The compiler rejects a flow that
breaks any of these rules.

### Node Types

Current `FlowNodeType` values:

- Control: `Start`, `End`, `Timer`
- Routing: `InclusiveGateway`, `ParallelFork`, `ParallelJoin`
- Human: `Approval`, `HumanTask`*
- Task: `Script`, `CreateRecord`, `GetRecord`, `UpdateRecord`, `DeleteRecord`, `QueryRecords`, `ValidateData`, `Transform`, `CallService`, `CallWebhook`, `SendEmail`, `SendSms`, `SendInboxNotification`, `QueryAI`, `AsyncTask`, `GenerateFile`
- Subflow: `Subflow`
- Data: `ForEach`*, `ReturnValue`

\* `HumanTask` and `ForEach` are defined but **not yet runtime-supported**: they are omitted
from the node palette and rejected at compile time (`UNSUPPORTED_NODE_TYPE`), so a flow
containing them cannot publish.

Each `FlowNodeType` declares the scenarios that may use it (`FlowNodeType.getScenarios()`); the palette
and the compiler (`INVALID_SCENARIO_NODE`) both read it:

| Node types | Scenarios |
|---|---|
| `Start`, `End`, `Script`, `ReturnValue`, `CreateRecord`, `GetRecord`, `UpdateRecord`, `DeleteRecord`, `QueryRecords`, `CallService`, `CallWebhook` | all four |
| `Timer`, the gateways, `Subflow`, `ValidateData`, `Transform`, `SendEmail`, `SendSms`, `SendInboxNotification`, `QueryAI`, `AsyncTask`, `GenerateFile`, `ForEach` | Approval, Automation |
| `Approval`, `HumanTask` | Approval |

Node-behavior invariants:

- **`Subflow` is synchronous-only**: the orchestrator fails the parent if a child
  enters a waiting state (`TIMER` / `ASYNC`), and recursive subflow invocation is
  rejected. The child must be an Automation flow with a `Subflow` trigger, else the
  parent fails. Model human steps in an Approval flow, not a child.
- **`ParallelFork` branches execute serially** (deterministic order), not
  wall-clock-parallel — the gateway models routing topology, not concurrency.

### Runtime Statuses

Defined `FlowExecutionStatus` values:

- `Pending`
- `Running`
- `Waiting` — parked on one or more waits (a pending approval and/or timer / async callback). The
  specific reasons live in the instance's pending-approval list and wait-token list, so a single
  status covers parallel branches suspended for different reasons at once.
- `Rejected`
- `Returned` — sent back to the initiator; ends the instance like a rejection. The initiator edits and
  submits again, which starts a new instance from the beginning
- `Withdrawn`
- `Cancelled`
- `Completed`
- `Failed`

## API Overview

Host applications embedding the engine in-process should integrate through the
`FlowClient` facade (`io.softa.starter.flow.api`) rather than looping back over
REST — actor / initiator / tenant identity is always resolved server-side from the
context, never passed as call parameters. `FlowAutoConfiguration` registers the default
`InProcessFlowClient` only when the application defines no `FlowClient` bean of its own.
See [docs/integration-guide.md](docs/integration-guide.md).

### Design APIs (flow editor)

Base path: `/flow/designs` — the dedicated surface for the graphical editor
(full contract in [`docs/frontend-editor-api.md`](docs/frontend-editor-api.md)).
The generic model API at `/FlowDesign/**` remains available as the platform
data plane and is intentionally not intercepted.

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/designs` | Create a draft |
| `GET` | `/flow/designs?keyword=&scenario=&pageNumber=&pageSize=` | Paged draft list (canvas excluded) |
| `GET` | `/flow/designs/{id}` | Load one draft (canvas + optimistic-lock version) |
| `POST` | `/flow/designs/{id}/save` | Auto-save the canvas; stale versions are rejected |
| `POST` | `/flow/designs/{id}/delete` | Delete a draft if it has never been published |
| `POST` | `/flow/designs/{id}/duplicate` | Copy a draft under a fresh name (`"<name> (copy)"` when `newFlowName` is omitted) |
| `POST` | `/flow/designs/validate` | Validate the posted document; diagnostics returned as a success payload |
| `POST` | `/flow/designs/{id}/publish` | Publish the saved draft as a new active revision |
| `POST` | `/flow/designs/{id}/restore?bundleId=` | Restore the draft canvas from a historical bundle snapshot |
| `GET` | `/flow/designs/{id}/status` | Publish status: revision badge + dirty flag |
| `GET` | `/flow/designs/{id}/availableVariables?nodeId=` | Variables visible to a node (expression autocomplete) |
| `POST` | `/flow/designs/{id}/debugRun` | Compile and run the current draft as a debug bundle (not a sandbox) |
| `GET` | `/flow/designs/{id}/debugRuns` | Debug-run history, newest first |

### Bundle APIs (published revisions)

Base path: `/flow/bundles`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/bundles?flowId=` | Revision list of one flow (or, without `flowId`, the active bundle of every flow of the caller's tenant) |
| `GET` | `/flow/bundles/{bundleId}?include=design` | One revision's summary, optionally with the canvas-complete design snapshot |
| `POST` | `/flow/bundles/{bundleId}/activate` | Make this revision the flow's effective one (rollback = roll-forward) |

### Node Descriptor APIs

Base path: `/flow/nodeDescriptors`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/nodeDescriptors` | List all node descriptors for the editor palette |
| `GET` | `/flow/nodeDescriptors?scenario=Approval` | Filter descriptors by `FlowScenario` |

### Instance APIs

Base path: `/flow/instances` — launch, reads and approval actions on runtime instances.

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/instances/start` | Start an Automation flow whose trigger is `Api`, by `flowId` or a pinned `bundleId`; any other flow is refused |
| `GET` | `/flow/instances/{instanceId}?includeTrace=` | Get one runtime instance (trace excluded by default) |
| `GET` | `/flow/instances/{instanceId}/overlay` | Per-node run state for canvas painting |
| `GET` | `/flow/instances/{instanceId}/trace?sinceSequence=` | Incremental trace rows for polling |
| `POST` | `/flow/instances/search` | Paged summaries of the caller's own record approvals — the initiator filter is always the authenticated user, and automation runs their writes triggered are left out (filters: flowId/status/model/row/createdFrom/createdTo) |
| `POST` | `/flow/instances/approve` | Approve a pending approval |
| `POST` | `/flow/instances/reject` | Reject a pending approval; the comment saying why is required |
| `POST` | `/flow/instances/transfer` | Transfer a pending approval task |
| `POST` | `/flow/instances/delegate` | Delegate a pending approval task |
| `POST` | `/flow/instances/add-sign-before` | Insert a prerequisite signer |
| `POST` | `/flow/instances/add-sign-after` | Insert a follow-up signer |
| `POST` | `/flow/instances/cc` | Send a CC notification |
| `POST` | `/flow/instances/cc/read` | Mark a CC as read |
| `POST` | `/flow/instances/return` | Return a pending approval to the initiator, ending the instance as `Returned`. Only the step's approver, with a comment saying what to change |
| `POST` | `/flow/instances/withdraw` | Withdraw an Approval instance (its initiator, when the flow allows it) |
| `POST` | `/flow/instances/urge` | Urge pending approvers |
| `POST` | `/flow/instances/comment` | Add a comment audit entry |
| `GET` | `/flow/instances/{instanceId}/nodes/{nodeId}/formPermissions` | Get field-level form permissions |
| `GET` | `/flow/instances/by-record?modelName=&rowId=` | A business record's approval history: its approval instances newest first, with the steps still waiting, the approvers yet to act and the approval actions, every user named (names are resolved only once the caller is allowed); a failed instance says where and why it stopped (`failure`). Allowed to whoever took part in its approvals or can read the record |
| `GET` | `/flow/instances/preview?modelName=&rowId=` | What submitting the record would do now, without submitting it: the Submit flow it would get and its approval steps, each with the approvers who would be asked or why it passes on its own; a refusal, no applicable flow or several are reported, a route decided only once the approval runs is reported as undecided. Nothing is started or written. Allowed to whoever can read the record (see [Previewing a record's approval](#previewing-a-records-approval)) |
| `GET` | `/flow/instances/{instanceId}/record` | The business record a record's approval runs on — any other instance is refused, since its record was named freely rather than submitted — for its initiator and the people it gives a task to: read past their own row permissions, limited to the fields the waiting steps list in their field permissions (`Readonly` / `Editable` / `Required`; once nothing waits, those of every approval step). A step that lists no fields shows them all; a field marked `Hidden` never shows. Read as the model read API returns a record (relations and options as references, masked fields masked), with each one-to-many's rows in full — an expense claim comes with its lines |

Who may view an instance: its initiator and the people it gives a task to (approvers pending or done, CC
recipients, transfer, delegation and add-sign targets) by default; anyone else through the standard role and
data-scope configuration — they may read the `FlowInstance` row with their own permissions, or, for a
record's approval, read the record itself.

The defaults need no grant on the flow models, which no business role holds. The engine's own bookkeeping —
loading and saving instances, the approval ledger, the task and branch projections, delegation and auto-CC
rules — runs past the caller's data permissions: who may act is the action handlers' call, who may see the
access guard's. The current user's own lists — inbox counts, pending / completed / CC tasks and the inbox
(`/flow/approvalTasks`), their approval history and sent CCs (`/flow/approvalRecords`), their delegations,
and `POST /flow/instances/search`, with their `FlowClient` counterparts — carry `@SkipPermissionCheck`:
pinned to the caller, row scope could only empty them. The per-instance reads (`.../instance/{instanceId}`,
the instance detail endpoints) are not waived, because the guard judges everyone but the initiator and the
task holders by their own permissions.

### Automation APIs

Base path: `/flow/automation` — trigger firing and transient runs; none of these reads or
acts on an existing instance.

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/automation/trigger` | Fire a trigger event and start matching flows. `Submit`, `Api` and `Subflow` events are refused: those flows start by record submission, by id, or from a parent flow |
| `POST` | `/flow/automation/onchange` | Run `COMPUTE` flows transiently and return variable diffs |
| `POST` | `/flow/automation/validate` | Run `VALIDATION` flows transiently against candidate row data; returns each flow's declared outputs keyed by `flowId` |

Async-task and timer resumes have no REST endpoint: `FlowAsyncTaskConsumer`,
`FlowTimerConsumer` and the due-timer sweep of the maintenance cron call
`FlowRuntimeEngine` in-process.

`actorId` / `initiatorId` / `tenantId` are **never** sent by clients: every action
request stamps them server-side from the login context (`@JsonProperty(READ_ONLY)`),
and all query endpoints resolve the current actor from the context. The in-process
`FlowClient` resolves the actor the same way and calls the same services as these
endpoints.

## Approval Projection APIs

### Approval Tasks

Base path: `/flow/approvalTasks`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/approvalTasks/counts` | Inbox badge counts for the current actor (`{pendingApprovals, unreadCc}` — same filter definitions as the paged `/pending` and `/cc?read=false` queries) |
| `GET` | `/flow/approvalTasks/pending?flowId=&instanceId=&nodeId=&pageNumber=&pageSize=` | Pending tasks for the current actor (paged: returns `Page`) |
| `GET` | `/flow/approvalTasks/completed?flowId=&instanceId=&nodeId=&pageNumber=&pageSize=` | Completed tasks for the current actor (paged) |
| `GET` | `/flow/approvalTasks/cc?read=&flowId=&instanceId=&nodeId=&pageNumber=&pageSize=` | CC tasks for the current actor (paged; `read=false` unread, `read=true` acknowledged, omit `read` for both) |
| `GET` | `/flow/approvalTasks/inbox?flowId=&instanceId=&nodeId=&includeCompletedApprovals=&pageNumber=&pageSize=` | Unified inbox view (pending approvals + CC; optional completed approvals) |
| `GET` | `/flow/approvalTasks/instance/{instanceId}` | All tasks for one runtime instance (participants / initiator only) |

### Approval Records

Base path: `/flow/approvalRecords`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/approvalRecords/instance/{instanceId}` | Approval history by runtime instance |
| `GET` | `/flow/approvalRecords/history` | Actor-scoped approval history |
| `GET` | `/flow/approvalRecords/cc/sent` | Sender-side CC history |

### CC Config

Base path: `/flow/ccConfigs`

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/ccConfigs` | Create a CC rule |
| `GET` | `/flow/ccConfigs?flowId=` | List CC rules by `flowId` |
| `POST` | `/flow/ccConfigs/{id}/deactivate` | Deactivate a CC rule |

### Delegation

Base path: `/flow/delegations`

| Method | Path | Description |
|---|---|---|
| `POST` | `/flow/delegations` | Create a delegation rule |
| `GET` | `/flow/delegations/my` | Delegations created by the caller (delegator resolved from the login context) |
| `GET` | `/flow/delegations/to-me` | Active delegations assigned to the caller (delegate resolved from the login context) |
| `POST` | `/flow/delegations/{id}/cancel` | Cancel one of the caller's delegation rules; another user's is refused (403) |

### Monitor

Base path: `/flow/monitor` — operator-facing; the search endpoint requires the system admin role (`@RequireRole(SystemRoleAdmin)`, fail-closed when the host app wires no role provider). Opening another user's instance (detail / overlay / trace / approval history) is decided by the caller's permissions on `FlowInstance`, as for any viewer who is not a participant.

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/monitor/health` | Flow runtime health snapshot (per-status instance counts, overdue timers) |
| `POST` | `/flow/monitor/instances/search` | Cross-initiator paged instance summaries; honors the request's `initiatorId` filter when present |

### Event Logs

Base path: `/flow/events`

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow/events?flowId=&sourceModel=&sourceRowId=&instanceId=&success=&eventTimeFrom=&eventTimeTo=&pageNumber=&pageSize=` | Paged event log, newest first (filters combine with AND; list rows exclude the parameters payload) |
| `GET` | `/flow/events/{id}` | Single event including the raw trigger-parameters payload |

## Approval Model

### Approval And Reject Thresholds

- Approval modes: `ANY_ONE`, `UNANIMOUS`, `MIN_COUNT`, `PERCENTAGE`
- Reject modes: `ANY_ONE`, `UNANIMOUS`, `MIN_COUNT`, `PERCENTAGE`
- One actor can only cast one effective vote per cycle.
- Compile-time validators reject impossible threshold combinations.

### Approver Resolution

Approval nodes support:

- static approvers via `config.approvers`
- dynamic approvers via `config.approverSource`
- empty-approver strategies via `config.emptyApproverStrategy`

Supported dynamic source types:

- from start variables: `VariableList`, `Expression`, `InitiatorManager` (`initiatorManagerId`), `Role` (`roleApprovers`)
- from the organization: `Supervisor` (the initiator's N-th level supervisor), `DeptLeader` and `DeptHrbp`
  (of a department, by default the initiator's), `RoleQuery` (holders of the roles named by `roleIds`, by
  `roleCodes`, or both — optionally narrowed to the initiator's or one department), `Position`, `Department`

Approvers are **login account ids**. The organization is read through the `OrganizationService` SPI, whose
default `FlowOrgService` follows the HR conventions permission-starter already reads, so an application on
those models configures nothing:

| Purpose | Model.field |
|---|---|
| Account → employee | `Employee.userId` |
| Name, department, position | `Employee.fullName`, `departmentId`, `jobPositionId` |
| Supervisor (an employee id) | `Employee.piEmployeeId` |
| Department leader, HRBP (employee ids), parent | `Department.picEmpId`, `hrbpEmpId`, `parentId` |
| Role holders, role codes | `UserRoleRel.userId` / `roleId`, `Role.code` |

An employee without an account counts as nobody, so a step resolving only to such employees is empty and
follows its empty-approver strategy; a role holder is an account and approves even without an employee
profile. A department without a leader (or HRBP) defers to its parent's. Lookups skip row scope — who
approves must not depend on what the initiator may see. Every model and field name can be overridden
under `softa.flow.org.*` (`OrganizationServiceProperties`); an application with another organization
system provides its own `OrganizationService` bean.

Role codes, unlike role ids, are the same in every tenant, so a flow seeded to many tenants names its built-in
roles by code (`roleCodes`); the designer offers both lists, since roles an administrator creates have no code
and are picked by id.

Approver dedup: the default policy is **`GLOBAL`** — a user who already approved an
earlier node is auto-passed on later nodes (recorded as `AUTO_APPROVE`). Residual
risk: a mid-flow form edit is still auto-endorsed by that earlier approval; set the
policy to `CONTIGUOUS` or `NONE` on flows where a re-look matters.

### Approver's writes

Once the actor is validated as the node's approver, the approval's form edits and every node it resumes
run with the actor's own row scope set aside. Approving is the grant: an approver rarely holds update on
the record they approve, and with row scope granted per action (permission-starter) that would refuse
the approval itself. Host endpoints that apply an approval outside the engine do the same, after an
entry check that the approver can see the request (`@RequirePermission`). A record's own write rules
(`ModelWriteValidator`) therefore see these writes as the system's: a rule that freezes a record while it is
in approval — no person edits it — still lets through the fields a node marks editable.

Set aside means **every** permission check, not only row scope — sensitive-field write checks included.
The node's form configuration (its field permissions, `formPermissions`) is the only authorization for the
fields the approver edits. Only the fields it marks `EDITABLE` or `REQUIRED` are written back; any other key
in the approve request's `formData` — a `HIDDEN` or `READONLY` field, or one the node does not list — is
dropped, and a node with no `formPermissions` writes nothing. An approver whose roles hold no sensitive
field set covering a field can therefore still write it if the node's form exposes it as editable. Flow
designers must not make a sensitive field (pay, bank details, identity numbers, …) editable on an approval
form unless every approver that node can resolve to is meant to edit it; show it read-only, or leave it
off the form.

### Additional Approval Actions

- transfer
- delegate
- add-sign-before
- add-sign-after
- cc
- read-cc
- return (to the initiator; ends the instance)
- withdraw
- urge
- comment

### Timeout Handling

Approval timeout strategies:

- `REMIND`
- `AUTO_APPROVE`
- `AUTO_REJECT`
- `ESCALATE`

`ApprovalTimeoutScheduler` handles reminders, auto actions, and escalation against persisted pending approvals.

It runs off a cron row. The starter's maintenance crons (approval timeout reminders and handling, delegation
expiry, timer sweep, stuck-instance scan, debug bundle purge, automation run purge) ship switched off in
`data-system/SysCron.FlowMaintenance.json`. The app lists that file under `platform` in its
`seed-manifest.yml`, or the rows never exist; then switch on the ones it uses. See the
[seed-starter README](../seed-starter/README.md).

## Task Execution Model

Executor-backed task nodes use `TaskNodeConfig`. The node **`FlowNodeType`**
selects the executor — there is no separate `executor` field in config.

```json
{
  "input": {
    "to": ["{{ applicantEmail }}"],
    "subject": "Order {{ orderId }} confirmed",
    "htmlBody": "<h1>Thank you for your order!</h1>"
  },
  "outputVariable": "taskResult"
}
```

- `input` supports `{{ expr }}` interpolation; resolution is owned by the executor
  (type-aware for data executors, recursive interpolation for free-shape payloads)
- `options` is executor-specific
- `outputVariable`, when present, stores the raw executor result under one variable name

Every task node type has a **typed input config DTO** registered in `TaskConfigTypes`:
the compiler parses `config.input` into the DTO at publish time and rejects missing
required keys (`MISSING_REQUIRED_INPUT`), so a misconfigured node fails at publish
instead of mid-execution. `DefaultTaskExecutorRegistry` asserts at boot that every
registered executor's node type has such an entry.

### Bundled Task Executors

| Node type | Executor bean | Notes |
|---|---|---|
| `CREATE_RECORD` | `CreateDataTaskExecutor` | Built in |
| `GET_RECORD` | `GetDataTaskExecutor` | Built in |
| `UPDATE_RECORD` | `UpdateDataTaskExecutor` | Built in |
| `DELETE_RECORD` | `DeleteDataTaskExecutor` | Built in |
| `QUERY_RECORDS` | `QueryRecordsTaskExecutor` | Built in; opt-out via `flow.task.builtin.query-records.enabled=false` to register a custom executor |
| `VALIDATE_DATA` | `ValidateDataTaskExecutor` | Built in |
| `TRANSFORM` | `ExtractTransformTaskExecutor` | Built in |
| `CALL_WEBHOOK` | `WebHookTaskExecutor` | Built in |
| `CALL_SERVICE` | `CallServiceTaskExecutor` | **Disabled by default**. Opt-in via `flow.task.builtin.call-service.enabled=true`; a non-empty `flow.task.call-service.allow-list` of permitted bean-name prefixes is then mandatory |
| `SEND_EMAIL` | `SendEmailTaskExecutor` | Registered only when `MessageService` is available |
| `SEND_SMS` | `SendSmsTaskExecutor` | Registered only when `MessageService` is available |
| `SEND_INBOX_NOTIFICATION` | `SendInboxNotificationTaskExecutor` | Registered only when `MessageService` is available |
| `QUERY_AI` | `QueryAiTaskExecutor` | Registered only when `AiRobotService` is available |
| `ASYNC_TASK` | `AsyncTaskExecutor` | Registered only when `FlowAsyncTaskProducer` is available |
| `GENERATE_FILE` | `GenerateFileTaskExecutor` | Registered only when `DocumentTemplateService` (file-starter) is available |

Current module notes:

- `CALL_SERVICE` ships a bundled executor but is **disabled by default**: it invokes an arbitrary Spring bean method by name, so it must be explicitly enabled and given a non-empty bean-name allow-list.

## Approving Business Records

A business record — a leave request, an expense claim — is approved by an Approval flow whose trigger is a
`SubmitTrigger(modelName, conditionExpression)`. The host implements one `ModelApprovalHandler` per model
(`runtime/spi`, a Spring bean) and calls `FlowClient` from its own submit and withdraw endpoints:

| `FlowClient` | What happens |
|---|---|
| `submitRecord(modelName, rowId)` | `handler.prepare(rowId)` checks the record with the submitter's own permissions and returns the instance title and routing variables; the flow is chosen among the tenant's active flows whose submit trigger names the model and whose condition holds for the record — exactly one must match, else the submission is refused naming why; `handler.onSubmitted(rowId)` moves the record to "in approval"; the record's approval starts. All in the caller's transaction. Every submission starts a new approval from the beginning — after a return too, with the flow chosen again for the record as it is now |
| `withdrawRecord(modelName, rowId, comment)` | Withdraws the waiting approval (the engine checks the actor is the initiator and the flow allows it). When the record's approval **failed**, its submitter takes the record back the same way — the handler is told it was withdrawn; the failed instance stays for flow monitoring. Refused when the record has no approval in progress |
| `recordApprovals(modelName, rowId)` | The approval history, as `GET /flow/instances/by-record` |
| `previewRecord(modelName, rowId)` | The approval a submission would start, as `GET /flow/instances/preview` — see below. Call it outside a transaction |

Only the instance a submission starts is the record's **approval** (`FlowExecutionState.recordApproval`): a
debug run of the same flow on the same record is not, and is never written back (an API start of an Approval
flow is refused — its trigger is `Submit`). A record has
at most one approval in progress — `FlowInstance.openRecordId` holds the record's id while its approval is
unfinished, under a unique index with the model, so a second submission is refused even when two arrive at
once.

Results reach the record through one listener, `ApprovalOutcomeListener`: when a record's approval completes,
is rejected, returned, withdrawn or cancelled, it calls `handler.onOutcome(rowId, outcome)` with the type, the
deciding user and their comment (none for an automatic approval) — in the action's transaction, with
permission checks skipped, so a handler that throws vetoes the action. Failed instances write nothing; the
submitter takes the record back by withdrawing it. A finished instance is saved again on a comment or a CC
read; such saves are not handed on, but handlers must still be idempotent (change the record only while it is
in approval). `onSubmitted` runs before the instance starts because a flow with no approval step completes
while it starts. A step whose approver resolves to nobody fails at runtime under its empty-approver strategy
(`Error` by default); the record's approval preview shows where before anyone submits.

On a submit flow the submitter's own step approves itself: when a step's approvers include the submitter,
they are counted as having approved — recorded as `AUTO_APPROVE` ("Auto-approved (submitter)") in the
approval history — and the step's approval mode decides the rest: an any-one step passes, an all-approve step
still waits for the others. An empty step whose strategy is `AutoApprove` is recorded the same way, with no
actor ("Auto-approved (no approver)").

### Previewing a record's approval

`GET /flow/instances/preview?modelName=&rowId=` (`FlowClient.previewRecord`) answers what submitting the record
would do now, without submitting it — for a record page that shows the approval path before anyone presses
Submit. It makes the submission's own checks and choice of flow: `handler.prepare(rowId)` with the requester's
own permissions, the check that no approval is in progress, the submit triggers. A refusal, no applicable flow
or several are reported in `status` (`Refused`, `NoFlow`, `SeveralFlows`, with the flows that apply in
`candidates`) and `message` — the message the submission would be refused with — rather than raised.
`onSubmitted` is never called.

When one flow applies (`Ready`), `ApprovalPathPreviewer` walks it from its start node the way the engine runs it,
every approval step assumed approved: routes are decided by `FlowGatewayRouter` over the variables the
submission would start with, approvers by `PendingApprovalFactory` (auto-delegation included) and automatic
approvals by `ApproverDedupService`, on a state that is never saved. `steps` lists the approval steps in the
order the approval reaches them, each with an `outcome`:

| Outcome | The step |
|---|---|
| `Waiting` | Waits for `approvers`, of whom `requiredApprovals` must approve |
| `AutoApproved` | Passes on its own: `autoApprovals` counts as having approved the submitter (`Submitter`) or someone who approved an earlier step, under the flow's approver dedup (`EarlierApproval`) |
| `NoApproverApproved`, `NoApproverSkipped` | Resolves to nobody; its empty-approver strategy approves it on record or skips it |
| `NoApproverFails` | Resolves to nobody under strategy `Error`: the approval would stop there, as `failure` says |
| `Undetermined` | Who approves it, or whether it passes on its own, depends on data only known once the approval runs |

Nothing is guessed. The preview runs no node: once the walk passes a script, a task or a subflow, the variables
that node writes are unknown — all of them for a task without an output variable and for an async task — and
the instance id is unknown from the start. A route whose conditions read an unknown variable is listed in
`undecided` and not followed; a step whose approvers come from a dynamic source, reached after such a node, is
`Undetermined`; with approver dedup on, so is a step whose approver may or may not have approved an earlier
step, one of several there who could pass it. An error the engine would fail on — a route matching no edge or
several, a condition that cannot be evaluated — ends the walk in `failure`.

Nothing is written: no instance, task, ledger row or notification, and no delegation's use is counted. The
preview is allowed to whoever can read the record with their own permissions
(`FlowInstanceAccessGuard.requireRecordReader`) and is never waived from row scope; taking part in the record's
earlier approvals is not enough. Users are named as in the approval history. It runs in no transaction of its
own, and `FlowClient.previewRecord` should be called outside one: a refusal thrown through a transactional
method marks the transaction it joins rollback-only.

Default flows reach tenants as tenant seed files: one `FlowDesign` row per file, its trigger naming the
model. When seed-starter announces the rows a file created (`SeedFileLoadedEvent`), `SeedFlowPublisher`
publishes each new design; a design that does not compile fails the file in that tenant. Tenants then edit
their own copy in the designer.

## Notifications

The default `ApprovalNotificationService` is `InboxApprovalNotificationService`: Workflow-type inbox messages
through message-starter that point at the flow instance (`sourceModel` = `FlowInstance`, `sourceId` = its
instance id; the client opens the instance and goes on from there) — tasks to the approvers still to act
(assigned, transferred, delegated, urged, about to time out), CCs to their recipients, and the result to the
initiator (approved; rejected or returned, with the comment). Only an Approval flow announces its completion:
an Automation run is not something the user whose write triggered it asked to hear about. Messages are written after the action commits,
in a transaction of their own: a rolled-back action sends nothing, and a message that cannot be written is
logged without failing the action. Titles longer than the inbox's 200 characters are cut. Texts are translated
into the tenant's default language. Provide your own `ApprovalNotificationService` bean for email, SMS or IM.

## Trigger And Messaging Integration

Supported trigger sources (`TriggerSource` sealed sub-types), each the start contract of the flows that
declare it (see [Flow Scenarios](#flow-scenarios) for which scenario takes which):

- `EntityChange` — a change log of the model. Each change log starts a run at most once per flow: its
  `uuid` travels as the event id onto `FlowInstance.triggerEventId` (unique with the flow), so a redelivered
  change log returns the run it already started, while distinct changes of one row each start their own
- `Cron` — the flow's own Quartz schedule; started only by it (see [Cron-triggered flows](#cron-triggered-flows)):
  other cron jobs' ticks never start it, and `POST /flow/automation/trigger` refuses the type
- `FieldChange` (COMPUTE scenario only)
- `Api` (AUTOMATION scenario only) — started explicitly by id; no event matches it, and
  `POST /flow/automation/trigger` refuses the type
- `Subflow` (AUTOMATION scenario only) — run only from a parent's Subflow node; refused as an event likewise
- `Submit` (APPROVAL scenario only) — names the model whose records the flow approves and, optionally, a
  condition over the record. Started only by record submission (see Approving Business Records); change
  logs never produce it and `POST /flow/automation/trigger` refuses it

Pulsar-connected components are conditionally registered:

| Property | Component | Purpose |
|---|---|---|
| `mq.topics.flow-event.topic` | `FlowEventProducer`, `FlowEventConsumer` | Async trigger fire and consume |
| `mq.topics.flow-async-task.topic` | `FlowAsyncTaskProducer`, `FlowAsyncTaskConsumer` | Async task dispatch and callback resume |
| `mq.topics.change-log.topic` | `ChangeLogFlowConsumer` | Entity change driven flow triggers |
| `mq.topics.cron-task.topic` | `CronFlowJobProvider`, `CronTaskFlowConsumer` | Cron-triggered flows on the cron schedule, and their start |
| `mq.topics.flow-timer.topic` | `FlowTimerConsumer` | Timer wake-up consume side |

Important timer note:

- The module includes the timer resume consumer.
- A scheduler or producer that publishes `FlowTimerMessage` still has to be provided by the host application or another integration module.

### Cron-triggered flows

A Cron-triggered flow owns its schedule: the trigger's `cronExpression`, in the Quartz format cron-starter runs
(`second minute hour day-of-month month day-of-week [year]`, e.g. `0 0 9 ? * MON-FRI` for 09:00 on weekdays, in
the scheduler's time zone). Publishing parses it the way the scheduler does: a blank expression fails with
`CRON_MISSING_EXPRESSION`, one the scheduler cannot run with `CRON_INVALID_EXPRESSION`. Automation, Validation and
Compute flows may run on a schedule, Approval flows may not (see [Flow Scenarios](#flow-scenarios)); a scheduled
Validation or Compute flow is evaluated transiently, like any of their runs.

- **Scheduling.** `CronFlowJobProvider` is a cron-starter `CronJobProvider`: the scheduling leader reads every
  tenant's active Cron-triggered revisions (`FlowBundle.cronExpression`, copied from the trigger at publish) when it
  starts scheduling and again every 30 seconds, and runs one job per revision, named `flow:<bundleId>`. Publishing,
  activating another revision (rollback or roll-forward) and switching to another trigger therefore move or remove the
  schedule within 30 seconds, whichever instance did it — an execution due in between is skipped, never run twice;
  no `SysCron` row is written. Nothing is scheduled while `mq.topics.cron-task.topic` is not configured.
- **Tenants.** A tenant's flow runs in that tenant's context, and only while the tenant is active; a platform-shared
  flow (`tenantId = 0`) runs once in every active tenant, each run in that tenant's context. Without multi-tenancy
  (`system.enable-multi-tenancy=false`), every flow runs once, with no tenant.
- **Starting.** `CronTaskFlowConsumer` starts the revision a `flow:<bundleId>` message names
  (`FlowAutomationService.fireCron`), and only while it is still its flow's active revision: the job of a replaced
  revision firing before the leader's next read starts nothing. Every other cron message — the maintenance crons,
  other modules' jobs, a `SysCron` row whatever its name — is ignored, and no trigger event matches a Cron flow. The
  flow starts with no initiator and the time the schedule fired as variable `triggerTime`, and an Automation flow's
  fire is logged in `FlowEvent` like any other.
- **Every execution starts its own run**, even while the previous one is still running or waiting. An execution
  carries no event id, so unlike a change log, a redelivered cron message is not recognized as one that already ran.

## Persistence Model

| Entity | Description |
|---|---|
| `FlowDesign` | Draft working copy |
| `FlowBundle` | Published compiled bundle |
| `FlowInstance` | Persisted runtime instance state (trace and approval history are NOT embedded — see the two dedicated tables). Approval instances are kept; finished Automation runs, with their trace, branches and the trigger event log, are purged after `flow.automation.retention-days` (default 30) by the `flow_automation_purge` cron |
| `FlowExecutionTrace` | Append-only execution trace rows (the in-memory state keeps only the current attempt's delta) |
| `FlowApprovalTask` | Pending/completed/CC task projections |
| `FlowApprovalRecord` | Approval action audit ledger — the authoritative approval history, flushed by the instance store in the same transaction as the instance row |
| `FlowCcConfig` | Automatic CC rules |
| `FlowDelegation` | Delegation rules |
| `FlowEvent` | Trigger event logs |
| `FlowDebugHistory` | Debug snapshots |
| `FlowParallelBranch` | Parallel branch tracking |

Primary storage adapters in this module:

- `OrmFlowBundleRegistry`
- `OrmFlowInstanceStore`

`OrmFlowBundleRegistry` loads nothing at startup and caches per tenant:

- **Trigger matching** reads an index of the tenant's active bundles, loaded in that tenant's own context on its first event (an empty result is kept too) and merged with the platform-shared bundles (`tenantId = 0`); a context without a tenant matches the shared ones only. Entries live for `flow.trigger-cache-ttl-seconds` (default `60`). Publishing or activating a revision drops the tenant's entry at once on the instance that did it; other instances pick the change up within the TTL. Change logs of models no flow watches are not turned into trigger events.
- **Definitions** are cached by `bundleId` in a bounded LRU (a published revision never changes). A bundle of another tenant reads as not found, whether cached or not; platform-shared bundles are visible to every tenant.
- **The active revision of a flow** is read from the database on every lookup, so starting by `flowId` follows a publish or rollback made on any instance.

## Node Descriptor Contract

The editor palette is built from `FlowNodeDescriptor` records exposed through `/flow/nodeDescriptors`.

- structural descriptors come from `BuiltinNodeDescriptorProvider`
- executor-backed task descriptors come from `TaskExecutorDescriptorProvider`
- descriptors include label, icon, sort order, config schema, default config, and allowed scenarios

`TaskExecutorDescriptorProvider` only exposes task nodes for executors that are actually registered as Spring beans, so conditional executors such as `QUERY_AI` and `ASYNC_TASK` only appear when their dependencies are present.

## Frontend Graph Compatibility

Design graph DTOs remain aligned with xyflow/react style structures:

| xyflow/react | flow-starter |
|---|---|
| `Node.id` | `FlowGraphNode.id` |
| `Node.type` | `FlowGraphNode.type` |
| `Node.position` | `FlowGraphNode.position` |
| `Node.width` / `height` | `FlowGraphNode.width` / `height` |
| `Node.data` | `FlowGraphNode.data` |
| `Edge.id` | `FlowGraphEdge.id` |
| `Edge.source` / `target` | `FlowGraphEdge.source` / `target` |
| `Edge.sourceHandle` / `targetHandle` | `FlowGraphEdge.sourceHandle` / `targetHandle` |
| `Edge.type` | `FlowGraphEdge.type` |
| `Edge.label` | `FlowGraphEdge.label` |
| `Edge.data` | `FlowGraphEdge.data` |
| `Viewport {x, y, zoom}` | `FlowGraphViewport {x, y, zoom}` |

## Host Application Integration Checklist

Before enabling `flow-starter` in a real app, verify:

- add `flow-starter` to the application classpath
- create and expose `FlowDesign` drafts through the generic model APIs
- provide your own `ApprovalNotificationService` if you need email, SMS or IM beyond the default inbox messages
- check the organization follows the default HR conventions, or override `softa.flow.org.*`, or provide your own `OrganizationService`
- for business records: implement a `ModelApprovalHandler` per model, submit and withdraw through `FlowClient`, and ship each default flow as a tenant seed file
- enable Pulsar topics if using triggers or async tasks
- provide timer scheduling/publishing if using `TIMER` nodes
- enable `CALL_SERVICE` explicitly (plus its bean-name allow-list) if flows need to invoke Spring beans; all other task node types ship working executors, conditionally registered on their capability beans
