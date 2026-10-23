package io.softa.starter.seed.manifest;

import java.util.List;
import java.util.Set;

/**
 * One seed file as the manifest registers it.
 *
 * @param file      file name inside its directory, e.g. {@code LeaveType.NZ.json} — unique across the manifest,
 *                  and what the load records name it by
 * @param level     who the rows belong to, read off the directory the file sits in
 * @param overwrite for a tenant file: run again whenever the file changes, overwriting the tenant's copy.
 *                  Platform files always reload, and never carry the flag
 * @param module    for a tenant file: the plan module a tenant must be entitled to; null for every plan
 * @param countries for a tenant file: the countries whose tenants run it; empty for every country
 */
public record SeedFile(String file, SeedLevel level, boolean overwrite, String module, List<String> countries) {

    public SeedFile {
        countries = List.copyOf(countries);
    }

    /** Whether a change to the file reaches a scope that already ran it: always for platform files. */
    public boolean reloadsWhenChanged() {
        return level.isPlatform() || overwrite;
    }

    /** Whether a tenant entitled to these modules runs the file; null modules — no plans in use — is every one. */
    public boolean appliesToModules(Set<String> modules) {
        return modules == null || module == null || modules.contains(module);
    }

    /** Whether the file is for a tenant in these countries: one that names none is for every tenant. */
    public boolean appliesToCountries(Set<String> tenantCountries) {
        return countries.isEmpty() || countries.stream().anyMatch(tenantCountries::contains);
    }

    /** Classpath location, e.g. {@code data-tenant/LeaveType.NZ.json}. */
    public String path() {
        return level.dataDir() + file;
    }
}
