package io.softa.starter.flow.runtime;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.starter.flow.entity.ApprovalModel;

/** A leave request, as the record-approval tests submit it: an approved model and what its flows route on. */
@Data
@EqualsAndHashCode(callSuper = true)
public class LeaveRequest extends ApprovalModel {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** The subject: the employee on leave. */
    private Long employeeId;

    private String leaveType;

    private Integer days;
}
