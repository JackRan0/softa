package io.softa.starter.flow.runtime.spi;

/**
 * Business callbacks of one model whose records are approved by flows: implement one per model (a
 * Spring bean) and submit records through {@code FlowClient.submitRecord}. Which flow approves a
 * record is not decided here — each flow's submit trigger names its model and, optionally, the
 * records it applies to.
 *
 * <p>The approval's progress lives on the flow instance; the record keeps only its result. Row ids
 * are strings, as everywhere in the engine; convert them to the model's own id type as needed.
 */
public interface ModelApprovalHandler {

    /** The model whose records this handler serves, e.g. {@code "LeaveRequest"}: one handler per model. */
    String modelName();

    /**
     * Checks that the record may be submitted now and says what the flow needs. Runs first, with
     * the submitter's own permissions, in the submission's transaction: verify the record's status,
     * that the caller may submit it and the business rules, and throw to refuse. Called on every
     * submission — after a return too — so the flow always sees the record as it is.
     *
     * @param rowId the submitted record
     * @return the instance title and the routing variables
     */
    ApprovalSubmission prepare(String rowId);

    /**
     * The submission passed its checks: move the record to "in approval". Runs in the submission's
     * transaction with permission checks skipped, just before the approval instance starts — before,
     * because a flow without an approval step completes while it starts, and its result must land on
     * a record that is already in approval. Should the start fail, the transaction rolls this back.
     * Concurrent submissions of one record are refused by the engine (a record has one approval in
     * progress at most), and the losing transaction rolls this back too.
     *
     * @param rowId the submitted record
     */
    void onSubmitted(String rowId);

    /**
     * The approval reached a result — approved, rejected, returned to its submitter, withdrawn: write
     * that to the record and run the follow-up business actions. A returned record is edited and
     * submitted again, which starts a new approval. Runs inside the approval action's transaction
     * with permission checks skipped; throwing vetoes the action, which then rolls back and reports
     * the error to whoever acted.
     * <p>
     * Must be idempotent. A finished instance is saved again when someone comments on it or reads a
     * CC, and the same outcome may arrive again: change the record only while it is still in
     * approval, and do nothing otherwise.
     *
     * @param rowId   the submitted record
     * @param outcome how the approval ended, and who ended it
     */
    void onOutcome(String rowId, ApprovalOutcome outcome);
}
