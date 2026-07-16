package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.entity.FlowInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link FlowInstanceServiceImpl#searchSummaries} — the filter and
 * projection builder shared by the runtime "my instances" endpoint and the
 * cross-initiator monitor endpoint.
 */
class FlowInstanceServiceImplSearchTest {

    private final FlowInstanceServiceImpl service = spy(new FlowInstanceServiceImpl());

    private final ArgumentCaptor<FlexQuery> queryCaptor = ArgumentCaptor.forClass(FlexQuery.class);

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<Page<FlowInstance>> pageCaptor =
            ArgumentCaptor.forClass((Class<Page<FlowInstance>>) (Class<?>) Page.class);

    private static FlowInstanceSearchRequest request(String initiatorId,
                                                     LocalDateTime createdFrom, LocalDateTime createdTo,
                                                     Integer pageNumber, Integer pageSize) {
        return new FlowInstanceSearchRequest(null, null, null, initiatorId, null, null,
                createdFrom, createdTo, pageNumber, pageSize);
    }

    private void runSearch(FlowInstanceSearchRequest request, String forcedInitiatorId) {
        doReturn(Page.<FlowInstance>of(1, 50)).when(service).searchPage(any(FlexQuery.class), any());
        service.searchSummaries(request, forcedInitiatorId);
        verify(service).searchPage(queryCaptor.capture(), pageCaptor.capture());
    }

    @Test
    void forcedInitiatorOverridesRequestedInitiator() {
        runSearch(request("someoneElse", null, null, null, null), "me");
        String filters = queryCaptor.getValue().getFilters().toString();
        assertTrue(filters.contains("\"me\"") || filters.contains("me"));
        assertFalse(filters.contains("someoneElse"));
    }

    @Test
    void monitorSearchHonorsRequestedInitiator() {
        runSearch(request("targetUser", null, null, null, null), null);
        assertTrue(queryCaptor.getValue().getFilters().toString().contains("targetUser"));
    }

    @Test
    void monitorSearchWithoutInitiatorAppliesNoInitiatorFilter() {
        runSearch(request(null, null, null, null, null), null);
        assertFalse(queryCaptor.getValue().getFilters().toString().contains("initiatorId"));
    }

    @Test
    void createdRangeBoundsAreApplied() {
        runSearch(request(null,
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 14, 0, 0), null, null), null);
        String filters = queryCaptor.getValue().getFilters().toString();
        assertTrue(filters.contains("createdTime"));
        assertTrue(filters.indexOf("createdTime") != filters.lastIndexOf("createdTime"),
                "both bounds should produce a createdTime filter each");
    }

    @Test
    void summaryProjectionExcludesJsonStateColumns() {
        runSearch(request(null, null, null, null, null), null);
        List<String> fields = queryCaptor.getValue().getFields();
        assertEquals(16, fields.size());
        assertTrue(fields.containsAll(List.of("id", "instanceId", "bundleId", "designId", "flowCode",
                "flowRevision", "title", "modelName", "rowId", "initiatorId", "status", "failedNodeId",
                "nextFireAt", "resubmissionCount", "createdTime", "updatedTime")));
        assertFalse(fields.contains("variables"));
        assertFalse(fields.contains("inputPayload"));
        assertFalse(fields.contains("pendingApprovals"));
        assertFalse(fields.contains("waitTokens"));
    }

    @Test
    void pagingDefaultsToFirstPageOfFifty() {
        runSearch(request(null, null, null, null, null), null);
        assertEquals(1, pageCaptor.getValue().getPageNumber());
        assertEquals(50, pageCaptor.getValue().getPageSize());
    }

    @Test
    void explicitPagingIsHonored() {
        runSearch(request(null, null, null, 3, 20), null);
        assertEquals(3, pageCaptor.getValue().getPageNumber());
        assertEquals(20, pageCaptor.getValue().getPageSize());
    }
}
