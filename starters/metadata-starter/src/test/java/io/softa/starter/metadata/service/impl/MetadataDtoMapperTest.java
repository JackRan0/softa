package io.softa.starter.metadata.service.impl;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataDtoMapperTest {

    private static MetaField field(String name, String label) {
        MetaField field = Mockito.mock(MetaField.class);
        Mockito.when(field.getFieldName()).thenReturn(name);
        Mockito.when(field.getLabel()).thenReturn(label);
        Mockito.when(field.getModelName()).thenReturn("Customer");
        Mockito.when(field.getFieldType()).thenReturn(FieldType.STRING);
        return field;
    }

    @Test
    void listsTheFieldsByLabelIgnoringCase() {
        MetaModel model = Mockito.mock(MetaModel.class);
        Mockito.when(model.getModelName()).thenReturn("Customer");
        // The registry hands them back in no particular order. A field without a label goes by its name.
        List<MetaField> fields = List.of(
                field("email", "Work Email"), field("draft", null), field("code", "Customer Code"),
                field("piEmployeeId", "Manager"), field("status", "active Status"));
        try (MockedStatic<ModelManager> manager = Mockito.mockStatic(ModelManager.class)) {
            manager.when(() -> ModelManager.getModel("Customer")).thenReturn(model);
            manager.when(() -> ModelManager.getModelFields("Customer")).thenReturn(fields);

            assertThat(MetadataDtoMapper.toModelDTO("Customer").getModelFields().keySet())
                    .containsExactly("status", "code", "draft", "piEmployeeId", "email");
        }
    }

    @Test
    void carriesTheAuditLogSwitchSoTheFormCanHideItsHistory() {
        MetaModel model = Mockito.mock(MetaModel.class);
        Mockito.when(model.getModelName()).thenReturn("Customer");
        try (MockedStatic<ModelManager> manager = Mockito.mockStatic(ModelManager.class)) {
            manager.when(() -> ModelManager.getModel("Customer")).thenReturn(model);
            manager.when(() -> ModelManager.getModelFields("Customer")).thenReturn(List.of());
            // Read through ModelManager, which treats a NULL column as on, not off the raw field.
            manager.when(() -> ModelManager.isAuditLogEnabled("Customer")).thenReturn(false);
            assertThat(MetadataDtoMapper.toModelDTO("Customer").isEnableAuditLog()).isFalse();

            manager.when(() -> ModelManager.isAuditLogEnabled("Customer")).thenReturn(true);
            assertThat(MetadataDtoMapper.toModelDTO("Customer").isEnableAuditLog()).isTrue();
        }
    }
}
