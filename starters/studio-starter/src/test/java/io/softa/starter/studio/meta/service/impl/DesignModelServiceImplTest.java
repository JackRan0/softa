package io.softa.starter.studio.meta.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.studio.meta.service.DesignFieldService;
import io.softa.starter.studio.meta.service.DesignModelIndexService;

/**
 * Root-cause cascade for the no-code lane: deleting a {@code DesignModel} must also remove its child
 * {@code DesignField} / {@code DesignModelIndex} rows, so no orphan children are left behind in the
 * env's {@code design_*} workspace. Children are matched by the rename-stable surrogate FK
 * {@code modelId} (globally unique → inherently env-scoped) and dropped before the parent.
 */
@ExtendWith(MockitoExtension.class)
class DesignModelServiceImplTest {

    @Mock
    private ModelService<Long> modelService;   // inherited EntityServiceImpl field

    @Mock
    private DesignFieldService fieldService;

    @Mock
    private DesignModelIndexService indexService;

    @InjectMocks
    private DesignModelServiceImpl service;

    @BeforeEach
    void wireInheritedModelService() {
        // @InjectMocks wires the subclass fields (fieldService/indexService) but not the protected
        // modelService declared on EntityServiceImpl — set that inherited field explicitly.
        ReflectionTestUtils.setField(service, "modelService", modelService);
    }

    @Test
    @DisplayName("deleteByIds cascade-deletes child fields + indexes (scoped by modelId) before the parent model")
    void deleteByIdsCascadesChildrenFirst() {
        List<Long> ids = List.of(100L, 200L);
        when(modelService.deleteByIds(eq("DesignModel"), eq(ids))).thenReturn(true);

        assertTrue(service.deleteByIds(ids));

        ArgumentCaptor<Filters> fieldFilter = ArgumentCaptor.forClass(Filters.class);
        ArgumentCaptor<Filters> indexFilter = ArgumentCaptor.forClass(Filters.class);
        // Children must drop before the parent.
        InOrder inOrder = Mockito.inOrder(fieldService, indexService, modelService);
        inOrder.verify(fieldService).deleteByFilters(fieldFilter.capture());
        inOrder.verify(indexService).deleteByFilters(indexFilter.capture());
        inOrder.verify(modelService).deleteByIds("DesignModel", ids);

        // Each child delete is scoped to the deleted parents by the surrogate FK modelId.
        for (Filters filters : List.of(fieldFilter.getValue(), indexFilter.getValue())) {
            String rendered = filters.toString();
            assertTrue(rendered.contains("modelId"), rendered);
            assertTrue(rendered.contains("100") && rendered.contains("200"), rendered);
        }
    }

    @Test
    @DisplayName("deleteById funnels through deleteByIds so a single-id delete also cascades")
    void deleteByIdCascades() {
        when(modelService.deleteByIds(eq("DesignModel"), eq(List.of(100L)))).thenReturn(true);

        assertTrue(service.deleteById(100L));

        verify(fieldService).deleteByFilters(any(Filters.class));
        verify(indexService).deleteByFilters(any(Filters.class));
        verify(modelService).deleteByIds("DesignModel", List.of(100L));
    }

    @Test
    @DisplayName("deleteByIds with no ids skips the child cascade")
    void deleteByIdsEmptySkipsCascade() {
        assertFalse(service.deleteByIds(List.of()));

        verify(fieldService, never()).deleteByFilters(any());
        verify(indexService, never()).deleteByFilters(any());
    }
}
