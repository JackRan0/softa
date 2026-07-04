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
import io.softa.starter.studio.meta.service.DesignOptionItemService;

/**
 * Root-cause cascade for the no-code lane: deleting a {@code DesignOptionSet} must also remove its
 * child {@code DesignOptionItem} rows, so no orphan items are left behind in the env's {@code design_*}
 * workspace. Items are matched by the rename-stable surrogate FK {@code optionSetId} (globally unique
 * → inherently env-scoped) and dropped before the parent.
 */
@ExtendWith(MockitoExtension.class)
class DesignOptionSetServiceImplTest {

    @Mock
    private ModelService<Long> modelService;   // inherited EntityServiceImpl field

    @Mock
    private DesignOptionItemService optionItemService;

    @InjectMocks
    private DesignOptionSetServiceImpl service;

    @BeforeEach
    void wireInheritedModelService() {
        // @InjectMocks wires the subclass field (optionItemService) but not the protected modelService
        // declared on EntityServiceImpl — set that inherited field explicitly.
        ReflectionTestUtils.setField(service, "modelService", modelService);
    }

    @Test
    @DisplayName("deleteByIds cascade-deletes child items (scoped by optionSetId) before the parent option-set")
    void deleteByIdsCascadesItemsFirst() {
        List<Long> ids = List.of(11L, 22L);
        when(modelService.deleteByIds(eq("DesignOptionSet"), eq(ids))).thenReturn(true);

        assertTrue(service.deleteByIds(ids));

        ArgumentCaptor<Filters> itemFilter = ArgumentCaptor.forClass(Filters.class);
        InOrder inOrder = Mockito.inOrder(optionItemService, modelService);
        inOrder.verify(optionItemService).deleteByFilters(itemFilter.capture());
        inOrder.verify(modelService).deleteByIds("DesignOptionSet", ids);

        String rendered = itemFilter.getValue().toString();
        assertTrue(rendered.contains("optionSetId"), rendered);
        assertTrue(rendered.contains("11") && rendered.contains("22"), rendered);
    }

    @Test
    @DisplayName("deleteById funnels through deleteByIds so a single-id delete also cascades")
    void deleteByIdCascades() {
        when(modelService.deleteByIds(eq("DesignOptionSet"), eq(List.of(11L)))).thenReturn(true);

        assertTrue(service.deleteById(11L));

        verify(optionItemService).deleteByFilters(any(Filters.class));
        verify(modelService).deleteByIds("DesignOptionSet", List.of(11L));
    }

    @Test
    @DisplayName("deleteByIds with no ids skips the child cascade")
    void deleteByIdsEmptySkipsCascade() {
        assertFalse(service.deleteByIds(List.of()));

        verify(optionItemService, never()).deleteByFilters(any());
    }
}
