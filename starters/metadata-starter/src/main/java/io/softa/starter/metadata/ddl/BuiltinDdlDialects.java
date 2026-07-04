package io.softa.starter.metadata.ddl;

import java.util.List;

import io.softa.starter.metadata.ddl.dialect.DdlDialectRegistry;
import io.softa.starter.metadata.ddl.dialect.MySqlDdlDialect;
import io.softa.starter.metadata.ddl.dialect.PostgreSqlDdlDialect;
import io.softa.starter.metadata.ddl.spi.BuiltinDdlMetadataResolver;

/**
 * Builds a {@link DdlDialectRegistry} whose dialects resolve column types from
 * compile-time annotation knowledge ({@link BuiltinDdlMetadataResolver}) only.
 *
 * <p>Used by the annotation-sourced DDL paths — the boot {@code MetadataAnnotationScanner} and the
 * studio connector publish to a Softa runtime — which must <b>not</b> inject the shared
 * {@code DdlDialectRegistry} bean: with {@code studio-starter} on the classpath that bean resolves types
 * through the {@code design_*} tables (via {@code ModelManager}), the design workspace, not the running
 * binary's annotations. Annotation reconciliation must be driven by the annotations themselves, so these
 * paths render on the builtin resolver instead.
 */
public final class BuiltinDdlDialects {

    private BuiltinDdlDialects() {
    }

    public static DdlDialectRegistry registry() {
        return new DdlDialectRegistry(List.of(
                new MySqlDdlDialect(new BuiltinDdlMetadataResolver()),
                new PostgreSqlDdlDialect(new BuiltinDdlMetadataResolver())));
    }
}
