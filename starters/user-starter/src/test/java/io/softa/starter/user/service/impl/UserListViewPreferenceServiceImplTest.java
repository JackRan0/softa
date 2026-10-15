package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.user.dto.UserListViewPreferenceDTO;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * An arrangement holds field names, never authority: whatever the reader may not read now is dropped
 * when it is served and when it is saved — a withdrawn sensitive set takes its columns with it.
 */
class UserListViewPreferenceServiceImplTest {

    private static final String EMPLOYEE = "Employee";
    private static final String CONTACT = "EmpEmergencyContact";

    private MockedStatic<ModelManager> modelManager;
    private UserListViewPreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(any())).thenReturn(true);
        modelManager.when(() -> ModelManager.existField(EMPLOYEE, "emergencyContacts")).thenReturn(true);
        MetaField contacts = mock(MetaField.class);
        when(contacts.getRelatedModel()).thenReturn(CONTACT);
        modelManager.when(() -> ModelManager.getModelField(EMPLOYEE, "emergencyContacts")).thenReturn(contacts);

        PermissionService permissions = mock(PermissionService.class);
        // The reader's sensitive sets withhold Employee.salary and the contact's phone number.
        when(permissions.filterReadableFields(eq(EMPLOYEE), anyCollection(), eq(AccessType.READ)))
                .thenAnswer(call -> without(call.getArgument(1), Set.of("salary")));
        when(permissions.filterReadableFields(eq(CONTACT), anyCollection(), eq(AccessType.READ)))
                .thenAnswer(call -> without(call.getArgument(1), Set.of("phoneNumber")));

        service = new UserListViewPreferenceServiceImpl();
        ReflectionTestUtils.setField(service, "permissionService", permissions);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void dropsTheFieldsTheReaderMayNotReadAndKeepsTheOrderOfTheRest() {
        UserListViewPreferenceDTO dto = arrangement();
        dto.setColumnOrder(List.of("fullName", "salary", "code"));
        dto.setHiddenColumns(List.of("salary"));
        dto.setExportFields(List.of("salary", "fullName", "code"));

        UserListViewPreferenceDTO out = service.readable(dto);

        assertThat(out.getColumnOrder()).containsExactly("fullName", "code");
        assertThat(out.getHiddenColumns()).isEmpty();
        assertThat(out.getExportFields()).containsExactly("fullName", "code");
    }

    @Test
    void checksAnObjectsFieldsAgainstTheModelThatObjectIs() {
        UserListViewPreferenceDTO dto = arrangement();
        Map<String, List<String>> objects = new LinkedHashMap<>();
        objects.put("emergencyContacts", List.of("name", "phoneNumber"));
        dto.setExportObjectFields(objects);

        UserListViewPreferenceDTO out = service.readable(dto);

        assertThat(out.getExportObjectFields()).containsOnlyKeys("emergencyContacts");
        assertThat(out.getExportObjectFields().get("emergencyContacts")).containsExactly("name");
    }

    @Test
    void dropsAnObjectTheListsModelNoLongerHas() {
        UserListViewPreferenceDTO dto = arrangement();
        dto.setExportObjectFields(Map.of("retiredCollection", List.of("name")));

        assertThat(service.readable(dto).getExportObjectFields()).isEmpty();
    }

    @Test
    void leavesPartsThatWereNotSentAsNull() {
        // Null means "not part of this save", so the export dialog's save cannot wipe the columns.
        UserListViewPreferenceDTO out = service.readable(arrangement());

        assertThat(out.getColumnOrder()).isNull();
        assertThat(out.getExportFields()).isNull();
        assertThat(out.getExportObjectFields()).isNull();
    }

    @Test
    void refusesAnArrangementNoListWouldProduce() {
        UserListViewPreferenceDTO dto = arrangement();
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= UserListViewPreferenceServiceImpl.MAX_NAMES; i++) tooMany.add("f" + i);
        dto.setExportFields(tooMany);

        assertThatThrownBy(() -> service.saveMine(dto)).isInstanceOf(BusinessException.class);
    }

    @Test
    void mergesViewSettingsKeyByKeyAndRemovesAKeySentAsNull() {
        // Saving the tree's state must not wipe a setting saved by another part of the list.
        String stored = "{\"sidePanelCollapsed\":false,\"other\":1}";
        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("sidePanelCollapsed", true);
        assertThat(UserListViewPreferenceServiceImpl.mergeSettings(stored, incoming))
                .isEqualTo("{\"sidePanelCollapsed\":true,\"other\":1}");

        Map<String, Object> removal = new LinkedHashMap<>();
        removal.put("sidePanelCollapsed", null);
        removal.put("other", null);
        assertThat(UserListViewPreferenceServiceImpl.mergeSettings(stored, removal)).isNull();
    }

    @Test
    void refusesViewSettingsTooLargeToBeAFewFlags() {
        Map<String, Object> incoming = Map.of("blob", "x".repeat(UserListViewPreferenceServiceImpl.MAX_SETTINGS_LENGTH));
        assertThatThrownBy(() -> UserListViewPreferenceServiceImpl.mergeSettings(null, incoming))
                .isInstanceOf(BusinessException.class);
    }

    private static UserListViewPreferenceDTO arrangement() {
        UserListViewPreferenceDTO dto = new UserListViewPreferenceDTO();
        dto.setViewStateKey("table|Employee|/corehr/employee/employee|");
        dto.setModelName(EMPLOYEE);
        return dto;
    }

    private static List<String> without(Collection<String> names, Set<String> blocked) {
        return names.stream().filter(n -> !blocked.contains(n)).toList();
    }
}
