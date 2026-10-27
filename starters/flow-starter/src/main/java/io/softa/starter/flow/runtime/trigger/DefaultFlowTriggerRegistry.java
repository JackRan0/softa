package io.softa.starter.flow.runtime.trigger;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.design.trigger.*;
import io.softa.starter.flow.message.ChangeLogTriggerMapper;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Default trigger registry that matches the bundles the registry offers for an event's
 * model by trigger type, source model, and optional condition expression.
 */
@Component
public class DefaultFlowTriggerRegistry {

    static final String PARAM_CHANGED_FIELDS = "_changedFields";

    private final FlowBundleRegistry bundleRegistry;

    public DefaultFlowTriggerRegistry(FlowBundleRegistry bundleRegistry) {
        this.bundleRegistry = bundleRegistry;
    }

    public List<CompiledFlowDefinition> findMatchingFlows(FlowTriggerEvent event) {
        // Candidates are scoped to the event's tenant (plus platform-shared flows), which must be
        // in context — the consumers restore it from the message. The tenant check below still
        // holds the line for a registry that offers more.
        Long currentTenant = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        List<CompiledFlowDefinition> matches = new ArrayList<>();
        for (CompiledFlowDefinition definition : bundleRegistry.listTriggerCandidates(event.getSourceModel())) {
            if (!tenantMatches(definition, currentTenant)) {
                continue;
            }
            if (matches(definition, event)) {
                matches.add(definition);
            }
        }
        return matches;
    }

    /**
     * The current tenant's active flows (plus platform-shared ones) whose {@link SubmitTrigger}
     * names {@code modelName} and whose condition holds for {@code record} — the flows that may
     * approve the submitted record. The caller decides what zero or several matches mean.
     *
     * @param modelName the submitted record's model
     * @param record    the submitted record's fields, the condition's variables
     * @throws FlowRuntimeException when a matching flow's condition cannot be evaluated: an
     *                              unusable condition is a configuration fault to report, not a
     *                              "does not apply"
     */
    public List<CompiledFlowDefinition> findSubmitFlows(String modelName, Map<String, Object> record) {
        Long currentTenant = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        List<CompiledFlowDefinition> matches = new ArrayList<>();
        for (CompiledFlowDefinition definition : bundleRegistry.listTriggerCandidates(modelName)) {
            if (!tenantMatches(definition, currentTenant)
                    || !(definition.getTrigger() instanceof SubmitTrigger trigger)
                    || !Objects.equals(trigger.modelName(), modelName)) {
                continue;
            }
            if (!StringUtils.hasText(trigger.conditionExpression())
                    || evaluateSubmitCondition(definition, trigger, record)) {
                matches.add(definition);
            }
        }
        return matches;
    }

    private static boolean evaluateSubmitCondition(CompiledFlowDefinition definition, SubmitTrigger trigger,
                                                   Map<String, Object> record) {
        try {
            return ComputeUtils.executeBoolean(trigger.conditionExpression(),
                    record == null ? new HashMap<>() : new HashMap<>(record));
        } catch (Exception e) {
            throw new FlowRuntimeException("The submit condition of flow '" + definition.getFlowName()
                    + "' cannot be evaluated: " + e.getMessage(), e);
        }
    }

