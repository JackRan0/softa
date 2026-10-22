package io.softa.starter.file.support;

import java.util.List;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.scope.MultiCountryScope;
import io.softa.starter.file.entity.ImportTemplate;

/**
 * The one rule for which import templates a caller may see: their own countries' plus the ones with
 * no country, which apply everywhere.
 *
 * <p>Import templates are not on the country axis — a template with no country is the common case
 * (job grades, cost centres, departments have no country dimension), so the ORM's per-country
 * narrowing leaves the model alone. Every read that lists templates therefore states the rule itself:
 * the import dialog's picker, the administration list, the reference picker behind a Template field,
 * and the export of the list. Held here so they all state the same one.
 *
 * <p>Written as <b>country is unset OR country in (my countries)</b>, never a bare membership test —
 * that would empty every listing for every tenant, since most rows hold null. The set is the caller's
 * own countries, resolved the same way {@code MultiCountryScope} resolves them; when nothing is known
 * the filter is skipped rather than tightened. Resolved from the request context, never from the
 * payload. "Unset" covers an empty string as well as null: a row saved with a cleared country means
 * every country just as much as one never given one.
 */
public final class ImportTemplateCountryScope {

    private ImportTemplateCountryScope() {
    }

    /** The scope alone, or null when the caller's countries are unknown. */
    public static Filters countryScope() {
        List<String> countries = MultiCountryScope.countriesInPlay(ContextHolder.getContext());
        if (countries.isEmpty()) {
            return null;
        }
        return new Filters().add(ImportTemplate::getCountry, Operator.IS_NOT_SET, null)
                .or(new Filters().eq(ImportTemplate::getCountry, ""))
                .or(new Filters().in(ImportTemplate::getCountry, countries));
    }

    /** The caller's filters AND the scope; the filters alone when there is nothing to narrow by. */
    public static Filters withCountryScope(Filters filters) {
        Filters scope = countryScope();
        if (scope == null) {
            return filters;
        }
        return Filters.isEmpty(filters) ? scope : Filters.and(filters, scope);
    }

    /** {@link #withCountryScope} for reads that name their model: a no-op for every other model. */
    public static Filters forModel(String modelName, Filters filters) {
        return ImportTemplate.class.getSimpleName().equals(modelName) ? withCountryScope(filters) : filters;
    }
}
