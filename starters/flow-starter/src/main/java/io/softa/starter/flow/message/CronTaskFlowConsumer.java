package io.softa.starter.flow.message;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;

/**
 * Starts the Cron-triggered flows on their schedule: consumes the messages of the jobs
 * {@link CronFlowJobProvider} puts on the cron schedule and starts the revision each one names, in the
 * tenant the message carries. Every other cron message — maintenance jobs, other modules' jobs — is
 * ignored.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.cron-task.topic")
public class CronTaskFlowConsumer {

    /** Flow variable holding the time the schedule fired. */
    static final String PARAM_TRIGGER_TIME = "triggerTime";

    @Autowired
    private FlowAutomationService automationService;

    @PulsarListener(
            topics = "${mq.topics.cron-task.topic}",
            subscriptionName = "${mq.topics.cron-task.flow-sub:cron-task-flow-sub}",
            subscriptionType = SubscriptionType.Shared,
            deadLetterPolicy = "flowDeadLetterPolicy"
    )
    public void onMessage(CronTaskMessage cronTaskMessage) {
        Long bundleId = CronFlowJobProvider.bundleIdOf(cronTaskMessage);
        if (bundleId == null) {
            return;
        }
        Runnable task = () -> {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put(PARAM_TRIGGER_TIME, cronTaskMessage.getTriggerTime());
            automationService.fireCron(bundleId, parameters);
            log.debug("Processed cron trigger of flow bundle {}", bundleId);
        };

        Context ctx = cronTaskMessage.getContext();
        if (ctx != null) {
            ContextHolder.runWith(ctx, task);
        } else {
            task.run();
        }
    }
}
