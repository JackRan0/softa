package io.softa.starter.flow.enums;

import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import io.softa.framework.base.enums.OptionItemTone;
import io.softa.framework.orm.vo.OptionReference;

/**
 * Where a record stands in its approval: the {@code approvalStatus} of an {@code ApprovalModel}. The
 * framework writes it, the same way for every model: {@link #PENDING} when the record is submitted, the
 * result when its approval ends. No person writes it, and no handler does.
 *
 * <p>Two values freeze the record ({@link #isLocked()}): a record in approval waits for its result or a
 * withdrawal, and an approved one is settled. From any other value its submitter edits it and submits it
 * again, which starts a new approval from the beginning.
 *
 * <p>What happens to a record after its approval — paid, in effect, voided — is not an approval status: it
 * goes in the model's own status.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum ApprovalStatus {

    @OptionItem(itemTone = OptionItemTone.NEUTRAL)
    DRAFT("Draft"),

    /** Submitted: its approval is in progress. */
    @OptionItem(itemTone = OptionItemTone.INFO)
    PENDING("Pending"),

    @OptionItem(itemTone = OptionItemTone.SUCCESS)
    APPROVED("Approved"),

    @OptionItem(itemTone = OptionItemTone.ERROR)
    REJECTED("Rejected"),

    /** An approver sent it back to its submitter to edit. */
    @OptionItem(itemTone = OptionItemTone.WARNING)
    RETURNED("Returned"),

    /** Its submitter took it back while it waited, or after its approval failed. */
    @OptionItem(itemTone = OptionItemTone.NEUTRAL)
    WITHDRAWN("Withdrawn"),

    /** Its approval instance was cancelled. */
    @OptionItem(itemTone = OptionItemTone.NEUTRAL)
    CANCELLED("Cancelled"),
    ;

    @JsonValue
    private final String code;

    /** Whether the record is frozen: in approval, or approved. */
    public boolean isLocked() {
        return this == PENDING || this == APPROVED;
    }

    /** The status an approval's result leaves its record in. */
    public static ApprovalStatus resultOf(ApprovalOutcomeType outcome) {
        return switch (outcome) {
            case APPROVED -> APPROVED;
            case REJECTED -> REJECTED;
            case RETURNED -> RETURNED;
            case WITHDRAWN -> WITHDRAWN;
            case CANCELLED -> CANCELLED;
        };
    }

    /** A status as stored or sent — the enum, its code or an option reference — or null for none. */
    public static @Nullable ApprovalStatus from(@Nullable Object value) {
        String code = switch (value) {
            case null -> null;
            case ApprovalStatus status -> status.code;
            case OptionReference option -> option.getItemCode();
            case Map<?, ?> option -> Objects.toString(option.get("itemCode"), null);
            default -> value.toString();
        };
        for (ApprovalStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
