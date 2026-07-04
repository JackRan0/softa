package io.softa.starter.metadata.checksum;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes the per-aggregate checksum from in-memory metadata entities by
 * reflectively projecting each entity onto the shared key-attribute allow-list and
 * delegating to {@link AggregateChecksum}.
 *
 * <p>This is test-support, not a wired service: nothing injects it. It is constructed
 * directly (via {@code new}) by the cross-side checksum golden test in studio-starter,
 * which pins the load-bearing guarantee that the runtime ({@code Sys*}) and studio
 * ({@code Design*}) sides hash equal iff their schema-relevant state is equal. The same
 * instance serves both sides because both expose the allow-listed attributes under
 * identical field names.
 *
 * <p>Reflection (not a fixed getter contract) keeps this decoupled from the two entity
 * hierarchies; an allow-listed attribute that is absent on one side is read as
 * {@code null}, which {@link CanonicalMetadataSerializer} treats identically to an
 * explicit null — so a benign field-set difference never causes a false-diff.
 */
public class AggregateChecksumService {

    /** Model + its fields + its indexes → aggregate checksum. */
    public String modelChecksum(Object model, List<?> fields, List<?> indexes) {
        return AggregateChecksum.ofModel(
                project(model, AggregateChecksum.MODEL_ATTRS),
                projectAll(fields, AggregateChecksum.FIELD_ATTRS),
                projectAll(indexes, AggregateChecksum.INDEX_ATTRS));
    }

    /** OptionSet + its items → aggregate checksum. */
    public String optionSetChecksum(Object optionSet, List<?> items) {
        return AggregateChecksum.ofOptionSet(
                project(optionSet, AggregateChecksum.OPTION_SET_ATTRS),
                projectAll(items, AggregateChecksum.OPTION_ITEM_ATTRS));
    }

    private List<Map<String, Object>> projectAll(List<?> entities, List<String> attrs) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(entities.size());
        for (Object e : entities) {
            out.add(project(e, attrs));
        }
        return out;
    }

    private Map<String, Object> project(Object entity, List<String> attrs) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (entity == null) {
            return map;
        }
        for (String attr : attrs) {
            Field f = field(entity.getClass(), attr);
            if (f == null) {
                continue;   // absent attr → treated as null by the serializer
            }
            try {
                map.put(attr, f.get(entity));
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(
                        "Cannot read attribute '" + attr + "' on " + entity.getClass().getName(), ex);
            }
        }
        return map;
    }

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private static Field field(Class<?> type, String name) {
        return FIELD_CACHE.computeIfAbsent(type.getName() + '#' + name, k -> {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // walk up the hierarchy
                }
            }
            return null;
        });
    }
}
