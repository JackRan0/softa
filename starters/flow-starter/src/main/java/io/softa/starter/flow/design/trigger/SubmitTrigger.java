package io.softa.starter.flow.design.trigger;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger fired when a business record is submitted for approval.
 * <p>
 * Declares which model a Process flow approves and, optionally, which of its records: when a
 * record is submitted, the flow is chosen among the tenant's active flows whose submit trigger
 * names that model and whose condition holds for the record — exactly one must match. Only the
 * record-submission path ({@code FlowLaunchService.submitRecord}) fires it: change logs never
 * produce it, and the generic trigger endpoint refuses it.
 */
@Schema(name = "SubmitTrigger")
public record SubmitTrigger(

        @Schema(description = "Model name of the records this flow approves")
        String modelName,

        @Schema(description = "Optional AviatorScript expression over the submitted record's fields; "
                + "the flow applies only when true. Blank = every record of the model")
        String conditionExpression

) implements TriggerSource {}
