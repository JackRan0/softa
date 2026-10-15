package io.softa.framework.orm.jdbc.pipeline;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

/**
 * Which fields of a patch count as changed. The stored value is read back as {@code java.time};
 * the patch carries what the caller sent.
 */
class DataUpdatePipelineDifferTest {

    @Test
    void anUnchangedDateIsNotAChange() {
        Map<Serializable, Map<String, Object>> stored = new HashMap<>();
        stored.put(7L, new HashMap<>(Map.of("id", 7L,
                "startTime", LocalDate.of(2026, 10, 17), "signedDate", LocalDate.of(2026, 11, 11))));
        Map<String, Object> patch = new HashMap<>(Map.of("id", 7L,
                "startTime", LocalDate.of(2026, 8, 4), "signedDate", LocalDate.of(2026, 11, 11)));

        List<Map<String, Object>> differ;
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.isVersionControl("Doc")).thenReturn(false);
            differ = DataUpdatePipeline.differFromPrevious("Doc", "id", Set.of("startTime", "signedDate"),
                    List.of(patch), stored);
        }

        assertThat(differ).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("startTime", LocalDate.of(2026, 8, 4));
            assertThat(row).doesNotContainKey("signedDate");
        });
    }

    @Test
    void aPatchThatChangesNothingIsNoUpdate() {
        Map<Serializable, Map<String, Object>> stored = new HashMap<>();
        stored.put(7L, new HashMap<>(Map.of("id", 7L, "signedDate", LocalDate.of(2026, 11, 11))));
        Map<String, Object> patch = new HashMap<>(Map.of("id", 7L, "signedDate", "2026-11-11"));

        List<Map<String, Object>> differ;
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.isVersionControl("Doc")).thenReturn(false);
            differ = DataUpdatePipeline.differFromPrevious("Doc", "id", Set.of("signedDate"), List.of(patch), stored);
        }

        assertThat(differ).isEmpty();
    }
}
