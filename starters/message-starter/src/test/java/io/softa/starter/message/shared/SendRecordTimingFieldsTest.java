package io.softa.starter.message.shared;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.impl.MailSendRecordServiceImpl;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.impl.SmsSendRecordServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two timing columns ride on writes the send path already makes: the claim
 * stamps {@code attemptStartedAt}, and the SENT write carries
 * {@code sendDurationMs}. No extra statement, no extra round trip.
 */
class SendRecordTimingFieldsTest {

    @Test
    void mailClaimStampsTheAttemptStart() {
        ModelService<Long> modelService = modelService();
        MailSendRecordServiceImpl service = new MailSendRecordServiceImpl();
        ReflectionTestUtils.setField(service, "modelService", modelService);

        assertTrue(service.markSending(7L, 3L));

        Map<String, Object> patch = capturePatch(modelService, "MailSendRecord");
        assertEquals(MailSendStatus.SENDING, patch.get("status"));
        assertEquals(3L, patch.get("version"));
        assertInstanceOf(LocalDateTime.class, patch.get("attemptStartedAt"));
    }

    @Test
    void mailSentWriteCarriesTheSendDuration() {
        ModelService<Long> modelService = modelService();
        MailSendRecordServiceImpl service = new MailSendRecordServiceImpl();
        ReflectionTestUtils.setField(service, "modelService", modelService);

        service.markSent(7L, 4L, "MSG-1", "smtp", 842);

        Map<String, Object> patch = capturePatch(modelService, "MailSendRecord");
        assertEquals(MailSendStatus.SENT, patch.get("status"));
        assertEquals(842, patch.get("sendDurationMs"));
    }

    @Test
    void smsClaimStampsTheAttemptStart() {
        ModelService<Long> modelService = modelService();
        SmsSendRecordServiceImpl service = new SmsSendRecordServiceImpl();
        ReflectionTestUtils.setField(service, "modelService", modelService);

        assertTrue(service.markSending(9L, 0L));

        Map<String, Object> patch = capturePatch(modelService, "SmsSendRecord");
        assertEquals(SmsSendStatus.SENDING, patch.get("status"));
        assertInstanceOf(LocalDateTime.class, patch.get("attemptStartedAt"));
    }

    @Test
    void smsSentWriteCarriesTheSendDuration() {
        ModelService<Long> modelService = modelService();
        SmsSendRecordServiceImpl service = new SmsSendRecordServiceImpl();
        ReflectionTestUtils.setField(service, "modelService", modelService);

        service.markSent(9L, 1L, "SID-1", 20L, SmsProvider.TWILIO, 311);

        Map<String, Object> patch = capturePatch(modelService, "SmsSendRecord");
        assertEquals(SmsSendStatus.SENT, patch.get("status"));
        assertEquals(311, patch.get("sendDurationMs"));
    }

    @SuppressWarnings("unchecked")
    private static ModelService<Long> modelService() {
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.updateOne(anyString(), anyMap())).thenReturn(true);
        return modelService;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> capturePatch(ModelService<Long> modelService, String modelName) {
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateOne(eq(modelName), patch.capture());
        return patch.getValue();
    }
}
