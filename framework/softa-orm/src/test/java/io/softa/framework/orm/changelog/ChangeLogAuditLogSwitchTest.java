package io.softa.framework.orm.changelog;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code @Model(enableAuditLog = false)} is for derived / report models rewritten in bulk: their
 * writes build no change log at all — nothing in the transaction buffer, no event — rather than a
 * log that is dropped later. A model with the switch on logs exactly as before.
 */
class ChangeLogAuditLogSwitchTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    @AfterEach
    void tearDown() {
        ChangeLogHolder.clear();
    }

    @Test
    void aModelWithTheAuditLogOffBuildsNoLogForAnyWrite() {
        try (MockedStatic<ModelManager> models = stubReport(false)) {
            ChangeLogPublisherImpl publisher = publisher();
            publisher.publishCreationLog("Report", List.of(row(1L, "headcount", 10)), NOW);
            publisher.publishUpdateLog("Report", List.of(row(1L, "headcount", 11)),
                    new HashMap<>(Map.of(1L, Map.of("headcount", 10))), NOW);
            publisher.publishDeletionLog("Report", List.of(row(1L, "headcount", 11)), NOW);

            assertThat(ChangeLogHolder.get()).isNull();
            verify(events, never()).publishEvent(any(Object.class));
        }
    }

    @Test
    void aModelWithTheAuditLogOnStillLogsItsWrites() {
        try (MockedStatic<ModelManager> models = stubReport(true)) {
            ChangeLogPublisherImpl publisher = publisher();
            publisher.publishCreationLog("Report", List.of(row(1L, "headcount", 10)), NOW);
            publisher.publishUpdateLog("Report", List.of(row(1L, "headcount", 11)),
                    new HashMap<>(Map.of(1L, Map.of("headcount", 10))), NOW);

            assertThat(ChangeLogHolder.get()).hasSize(2);
        }
    }

    private ChangeLogPublisherImpl publisher() {
        ChangeLogPublisherImpl publisher = new ChangeLogPublisherImpl();
        ReflectionTestUtils.setField(publisher, "applicationEventPublisher", events);
        return publisher;
    }

    private static Map<String, Object> row(Long id, String field, Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put(ModelConstant.ID, id);
        row.put(field, value);
        return row;
    }

    private static MockedStatic<ModelManager> stubReport(boolean auditLog) {
        MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
        MetaModel metaModel = new MetaModel();
        ReflectionTestUtils.setField(metaModel, "auditUpdateFields", Set.of(ModelConstant.UPDATED_TIME));
        models.when(() -> ModelManager.getModel("Report")).thenReturn(metaModel);
        models.when(() -> ModelManager.getModelPrimaryKey("Report")).thenReturn(ModelConstant.ID);
        models.when(() -> ModelManager.getModelFields("Report")).thenReturn(List.of());
        models.when(() -> ModelManager.isAuditLogEnabled("Report")).thenReturn(auditLog);
        return models;
    }
}
