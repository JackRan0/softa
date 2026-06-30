package io.softa.starter.user.service.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.service.NavigationModelResolver;

/**
 * In-memory snapshot of the navigation table. Built once at startup from the
 * deploy-time {@code navigation.json} seed; no runtime reload — the table is
 * system-level data that only changes via redeployment, so application restart
 * IS the reload trigger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavigationModelResolverImpl implements NavigationModelResolver {

    private final ModelService<?> modelService;

    private volatile Map<String, Navigation> snapshot = Map.of();

    @PostConstruct
    void init() {
        Map<String, Navigation> fresh = new HashMap<>();
        for (Navigation nav : modelService.searchList("Navigation", new FlexQuery(), Navigation.class)) {
            fresh.put(nav.getId(), nav);
        }
        this.snapshot = Map.copyOf(fresh);
        log.info("NavigationModelResolver loaded {} navigations", fresh.size());
    }

    @Override
    public String resolvePrimaryModel(String navigationId) {
        return NavigationModelResolver.modelOf(snapshot.get(navigationId));
    }

    @Override
    public Navigation findNavigation(String navigationId) {
        return snapshot.get(navigationId);
    }

    @Override
    public Collection<Navigation> allNavigations() {
        // snapshot is built via Map.copyOf in init() — already immutable.
        return snapshot.values();
    }
}
