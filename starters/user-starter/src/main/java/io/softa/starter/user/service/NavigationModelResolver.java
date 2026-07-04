package io.softa.starter.user.service;

import java.util.Collection;

import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.enums.NavigationType;

/**
 * Resolves a navigation's primary model code (PascalCase, matches
 * MetaModel.modelName / sys_model.model_name).
 *
 * Implementation reads {@code Navigation.model} field directly (no convention
 * derivation, no override JSON). Maintained as in-memory snapshot built at
 * startup; navigation rows are seed-only, redeploy to refresh.
 */
public interface NavigationModelResolver {

    /**
     * Returns the primary model code for the given navigation id, or null when
     * the nav is GROUP (no model) or a pure-container MENU (nav.model = null).
     */
    String resolvePrimaryModel(String navigationId);

    /**
     * Returns the full Navigation entity by id; null when not found.
     */
    Navigation findNavigation(String navigationId);

    /**
     * Read-only snapshot of every loaded {@link Navigation} row — used by
     * {@code PermissionRegistryValidator} so the validator doesn't issue
     * a fresh {@code searchList("Navigation", ...)} when this resolver
     * already holds the same rows in memory.
     */
    Collection<Navigation> allNavigations();

    /**
     * Quick helper — returns null for GROUP / pure-container MENU, the model otherwise.
     */
    static String modelOf(Navigation nav) {
        if (nav == null) return null;
        if (nav.getType() == NavigationType.GROUP) return null;
        return nav.getModel();
    }
}
