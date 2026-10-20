package io.softa.starter.message;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.MessageScope;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Returning from the listener acknowledges the message; throwing nacks it into a
 * redelivery. A rejected request must be acknowledged — this listener has no
 * dead-letter policy, so a nack would redeliver it forever — while an
 * infrastructure failure must still be redelivered.
 */
class MailRequestConsumerRejectionTest {

    private MessageService messageService;
    private MailRequestConsumer consumer;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        consumer = new MailRequestConsumer(messageService);
    }

    private static MailRequestMessage request() {
        return new MailRequestMessage(List.of("someone@example.test"), "user.verification-code",
                Map.of("code", "123456"), 42L, MessageScope.TENANT);
    }

    @Test
    void aRejectedRequestIsAcknowledgedNotRedelivered() {
        when(messageService.sendMail(any(SendMailDTO.class)))
                .thenThrow(new BusinessException("No mail template found for code ''{0}''.", "user.verification-code"));

        assertDoesNotThrow(() -> consumer.onMessage(request()));
    }

    @Test
    void anInfrastructureFailureStillPropagatesForRedelivery() {
        when(messageService.sendMail(any(SendMailDTO.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThrows(DataAccessResourceFailureException.class, () -> consumer.onMessage(request()));
    }
}
