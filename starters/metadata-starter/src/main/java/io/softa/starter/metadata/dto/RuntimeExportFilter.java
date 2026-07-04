package io.softa.starter.metadata.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Optional per-aggregate filter for the runtime metadata export (incremental fetch).
 * The studio deploy only pulls the aggregates whose checksum differs (or are runtime-only); this is
 * how it narrows the export from the whole {@code appCode} catalog to just those aggregates.
 *
 * <p>{@code keyColumn} is the aggregate-root business key on the requested model (camelCase, e.g.
 * {@code modelName} for the Model aggregate's tables, {@code optionSetCode} for the OptionSet's);
 * {@code keyValues} are the business keys to keep. The runtime ANDs {@code keyColumn IN keyValues}
 * onto the app-scope filter and validates {@code keyColumn} is a real column on the model.
 *
 * <p><b>Semantics</b>: a {@code null} {@code keyColumn} (or {@code null} {@code keyValues}) means
 * <i>no filter</i> — the full app-scoped export (used by full reverse / import). A non-null
 * {@code keyColumn} with an <i>empty</i> {@code keyValues} means <i>fetch nothing</i> (the caller has
 * no aggregate of this root to pull). Always sent as the request body so the key set is not bounded
 * by URL length.
 *
 * @param keyColumn aggregate-root business-key column, or {@code null} for a full export
 * @param keyValues business keys to keep (empty = nothing; ignored when {@code keyColumn} is null)
 */
public record RuntimeExportFilter(String keyColumn, List<String> keyValues) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
