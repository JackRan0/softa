package io.softa.starter.file.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.file.support.ImportTemplateCountryScope;

/**
 * What the template listing narrows by: the countries the caller works in.
 *
 * <p>The rule pinned here is the one that would be expensive to get wrong: a template with no country
 * applies to every country, and almost every template is that kind — job grades, cost centres,
 * departments have no country dimension at all. Narrow with a bare membership test and the listing
 * comes back empty for every tenant on the release that adds the column, because that is exactly the
 * moment every existing row holds null.
 */
class ImportTemplateCountryScopeTest {

    private final ImportTemplateController controller = new ImportTemplateController();

    private Filters scopeFor(Set<String> countries, String ownCountry) {
        Context context = new Context();
        context.setGrantedCountries(countries);
        context.setCompanyCountry(ownCountry);
        return ContextHolder.callWith(context, controller::countryScope);
    }

    @Test
    void aTemplateWithNoCountryIsListedAlongsideTheCallersCountries() {
        // Both halves matter. Without the first, an SG + NZ user loses every country-less template —
        // which is 94% of them. Without the second, they also see Malaysia's.
        assertThat(scopeFor(Set.of("SG", "NZ"), null))
                .hasToString("[[\"country\",\"IS NOT SET\",null],\"OR\",[\"country\",\"=\",\"\"],\"OR\",[\"country\",\"IN\",[\"NZ\",\"SG\"]]]");
    }

    @Test
    void aBlankCountryCountsAsNoCountry() {
        // A country cleared in a form is saved as an empty string, and means "every country" just as
        // much as null does — it must not disappear from everyone's list.
        assertThat(scopeFor(Set.of("SG"), null).toString()).contains("[\"country\",\"=\",\"\"]");
    }

    @Test
    void anEmptySetFallsBackToTheCallersOwnCountry() {
        // A self-service employee whose roles reach no company still imports for their own country.
        assertThat(scopeFor(Set.of(), "SG"))
                .hasToString("[[\"country\",\"IS NOT SET\",null],\"OR\",[\"country\",\"=\",\"\"],\"OR\",[\"country\",\"IN\",[\"SG\"]]]");
    }

    @Test
    void nothingKnownMeansNoNarrowingAtAll() {
        // Not "narrow to the templates with no country" — a request that consulted no snapshot has
        // nothing to narrow by, and showing every template beats showing none.
        assertThat(scopeFor(null, null)).isNull();
        assertThat(scopeFor(Set.of(), "")).isNull();
    }

    // ─────────────────────── the list endpoints ───────────────────────

    private Filters scopedFor(Set<String> countries, Filters callers) {
        Context context = new Context();
        context.setGrantedCountries(countries);
        return ContextHolder.callWith(context, () -> controller.withCountryScope(callers));
    }

    @Test
    void theCallersOwnFiltersAreKeptAndTheScopeIsAndedOn() {
        // The Admin list page goes through the generic search endpoints, not listByModel; a filter
        // the user set in the toolbar must survive, and the country scope must apply on top of it.
        Filters mine = new Filters().eq("modelName", "Employee");

        assertThat(scopedFor(Set.of("SG"), mine))
                .hasToString("[[\"modelName\",\"=\",\"Employee\"],\"AND\",[[\"country\",\"IS NOT SET\",null],\"OR\",[\"country\",\"=\",\"\"],\"OR\",[\"country\",\"IN\",[\"SG\"]]]]");
    }

    @Test
    void noFiltersFromTheCallerMeansTheScopeAlone() {
        assertThat(scopedFor(Set.of("SG"), null))
                .hasToString("[[\"country\",\"IS NOT SET\",null],\"OR\",[\"country\",\"=\",\"\"],\"OR\",[\"country\",\"IN\",[\"SG\"]]]");
        assertThat(scopedFor(Set.of("SG"), new Filters()))
                .hasToString("[[\"country\",\"IS NOT SET\",null],\"OR\",[\"country\",\"=\",\"\"],\"OR\",[\"country\",\"IN\",[\"SG\"]]]");
    }

    @Test
    void nothingToNarrowByLeavesTheCallersFiltersUntouched() {
        Filters mine = new Filters().add("country", Operator.EQUAL, "NZ");

        assertThat(scopedFor(null, mine)).isSameAs(mine);
        assertThat(scopedFor(null, null)).isNull();
    }

    @Test
    void theExportHookOnlyTouchesImportTemplates() {
        // Every other model exported through the same endpoint keeps its filters exactly as sent.
        Filters mine = new Filters().eq("active", true);
        Context context = new Context();
        context.setGrantedCountries(Set.of("SG"));

        assertThat(ContextHolder.callWith(context, () -> ImportTemplateCountryScope.forModel("Employee", mine))).isSameAs(mine);
        assertThat(ContextHolder.callWith(context, () -> ImportTemplateCountryScope.forModel("ImportTemplate", null)))
                .hasToString("[[\"country\",\"IS NOT SET\",null],\"OR\",[\"country\",\"=\",\"\"],\"OR\",[\"country\",\"IN\",[\"SG\"]]]");
    }
}
