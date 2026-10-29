package io.softa.starter.flow.runtime.engine;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.utils.SFunction;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.runtime.LeaveRequest;
import io.softa.starter.flow.runtime.spi.ApprovalSubmission;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * The boot checks of the approved models: their approval fields, and their subject — {@code employeeId}
 * unless the handler names another field, always a many-to-one onto the employee model.
 */
class ModelApprovalHandlerRegistryTest {

    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(anyString())).thenReturn(true);
        modelManager.when(() -> ModelManager.existField(anyString(), anyString())).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelField("LeaveRequest", "employeeId"))
                .thenReturn(toOne("employeeId", "Employee"));
        modelManager.when(() -> ModelManager.getModelField("Transfer", "transfereeId"))
                .thenReturn(toOne("transfereeId", "Employee"));
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void theSubjectIsEmployeeIdUnlessTheHandlerNamesAnother() {
        Handler<LeaveRequest> leave = new Handler<>(LeaveRequest.class, null);
        Handler<Transfer> transfer = new Handler<>(Transfer.class, Transfer::getTransfereeId);

        ModelApprovalHandlerRegistry.of(List.of(leave, transfer)).afterSingletonsInstantiated();

        assertEquals("employeeId", ModelApprovalHandlerRegistry.subjectFieldOf(leave));
        assertEquals("transfereeId", ModelApprovalHandlerRegistry.subjectFieldOf(transfer));
    }

    @Test
    void aSubjectThatIsNoEmployeeFailsTheBoot() {
        modelManager.when(() -> ModelManager.getModelField("LeaveRequest", "employeeId"))
                .thenReturn(toOne("employeeId", "Department"));
        ModelApprovalHandlerRegistry registry = ModelApprovalHandlerRegistry.of(
                List.of(new Handler<>(LeaveRequest.class, null)));

        IllegalStateException error = assertThrows(IllegalStateException.class, registry::afterSingletonsInstantiated);

        assertTrue(error.getMessage().contains("many-to-one field onto Employee"), error.getMessage());
    }

    @Test
    void aModelWithoutItsSubjectFieldFailsTheBoot() {
        modelManager.when(() -> ModelManager.existField("LeaveRequest", "employeeId")).thenReturn(false);
        ModelApprovalHandlerRegistry registry = ModelApprovalHandlerRegistry.of(
                List.of(new Handler<>(LeaveRequest.class, null)));

        IllegalStateException error = assertThrows(IllegalStateException.class, registry::afterSingletonsInstantiated);

        assertTrue(error.getMessage().contains("subject()"), error.getMessage());
    }

    @Test
    void aModelWithoutTheApprovalFieldsFailsTheBoot() {
        modelManager.when(() -> ModelManager.existField("LeaveRequest", "approvalStatus")).thenReturn(false);
        ModelApprovalHandlerRegistry registry = ModelApprovalHandlerRegistry.of(
                List.of(new Handler<>(LeaveRequest.class, null)));

        IllegalStateException error = assertThrows(IllegalStateException.class, registry::afterSingletonsInstantiated);

        assertTrue(error.getMessage().contains("approvalStatus"), error.getMessage());
    }

    private static MetaField toOne(String fieldName, String relatedModel) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "fieldType", FieldType.MANY_TO_ONE);
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        return field;
    }

    /** A transfer, whose subject is the employee transferred rather than an {@code employeeId}. */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Transfer extends ApprovalModel {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long id;

        private Long transfereeId;
    }

    private record Handler<T extends ApprovalModel>(Class<T> modelClass, SFunction<T, Long> subject)
            implements ModelApprovalHandler<T> {

        @Override
        public ApprovalSubmission submission(T record) {
            return new ApprovalSubmission("", null);
        }
    }
}
