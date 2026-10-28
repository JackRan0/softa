package io.softa.starter.metadata.scanner;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.jdbc.database.DbSessionLock;
import io.softa.framework.orm.meta.MetadataInitializer;
import io.softa.starter.metadata.catalog.SysCatalog;
import io.softa.starter.metadata.checksum.CatalogFingerprint;
import io.softa.starter.metadata.config.MetadataProperties;
import io.softa.starter.metadata.ddl.DdlOrchestrator;
import io.softa.starter.metadata.ddl.ReferenceColumnResolver;
import io.softa.starter.metadata.ddl.SysDdlContextBuilder;
import io.softa.starter.metadata.ddl.introspect.PhysicalDriftAuditor;
import io.softa.starter.metadata.ddl.introspect.PhysicalDriftReport;
import io.softa.starter.metadata.ddl.introspect.PhysicalSchema;
import io.softa.starter.metadata.ddl.spi.BuiltinDdlMetadataResolver;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.entity.SysOptionSet;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;
import io.softa.starter.metadata.scanner.checker.MetadataAnnotationChecker;
import io.softa.starter.metadata.scanner.diff.DiffEngine;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;

/**
 * Boot-time pre-initializer that reconciles annotation-derived metadata with
 * the {@code sys_*} catalog, confined to the packages named by
 * {@code system.metadata.scanner-scope}.
 *
 * <p>The bean is always registered; it gates itself at runtime on the scope
 * ({@link ScannerScope}):
 * <ul>
 *   <li>empty / unset scope ⇒ no-op (the safe production default — the
 *       {@link MetadataAnnotationChecker}
 *       observes drift read-only instead);</li>
 *   <li>{@code ["*"]} ⇒ reconcile every package;</li>
 *   <li>a partial list ⇒ reconcile only in-scope models/option-sets. Rows
 *       outside the scope are never read into the diff, so they are never
 *       updated or deleted — this is what lets multiple developers share one
 *       dev database without clobbering each other's rows. (Caveats: scope is per-package not per-class, the baseline is the
 *       shared live {@code sys_*}, and physical-table collisions are not
 *       addressed.)</li>
 * </ul>
 *
 * <p><b>Aggregate roots are never auto-deleted, under any scope.</b> A {@code SysModel} /
 * {@code SysOptionSet} row with no Java class — plus its fields, indexes and option items,
 * which follow their root — is confined out of the diff rather than removed
 * ({@link ScannerScope#confineFromDb}); {@code ["*"]} additionally names them in a WARN with
 * copy-paste SQL. Attribute rows of a root that IS in code are reconciled normally, deletions
 * included: there the annotations own the attribute set.
 *
 * <p>Flow:
 * <ol>
 *   <li>Scan the classpath for all {@code @Model} / {@code @OptionSet} declarations.</li>
 *   <li>Keep those whose package matches the scope; additionally pull in any
 *       {@code @OptionSet} enum referenced by an in-scope model's field
 *       (transitive inclusion) so a shared enum in another package is never
 *       orphaned.</li>
 *   <li>Parse them into an {@link AnnotationScanResult} (the from-code state).</li>
 *   <li>Load the current {@code sys_*} rows via {@link SysJdbcLoader} and
 *       confine them to the in-scope key set ({@link ScannerScope#confineFromDb}).</li>
 *   <li>Diff via {@link DiffEngine}; converge the physical schema via
 *       {@link DdlOrchestrator#converge} <b>first</b> — annotations against the
 *       introspected facts, so hand-made drift heals alongside declared changes, with
 *       the diff contributing renames and attribute deltas (introspection failure
 *       degrades to conservative metadata-only planning) — then write the {@code sys_*}
 *       rows via {@link SysJdbcWriter}: a failed DDL leaves the rows unwritten so the
 *       next boot retries (re-applied DDL degrades to WARN on duplicates).</li>
 * </ol>
 *
 * <p>This scanner uses {@code JdbcTemplate} directly, never
 * {@code SysModelService} / {@code ModelManager}. That keeps the
 * {@code SysModel}-describes-{@code SysModel} self-reference acyclic—
 * annotations describe schema, scanner writes hard-coded SQL.
 */
