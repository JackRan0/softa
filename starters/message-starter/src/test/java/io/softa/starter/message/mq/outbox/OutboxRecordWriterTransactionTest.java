package io.softa.starter.message.mq.outbox;

import java.sql.Connection;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import io.softa.framework.base.exception.VersionException;
import io.softa.starter.message.mq.TopicRoute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Runs {@link OutboxRecordWriter#transitionAndEnqueueAt} through real Spring
 * transaction proxies. A lost CAS is a {@code VersionException} thrown inside
 * the joined ModelService call: its proxy marks the shared transaction
 * rollback-only even though the transition catches the exception, so a plain
 * {@code return false} would end in {@code UnexpectedRollbackException} at
 * commit. The writer must report the lost race as {@code false} instead.
 */
class OutboxRecordWriterTransactionTest {

    /** Stands in for {@code ModelService.updateOne}: a joined transactional call that loses the CAS. */
    static class VersionedUpdate {
        @Transactional(rollbackFor = Exception.class)
        public boolean apply() {
            throw new VersionException("Data version does not match");
        }
    }

    @Test
    void aLostCasRollsBackQuietlyInsteadOfFailingTheCommit() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(invocation -> mock(Connection.class));
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);

        VersionedUpdate update = transactional(new VersionedUpdate(), transactionManager);
        OutboxService outboxService = mock(OutboxService.class);
        OutboxRecordWriter writer = transactional(new OutboxRecordWriter(outboxService), transactionManager);

        boolean transitioned = writer.transitionAndEnqueueAt(() -> {
            try {
                return update.apply();
            } catch (VersionException lostRace) {
                return false;
            }
        }, 7L, "MailSendRecord", TopicRoute.MAIL_SEND, LocalDateTime.now());

        assertFalse(transitioned);
        verifyNoInteractions(outboxService);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, PlatformTransactionManager transactionManager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
