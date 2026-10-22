package io.softa.starter.tenant.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcProxy;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.tenant.config.PlatformTenantProperties;
import io.softa.starter.tenant.entity.TenantInfo;

import static io.softa.framework.base.constant.BaseConstant.PLATFORM_TENANT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The platform tenant row is written by hand-built SQL, because its fixed id would not survive the model
 * layer. What a hand-built insert loses is the model's own check that every required column gets a value —
 * the tests here put that check back.
 */
class PlatformTenantCreatorTest {

    private JdbcProxy jdbcProxy;
    private PlatformTenantProperties properties;
    private PlatformTenantCreator creator;

    @BeforeEach
    void setUp() {
        jdbcProxy = mock(JdbcProxy.class);
        properties = new PlatformTenantProperties();
        properties.setDefaultCountry("sg");
        properties.setDefaultTimezone("UTC+08:00");
        properties.setDefaultLanguage("en-US");
        creator = new PlatformTenantCreator(jdbcProxy, properties);
    }

    // ---- the column list, held to the entity ------------------------------------------------

    @Test
    void everyRequiredStoredFieldWithoutADefaultIsInserted() {
        // A required field added to TenantInfo later would make this insert fail on the next fresh
        // deployment — long after the change, on someone else's machine. Fail the build instead.
        List<String> missing = new ArrayList<>();
        for (java.lang.reflect.Field f : declaredFields(TenantInfo.class)) {
            Field meta = f.getAnnotation(Field.class);
            if (meta == null || !meta.required() || !meta.defaultValue().isEmpty() || meta.dynamic()
                    || isToMany(meta)) {
                continue;
            }
            if (!PlatformTenantCreator.INSERTED_FIELDS.contains(f.getName())) {
                missing.add(f.getName());
            }
        }
        assertTrue(missing.isEmpty(), "TenantInfo has required fields the platform tenant insert does not write: "
                + missing + " — add them to PlatformTenantCreator.INSERTED_FIELDS and row().");
    }

    @Test
    void everyInsertedFieldExistsOnTheEntity() {
        // The other direction: a field renamed or dropped on TenantInfo would leave the insert naming a
        // column that is gone.
        List<String> declared = declaredFields(TenantInfo.class).stream().map(java.lang.reflect.Field::getName).toList();
        List<String> unknown = PlatformTenantCreator.INSERTED_FIELDS.stream().filter(f -> !declared.contains(f)).toList();
        assertTrue(unknown.isEmpty(), "The platform tenant insert names fields TenantInfo does not have: " + unknown);
    }

    @Test
    void theRowCarriesExactlyTheInsertedFieldsInOrder() {
        assertEquals(PlatformTenantCreator.INSERTED_FIELDS, List.copyOf(creator.row().keySet()));
    }

    // ---- behaviour ---------------------------------------------------------------------------

    @Test
    void createsTheRowWithTheFixedIdAndTheConfiguredAttributes() {
        try (MockedStatic<ModelManager> mm = metadata()) {
            when(jdbcProxy.queryForObject(eq(PlatformTenantCreator.MODEL), any(SqlParams.class), eq(Long.class)))
                    .thenReturn(0L);

            assertTrue(creator.createIfAbsent());

            ArgumentCaptor<SqlParams> insert = ArgumentCaptor.forClass(SqlParams.class);
            verify(jdbcProxy).update(eq(PlatformTenantCreator.MODEL), insert.capture());
            assertTrue(insert.getValue().getSql().startsWith("INSERT INTO tenant_info (id, name, code, status"),
                    insert.getValue().getSql());
            List<Object> args = Arrays.asList(insert.getValue().getArgsArray());
            assertEquals(PLATFORM_TENANT_ID, args.get(0), "the id is the platform tenant's, not a generated one");
            assertEquals("Active", args.get(3), "created active — nothing will ever move it there later");
            assertEquals("UTC+08:00", args.get(6));
            assertEquals("SG", args.get(7), "country codes are stored upper-case");
        }
    }

    @Test
    void leavesAnExistingRowAlone() {
        // Including a soft-deleted or suspended one: this creates, it never repairs.
        try (MockedStatic<ModelManager> mm = metadata()) {
            when(jdbcProxy.queryForObject(eq(PlatformTenantCreator.MODEL), any(SqlParams.class), eq(Long.class)))
                    .thenReturn(1L);

            assertFalse(creator.createIfAbsent());
            verify(jdbcProxy, never()).update(anyString(), any(SqlParams.class));
        }
    }

    @Test
    void anotherInstanceWinningTheRaceIsNotAFailure() {
        try (MockedStatic<ModelManager> mm = metadata()) {
            when(jdbcProxy.queryForObject(eq(PlatformTenantCreator.MODEL), any(SqlParams.class), eq(Long.class)))
                    .thenReturn(0L);
            when(jdbcProxy.update(eq(PlatformTenantCreator.MODEL), any(SqlParams.class)))
                    .thenThrow(new DuplicateKeyException("uk"));

            assertFalse(creator.createIfAbsent());
        }
    }

    @Test
    void refusesToGuessTheCountry() {
        properties.setDefaultCountry(null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> creator.row());
        assertTrue(e.getMessage().contains("softa.tenant.platform.default-country"), e.getMessage());
    }

    @Test
    void refusesAnUnknownTimezoneCode() {
        properties.setDefaultTimezone("Asia/Singapore");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> creator.row());
        assertTrue(e.getMessage().contains("default-timezone"), e.getMessage());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static List<java.lang.reflect.Field> declaredFields(Class<?> type) {
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields;
    }

    private static boolean isToMany(Field meta) {
        return Arrays.stream(meta.fieldType())
                .anyMatch(t -> t == FieldType.ONE_TO_MANY || t == FieldType.MANY_TO_MANY);
    }

    /** Table and column names as the scanner would resolve them: snake_case of the model and field names. */
    private static MockedStatic<ModelManager> metadata() {
        MetaModel model = mock(MetaModel.class);
        when(model.getTableName()).thenReturn("tenant_info");
        MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
        mm.when(() -> ModelManager.getModel(PlatformTenantCreator.MODEL)).thenReturn(model);
        mm.when(() -> ModelManager.getModelField(eq(PlatformTenantCreator.MODEL), anyString()))
                .thenAnswer(inv -> column(inv.getArgument(1)));
        return mm;
    }

    private static MetaField column(String fieldName) {
        MetaField field = mock(MetaField.class);
        when(field.getColumnName()).thenReturn(fieldName.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase());
        return field;
    }
}
