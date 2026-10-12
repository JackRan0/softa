package io.softa.framework.orm.scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * Narrows reads of a multi-country model — one whose rows are replicated per country, see
 * {@code @Model(multiCountry = true)} — to the countries the caller works in: those of the companies
 * their roles reach ({@code Context.grantedCountries}, bridged from the permission snapshot).
 *
 * <p>This used to narrow to the country of the company selected in a header switcher. The switcher is
 * gone; with nothing to select, the caller's own set is the answer to "which countries' values apply
 * to me", and a value domain seeded for six countries narrows to the one or two the caller's tenant
 * actually has companies in. Two companies in the same country share their value domains, so the set
 * is by country, not by company — and there is deliberately no per-company narrowing any more: which
 * companies' <i>records</i> a caller sees is the permission grant's job
 * ({@code PermissionServiceImpl.companyScope}), not a view.
 *
 * <p><b>Why this is applied around the permission filter rather than inside it.</b> Per-country
 * partitioning is data correctness, not authorization: a row belonging to another country is not
 * "data you may not see", it is data that does not apply. Folding it into
 * {@code PermissionService.appendScopeAccessFilters} would inherit that method's short-circuits —
 * it returns the caller's filters untouched for a bypassing or admin principal, for an {@code ALL}
 * scope rule, and (the case that matters most here) for a model with no explicit scope rule that
 * resolves to a shared reference/config target. A freshly converted value-domain model is exactly
 * that last case, so a condition added inside would never reach the models this mechanism exists
 * for. Wrapping the call covers every one of those paths.
 *
 * <p><b>It narrows a choice, never a lookup.</b> A caller that already names the rows it wants —
 * any filter mentioning {@code id} — is resolving values it holds, not choosing among candidates,
 * and gets them back untouched. Without this, expanding a stored value for display would break the
 * moment it came from a country outside the set: {@code XToOneGroupProcessor} resolves a
 * {@code ManyToOne} by issuing {@code searchList(relatedModel, id IN (…))}, and a pass type recorded
 * under a company the caller has since lost would render blank. Its {@code FilterControl.bypassAll()}
 * does not help — that only waives active-control and soft-delete. The permission filter avoids the
 * same trap by short-circuiting on {@code skipPermissionCheck}, which this deliberately does not do
 * (see above), so the distinction has to be drawn here instead.
 *
 * <p>The narrowing is also a <b>default, not a constraint</b>: a caller that already constrains the
 * country field keeps its own condition. That is what lets an employee form scope its dropdowns by
 * the country of the legal entity picked <i>in the form</i> (one country out of the caller's several),
 * and an import resolve a row's values against that row's own country. AND-ing instead would produce
 * {@code country IN ('SG','NZ') AND country = 'SG'} — correct here, but {@code country = 'MY'} for a
 * value legitimately recorded outside the set would be emptied, and the form's explicit choice is the
 * better-informed one either way.
 *
 * <p><b>Unknown means unnarrowed.</b> The set is {@code null} on a request that never consulted the
 * permission snapshot — anonymous and public endpoints, service-to-service calls, scheduler and MQ
 * threads, an import job — and empty for a caller whose roles reach no company. Both fall back to the
 * country of the company the caller belongs to when the request carries one
 * ({@code Context.companyCountry}, the self-service employee's case), and otherwise skip: narrowing to
 * nothing would empty a required dropdown instead, which looks like a data problem and is worse than an
 * unfiltered one. Callers on those paths that need a country pass it themselves.
 */
@Slf4j
public final class MultiCountryScope {

    private MultiCountryScope() {
    }

    /**
     * Append the per-country condition when {@code modelName} is multi-country and the request
     * knows its countries (or, failing that, the caller's own company country).
     *
     * @param modelName model being queried
     * @param filters   filters already assembled (permission scope included)
     * @return filters, narrowed by country where applicable
     */
    public static Filters append(String modelName, Filters filters) {
        // existModel first: getModel throws on an unknown name, and this sits on the generic
        // read path — an unknown model must fall through to the query that will report it,
        // not fail here with an unrelated error.
        if (modelName == null || !ModelManager.existModel(modelName)) {
            return filters;
        }
        MetaModel metaModel = ModelManager.getModel(modelName);
        if (!metaModel.isMultiCountry()) {
            return filters;
        }
        // Fixed by convention, asserted at init (ModelManager.validateMultiCountry) — nothing to
        // resolve or look up per model.
        String countryField = ModelConstant.COUNTRY_FIELD;
        List<String> countries = countriesInPlay(ContextHolder.getContext());
        if (countries.isEmpty()) {
            log.debug("No countries known for this request; model {} is not narrowed", modelName);
            return filters;
        }
        if (Filters.containsField(filters, ModelConstant.ID)) {
            // The caller named the rows — a display expansion, a by-id read, a cascade resolving
            // stored values. Narrowing those by country would hide data that legitimately belongs to
            // another one. Choosing among candidates never filters by id, so nothing that should be
            // narrowed is missed here.
            log.debug("Caller targets {} rows by id; not narrowing by country", modelName);
            return filters;
        }
        if (Filters.containsField(filters, countryField)) {
            log.debug("Caller already constrains {}.{}; keeping its condition", modelName, countryField);
            return filters;
        }
        return Filters.and(filters, Filters.of(countryField, Operator.IN, countries));
    }

    /**
     * The countries this request's value domains narrow to: the caller's own set when known and
     * non-empty, else the country of the company they belong to, else nothing. Sorted, so the same
     * set always compiles to the same SQL.
     */
    public static List<String> countriesInPlay(Context context) {
        Set<String> accessible = context.getGrantedCountries();
        if (accessible != null && !accessible.isEmpty()) {
            return new ArrayList<>(new TreeSet<>(accessible));
        }
        String own = context.getCompanyCountry();
        return StringUtils.isBlank(own) ? List.of() : List.of(own.trim());
    }
}