@Slf4j
@Component
public class MetadataAnnotationScanner implements MetadataInitializer {

    /** The boot DDL window's lock key: {@code softa:boot-ddl:<database>}. */
    private static final String BOOT_DDL_LOCK = "boot-ddl";

    private final MetadataReadPipeline pipeline;
    private final MetadataProperties properties;
    private final String appCode;
    private final SysJdbcWriter writer;
    private final DdlOrchestrator ddlOrchestrator;
    /** For the {@link DbSessionLock} only; {@code null} in the test seam ⇒ lockless. */
    private final DataSource lockDataSource;
    private final String datasourceUrl;

    @Autowired
    public MetadataAnnotationScanner(
            MetadataReadPipeline pipeline,
            MetadataProperties properties,
            SystemConfig systemConfig,
            JdbcTemplate jdbcTemplate,
            @Value("${spring.datasource.url:}") String datasourceUrl) {
        this(pipeline, properties, systemConfig.getAppCode(),
                new SysJdbcWriter(jdbcTemplate, systemConfig.getAppCode(), datasourceUrl),
                new DdlOrchestrator(jdbcTemplate, BuiltinDdlMetadataResolver.INSTANCE, datasourceUrl),
                jdbcTemplate.getDataSource(), datasourceUrl);
    }

    /**
     * Test seam: inject pre-built write-side collaborators directly, so the
     * DDL-before-rows ordering of {@link #initialize()} can be asserted with
     * mocks. Lockless (no {@link DataSource}).
     */
    MetadataAnnotationScanner(
            MetadataReadPipeline pipeline,
            MetadataProperties properties,
            String appCode,
            SysJdbcWriter writer,
            DdlOrchestrator ddlOrchestrator) {
        this(pipeline, properties, appCode, writer, ddlOrchestrator, null, null);
    }

