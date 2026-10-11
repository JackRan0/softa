package io.softa.framework.orm.service.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.versioning.VersioningStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A null given for a field whose writes are guarded is judged by what it does: on a create it writes
 * nothing, on an update it clears a stored value — or, over a stored null, changes nothing. Only the
 * write that changes something is handed to the permission check as written.
 */
class NullSensitiveWriteTest {

    private static final String MODEL = "Employee";

    private final PermissionService permissionService = mock(PermissionService.class);
    @SuppressWarnings("unchecked")
    private final JdbcService<Long> jdbcService = mock(JdbcService.class);
    private final VersioningStrategyResolver versioning = mock(VersioningStrategyResolver.class);
    private final VersioningStrategy strategy = mock(VersioningStrategy.class);
    private final ModelServiceImpl<Long> service = new ModelServiceImpl<>();
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(service, "versioning", versioning);
        when(versioning.of(anyString())).thenReturn(strategy);
        when(permissionService.getWriteGuardedFields(MODEL)).thenReturn(Set.of("salary"));

        models = Mockito.mockStatic(ModelManager.class);
        MetaField pk = new MetaField();
        ReflectionTestUtils.setField(pk, "fieldName", "id");
        ReflectionTestUtils.setField(pk, "fieldType", FieldType.LONG);
        models.when(() -> ModelManager.getModelPrimaryKeyField(MODEL)).thenReturn(pk);
        models.when(() -> ModelManager.getModelPrimaryKey(MODEL)).thenReturn("id");
        models.when(() -> ModelManager.getModelUpdatableFields(MODEL))
                .thenAnswer(inv -> new HashSet<>(Set.of("name", "salary")));
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private static Map<String, Object> row(Long id, Object salary) {
        Map<String, Object> row = new HashMap<>();
        if (id != null) row.put("id", id);
        row.put("name", "E");
        row.put("salary", salary);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Set<String> checkedFields(AccessType access) {
        ArgumentCaptor<Set<String>> fields = ArgumentCaptor.forClass(Set.class);
        verify(permissionService).checkIdsFieldsAccess(eq(MODEL), any(), fields.capture(), eq(access));
        return fields.getValue();
    }

    private void update(Map<String, Object> row, Object storedSalary) {
        when(strategy.resolveTargetIds(eq(MODEL), anyList())).thenReturn(List.<Serializable>of(1L));
        when(strategy.update(eq(MODEL), anyList(), anySet())).thenReturn(1);
        Map<String, Object> stored = new HashMap<>();
        stored.put("id", 1L);
        stored.put("salary", storedSalary);
        when(jdbcService.selectByIds(eq(MODEL), anyList(), anyList(), eq(ConvertType.ORIGINAL)))
                .thenReturn(List.of(stored));
        ContextHolder.runWith(new Context(), () -> service.updateList(MODEL, new ArrayList<>(List.of(row))));
    }

    @Test
    void aCreateGivingTheFieldAsNullDoesNotCountItAsAssigned() {
        when(strategy.create(eq(MODEL), anyList())).thenAnswer(inv -> {
            List<Map<String, Object>> rows = inv.getArgument(1);
            rows.forEach(r -> r.put("id", 1L));
            return rows;
        });

        ContextHolder.runWith(new Context(), () -> service.createList(MODEL, new ArrayList<>(List.of(row(null, null)))));

        assertThat(checkedFields(AccessType.CREATE)).contains("name").doesNotContain("salary");
    }

    @Test
    void anUpdateClearingAStoredValueIsAWrite() {
        update(row(1L, null), 8000);

        assertThat(checkedFields(AccessType.UPDATE)).contains("name", "salary");
    }

    @Test
    void anUpdateEchoingNullOverNullIsNotAWrite() {
        update(row(1L, null), null);

        assertThat(checkedFields(AccessType.UPDATE)).contains("name").doesNotContain("salary");
    }

    @Test
    void anUpdateWithAValueIsAWriteWithoutReadingTheStoredRow() {
        update(row(1L, 9000), null);

        assertThat(checkedFields(AccessType.UPDATE)).contains("name", "salary");
        verify(jdbcService, never()).selectByIds(anyString(), anyList(), anyList(), any(ConvertType.class));
    }

    @Test
    void theStoredRowIsNotReadWhenNoFieldIsGuarded() {
        when(permissionService.getWriteGuardedFields(MODEL)).thenReturn(Set.of());

        update(row(1L, null), 8000);

        assertThat(checkedFields(AccessType.UPDATE)).contains("name", "salary");
        verify(jdbcService, never()).selectByIds(anyString(), anyList(), anyList(), any(ConvertType.class));
    }
}
