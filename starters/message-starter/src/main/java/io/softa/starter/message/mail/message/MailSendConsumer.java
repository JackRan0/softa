package io.softa.starter.message.mail.message;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.service.impl.MailDeliveryProcessor;
import io.softa.starter.message.mq.outbox.OutboxContextSupport;
import io.softa.starter.message.mq.outbox.OutboxMessage;

/**
 * Consumes mail delivery messages from Pulsar and drives the delivery processor.
 * Initial attempts and delayed retries share this topic; retry timing is owned
 * by the transactional outbox.
 * <p>
 * Messages carry only {@code recordId} (plus tenant/trace for context).
 * Duplicate broker deliveries are safely rejected by the CAS claim in
 * {@link MailDeliveryProcessor#process(Long)}.
 * <p>
 * The subscription is Shared: every instance takes messages off the topic, each
 * with {@code softa.message.mail.send-concurrency} consumers, so one slow SMTP
 * call no longer holds up every mail behind it. Parallel consumers cannot send
 * a record twice — two deliveries of one record race for the same CAS claim and
 * only one wins. Switching an existing deployment from the former Exclusive
 * subscription needs one release where the old consumers disconnect before the
 * new ones subscribe (stop-then-start): Pulsar refuses a Shared consumer while
 * an Exclusive one is attached.
 * <p>
 * Only activated when {@code mq.topics.mail-send.topic} is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.mail-send.topic")
public class MailSendConsumer {

    @Autowired
    private MailDeliveryProcessor deliveryProcessor;

    @PulsarListener(topics = "${mq.topics.mail-send.topic}",
                    subscriptionName = "${mq.topics.mail-send.sub:mail-send-sub}",
                    subscriptionType = SubscriptionType.Shared,
                    concurrency = "${softa.message.mail.send-concurrency:1}")
    public void onSend(OutboxMessage message) {
        OutboxContextSupport.runWithContext(message, () -> {
            log.debug("Processing mail delivery: recordId={} traceId={}",
                    message.getRecordId(), message.getTraceId());
            deliveryProcessor.process(message.getRecordId());
        });
    }
}
