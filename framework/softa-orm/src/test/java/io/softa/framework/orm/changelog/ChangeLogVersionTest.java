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

import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * An update log records what the write changed, and the optimistic-lock version is not that: it is
 * the framework's, like the audit fields. A written versionLock row also carries the version it was
 * read at, so logging it would record the old version as the new one.
 */
class ChangeLogVersionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);

    @AfterEach
    void tearDown() {
        ChangeLogHolder.clear();
    }

    @Test
    void anUpdateLogLeavesTheVersionOfAVersionLockedModelOut() {
        try (MockedStatic<ModelManager> models = stubPayment(true)) {
            publish(Map.of(ModelConstant.ID, 1L, "status", "PAID", ModelConstant.VERSION, 3,
                    ModelConstant.UPDATED_TIME, NOW));

            ChangeLog log = ChangeLogHolder.get().getFirst();
            assertThat(log.getChangedFields()).containsExactly("status");
            assertThat(log.getDataAfterChange()).containsOnlyKeys("status");
        }
    }

    @Test
    void aVersionFieldOfAModelWithoutTheLockIsLoggedLikeAnyOther() {
        try (MockedStatic<ModelManager> models = stubPayment(false)) {
            publish(Map.of(ModelConstant.ID, 1L, "status", "PAID", ModelConstant.VERSION, 3));

            assertThat(ChangeLogHolder.get().getFirst().getChangedFields())
                    .containsExactlyInAnyOrder("status", ModelConstant.VERSION);
        }
    }

    private static void publish(Map<String, Object> writtenRow) {
        ChangeLogPublisherImpl publisher = new ChangeLogPublisherImpl();
        ReflectionTestUtils.setField(publisher, "applicationEventPublisher", mock(ApplicationEventPublisher.class));
        publisher.publishUpdateLog("Payment", List.of(new HashMap<>(writtenRow)),
                new HashMap<>(Map.of(1L, Map.of("status", "NEW"))), NOW);
    }

    private static MockedStatic<ModelManager> stubPayment(boolean versionLock) {
        MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
        MetaModel metaModel = new MetaModel();
        ReflectionTestUtils.setField(metaModel, "auditUpdateFields", Set.of(ModelConstant.UPDATED_TIME));
        models.when(() -> ModelManager.getModel("Payment")).thenReturn(metaModel);
        models.when(() -> ModelManager.getModelPrimaryKey("Payment")).thenReturn(ModelConstant.ID);
        models.when(() -> ModelManager.isVersionControl("Payment")).thenReturn(versionLock);
        models.when(() -> ModelManager.isAuditLogEnabled("Payment")).thenReturn(true);
        models.when(() -> ModelManager.getModelFields("Payment")).thenReturn(List.of());
        return models;
    }
}
