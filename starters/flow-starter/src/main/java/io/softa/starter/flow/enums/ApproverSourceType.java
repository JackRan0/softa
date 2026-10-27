package io.softa.starter.flow.enums;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Dynamic approver source types supported by approval nodes.
 */
@Getter
@AllArgsConstructor
public enum ApproverSourceType {
    VARIABLE_LIST("VariableList"),
    EXPRESSION("Expression"),
    INITIATOR_MANAGER("InitiatorManager"),
    ROLE("Role"),
    SUPERVISOR("Supervisor"),
    DEPT_LEADER("DeptLeader"),
    DEPT_HRBP("DeptHrbp"),
    ROLE_QUERY("RoleQuery"),
    POSITION("Position"),
    DEPARTMENT("Department"),
    ;

    @JsonValue
    private final String type;

    /**
     * Accepted spellings, upper-cased on both sides: the {@code @JsonValue} type and the constant
     * name. The two differ for multi-word constants — {@code VARIABLELIST} vs {@code VARIABLE_LIST}.
     */
    private static final Map<String, ApproverSourceType> NAMES_MAP = Stream.of(values()).collect(Collectors.toMap(ApproverSourceType::getType, Function.identity()));

    /**
     * Lenient parser used by the compiler to validate an untyped {@code approverSource.type}.
     * Not a Jackson hook — typed JSON binding goes through {@link JsonValue}.
     *
     * <p>Throws the JDK {@code IllegalArgumentException} deliberately:
     * {@code ApprovalConfigValidator} catches that type to turn an unknown source into a compile
     * diagnostic. Do not route this through {@code Assert} — softa's {@code IllegalArgumentException}
     * extends {@code BaseException}, not the JDK one, so that catch would silently stop matching.
     */
    public static ApproverSourceType of(String value) {
        ApproverSourceType sourceType = value == null ? null : NAMES_MAP.get(value);
        if (sourceType == null) {
            throw new IllegalArgumentException("Unsupported approver source type: " + value);
        }
        return sourceType;
    }
}

