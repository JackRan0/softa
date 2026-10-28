package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.compute.CronUtils;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Validates the trigger a flow declares against its {@link FlowScenario}.
 * <p>
 * Every flow declares one, and the trigger is the flow's start contract: a {@code Submit} flow starts
 * only by record submission, an {@code Api} flow only by an explicit start, a {@code Subflow} flow only
 * from a parent's Subflow node, a {@code Cron} flow only on its own schedule, and {@code EntityChange} /
 * {@code FieldChange} flows only by their events. An explicit start and a Subflow call both run an
 * Automation flow, so {@code Api} and {@code Subflow} belong to Automation alone.
 */
public class TriggerConsistencyValidator implements FlowValidator {

    private static final String MISSING_TRIGGER         = "MISSING_TRIGGER";
    private static final String TRIGGER_NOT_ALLOWED     = "TRIGGER_NOT_ALLOWED";
    private static final String SUBMIT_MISSING_MODEL    = "SUBMIT_MISSING_MODEL";
    private static final String CRON_MISSING_EXPRESSION = "CRON_MISSING_EXPRESSION";
    private static final String CRON_INVALID_EXPRESSION = "CRON_INVALID_EXPRESSION";

    private static final Map<FlowScenario, List<Class<? extends TriggerSource>>> ALLOWED_TRIGGERS =
            new EnumMap<>(Map.of(
                    FlowScenario.APPROVAL, List.of(SubmitTrigger.class),
                    FlowScenario.AUTOMATION, List.of(
                            EntityChangeTrigger.class, CronTrigger.class, ApiTrigger.class, SubflowTrigger.class),
                    FlowScenario.VALIDATION, List.of(EntityChangeTrigger.class, CronTrigger.class),
                    FlowScenario.COMPUTE, List.of(FieldChangeTrigger.class, EntityChangeTrigger.class, CronTrigger.class)));

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        FlowScenario scenario = definition.getScenario();
        TriggerSource trigger = definition.getTrigger();
        // A missing scenario is reported as MISSING_SCENARIO; there is nothing to check the trigger against.
        if (scenario != null) {
            List<Class<? extends TriggerSource>> allowed = ALLOWED_TRIGGERS.get(scenario);
            if (trigger == null) {
                d.add(CompileDiagnostic.flowLevel(MISSING_TRIGGER, "A '" + scenario.getType()
                        + "' flow must declare its trigger: " + typeNames(allowed)));
            } else if (!allowed.contains(trigger.getClass())) {
                d.add(CompileDiagnostic.flowLevel(TRIGGER_NOT_ALLOWED, "A '" + scenario.getType()
                        + "' flow cannot use a '" + typeName(trigger.getClass()) + "' trigger; it takes: "
                        + typeNames(allowed)));
            }
        }
        if (trigger instanceof SubmitTrigger submit && !StringUtils.hasText(submit.modelName())) {
            d.add(CompileDiagnostic.flowLevel(SUBMIT_MISSING_MODEL,
                    "Submit trigger must name the model whose records the flow approves"));
        }
        if (trigger instanceof CronTrigger cron) {
            validateCronExpression(definition.getName(), cron.cronExpression(), d);
        }
        return d;
    }

    /**
     * The expression must be one the cron scheduler can run: it is parsed the way the scheduler parses it, so a
     * flow that publishes is one that gets scheduled.
     */
    private static void validateCronExpression(String flowName, String cronExpression, List<CompileDiagnostic> d) {
        if (!StringUtils.hasText(cronExpression)) {
            d.add(CompileDiagnostic.flowLevel(CRON_MISSING_EXPRESSION, "Cron trigger must define a cron expression"));
            return;
        }
        try {
            CronUtils.getCron(flowName, cronExpression);
        } catch (IllegalArgumentException e) {
            d.add(CompileDiagnostic.flowLevel(CRON_INVALID_EXPRESSION, e.getMessage()));
        }
    }

    /** The {@code type} discriminator a trigger class serializes with: its name without "Trigger". */
    private static String typeName(Class<? extends TriggerSource> type) {
        return type.getSimpleName().replace("Trigger", "");
    }

    private static String typeNames(List<Class<? extends TriggerSource>> types) {
        return types.stream().map(TriggerConsistencyValidator::typeName).collect(Collectors.joining(", "));
    }
}
