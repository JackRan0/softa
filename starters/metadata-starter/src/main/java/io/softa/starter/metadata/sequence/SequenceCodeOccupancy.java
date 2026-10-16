package io.softa.starter.metadata.sequence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

/**
 * Which generated codes are already in use in the field a sequence fills, ignoring case.
 *
 * <p>A code can be taken before the counter reaches it: the field accepts a value typed by hand, and
 * a hand-typed {@code ZK1000030} sits waiting for the counter to arrive. Generating it again would
 * fail the insert on the unique key, or — where the database compares case-sensitively — slip in a
 * second code that differs from the first only in case. The allocator asks here and skips past.
 *
 * <p>Matching is case-insensitive on every database. The candidates are fetched with
 * {@code START_WITH} (PostgreSQL {@code ILIKE}, MySQL {@code LIKE} under a case-insensitive collation)
 * and compared exactly in memory afterwards: a prefix match over-fetches — and an {@code _} in a code
 * is a LIKE wildcard that over-fetches further — but the exact comparison removes both.
 *
 * <p>The lookup sees every row the unique key does: permission checks, the active and soft-delete
 * filters and the country narrowing are all waived, since each would hide a row whose code is still
 * taken. Tenant isolation applies as for any read.
 */
@Component
public class SequenceCodeOccupancy {

    /** Bounds the size of one OR of prefix conditions. */
    private static final int CHUNK = 200;

    private final ModelService<Serializable> modelService;

    public SequenceCodeOccupancy(@Lazy ModelService<Serializable> modelService) {
        this.modelService = modelService;
    }

    /**
     * @param code       the sequence code, {@code "<ModelName>.<fieldName>"}
     * @param candidates generated codes about to be used
     * @return the candidates already present in that field, lower-cased; empty when the code does not
     *         name a field of a known model, so a sequence without a backing field is not checked
     */
    public Set<String> taken(String code, Collection<String> candidates) {
        int dot = code == null ? -1 : code.lastIndexOf('.');
        if (dot <= 0 || candidates.isEmpty()) {
            return Set.of();
        }
        String model = code.substring(0, dot);
        String field = code.substring(dot + 1);
        if (!ModelManager.existModel(model) || ModelManager.getModelFieldOrNull(model, field) == null) {
            return Set.of();
        }
        Set<String> wanted = new HashSet<>();
        Set<String> distinct = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                wanted.add(normalize(candidate));
                distinct.add(candidate);
            }
        }
        Set<String> taken = new HashSet<>();
        List<String> pending = new ArrayList<>(distinct);
        for (int from = 0; from < pending.size(); from += CHUNK) {
            List<String> chunk = pending.subList(from, Math.min(from + CHUNK, pending.size()));
            for (Map<String, Object> row : read(model, field, chunk)) {
                if (row.get(field) instanceof CharSequence existing) {
                    String key = normalize(existing.toString());
                    if (wanted.contains(key)) {
                        taken.add(key);
                    }
                }
            }
        }
        return taken;
    }

    /** The comparison key: codes that differ only in case are the same code. */
    public static String normalize(String code) {
        return code.toLowerCase(Locale.ROOT);
    }

    private List<Map<String, Object>> read(String model, String field, List<String> chunk) {
        Filters filters;
        if (chunk.size() == 1) {
            filters = Filters.of(field, Operator.START_WITH, chunk.getFirst());
        } else {
            Filters[] rest = chunk.subList(2, chunk.size()).stream()
                    .map(value -> Filters.of(field, Operator.START_WITH, value))
                    .toArray(Filters[]::new);
            filters = Filters.or(Filters.of(field, Operator.START_WITH, chunk.get(0)),
                    Filters.of(field, Operator.START_WITH, chunk.get(1)), rest);
        }
        FlexQuery query = new FlexQuery(List.of(field), filters);
        query.setFilterControl(FilterControl.bypassAll());
        Context system = ContextHolder.getContext().copy();
        system.setSkipPermissionCheck(true);
        // The country narrowing ignores skipPermissionCheck by design and reads these two; a system
        // check belongs to no country.
        system.setGrantedCountries(null);
        system.setCompanyCountry(null);
        return ContextHolder.callWith(system, () -> modelService.searchList(model, query));
    }
}
