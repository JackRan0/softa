package io.softa.starter.metadata.scanner.checker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.softa.starter.metadata.config.MetadataProperties;
import io.softa.starter.metadata.ddl.ReferenceColumnResolver;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.scanner.MetadataReadPipeline;
import io.softa.starter.metadata.scanner.ScannerScope;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;
import io.softa.starter.metadata.scanner.diff.SysKeys;

/**
 * Post-boot, asynchronous drift detector. The bean is always registered but
 * only does work when {@code system.metadata.scanner-scope} is empty / unset —
 * i.e. when no
 * {@link io.softa.starter.metadata.scanner.MetadataAnnotationScanner} is
 * reconciling anything (the safe production default). Under any non-empty scope
 * the scanner owns reconciliation and this checker stays silent.
 *
 * <p>When active it scans the classpath after {@link ApplicationReadyEvent},
 * compares the annotation-derived metadata against the {@code sys_*} rows, and
 * logs any drift as WARN.
 *
 * <p>Production deployments must <b>not</b> mutate {@code sys_*} or DDL
 * silently. The checker is read-only — it never writes — and runs on a
 * virtual-thread executor so it cannot block the application start sequence.
 */
@Slf4j
@Component
public class MetadataAnnotationChecker {

    private final MetadataReadPipeline pipeline;
    private final RelationShapeLinter relationLinter;
    private final MetadataProperties properties;

    public MetadataAnnotationChecker(MetadataReadPipeline pipeline,
                                     RelationShapeLinter relationLinter,
                                     MetadataProperties properties) {
        this.pipeline = pipeline;
        this.relationLinter = relationLinter;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Run on a virtual thread so a slow scan / DB read can never block
        // application start or the main event-listener pipeline. Fire-and-forget
        // (no executor to leak — this is a one-shot post-boot check).
        Thread.ofVirtual().name("metadata-drift-checker").start(this::checkSafely);
    }

    private void checkSafely() {
        try {
            check();
        } catch (Exception e) {
            log.warn("MetadataAnnotationChecker failed; metadata drift detection skipped", e);
        }
    }

    void check() {
        if (!ScannerScope.of(properties.scannerScope()).isEmpty()) {
            log.debug("MetadataAnnotationChecker: scanner-scope is active; drift checker disabled "
                    + "(the scanner owns reconciliation for the configured packages)");
            return;
        }
        log.debug("MetadataAnnotationChecker: starting drift scan");

        Set<Class<?>> modelClasses = pipeline.discoverModelClasses();
        Set<Class<?>> optionSetEnums = pipeline.discoverOptionSetEnums();

        relationLinter.warnUnannotatedRelations(modelClasses);

        AnnotationScanResult fromCode = pipeline.parse(modelClasses, optionSetEnums);
        AnnotationScanResult current = pipeline.loadCurrentStateLenient();

        // Mirror the scanner: stamp every TO_ONE FK's referenced column onto
        // relatedFieldType + length/scale before diffing. The stored rows carry the
        // stamped values, so an unstamped from-code side would report every FK as
        // perpetual "attrs differ" drift. Universe = stored rows first, code last
        // (code wins), so an FK onto a code-absent model still mirrors its stored column.
        List<SysField> referenceUniverse = new ArrayList<>(current.fields());
        referenceUniverse.addAll(fromCode.fields());
        ReferenceColumnResolver.stampSysFields(fromCode.fields(), referenceUniverse);

        SchemaDiff diff = pipeline.diff(fromCode, current);
        if (diff.isEmpty()) {
            log.info("MetadataAnnotationChecker: no annotation/metadata drift");
            return;
        }
        warnDiff(diff);
    }

    private void warnDiff(SchemaDiff diff) {
        log.warn("MetadataAnnotationChecker: detected metadata drift between code and DB:");
        warnEntity("SysModel", diff.models(), SysKeys::of);
        warnEntity("SysField", diff.fields(), SysKeys::of);
        warnEntity("SysOptionSet", diff.optionSets(), SysKeys::of);
        warnEntity("SysOptionItem", diff.optionItems(), SysKeys::of);
        warnEntity("SysModelIndex", diff.modelIndexes(), SysKeys::of);
        log.warn("To resolve: either (a) apply the missing DDL/migration to the "
                + "database manually, or (b) add the package to "
                + "system.metadata.scanner-scope and restart to let the scanner reconcile.");
    }

    /**
     * Logs each {@code added} / {@code removed} / {@code modified} entry by its
     * natural key, so the operator can locate the specific drift without
     * running follow-up SQL.
     */
    private static <T> void warnEntity(String name, SchemaDiff.EntityDiff<T> entityDiff,
                                       Function<T, String> keyOf) {
        if (entityDiff.isEmpty()) {
            return;
        }
        log.warn("  {} (+{} -{} ~{}):",
                name,
                entityDiff.added().size(),
                entityDiff.removed().size(),
                entityDiff.modified().size());
        for (T item : entityDiff.added()) {
            log.warn("    + {}  (in code, missing in DB)", keyOf.apply(item));
        }
        for (T item : entityDiff.removed()) {
            log.warn("    - {}  (in DB, missing in code)", keyOf.apply(item));
        }
        for (SchemaDiff.Modification<T> mod : entityDiff.modified()) {
            log.warn("    ~ {}  (attrs differ)", keyOf.apply(mod.fromCode()));
        }
    }
}
