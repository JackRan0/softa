package io.softa.starter.message.shared.maintenance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.mq.outbox.OutboxRecordWriter;
import io.softa.starter.message.mq.outbox.OutboxService;
import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.retry.RetryProperties;
import io.softa.starter.message.shared.retry.SendFailureHandler;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The sweeper revives a stuck SENDING record while it has retries left, and
 * dead-letters it once the budget is spent — an attempt that never finishes
 * never reaches the failure handler that otherwise enforces the budget.
 */
class ZombieRecordSweeperTest {

    private MailSendRecordService mailService;
    private SmsSendRecordService smsService;
    private OutboxRecordWriter outboxRecordWriter;
    private SendFailureHandler sendFailureHandler;
    private ZombieRecordSweeper sweeper;

    @BeforeEach
    void setUp() {
        mailService = mock(MailSendRecordService.class);
        smsService = mock(SmsSendRecordService.class);
        OutboxService outboxService = mock(OutboxService.class);
        outboxRecordWriter = mock(OutboxRecordWriter.class);
        sendFailureHandler = mock(SendFailureHandler.class);

        sweeper = new ZombieRecordSweeper();
        ReflectionTestUtils.setField(sweeper, "mailSendRecordService", mailService);
        ReflectionTestUtils.setField(sweeper, "smsSendRecordService", smsService);
        ReflectionTestUtils.setField(sweeper, "outboxService", outboxService);
        ReflectionTestUtils.setField(sweeper, "outboxRecordWriter", outboxRecordWriter);
        ReflectionTestUtils.setField(sweeper, "properties", new MessageProperties());
        ReflectionTestUtils.setField(sweeper, "retryProperties", new RetryProperties());
        ReflectionTestUtils.setField(sweeper, "sendFailureHandler", sendFailureHandler);

        when(mailService.searchList(any(FlexQuery.class))).thenReturn(List.of());
        when(smsService.searchList(any(FlexQuery.class))).thenReturn(List.of());
        when(outboxService.findStalePublishing(any(), anyInt())).thenReturn(List.of());
    }

    private static MailSendRecord stuckMail(int retryCount) {
        MailSendRecord r = new MailSendRecord();
        r.setId(5L);
        r.setTenantId(42L);
        r.setStatus(MailSendStatus.SENDING);
        r.setVersion(3L);
        r.setRetryCount(retryCount);
        return r;
    }

    @Test
    void aStuckRecordWithRetriesLeftIsRevived() {
        when(mailService.searchList(any(FlexQuery.class))).thenReturn(List.of(stuckMail(4)));
        when(outboxRecordWriter.transitionAndEnqueueAt(any(BooleanSupplier.class), eq(5L),
                eq("MailSendRecord"), eq(TopicRoute.MAIL_SEND), any(LocalDateTime.class))).thenReturn(true);

        sweeper.sweep();

        verify(outboxRecordWriter).transitionAndEnqueueAt(any(BooleanSupplier.class), eq(5L),
                eq("MailSendRecord"), eq(TopicRoute.MAIL_SEND), any(LocalDateTime.class));
        verifyNoInteractions(sendFailureHandler);
    }

    @Test
    void aStuckRecordWithTheBudgetSpentIsDeadLetteredNotRevived() {
        // default-max-attempts = 5: five retries already made, this attempt is stuck again.
        when(mailService.searchList(any(FlexQuery.class))).thenReturn(List.of(stuckMail(5)));

        sweeper.sweep();

        verify(sendFailureHandler).deadLetter(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(3L), eq(5),
                eq("ZOMBIE_EXHAUSTED"), any(), eq(ErrorCategory.UNKNOWN), any());
        verify(outboxRecordWriter, never()).transitionAndEnqueueAt(any(), any(), any(), any(), any());
    }

    @Test
    void smsFollowsTheSameBudget() {
        SmsSendRecord sms = new SmsSendRecord();
        sms.setId(9L);
        sms.setTenantId(42L);
        sms.setStatus(SmsSendStatus.SENDING);
        sms.setVersion(1L);
        sms.setRetryCount(5);
        sms.setProviderType(SmsProvider.TWILIO);
        when(smsService.searchList(any(FlexQuery.class))).thenReturn(List.of(sms));

        sweeper.sweep();

        verify(sendFailureHandler).deadLetter(eq("sms"), eq("twilio"), eq(9L), eq(1L), eq(5),
                eq("ZOMBIE_EXHAUSTED"), any(), eq(ErrorCategory.UNKNOWN), any());
        verify(outboxRecordWriter, never()).transitionAndEnqueueAt(any(), any(), any(), any(), any());
    }
}
