package io.softa.starter.message.mq.pulsar;

import java.util.concurrent.CompletableFuture;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.pulsar.core.ProducerBuilderCustomizer;
import org.springframework.pulsar.core.PulsarOperations;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.mq.MqTopicsProperties;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.mq.outbox.OutboxMessage;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox messages must reach the broker as one-message entries: the Shared
 * delivery subscriptions hand out whole entries, so a batched burst would land
 * on a single consumer.
 */
class PulsarMqProducerTest {

    @Test
    @SuppressWarnings("unchecked")
    void outboxMessagesArePublishedUnbatched() {
        MqTopicsProperties.TopicConfig mailSend = new MqTopicsProperties.TopicConfig();
        mailSend.setTopic("mail-send-topic");
        MqTopicsProperties topics = new MqTopicsProperties();
        topics.getTopics().put("mail-send", mailSend);

        OutboxMessage payload = new OutboxMessage(7L, 1L, "trace-1");
        PulsarTemplate<Object> template = mock(PulsarTemplate.class);
        PulsarOperations.SendMessageBuilder<Object> builder =
                mock(PulsarOperations.SendMessageBuilder.class, RETURNS_SELF);
        when(template.newMessage(payload)).thenReturn(builder);
        when(builder.sendAsync()).thenReturn(CompletableFuture.completedFuture(MessageId.earliest));

        PulsarMqProducer producer = new PulsarMqProducer(topics);
        ReflectionTestUtils.setField(producer, "pulsarTemplate", template);

        assertTrue(producer.sendAsync(TopicRoute.MAIL_SEND, payload).isDone());

        ArgumentCaptor<ProducerBuilderCustomizer<Object>> customizer =
                ArgumentCaptor.forClass(ProducerBuilderCustomizer.class);
        verify(builder).withTopic("mail-send-topic");
        verify(builder).withProducerCustomizer(customizer.capture());
        ProducerBuilder<Object> producerBuilder = mock(ProducerBuilder.class);
        customizer.getValue().customize(producerBuilder);
        verify(producerBuilder).enableBatching(false);
    }
}
