package io.softa.starter.metadata.sequence;

import java.util.Optional;

/**
 * Says how a model reaches the company its records belong to, as a dot-path of fields ending on the
 * company model — for the {@code {companyAbbr}} sequence token.
 *
 * <p>Answered per model, not per record: a sequence is bound to one {@code Model.field}, so which
 * field leads to the company is a property of the model. The path's first hop is read from the record
 * being inserted; every later hop is a batched lookup.
 *
 * <p>Several implementations may be registered. They are asked in {@code @Order}, and the first
 * non-empty answer wins; an implementation that does not know a model returns empty so the next one
 * is asked. The framework's own {@link DefaultCompanyCascadePathResolver} runs last and knows only
 * the framework's company axis. An application whose records belong to a company in other ways — a
 * signing entity that must win over the org company, a child record that inherits its parent's — says
 * so with an implementation of its own, ordered ahead of the default.
 */
public interface CompanyCascadePathResolver {

    /** The model is the company model itself: the abbreviation is on the record being inserted. */
    String SELF_PATH = "";

    /**
     * @param modelName the model a sequence numbers
     * @return the dot-path to the company (e.g. {@code "companyId"}, {@code "employeeId.legalEntityId"}),
     *         {@link #SELF_PATH}, or empty when this resolver does not know the model
     */
    Optional<String> resolve(String modelName);
}