    MetadataAnnotationScanner(
            MetadataReadPipeline pipeline,
            MetadataProperties properties,
            String appCode,
            SysJdbcWriter writer,
            DdlOrchestrator ddlOrchestrator,
            DataSource lockDataSource,
            String datasourceUrl) {
        this.pipeline = pipeline;
        this.properties = properties;
        this.appCode = appCode;
        this.writer = writer;
        this.ddlOrchestrator = ddlOrchestrator;
        this.lockDataSource = lockDataSource;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void initialize() {
        // App identity is mandatory whenever metadata-starter is active —
        // every sys_* row must be attributable. Fail fast in every
        // mode (scanner AND checker/prod) before any catalog access.
        if (appCode == null || appCode.isBlank()) {
            throw new IllegalStateException(
                    """
                            system.app-code is not configured. metadata-starter requires a stable app identity\
                            : add e.g.
                              system:
                                app-code: my-app
                            to application.yml (format [a-z][a-z0-9-]{0,63}).""");
        }

        ScannerScope scope = ScannerScope.of(properties.scannerScope());
        if (scope.isEmpty()) {
            log.info("MetadataAnnotationScanner: scanner-scope empty; reconciler disabled (manage nothing)");
            return;
        }
        // Everything past this point may execute DDL and write sys_* rows — one boot at a
        // time across instances (DbSessionLock, 60s wait budget; null = lockless: test seam,
        // or a dialect without session locks).
        try (DbSessionLock ignored = DbSessionLock.acquire(lockDataSource, datasourceUrl, BOOT_DDL_LOCK, 60)) {
            // Stage A: the catalog tables themselves, physically converged from their own
            // annotations BEFORE the strict catalog read below — this is what lets a fresh
            // database bootstrap without baseline DDL and an existing one absorb additive
            // catalog-schema changes without a hand-written migration (the old
            // chicken-and-egg: the read SELECTs every entity column and used to run before
            // any DDL could help).
            AnnotationScanResult catalogFromCode = parseCatalogEntities();
            reconcileCatalogTables(catalogFromCode);
            reconcileInScope(scope, catalogFromCode);
        }
    }

    private AnnotationScanResult parseCatalogEntities() {
        AnnotationScanResult catalog = pipeline.parse(SysCatalog.BOOT_READ_ENTITIES, List.of());
        // TO_ONE FKs among the catalog entities resolve within the set itself.
        ReferenceColumnResolver.stampSysFields(catalog.fields(), catalog.fields());
        return catalog;
    }

    /**
     * Stage A. Introspection failure skips the reconcile rather than failing the boot —
     * on a healthy schema nothing is lost, and on a broken one the strict read below
     * remains the gate (with the drift audit reporting what it can).
     */
    private void reconcileCatalogTables(AnnotationScanResult catalogFromCode) {
        PhysicalSchema facts = ddlOrchestrator.introspect(catalogFromCode.models());
        if (facts == null) {
            log.warn("MetadataAnnotationScanner: catalog physical reconcile skipped — introspection "
                    + "unavailable; the strict catalog read remains the gate");
            return;
        }
        ddlOrchestrator.reconcilePhysical(catalogFromCode.models(), catalogFromCode.fields(),
                catalogFromCode.modelIndexes(), facts);
    }

    /** The diff-driven reconciliation of in-scope business models (the pre-existing lane). */
    private void reconcileInScope(ScannerScope scope, AnnotationScanResult catalogFromCode) {
        log.info("MetadataAnnotationScanner: scanner-scope active (matchAll={}), scanning classpath...",
                scope.matchesAll());

        Set<Class<?>> allModelClasses = pipeline.discoverModelClasses();
        Set<Class<?>> allOptionSetEnums = pipeline.discoverOptionSetEnums();

        List<Class<?>> inScopeModels = allModelClasses.stream()
                .filter(c -> scope.matches(c.getPackageName()))
                .toList();

        // Transitive inclusion: option-set enums whose package is in scope, plus
        // any referenced by an in-scope model's field — so a shared enum in an
        // out-of-scope package is never orphaned or deleted.
        // Parse in-scope models once; reused below for fromCode (avoids a second parse).
        AnnotationScanResult modelsResult = pipeline.parse(inScopeModels, List.of());
        Set<String> referencedCodes = modelsResult.fields().stream()
                .map(SysField::getOptionSetCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Class<?>> inScopeEnums = scope.selectOptionSetEnums(allOptionSetEnums, referencedCodes);

        log.info("MetadataAnnotationScanner: {} in-scope @Model class(es), {} in-scope @OptionSet enum(s) "
                        + "(of {} / {} on classpath)",
                inScopeModels.size(), inScopeEnums.size(),
                allModelClasses.size(), allOptionSetEnums.size());

        if (inScopeModels.isEmpty() && inScopeEnums.isEmpty()) {
            log.info("MetadataAnnotationScanner: no in-scope @Model/@OptionSet for the configured scope; "
                    + "nothing to reconcile");
            return;
        }

        // Parse the in-scope option-sets and merge with the already-parsed models.
        AnnotationScanResult enumsResult = pipeline.parse(List.of(), inScopeEnums);
        // The merge must carry modelsResult's rename declarations forward: the 5-arg
        // constructor substitutes RenameDeclarations.empty(), which silently turns every
        // declared rename into ADD + warn-only DROP — a data divorce the parse-time
        // guards do nothing to prevent (they fire during parse and validate only).
        AnnotationScanResult fromCode = new AnnotationScanResult(
                modelsResult.models(), modelsResult.fields(),
                enumsResult.optionSets(), enumsResult.optionItems(),
                modelsResult.modelIndexes(), modelsResult.renames());
        log.info("MetadataAnnotationScanner: from-code = {} model(s), {} field(s), {} option set(s), "
                        + "{} option item(s), {} index(es)",
                fromCode.models().size(), fromCode.fields().size(),
                fromCode.optionSets().size(), fromCode.optionItems().size(),
                fromCode.modelIndexes().size());

        Set<String> inScopeModelNames = new HashSet<>();
        fromCode.models().forEach(m -> inScopeModelNames.add(m.getModelName()));
        // A declared model rename claims its prior name: the old row (and its field /
        // index rows, which the DiffEngine cascade re-keys) must survive confinement,
        // or the pairing side of the rename is filtered away before the diff ever sees
        // it — the model would CREATE empty instead of RENAME, divorcing the data. It
        // is also not a code-less root: the renaming class is its code.
        inScopeModelNames.addAll(fromCode.renames().modelOldNames().values());
        Set<String> inScopeOptionCodes = fromCode.optionSets().stream()
                .map(SysOptionSet::getOptionSetCode).collect(Collectors.toSet());

        AnnotationScanResult current = pipeline.loadCurrentState();

        // Mirror every TO_ONE FK's referenced column onto relatedFieldType + length/scale before the
        // diff, so the physical type is stored (diffable) and a referenced-column change re-stamps its
        // dependents → MODIFY. Resolve against the in-scope code fields AND the full platform catalog
        // (in-scope code last, so it wins): under a partial scanner-scope an in-scope FK may point at
        // an out-of-scope model — sourcing the referenced column from the DB avoids resetting
        // relatedFieldType to null and churning a spurious MODIFY.
        List<SysField> referenceUniverse = new ArrayList<>(current.fields());
        referenceUniverse.addAll(fromCode.fields());
        ReferenceColumnResolver.stampSysFields(fromCode.fields(), referenceUniverse);

        // Identity is immutable once materialized: rows stamped with a different
        // app_code mean this catalog belongs to another app (or the yml identity
        // was changed without migrating). Refuse to reconcile.
        AppIdentityGuard.assertMatches(current, appCode);

        AnnotationScanResult fromDb = scope.confineFromDb(
                current, inScopeModelNames, inScopeOptionCodes);
        log.info("MetadataAnnotationScanner: from-db (in-scope) = {} model(s), {} field(s), "
                        + "{} option set(s), {} option item(s)",
                fromDb.models().size(), fromDb.fields().size(),
                fromDb.optionSets().size(), fromDb.optionItems().size());
        warnCodelessRoots(scope, current, inScopeModelNames, inScopeOptionCodes);

        // The row diff (code vs sys_*) is computed first: the convergence planner consumes
        // its rename pairings, attribute modifications and index-definition changes — the
        // deltas physical introspection cannot see.
        SchemaDiff diff = pipeline.diff(fromCode, fromDb);
        if (diff.isEmpty()) {
            log.info("MetadataAnnotationScanner: no metadata changes detected (idempotent catalog)");
        } else {
            for (String summary : writer.changeSummary(diff)) {
                log.info("MetadataAnnotationScanner: diff {}", summary);
            }
        }

        // One physical snapshot serves the convergence planning AND the residual drift
        // audit (null when introspection failed — the boot then degrades to conservative
        // metadata-only DDL planning). The catalog tables audit alongside the in-scope
        // business tables: under a narrow scope they are not in fromCode, yet their drift
        // belongs in the same report — Stage A deliberately emits no warn-units for those
        // states. Under ["*"] they are already in fromCode; the merge dedups by model name.
        AnnotationScanResult audited = withCatalogEntities(fromCode, catalogFromCode);
        List<SysModel> snapshotUniverse = withPriorTables(audited.models(), diff);
        PhysicalSchema facts = ddlOrchestrator.introspect(snapshotUniverse);
        log.info("MetadataAnnotationScanner: physical snapshot = {}",
                facts == null ? "unavailable (metadata-only DDL planning)"
                        : facts.tables().size() + " managed table(s)");
        PhysicalDriftReport physicalDrift = null;
        if (facts == null) {
            // Degraded lane: plan DDL purely from the metadata diff (additive auto, DROP /
            // undeclared-rename warn-only); physical drift can be neither converged nor
            // audited this boot.
            if (!diff.isEmpty()) {
                ddlOrchestrator.apply(diff, fromCode.models(), fromCode.fields());
            }
        } else {
            // Convergence lane: annotations are the single source of truth — every
            // in-scope owned table is converged to its declared shape, hand-made drift
            // included, before the rows commit. The audit then runs on the healed
            // snapshot, so it reports only the residual (projection tables, out-of-scope
            // catalog drift, anything convergence cannot own).
            boolean executed = ddlOrchestrator.converge(fromCode.models(), fromCode.fields(),
                    fromCode.modelIndexes(), diff, facts);
            if (executed) {
                facts = ddlOrchestrator.introspect(snapshotUniverse);
            }
            if (facts != null) {
                physicalDrift = PhysicalDriftAuditor.audit(
                        audited.models(), audited.fields(), audited.modelIndexes(), facts);
                PhysicalDriftAuditor.warn(physicalDrift, "MetadataAnnotationScanner");
            }
        }

        if (!diff.isEmpty()) {
            // Rows second: a DDL failure above leaves them unwritten, so the next boot
            // recomputes the same diff and retries. The opposite order is unrecoverable —
            // once the rows are committed the next boot's diff is empty and the failed DDL
            // is never re-attempted. Re-running DDL that already succeeded is absorbed by
            // DdlErrorClassifier's already-applied degradation.
            writer.apply(diff);
            log.info("MetadataAnnotationScanner: applied {} row change(s) to sys_*", diff.totalCount());
        }

        // Stamp the configured identity onto platform rows that predate the
        // app_code column. Idempotent (fills NULLs only).
        int stamped = writer.backfillAppCode();
        if (stamped > 0) {
            log.info("MetadataAnnotationScanner: backfilled app_code='{}' on {} platform row(s)",
                    appCode, stamped);
        }

        // Resolve the surrogate FKs from their business codes now that all sys_* rows are
        // written and app_code is stamped. Runs unconditionally (even on an idempotent boot) so a row
        // left NULL by a prior partial boot self-heals. Idempotent — a deterministic re-derivation.
        int linked = writer.populateSurrogateFks();
        if (linked > 0) {
            log.info("MetadataAnnotationScanner: resolved surrogate FK(s) on {} sys_* row(s)", linked);
        }

        // Boot health snapshot for GET /metadata/status. The in-scope catalog now equals the
        // code by construction (either the diff was empty or it was just applied), so both
        // fingerprints are the code-side one.
        String fingerprint = CatalogFingerprint.of(fromCode.models(), fromCode.fields(),
                fromCode.modelIndexes(), fromCode.optionSets(), fromCode.optionItems());
        MetadataStatus.record(new MetadataStatus("scanner", fingerprint, fingerprint,
                true, diff.totalCount(), physicalDrift, LocalDateTime.now()));
    }

    /**
     * The in-scope result plus any catalog entity models not already in it (with their
     * fields and indexes), for the physical snapshot + drift audit. Row-side artifacts
     * (option sets / items) are irrelevant to physical auditing and are not merged.
     */
    private static AnnotationScanResult withCatalogEntities(AnnotationScanResult fromCode,
                                                            AnnotationScanResult catalogFromCode) {
        Set<String> inCode = fromCode.models().stream()
                .map(SysModel::getModelName).collect(Collectors.toSet());
        List<SysModel> missing = catalogFromCode.models().stream()
                .filter(m -> !inCode.contains(m.getModelName())).toList();
        if (missing.isEmpty()) {
            return fromCode;
        }
        Set<String> missingNames = missing.stream().map(SysModel::getModelName).collect(Collectors.toSet());
        List<SysModel> models = new ArrayList<>(fromCode.models());
        models.addAll(missing);
        List<SysField> fields = new ArrayList<>(fromCode.fields());
        catalogFromCode.fields().stream()
                .filter(f -> missingNames.contains(f.getModelName())).forEach(fields::add);
        List<SysModelIndex> indexes = new ArrayList<>(fromCode.modelIndexes());
        catalogFromCode.modelIndexes().stream()
                .filter(i -> missingNames.contains(i.getModelName())).forEach(indexes::add);
        return new AnnotationScanResult(models, fields,
                fromCode.optionSets(), fromCode.optionItems(), indexes);
    }

    /**
     * The snapshot universe: every audited model's table, plus the tables a rename-in-flight
     * may still physically be under — the diff's fromDb side of a pending {@code tableName}
     * change, and the {@code snake_case(renamedFrom)} derivation for declared model renames
     * whose {@code sys_*} rows already moved on (drift healing). Without the prior names the
     * convergence planner could not see the old table and would genesis-create an empty
     * successor instead of renaming — a silent data divorce.
     */
    private static List<SysModel> withPriorTables(List<SysModel> models, SchemaDiff diff) {
        List<SysModel> universe = new ArrayList<>(models);
        Set<String> tables = models.stream()
                .map(m -> SysDdlContextBuilder.resolveTableName(m).toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        for (SchemaDiff.Modification<SysModel> mod : diff.models().modified()) {
            addPriorTable(universe, tables, SysDdlContextBuilder.resolveTableName(mod.fromDb()));
        }
        for (SysModel model : models) {
            if (model.getRenamedFrom() != null && !model.getRenamedFrom().isBlank()) {
                addPriorTable(universe, tables, StringTools.toUnderscoreCase(model.getRenamedFrom()));
            }
        }
        return universe;
    }

    private static void addPriorTable(List<SysModel> universe, Set<String> tables, String priorTable) {
        if (tables.add(priorTable.toLowerCase(Locale.ROOT))) {
            SysModel stub = new SysModel();
            stub.setTableName(priorTable);
            universe.add(stub);
        }
    }

    /**
     * One consolidated WARN naming the catalog roots that have no {@code @Model} /
     * {@code @OptionSet} class. {@link ScannerScope#confineFromDb} never deletes them, so the
     * only path to cleanup is a human running the SQL — emit it ready to paste, children before
     * parents, scoped to this app's rows.
     *
     * <p>Only under a full {@code ["*"]} scope. A partial scope deliberately shares the catalog
     * with packages it does not manage, where a code-less row is somebody else's row rather than
     * an orphan — naming those every boot would be pure noise.
     */
    private void warnCodelessRoots(ScannerScope scope, AnnotationScanResult current,
                                   Set<String> inScopeModelNames,
                                   Set<String> inScopeOptionCodes) {
        if (!scope.matchesAll()) {
            return;
        }
        List<String> models = current.models().stream()
                .map(SysModel::getModelName)
                .filter(name -> !inScopeModelNames.contains(name))
                .distinct().sorted().toList();
        List<String> optionSets = current.optionSets().stream()
                .map(SysOptionSet::getOptionSetCode)
                .filter(code -> !inScopeOptionCodes.contains(code))
                .distinct().sorted().toList();
        if (models.isEmpty() && optionSets.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder();
        for (String modelName : models) {
            sql.append("\n-- model ").append(modelName)
                    .append(deleteBy("sys_field", "model_name", modelName))
                    .append(deleteBy("sys_model_index", "model_name", modelName))
                    .append(deleteBy("sys_model", "model_name", modelName));
        }
        for (String optionSetCode : optionSets) {
            sql.append("\n-- option set ").append(optionSetCode)
                    .append(deleteBy("sys_option_item", "option_set_code", optionSetCode))
                    .append(deleteBy("sys_option_set", "option_set_code", optionSetCode));
        }
        log.warn("MetadataAnnotationScanner: {} model(s) and {} option set(s) in the catalog have no"
                        + " Java class and were LEFT UNTOUCHED — an aggregate root is never"
                        + " auto-deleted (it may be Studio- or seed-authored on purpose). Models: {};"
                        + " option sets: {}. If they really are orphans, run:{}",
                models.size(), optionSets.size(), models, optionSets, sql);
    }

    private String deleteBy(String table, String column, String value) {
        return "\nDELETE FROM " + table + " WHERE " + column + " = '" + value
                + "' AND app_code = '" + appCode + "';";
    }
}
