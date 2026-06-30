package io.softa.starter.user.util;

import java.util.Map;

/**
 * Lenient {@code Object} → {@code Long} id extractor for raw values
 * returned by {@code ModelService.searchList(...)} (which deserializes
 * each row as a {@code Map<String, Object>}). Centralised here so
 * consumers don't drift in subtle ways — e.g. one branch handling
 * {@code Map} (ModelReference) form while another doesn't, or one
 * throwing on a bad string while another returns null.
 *
 * <p>Accepted input shapes:
 * <ul>
 *   <li>{@code null} → {@code null}</li>
 *   <li>{@code Number} (Long / Integer / BigInteger / ...) →
 *       {@code Number.longValue()}</li>
 *   <li>parseable decimal {@code String} (FE convention serialises all
 *       ids as strings) → {@code Long.parseLong}; empty / non-parseable
 *       strings → {@code null}</li>
 *   <li>{@code Map<?, ?>} with an {@code id} entry (the framework's
 *       default ManyToOne / OneToOne expansion shape:
 *       {@code {"id": 123, "displayName": "..."}}) → recurse on
 *       {@code m.get("id")}</li>
 *   <li>anything else (boolean, array, object without {@code id}) →
 *       {@code null}</li>
 * </ul>
 *
 * <p>Never throws. Designed for data crossing the wire / JSON
 * deserialization boundary where input is untrusted.
 *
 * <h3>vs {@code io.softa.framework.orm.utils.IdUtils.convertIdToLong}</h3>
 * Framework {@code IdUtils.convertIdToLong} is strict: it throws on
 * empty / non-parseable strings, non-Integer/Long/String inputs, and
 * doesn't handle the {@code Map} (ModelReference) form. Use it when
 * input is known-clean (e.g. inside a service computing on freshly
 * validated entities); use this util when input came from
 * {@code ModelService.searchList} rows or external payloads.
 *
 * <h3>vs {@code JsonArrayUtils.toLong(JsonNode)}</h3>
 * Different input type — {@link JsonArrayUtils#toLong(tools.jackson.databind.JsonNode)}
 * handles raw Jackson tree nodes (e.g. {@code JsonNode} request bodies
 * or DB JSON columns), this util handles already-deserialised
 * {@code Object} values. Not interchangeable.
 */
public final class ModelRefIds {

    private ModelRefIds() {}

    /**
     * Extract a {@code Long} id from an arbitrary value — see class
     * javadoc for accepted shapes.
     */
    public static Long extractLongId(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            if (s.isEmpty()) return null;
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (value instanceof Map<?, ?> m) {
            return extractLongId(m.get("id"));   // recurse once for ModelReference shape
        }
        return null;
    }
}
