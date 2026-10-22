package io.softa.framework.orm.jdbc;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What an update log carries besides the columns it wrote.
 *
 * <p>Only the written columns, it could say neither which record the row belongs to nor what the
 * row is called: editing one of several holidays was listed as "Holiday Calendar Details · Start
 * Day", with no way to tell which holiday. The row's references and display name now come along,
 * read in the query that already fetches the originals.
 */
class ChangeLogContextFieldsTest {

    private MockedStatic<ModelManager> models;
    private SystemConfig previous;

    private static MetaField field(String name, FieldType type) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "fieldName", name);
        ReflectionTestUtils.setField(field, "fieldType", type);
        return field;
    }

    @BeforeEach
    void setUp() {
        previous = SystemConfig.env;
        SystemConfig config = new SystemConfig();
        ReflectionTestUtils.setField(config, "enableChangeLog", true);
        SystemConfig.env = config;

        MetaModel holiday = mock(MetaModel.class);
        when(holiday.getDisplayName()).thenReturn(List.of("name"));
        models = mockStatic(ModelManager.class);
        models.when(() -> ModelManager.getModel("HolidayCalendarDetail")).thenReturn(holiday);
        models.when(() -> ModelManager.isAuditLogEnabled("HolidayCalendarDetail")).thenReturn(true);
        models.when(() -> ModelManager.getModelStoredFields("HolidayCalendarDetail"))
                .thenReturn(List.of("id", "holidayCalendarId", "name", "startDay", "endDay"));
        models.when(() -> ModelManager.getModelFields("HolidayCalendarDetail")).thenReturn(List.of(
                field("id", FieldType.LONG),
                field("holidayCalendarId", FieldType.MANY_TO_ONE),
                field("name", FieldType.STRING),
                field("startDay", FieldType.DATE),
                field("tagIds", FieldType.MANY_TO_MANY)));
    }

    @AfterEach
    void tearDown() {
        models.close();
        SystemConfig.env = previous;
    }

    @Test
    void carriesTheParentAndTheNameBesidesWhatWasWritten() {
        Set<String> context = new JdbcServiceImpl<>()
                .changeLogContextFields("HolidayCalendarDetail", Set.of("startDay"));

        assertThat(context).containsExactlyInAnyOrder("holidayCalendarId", "name");
    }

    @Test
    void addsNothingTheUpdateAlreadyReads() {
        // A renamed holiday reads its name anyway; it is not asked for twice.
        Set<String> context = new JdbcServiceImpl<>()
                .changeLogContextFields("HolidayCalendarDetail", Set.of("name"));

        assertThat(context).containsExactly("holidayCalendarId");
    }

    @Test
    void readsNothingExtraWhenChangeLogsAreOff() {
        ReflectionTestUtils.setField(SystemConfig.env, "enableChangeLog", false);

        assertThat(new JdbcServiceImpl<>()
                .changeLogContextFields("HolidayCalendarDetail", Set.of("startDay"))).isEmpty();
    }

    @Test
    void readsNothingExtraForAModelWithItsAuditLogOff() {
        models.when(() -> ModelManager.isAuditLogEnabled("HolidayCalendarDetail")).thenReturn(false);

        assertThat(new JdbcServiceImpl<>()
                .changeLogContextFields("HolidayCalendarDetail", Set.of("startDay"))).isEmpty();
    }

    @Test
    void theUpdateItselfSeesTheOriginalsItAlwaysDid() {
        // The context rides along for the log only: the merge and constraint checks the update runs
        // on what it read must not find columns they never asked for.
        Map<Serializable, Map<String, Object>> read = Map.of(1L, new HashMap<>(Map.of(
                "id", 1L, "startDay", "2026-06-16", "holidayCalendarId", 9L, "name", "lg")));

        Map<Serializable, Map<String, Object>> forPipeline = JdbcServiceImpl.withoutKeys(
                read, Set.of("holidayCalendarId", "name"), Set.of("startDay"));

        assertThat(forPipeline.get(1L)).containsOnlyKeys("id", "startDay");
        // The log's copy keeps them.
        assertThat(read.get(1L)).containsKeys("holidayCalendarId", "name");
    }
}