    /**
     * Whether any flow in the current context watches entity changes of {@code model} — lets the
     * change-log paths skip mapping rows nothing listens to.
     */
    public boolean hasEntityChangeTrigger(String model) {
        for (CompiledFlowDefinition definition : bundleRegistry.listTriggerCandidates(model)) {
            if (definition.getTrigger() instanceof EntityChangeTrigger trigger
                    && (!StringUtils.hasText(trigger.modelName()) || trigger.modelName().equals(model))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Match-time tenant isolation: a platform-shared flow ({@code tenantId == 0}) applies to
     * every tenant; otherwise the flow's tenant must equal the event's current tenant.
     */
    private boolean tenantMatches(CompiledFlowDefinition definition, Long currentTenant) {
        Long flowTenant = definition.getTenantId();
        if (FlowBundleRegistry.SHARED_TENANT_ID.equals(flowTenant)) {
            return true;
        }
        return Objects.equals(flowTenant, currentTenant);
    }

    private boolean matches(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        TriggerSource trigger = definition.getTrigger();
        // A submit trigger is chosen per record by findSubmitFlows, never by a generic event.
        if (trigger == null || trigger instanceof SubmitTrigger) {
            return false;
        }
        // Derive the trigger type discriminator from the TriggerSource class name
        String triggerTypeName = resolveTriggerTypeName(trigger);
        if (!Objects.equals(triggerTypeName, event.getType())) {
            return false;
        }
        if (trigger instanceof FieldChangeTrigger fieldTrigger && !matchesFieldChange(fieldTrigger, event)) {
            return false;
        }
        // For entity-related triggers, match source model, change events, and evaluate condition
        if (trigger instanceof EntityChangeTrigger(String modelName, List<ChangeEvent> events, String conditionExpression)) {
            if (StringUtils.hasText(modelName)
                    && !Objects.equals(modelName, event.getSourceModel())) {
                return false;
            }
            if (!matchesChangeEvents(events, event)) {
                return false;
            }
            if (StringUtils.hasText(conditionExpression)) {
                try {
                    return ComputeUtils.executeBoolean(
                            conditionExpression, event.getParameters());
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean matchesFieldChange(FieldChangeTrigger trigger, FlowTriggerEvent event) {
        if (StringUtils.hasText(trigger.modelName())
                && !Objects.equals(trigger.modelName(), event.getSourceModel())) {
            return false;
        }
        if (trigger.fieldNames() == null || trigger.fieldNames().isEmpty()) {
            return true;
        }
        Map<String, Object> params = event.getParameters();
        Object rawChangedFields = params == null ? null : params.get(PARAM_CHANGED_FIELDS);
        if (rawChangedFields instanceof Collection<?> changedFields) {
            return trigger.fieldNames().stream().anyMatch(changedFields::contains);
        }
        if (rawChangedFields instanceof String changedField) {
            return trigger.fieldNames().contains(changedField);
        }
        return false;
    }

    /**
     * Matches the change event's access type against the trigger's configured
     * {@code events} filter. An empty/unset filter matches every change event;
     * a non-empty filter fires only when the event's access type maps to one of
     * the configured {@link ChangeEvent}s (so a CREATE-only trigger never fires
     * on an UPDATE/DELETE, and no trigger fires on a READ).
     */
    private boolean matchesChangeEvents(List<ChangeEvent> events, FlowTriggerEvent event) {
        if (events == null || events.isEmpty()) {
            return true;
        }
        ChangeEvent actual = resolveChangeEvent(event);
        return actual != null && events.contains(actual);
    }

    private ChangeEvent resolveChangeEvent(FlowTriggerEvent event) {
        Map<String, Object> params = event.getParameters();
        Object raw = params == null ? null : params.get(ChangeLogTriggerMapper.PARAM_ACCESS_TYPE);
        if (raw == null) {
            return null;
        }
        return switch (raw.toString()) {
            case "CREATE" -> ChangeEvent.CREATE;
            case "UPDATE" -> ChangeEvent.UPDATE;
            case "DELETE" -> ChangeEvent.DELETE;
            default -> null; // e.g. READ — entity-change triggers never fire on reads
        };
    }

    private String resolveTriggerTypeName(TriggerSource trigger) {
        // Returns the @JsonSubTypes name value for the concrete type
        return switch (trigger) {
            case EntityChangeTrigger t -> "EntityChange";
            case ApiTrigger t          -> "Api";
            case CronTrigger t         -> "Cron";
            case SubflowTrigger t      -> "Subflow";
            case FieldChangeTrigger t  -> "FieldChange";
            case SubmitTrigger t       -> "Submit";
        };
    }
}
