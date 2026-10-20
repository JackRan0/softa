package io.softa.starter.message.mail.smtp;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import io.softa.starter.message.mail.enums.SendProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Connection reuse in {@link PooledSmtpSender}: against an embedded GreenMail
 * server for the reuse and retirement rules, and against mocked connections for
 * the failure paths a real server will not produce on demand.
 */
class PooledSmtpSenderTest {

    private static final String USER = "noreply@example.com";
    private static final String PASS = "secret";
    private static final PooledSmtpSender.Key KEY =
            new PooledSmtpSender.Key(SendProtocol.SMTP, "127.0.0.1", 25, USER, PASS, false, false);

    private final List<PooledSmtpSender> senders = new ArrayList<>();
    private GreenMail greenMail;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();
        greenMail.setUser(USER, USER, PASS);
    }

    @AfterEach
    void tearDown() {
        senders.forEach(PooledSmtpSender::shutdown);
        greenMail.stop();
    }

    @Test
    void consecutiveSendsShareOneConnection() throws Exception {
        CountingSender sender = greenMailSender(Duration.ofSeconds(30), 100);

        send(sender, "one");
        send(sender, "two");
        send(sender, "three");

        assertEquals(1, sender.opened.get());
        assertTrue(greenMail.waitForIncomingEmail(5000, 3));
    }

    @Test
    void connectionIsRetiredAfterMaxMessages() throws Exception {
        CountingSender sender = greenMailSender(Duration.ofSeconds(30), 2);

        send(sender, "one");
        send(sender, "two");
        send(sender, "three");

        assertEquals(2, sender.opened.get());
        assertTrue(greenMail.waitForIncomingEmail(5000, 3));
    }

    @Test
    void connectionIdlePastTimeoutIsNotReused() throws Exception {
        CountingSender sender = greenMailSender(Duration.ZERO, 100);

        send(sender, "one");
        send(sender, "two");

        assertEquals(2, sender.opened.get());
    }

    @Test
    void failedSendClosesItsConnectionInsteadOfPoolingIt() throws Exception {
        Transport failing = mock(Transport.class);
        doThrow(new MessagingException("451 temporary failure")).when(failing).sendMessage(any(), any());
        Transport next = mock(Transport.class);
        PooledSmtpSender sender = mockedSender(Duration.ofSeconds(30), failing, next);

        assertThrows(MailSendException.class, () -> send(sender, "one"));
        verify(failing).close();

        send(sender, "two");
        verify(next).sendMessage(any(), any());
    }

    @Test
    void connectionTheServerClosedIsReplacedBeforeTheSend() throws Exception {
        // A mock answers isConnected() with false — exactly what a failed NOOP probe returns.
        Transport dropped = mock(Transport.class);
        Transport fresh = mock(Transport.class);
        PooledSmtpSender sender = mockedSender(Duration.ofSeconds(30), dropped, fresh);

        send(sender, "one");
        send(sender, "two");

        verify(dropped).sendMessage(any(), any());
        verify(dropped).close();
        verify(fresh).sendMessage(any(), any());
    }

    @Test
    void evictIdleClosesExpiredConnections() throws Exception {
        Transport connection = mock(Transport.class);
        PooledSmtpSender sender = mockedSender(Duration.ZERO, connection);

        send(sender, "one");
        verify(connection, never()).close();

        sender.evictIdle();
        verify(connection).close();
    }

    @Test
    void evictIdleKeepsConnectionsWithinTheTimeout() throws Exception {
        Transport connection = mock(Transport.class);
        PooledSmtpSender sender = mockedSender(Duration.ofSeconds(30), connection);

        send(sender, "one");
        sender.evictIdle();

        verify(connection, never()).close();
    }

    @Test
    void shutdownClosesIdleConnectionsAndStopsPooling() throws Exception {
        Transport pooled = mock(Transport.class);
        Transport afterShutdown = mock(Transport.class);
        PooledSmtpSender sender = mockedSender(Duration.ofSeconds(30), pooled, afterShutdown);

        send(sender, "one");
        sender.shutdown();
        verify(pooled).close();

        // A sender still in use after shutdown (a send that raced the swap) hangs up afterwards.
        send(sender, "two");
        verify(afterShutdown).close();
    }

    @Test
    void keyNeverRendersThePassword() {
        assertFalse(KEY.toString().contains(PASS));
    }

    private CountingSender greenMailSender(Duration idleTimeout, int maxMessages) {
        CountingSender sender = new CountingSender(idleTimeout, maxMessages);
        sender.setHost("127.0.0.1");
        sender.setPort(greenMail.getSmtp().getPort());
        sender.setUsername(USER);
        sender.setPassword(PASS);
        sender.getJavaMailProperties().put("mail.smtp.auth", true);
        senders.add(sender);
        return sender;
    }

    /** A sender whose connections are the given mocks, opened in order. */
    private PooledSmtpSender mockedSender(Duration idleTimeout, Transport... connections) {
        Deque<Transport> unopened = new ArrayDeque<>(List.of(connections));
        PooledSmtpSender sender = new PooledSmtpSender(KEY, idleTimeout, 100) {
            @Override
            Transport openConnection() {
                return unopened.pop();
            }
        };
        senders.add(sender);
        return sender;
    }

    private static void send(JavaMailSenderImpl sender, String subject) throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(USER);
        helper.setTo("alice@example.com");
        helper.setSubject(subject);
        helper.setText("hello");
        sender.send(message);
    }

    /** Counts the real connections the pool opens against GreenMail. */
    private static final class CountingSender extends PooledSmtpSender {

        private final AtomicInteger opened = new AtomicInteger();

        CountingSender(Duration idleTimeout, int maxMessages) {
            super(KEY, idleTimeout, maxMessages);
        }

        @Override
        Transport openConnection() throws MessagingException {
            opened.incrementAndGet();
            return super.openConnection();
        }
    }
}
