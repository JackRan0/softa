package io.softa.starter.metadata.scanner;

import java.util.LinkedHashSet;
import java.util.Set;

import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;

/**
 * Boot identity guard: the app identity is immutable once materialized. Rows
 * stamped with an {@code app_code} different from the configured
 * {@code system.app-code} mean the catalog belongs to another app — most likely
 * the yml identity was changed without migrating, or the runtime points at the
 * wrong database.
 *
 * <p>{@code NULL} app_code is tolerated: pre-V8 rows are backfilled by
 * {@link SysJdbcWriter#backfillAppCode()} (dev) or the platform Plan/Apply
 * channel (prod).
 *
 * <p>Used two ways: the scanner {@linkplain #assertMatches asserts} (boot
 * failure); the checker {@linkplain #findForeign finds} and reports mismatches
 * as drift.
 */
public final class AppIdentityGuard {

    private AppIdentityGuard() {
    }

    /** Distinct non-null app codes on platform rows that differ from {@code configured}. */
    public static Set<String> findForeign(AnnotationScanResult platform, String configured) {
        Set<String> foreign = new LinkedHashSet<>();
        platform.models().forEach(e -> collect(foreign, e.getAppCode(), configured));
        platform.fields().forEach(e -> collect(foreign, e.getAppCode(), configured));
        platform.optionSets().forEach(e -> collect(foreign, e.getAppCode(), configured));
        platform.optionItems().forEach(e -> collect(foreign, e.getAppCode(), configured));
        platform.modelIndexes().forEach(e -> collect(foreign, e.getAppCode(), configured));
        return foreign;
    }

    /**
     * Scanner (write path) entry: refuse to reconcile against a catalog owned
     * by a different app.
     *
     * @throws IllegalStateException naming the foreign code(s) and the remediation
     */
    public static void assertMatches(AnnotationScanResult platform, String configured) {
        Set<String> foreign = findForeign(platform, configured);
        if (foreign.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "MetadataAnnotationScanner: sys_* rows carry app_code "
                        + foreign + " but system.app-code is '" + configured + "'. "
                        + "The app identity is immutable once materialized. "
                        + "If this runtime was intentionally re-identified, migrate first — for each sys_* table:\n"
                        + "  UPDATE <sys_table> SET app_code = '" + configured + "' WHERE app_code = '<old>';\n"
                        + "and align DesignApp.appCode on the Studio side. "
                        + "Otherwise check that spring.datasource points at the right database.");
    }

    private static void collect(Set<String> foreign, String rowCode, String configured) {
        if (rowCode != null && !rowCode.isBlank() && !rowCode.equals(configured)) {
            foreign.add(rowCode);
        }
    }
}
