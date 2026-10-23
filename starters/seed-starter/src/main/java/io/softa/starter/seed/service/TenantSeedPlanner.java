package io.softa.starter.seed.service;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.seed.manifest.SeedFile;

/**
 * Which tenants seed data reaches, and which tenant files each of them is due.
 *
 * <p>Tenants come from the framework's {@link TenantInfoService} and plans from {@link EntitlementService}, both
 * implemented by tenant-starter; without them there are no tenants to run, and every package is due. A
 * tenant's countries are its default country and those of its companies.
 */
@Component
public class TenantSeedPlanner {

    private final SeedCatalog catalog;
    private final ObjectProvider<TenantInfoService> tenantInfoService;
    private final ObjectProvider<EntitlementService> entitlementService;
    private final ModelService<?> modelService;

    public TenantSeedPlanner(SeedCatalog catalog, ObjectProvider<TenantInfoService> tenantInfoService,
                             ObjectProvider<EntitlementService> entitlementService, ModelService<?> modelService) {
        this.catalog = catalog;
        this.tenantInfoService = tenantInfoService;
        this.entitlementService = entitlementService;
        this.modelService = modelService;
    }

    /**
     * The tenants seed data reaches: those whose setup finished — active, suspended or closed. Never the
     * platform tenant (-1), which is planted active rather than set up: its own rows are the platform-tenant
     * files, loaded with the platform's, and a tenant file has no business there.
     */
    public List<Long> provisionedTenantIds() {
        TenantInfoService tenants = tenantInfoService.getIfAvailable();
        return tenants == null ? List.of() : tenants.getProvisionedTenantIds().stream()
                .filter(tenantId -> !BaseConstant.PLATFORM_TENANT_ID.equals(tenantId))
                .toList();
    }

    /** The tenant's name for the records, or null when unknown. */
    public String tenantName(Long tenantId) {
        TenantInfoService tenants = tenantInfoService.getIfAvailable();
        return tenants == null ? null : tenants.getTenantName(tenantId);
    }

    /** The tenant files the tenant is due, in load order: its plan's packages, for its countries. */
    public List<SeedFile> dueFiles(Long tenantId) {
        EntitlementService entitlement = entitlementService.getIfAvailable();
        Set<String> modules = entitlement == null ? null : entitlement.entitledModules(tenantId);
        return catalog.manifest().tenantFiles(modules, countriesOf(tenantId));
    }

    /** The tenant's default country and the countries of its companies. */
    Set<String> countriesOf(Long tenantId) {
        Set<String> countries = new LinkedHashSet<>();
        TenantInfoService tenants = tenantInfoService.getIfAvailable();
        if (tenants != null) {
            addCode(countries, tenants.getDefaultCountry(tenantId));
        }
        if (ModelManager.existModel(ModelConstant.COMPANY_MODEL)) {
            FlexQuery query = new FlexQuery(List.of(ModelConstant.COUNTRY_FIELD), new Filters());
            query.setDistinct(true);
            ContextUtils.inTenantContext(tenantId, () -> modelService.searchList(ModelConstant.COMPANY_MODEL, query)
                    .forEach(row -> addCode(countries, row.get(ModelConstant.COUNTRY_FIELD))));
        }
        return countries;
    }

    /** A country as a code: the value itself, or the id of a reference read as a map. */
    private static void addCode(Set<String> into, Object value) {
        Object code = value instanceof Map<?, ?> reference ? reference.get(ModelConstant.ID) : value;
        if (code instanceof Serializable && !Objects.toString(code, "").isBlank()) {
            into.add(code.toString().trim());
        }
    }
}
