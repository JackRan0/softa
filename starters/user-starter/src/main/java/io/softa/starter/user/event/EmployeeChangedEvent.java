package io.softa.starter.user.event;

/**
 * Fired when an Employee row materially changes in a way that could shift
 * its dynamic-role memberships — hire, transfer (department / position /
 * legal entity), status change, termination, rehire. DynamicRoleSyncJob
 * listens to this and re-evaluates membership for the affected user.
 *
 * <p>Publish from the entry point that mutates the Employee row (Employee
 * service / preboarding job / status sweeper / etc.):
 *
 * <pre>{@code
 * @Autowired ApplicationEventPublisher publisher;
 * ...
 * publisher.publishEvent(new EmployeeChangedEvent(emp.getId(), Kind.TRANSFER));
 * }</pre>
 *
 * <p>The kind is informational — for logs and future per-kind filtering;
 * the listener currently re-runs the full rule set regardless.
 */
public record EmployeeChangedEvent(Long employeeId, Kind kind) {

    public enum Kind {
        HIRE,
        TRANSFER,
        STATUS_CHANGE,
        TERMINATION,
        REHIRE,
        OTHER,
    }
}
