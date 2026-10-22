package io.softa.framework.orm.aspect;

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.jdbc.database.SqlParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In debug mode a batch update logs the count of each row the way it logs the rows' params: a
 * version-locked batch is checked row by row, so which row matched nothing is what there is to see.
 */
class ExecuteSqlAspectTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ExecuteSqlAspect.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void aBatchLogsTheCountOfEachRow() throws Throwable {
        assertThat(logOfBatch(new int[]{1, 0})).contains("Results: [1, 0]");
    }

    @Test
    void aLongBatchLogsItsFirstCountsAndHowManyRowsItHad() throws Throwable {
        assertThat(logOfBatch(new int[]{1, 1, 0, 1, 1})).contains("Results: [1, 1, 0] ... Total rows: 5");
    }

    /** Runs a batch update through the aspect in debug mode and returns what it logged. */
    private String logOfBatch(int[] counts) throws Throwable {
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            batchArgs.add(new Object[]{i * 10, (long) i});
        }
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(
                new Object[]{"PayRunResult", new SqlParams("UPDATE pay_run_result SET amount=? WHERE id = ?"), batchArgs});
        when(joinPoint.proceed()).thenReturn(counts);
        Context context = new Context();
        context.setDebug(true);

        Object result = ContextHolder.callWith(context, () -> new ExecuteSqlAspect().around(joinPoint));

        assertThat(result).isSameAs(counts);
        return logAppender.list.getLast().getFormattedMessage();
    }
}
