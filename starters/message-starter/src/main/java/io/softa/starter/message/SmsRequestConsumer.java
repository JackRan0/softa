package io.softa.starter.message;

import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.message.SmsRequestMessage;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.sms.dto.SendSmsDTO;

/**
 * Consumes {@link SmsRequestMessage} off the sms-request MQ topic and delivers each recipient through
 * the SMS pipeline. The counterpart of {@link MailRequestConsumer}: any starter can request a
 * <b>templated</b> text without depending on message-starter (⊥).
 *
 * <p>Fans one message out into one {@link SendSmsDTO} per number — {@code SendSmsDTO} addresses a
 * single {@code phoneNumber} (unlike mail's recipient list), because provider routing is per-number
 * (by dial code) and each send gets its own record.
 *
 * <p>Registered only when {@code mq.topics.sms-request.sub} is configured: same publish-vs-deliver
 * role split as mail — every service that can request SMS configures the topic, only the deployment
 * owning delivery (providers + templates) declares a subscription.
 *
 * <p>The subscription is Shared, like the mail-request one: every replica takes requests off the
 * topic, and each message still reaches one consumer. The one-time switch needs a stop-then-start
 * release; see {@code MailSendConsumer}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.sms-request.sub")
public class SmsRequestConsumer {

    private final MessageService messageService;

    public SmsRequestConsumer(MessageService messageService) {
        this.messageService = messageService;
    }

    @PulsarListener(topics = "${mq.topics.sms-request.topic}",
            subscriptionName = "${mq.topics.sms-request.sub}",
            subscriptionType = SubscriptionType.Shared)
    public void onMessage(SmsRequestMessage message) {
        if (message == null || message.to() == null || message.to().isEmpty()
                || message.templateCode() == null || message.templateCode().isBlank()) {
            return;
        }
        for (String number : message.to()) {
            if (number == null || number.isBlank()) {
                continue;
            }
            SendSmsDTO sms = new SendSmsDTO();
            sms.setPhoneNumber(number);
            sms.setTemplateCode(message.templateCode());
            sms.setTemplateVariables(message.variables());
            messageService.sendSms(sms);
        }
        log.debug("Delivered sms-request → template '{}' to {} recipient(s)",
                message.templateCode(), message.to().size());
    }
}
