package io.softa.starter.flow.controller;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.dto.FlowRecordWithdrawRequest;
import io.softa.starter.flow.runtime.LeaveRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * An approved model's controller submits and withdraws its own model's records, named by its entity.
 */
class ApprovalModelControllerTest {

    private final FlowClient flowClient = mock(FlowClient.class);
    private final LeaveRequestController controller = new LeaveRequestController();

    {
        controller.flowClient = flowClient;
    }

    @Test
    void theModelIsTheEntityTheControllerServes() {
        assertEquals("LeaveRequest", controller.modelName);
    }

    @Test
    void submitAndWithdrawGoThroughTheFlowClient() {
        controller.submit(42L);
        controller.withdraw(42L, new FlowRecordWithdrawRequest("not needed"));
        controller.withdraw(43L, null);

        verify(flowClient).submitRecord("LeaveRequest", "42");
        verify(flowClient).withdrawRecord("LeaveRequest", "42", "not needed");
        verify(flowClient).withdrawRecord("LeaveRequest", "43", null);
    }

    interface LeaveRequestService extends EntityService<LeaveRequest, Long> {
    }

    static class LeaveRequestController extends ApprovalModelController<LeaveRequestService, LeaveRequest, Long> {
    }
}
