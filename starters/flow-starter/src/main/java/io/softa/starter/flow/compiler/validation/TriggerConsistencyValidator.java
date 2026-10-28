package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.compute.CronUtils;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Validates trigger/scenario consistency using {@link TriggerSource} sealed
 * interface sub-types and {@link FlowScenario}.
 */
public class TriggerConsistencyValidator implements FlowValidator {

    private static final String FIELD_CHANGE_WRONG_SCENARIO  = "FIELD_CHANGE_WRONG_SCENARIO";
    private static final String COMPUTE_MISSING_TRIGGER       = "COMPUTE_MISSING_TRIGGER";
    private static final String VALIDATION_MISSING_TRIGGER    = "VALIDATION_MISSING_TRIGGER";
    private static final String PROCESS_FIELD_CHANGE_TRIGGER  = "PROCESS_FIELD_CHANGE_TRIGGER";
    private static final String SUBMIT_WRONG_SCENARIO         = "SUBMIT_WRONG_SCENARIO";
    private static final String SUBMIT_MISSING_MODEL          = "SUBMIT_MISSING_MODEL";
    private static final String CRON_WRONG_SCENARIO           = "CRON_WRONG_SCENARIO";
    private static final String CRON_MISSING_EXPRESSION       = "CRON_MISSING_EXPRESSION";
    private static final String CRON_INVALID_EXPRESSION       = "CRON_INVALID_EXPRESSION";

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        TriggerSource trigger = definition.getTrigger();
        FlowScenario scenario = definition.getScenario();

        if (trigger instanceof FieldChangeTrigger) {
            if (!FlowScenario.COMPUTE.equals(scenario)) {
                d.add(CompileDiagnostic.flowLevel(FIELD_CHANGE_WRONG_SCENARIO,
                        "FieldChange trigger is only allowed for Compute scenario flows, "
                        + "but scenario is '" + (scenario != null ? scenario.getType() : "null") + "'"));
            }
        }

        if (FlowScenario.COMPUTE.equals(scenario) && trigger == null) {
            d.add(CompileDiagnostic.flowLevel(COMPUTE_MISSING_TRIGGER, "Compute scenario flow must define a trigger"));
        }

        if (FlowScenario.VALIDATION.equals(scenario) && trigger == null) {
            d.add(CompileDiagnostic.flowLevel(VALIDATION_MISSING_TRIGGER, "Validation scenario flow must define a trigger"));
        }

        if (FlowScenario.PROCESS.equals(scenario) && trigger instanceof FieldChangeTrigger) {
            d.add(CompileDiagnostic.flowLevel(PROCESS_FIELD_CHANGE_TRIGGER,
                    "Process scenario flow cannot use FieldChange as its primary trigger"));
        }

        if (trigger instanceof SubmitTrigger submit) {
            // Submission starts an approval instance on the record — only a Process flow has one.
            if (scenario != null && !FlowScenario.PROCESS.equals(scenario)) {
                d.add(CompileDiagnostic.flowLevel(SUBMIT_WRONG_SCENARIO,
                        "Submit trigger is only allowed for Process scenario flows, but scenario is '"
                                + scenario.getType() + "'"));
            }
            if (!StringUtils.hasText(submit.modelName())) {
                d.add(CompileDiagnostic.flowLevel(SUBMIT_MISSING_MODEL,
                        "Submit trigger must name the model whose records the flow approves"));
            }
        }

        if (trigger instanceof CronTrigger cron) {
            // A scheduled run has no caller to hand a Validation or Compute result to.
            if (scenario != null && !FlowScenario.PROCESS.equals(scenario)) {
                d.add(CompileDiagnostic.flowLevel(CRON_WRONG_SCENARIO,
                        "Cron trigger is only allowed for Process scenario flows, but scenario is '"
                                + scenario.getType() + "'"));
            }
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
}
