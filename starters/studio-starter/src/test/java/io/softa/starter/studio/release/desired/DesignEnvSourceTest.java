package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;

/**
 * {@link DesignEnvSource} bundles one env's five {@code design_*} meta-tables into the
 * {@link DesignRows} the comparator consumes, each scoped to {@code (appId, envId)}
 * (per-env design).
 */
class DesignEnvSourceTest {

    @Test
    @DisplayName("loads all five env-scoped design meta-tables into DesignRows")
    @SuppressWarnings("unchecked")
    void loadsFiveEnvScopedMetaTables() {
        ModelService<Long> modelService = mock(ModelService.class);
        List<Map<String, Object>> models = List.of(
                Map.of("id", 1L, "modelName", "Customer", "appId", 7L, "envId", 200L));
        List<Map<String, Object>> fields = List.of(
                Map.of("id", 10L, "modelId", 1L, "fieldName", "code", "appId", 7L, "envId", 200L));
        when(modelService.searchList(eq("DesignModel"), any(FlexQuery.class))).thenReturn(models);
        when(modelService.searchList(eq("DesignField"), any(FlexQuery.class))).thenReturn(fields);
        when(modelService.searchList(eq("DesignModelIndex"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("DesignOptionSet"), any(FlexQuery.class))).thenReturn(List.of());
        when(modelService.searchList(eq("DesignOptionItem"), any(FlexQuery.class))).thenReturn(List.of());

        DesignRows rows = new DesignEnvSource(modelService).load(7L, 200L);

        assertEquals(models, rows.models());
        assertEquals(fields, rows.fields());
        assertEquals(List.of(), rows.indexes());
        assertEquals(List.of(), rows.optionSets());
        assertEquals(List.of(), rows.items());
        // All five meta-tables queried, each env-scoped (appId + envId filter).
        verify(modelService, times(5)).searchList(anyString(), any(FlexQuery.class));
    }
}
