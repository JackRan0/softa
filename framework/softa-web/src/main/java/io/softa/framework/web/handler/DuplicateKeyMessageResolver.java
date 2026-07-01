package io.softa.framework.web.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.i18n.I18n;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaIndex;
import io.softa.framework.orm.meta.ModelManager;

/**
 * Turns a database unique-constraint violation into a friendly, localized end-user message.
 *
 * <p>Path: violated index name (see {@link UniqueViolationParser}, PostgreSQL + MySQL) &rarr;
 * {@link ModelManager#getIndex} (the in-memory registry, keyed by the globally-unique index
 * name) &rarr; either the index's custom {@code message} or a message composed from its member
 * fields' labels. Everything it reads is framework-level ({@code ModelManager} from softa-orm,
 * {@code I18n} from softa-base), so no metadata catalog SPI / optional-bean indirection is needed;
 * {@code WebExceptionHandler} calls {@link #resolve} directly.
 *
 * <p>Three tiers, in order:
 * <ol>
 *   <li>the index's explicit {@code message} (a self-contained sentence, its own i18n key) —
 *       e.g. <em>"An employee is already assigned to this project."</em>;</li>
 *   <li>a composed message from the member-field labels —
 *       e.g. <em>"A record with the same Email already exists."</em>;</li>
 *   <li>none — return {@link Optional#empty()} so the caller falls back to the raw driver message.</li>
 * </ol>
 *
 * <p>Failure-safe: never throws — returns {@link Optional#empty()} on any miss (not a unique
 * violation, name unrecoverable, index unknown to the runtime, unknown field).
 */
@Slf4j
public final class DuplicateKeyMessageResolver {

    /** Localizable template; {0} is the comma-joined list of violated field labels. */
    private static final String MESSAGE_TEMPLATE = "A record with the same {0} already exists.";

    private DuplicateKeyMessageResolver() {}

    /**
     * @param duplicateKeyException the caught exception (typically a Spring
     *                              {@code DuplicateKeyException} / {@code DataIntegrityViolationException}
     *                              wrapping a {@code java.sql.SQLException})
     * @return the resolved message, or empty if it cannot be resolved
     */
    public static Optional<DuplicateKeyMessage> resolve(Throwable duplicateKeyException) {
        try {
            String indexName = UniqueViolationParser.parseConstraintName(duplicateKeyException);
            if (indexName == null) {
                return Optional.empty();
            }
            MetaIndex index = ModelManager.getIndex(indexName);
            if (index == null) {
                return Optional.empty();
            }
            String logDetail = "constraint=" + indexName
                    + ", model=" + index.getModelName()
                    + ", fields=" + index.getIndexFields();

            // Tier 1 — explicit per-index message (zero-arg I18n.get short-circuits MessageFormat,
            // so an apostrophe in the sentence renders literally).
            if (StringUtils.isNotBlank(index.getMessage())) {
                return Optional.of(new DuplicateKeyMessage(I18n.get(index.getMessage()), logDetail));
            }

            // Tier 2 — compose from the member fields' labels.
            List<String> labels = resolveFieldLabels(index.getModelName(), index.getIndexFields());
            if (labels.isEmpty()) {
                return Optional.empty();
            }
            String userMessage = I18n.get(MESSAGE_TEMPLATE, String.join(", ", labels));
            return Optional.of(new DuplicateKeyMessage(userMessage, logDetail));
        } catch (Exception e) {
            log.debug("Could not resolve a friendly duplicate-key message; falling back to raw message", e);
            return Optional.empty();
        }
    }

    private static List<String> resolveFieldLabels(String modelName, List<String> fields) {
        if (StringUtils.isBlank(modelName) || CollectionUtils.isEmpty(fields)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>(fields.size());
        for (String field : fields) {
            // Skip the tenant discriminator so a multi-tenant unique index
            // (e.g. tenantId + code) reads naturally as just "Code".
            if (ModelConstant.TENANT_ID.equals(field)) {
                continue;
            }
            MetaField metaField = ModelManager.getModelField(modelName, field);
            labels.add(metaField.getLabel());
        }
        return labels;
    }

    /**
     * @param userMessage friendly, localized text for the API response
     * @param logDetail   stable, English, value-free descriptor for the server log
     *                    (e.g. {@code "constraint=uk_x, model=Y, fields=[a]"}) —
     *                    never localized, never the raw driver text
     */
    public record DuplicateKeyMessage(String userMessage, String logDetail) {}
}
