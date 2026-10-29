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
 * framework writes it, the same way for every model, along one state machine:
 * <ul>
 *   <li>{@link #DRAFT}: the only status a record is edited and submitted from, and the only one its
 *       applicant cancels it from.</li>
 *   <li>{@link #PENDING}: submitted, its approval in progress. An approval returned to its submitter, or
 *       withdrawn by them, puts the record back to {@link #DRAFT}, to be edited and submitted again as a new
 *       approval.</li>
 *   <li>{@link #APPROVED}, {@link #REJECTED}, {@link #CANCELLED}: final. Nothing moves the record on.</li>
 * </ul>
 * No person writes it, and no handler does. Every status but {@link #DRAFT} freezes the record
 * ({@link #isLocked()}). A return or a withdrawal is not a status the record stays in: its approval history
 * keeps it.
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

    /** Its applicant cancelled the draft, or its approval instance was cancelled. */
    @OptionItem(itemTone = OptionItemTone.NEUTRAL)
    CANCELLED("Cancelled"),
    ;

    @JsonValue
    private final String code;

    /** Whether the record is frozen: anything but a draft — in approval, or its approval over for good. */
    public boolean isLocked() {
        return this != DRAFT;
    }

    /**
     * The status an approval's result leaves its record in: a return or a withdrawal hands the record back
     * as a draft, every other result is final.
     */
    public static ApprovalStatus resultOf(ApprovalOutcomeType outcome) {
        return switch (outcome) {
            case APPROVED -> APPROVED;
            case REJECTED -> REJECTED;
            case RETURNED, WITHDRAWN -> DRAFT;
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
