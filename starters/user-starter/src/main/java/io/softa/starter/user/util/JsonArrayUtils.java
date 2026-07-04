package io.softa.starter.user.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Lenient JSON-array → Java collection converters for {@code JsonNode}
 * columns ({@code permission.endpoints} / {@code role_navigation.dataScopes}
 * / {@code sensitive_field_set.fieldCodes} / {@code role_navigation.permissionIds}
 * etc.). Centralised here so consumers don't drift in subtle ways — e.g.
 * one consumer accepting numeric elements as stringified ids while another
 * silently dropping them.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code null} / non-array nodes → empty result, never throw.</li>
 *   <li>Non-string elements → see {@link #toStringList(JsonNode, boolean)}'s
 *       {@code coerceNumeric} flag. Default ({@link #toStringList(JsonNode)})
 *       is strict (string-only) to match the original SensitiveFieldSetCache
 *       behaviour, which is the safest default for new code.</li>
 * </ul>
 *
 * <h3>Why not {@code io.softa.framework.base.utils.JsonUtils}?</h3>
 * Framework {@code JsonUtils.jsonNodeToStringList} / {@code jsonNodeToLongList}
 * exist but use {@code mapper.convertValue} which:
 * <ol>
 *   <li>Returns {@code null} (not {@code List.of()}) for null / non-array
 *       input — every caller would need a null check.</li>
 *   <li>Lets Jackson silently coerce element types — a {@code [1, 2, 3]}
 *       array passes through {@code jsonNodeToStringList} as
 *       {@code ["1", "2", "3"]}, defeating the "fieldCode must be a real
 *       string" intent.</li>
 *   <li>Throws on unparseable elements ({@code ["abc"]} → {@code Long}
 *       blows up).</li>
 *   <li>Has no {@code Set} variants or {@code Long}-from-mixed-elements
 *       helper.</li>
 * </ol>
 * This util keeps the "strict + null-safe + never throw" contract that
 * SFS cache / PermissionInfoEnricher / FieldFilter rely on. If you don't
 * need that contract (e.g. trusted internal JSON), prefer the framework
 * helpers — same API name, slightly different shape.
 */
public final class JsonArrayUtils {

    private JsonArrayUtils() {}

    /**
     * Strict version: only {@link JsonNode#isString()} elements are kept;
     * numbers / booleans / nested objects are silently dropped. Suitable
     * for field-name / set-id columns where numeric elements would be a
     * data error, not a legitimate value.
     */
    public static List<String> toStringList(JsonNode node) {
        return toStringList(node, false);
    }

    /**
     * @param coerceNumeric when true, JsonNode numeric leaves are converted
     *                       to their string form. Use for id columns
     *                       (permission ids, role_navigation.permissionIds)
     *                       where ids may be persisted as numbers in some
     *                       JSON dialects.
     */
    public static List<String> toStringList(JsonNode node, boolean coerceNumeric) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode el : node) {
            if (el.isString()) {
                out.add(el.asString());
            } else if (coerceNumeric && el.isNumber()) {
                out.add(String.valueOf(el.asLong()));
            }
        }
        return out;
    }

    /** Strict-string variant returning a Set (dedup applied). */
    public static Set<String> toStringSet(JsonNode node) {
        return new HashSet<>(toStringList(node));
    }

    /** Numeric-coercing variant returning a Set. */
    public static Set<String> toStringSet(JsonNode node, boolean coerceNumeric) {
        return new HashSet<>(toStringList(node, coerceNumeric));
    }

    /**
     * Lenient single-node → Long. Accepts numeric leaves (returns
     * {@code node.asLong()}), string leaves (parses as decimal), null /
     * non-leaf nodes (returns {@code null}). Use for id columns where the
     * upstream JSON dialect mixes numbers and stringified numbers
     * (frontend convention serializes ids as strings; tests / older seeds
     * may persist them as numbers).
     */
    public static Long toLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.asLong();
        if (node.isString()) {
            String s = node.asString();
            if (s == null || s.isEmpty()) return null;
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Array of mixed-form id elements → deduped Long list (preserves
     * insertion order via {@link java.util.LinkedHashSet} discipline at
     * the caller — this method itself returns a plain list with no
     * dedup). Non-coercible elements ({@code null}, booleans, objects)
     * are silently dropped, matching the {@link #toStringList} contract.
     */
    public static List<Long> toLongList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Long> out = new ArrayList<>(node.size());
        for (JsonNode el : node) {
            Long v = toLong(el);
            if (v != null) out.add(v);
        }
        return out;
    }
}
