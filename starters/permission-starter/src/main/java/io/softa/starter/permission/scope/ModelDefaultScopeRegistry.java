package io.softa.starter.permission.scope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.startup.StartupSnapshot;
import io.softa.starter.permission.entity.ModelDefaultScope;
import io.softa.starter.permission.spi.ScopeType;

/**
 * Which models declare a default row scope, and what it is.
 *
 * <p>A {@link StartupSnapshot}: one map over {@link ModelDefaultScope}, built at startup once the seed files the
 * build owns have loaded. The rows change only at startup, so the map never needs reloading.
 *
 * <p>A row naming no known {@link ScopeType} is dropped with a warning rather than failing the
 * request. The boot validator reports those, which is where a typo should surface; a request is the
 * wrong place to discover one, and treating it as "no declaration" leaves the model on the
 * fail-closed path — the safe reading of a value nobody can make sense of.
 */
@Slf4j
@Component
public class ModelDefaultScopeRegistry implements StartupSnapshot {

    private final ModelDefaultScopeReader reader;

    private volatile Map<String, ScopeType> byModel;

    public ModelDefaultScopeRegistry(ModelDefaultScopeReader reader) {
        this.reader = reader;
    }

    @Override
    public Set<String> sourceModels() {
        return Set.of(ModelDefaultScopeReader.MODEL);
    }

    @Override
    public void build() {
        List<Map<String, Object>> rows = reader.read();
        Map<String, ScopeType> loaded = HashMap.newHashMap(rows.size());
        for (Map<String, Object> row : rows) {
            String model = str(row.get("id"));
            ScopeType type = parse(str(row.get("scopeType")), model);
            if (model != null && type != null) {
                loaded.put(model, type);
            }
        }
        byModel = Map.copyOf(loaded);
    }

    /** The scope {@code modelName} falls back to, or {@code null} when it declares none. */
    public ScopeType scopeFor(String modelName) {
        return modelName == null ? null : all().get(modelName);
    }

    /** Every declaration, for the boot report. */
    public Map<String, ScopeType> all() {
        Map<String, ScopeType> built = byModel;
        if (built == null) {
            throw new IllegalStateException("ModelDefaultScopeRegistry is read before it is built: it is built at "
                    + "startup, once the seed files the build owns have loaded, and nothing may ask it before.");
        }
        return built;
    }

    private ScopeType parse(String code, String model) {
        if (StringUtils.isBlank(code)) {
            return null;
        }
        try {
            return ScopeType.valueOf(code.trim());
        } catch (IllegalArgumentException unknown) {
            log.warn("ModelDefaultScope row for {} names no known ScopeType ('{}'); ignoring it",
                    model, code);
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : StringUtils.trimToNull(value.toString());
    }
}
