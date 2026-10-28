package io.softa.starter.user.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.startup.StartupSnapshot;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.service.NavigationModelResolver;

/**
 * In-memory snapshot of the navigation table — a {@link StartupSnapshot}, built once at startup after the seed
 * files the build owns have loaded. Navigation rows change only at startup, so it never needs reloading.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavigationModelResolverImpl implements NavigationModelResolver, StartupSnapshot {

    private final ModelService<?> modelService;

    /** Navigation id → row; null until built. */
    private volatile Map<String, Navigation> snapshot;

    @Override
    public Set<String> sourceModels() {
        return Set.of("Navigation");
    }

    @Override
    public void build() {
        Map<String, Navigation> fresh = new HashMap<>();
        for (Navigation nav : modelService.searchList("Navigation", new FlexQuery(), Navigation.class)) {
            fresh.put(nav.getId(), nav);
        }
        this.snapshot = Map.copyOf(fresh);
        log.info("NavigationModelResolver loaded {} navigations", fresh.size());
    }

    private Map<String, Navigation> built() {
        Map<String, Navigation> built = snapshot;
        if (built == null) {
            throw new IllegalStateException("NavigationModelResolver is read before it is built: it is built at "
                    + "startup, once the seed files the build owns have loaded, and nothing may ask it before.");
        }
        return built;
    }

    @Override
    public String resolvePrimaryModel(String navigationId) {
        return NavigationModelResolver.modelOf(built().get(navigationId));
    }

    @Override
    public Navigation findNavigation(String navigationId) {
        return built().get(navigationId);
    }

    @Override
    public Collection<Navigation> allNavigations() {
        // The snapshot is built via Map.copyOf — already immutable.
        return built().values();
    }
}
