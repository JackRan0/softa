package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.flow.entity.FlowEvent;
import io.softa.starter.flow.entity.FlowInstance;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The automation purge works in batches: it takes finished Automation runs past the retention period
 * until a batch comes back short or the batch limit is reached, then the trigger event log of the same age.
 */
class FlowAutomationPurgeServiceTest {

    private final FlowInstanceServiceImpl instanceService = mock(FlowInstanceServiceImpl.class);
    private final FlowEventServiceImpl eventService = mock(FlowEventServiceImpl.class);
    private final FlowInstancePurger purger = mock(FlowInstancePurger.class);
    private final FlowAutomationPurgeService service =
            new FlowAutomationPurgeService(instanceService, eventService, purger);

    @BeforeEach
    void retention() {
        ReflectionTestUtils.setField(service, "retentionDays", 30);
        when(eventService.searchList(any(FlexQuery.class))).thenReturn(List.of());
    }

    private static List<FlowInstance> runs(int count) {
        List<FlowInstance> runs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FlowInstance run = new FlowInstance();
            run.setId((long) i);
            run.setInstanceId("run-" + i);
            runs.add(run);
        }
        return runs;
    }

    @Test
    void takesFinishedAutomationRunsUntilABatchComesBackShort() {
        when(instanceService.searchList(any(FlexQuery.class))).thenReturn(runs(2), runs(1));

        service.purgeExpiredRuns(2, 10);

        verify(purger, times(2)).delete(anyList());
        ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
        verify(instanceService, times(2)).searchList(query.capture());
        String filters = query.getValue().getFilters().toString();
        assertTrue(filters.contains("scenario") && filters.contains("status") && filters.contains("updatedTime"), filters);
    }

    @Test
    void leavesABacklogBeyondTheBatchLimitForTheNextRun() {
        when(instanceService.searchList(any(FlexQuery.class))).thenReturn(runs(2));

        service.purgeExpiredRuns(2, 3);

        verify(purger, times(3)).delete(anyList());
    }

    @Test
    void purgesTheTriggerEventLogOfTheSameAge() {
        when(instanceService.searchList(any(FlexQuery.class))).thenReturn(List.of());
        FlowEvent event = new FlowEvent();
        event.setId(9L);
        when(eventService.searchList(any(FlexQuery.class))).thenReturn(List.of(event));

        service.purgeExpiredRuns(2, 10);

        verify(eventService).deleteByIds(List.of(9L));
    }

    @Test
    void nothingExpiredDeletesNothing() {
        when(instanceService.searchList(any(FlexQuery.class))).thenReturn(List.of());

        service.purgeExpiredRuns(2, 10);

        verify(eventService, never()).deleteByIds(anyList());
    }
}
