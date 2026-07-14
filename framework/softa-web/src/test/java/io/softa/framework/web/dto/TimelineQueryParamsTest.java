package io.softa.framework.web.dto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FlexQuery;

/**
 * The {@code acrossTimeline} API flag opts a timeline read into the full version list by
 * setting {@link FlexQuery#acrossTimelineData()} (the explicit half of the across-timeline
 * dual trigger). Verifies the flag reaches the FlexQuery for the row-list read params
 * ({@link SearchListParams} → /searchList, {@link QueryParams} → /searchPage,/searchPivot).
 */
class TimelineQueryParamsTest {

    @Test
    void searchListParamsAcrossTimelineFlagReachesFlexQuery() {
        SearchListParams params = new SearchListParams();
        params.setAcrossTimeline(true);
        Assertions.assertTrue(SearchListParams.convertParamsToFlexQuery(params).isAcrossTimeline());
    }

    @Test
    void searchListParamsDefaultsToClamped() {
        Assertions.assertFalse(SearchListParams.convertParamsToFlexQuery(new SearchListParams()).isAcrossTimeline());
        SearchListParams explicitFalse = new SearchListParams();
        explicitFalse.setAcrossTimeline(false);
        Assertions.assertFalse(SearchListParams.convertParamsToFlexQuery(explicitFalse).isAcrossTimeline());
    }

    @Test
    void queryParamsAcrossTimelineFlagReachesFlexQuery() {
        QueryParams params = new QueryParams();
        params.setAcrossTimeline(true);
        Assertions.assertTrue(QueryParams.convertParamsToFlexQuery(params).isAcrossTimeline());
    }

    @Test
    void queryParamsDefaultsToClamped() {
        Assertions.assertFalse(QueryParams.convertParamsToFlexQuery(new QueryParams()).isAcrossTimeline());
    }
}
