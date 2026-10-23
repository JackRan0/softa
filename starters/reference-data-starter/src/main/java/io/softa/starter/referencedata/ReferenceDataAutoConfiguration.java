package io.softa.starter.referencedata;

import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration entry point for the reference-data-starter.
 *
 * <p>Scans the {@code io.softa.starter.referencedata} package for
 * {@code @Service}, {@code @Component}, etc. beans. Activates the entity
 * registry and service layer for {@code CountryRegion}, {@code Currency},
 * and {@code CountrySubdivision}.
 *
 * <p>This starter is self-contained — it doesn't depend on application
 * config. Apps that include it automatically gain the platform-level
 * reference data services; the seed data is loaded by the platform admin
 * through seed-starter's {@code POST /SeedData/platform/load}, once the app
 * lists the files in its seed manifest.
 */
@ComponentScan
public class ReferenceDataAutoConfiguration {
}
