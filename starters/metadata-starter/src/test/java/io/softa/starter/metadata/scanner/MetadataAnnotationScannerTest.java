package io.softa.starter.metadata.scanner;

import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import io.softa.framework.base.config.SystemConfig;
import io.softa.starter.metadata.config.MetadataProperties;
import io.softa.starter.metadata.ddl.DdlOrchestrator;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Verifies two safety properties of {@link MetadataAnnotationScanner#initialize()}:
 * <ul>
 *   <li>prod default: with an empty {@code scanner-scope} the scanner is an
 *       inert no-op — it never reads or writes the database;</li>
 *   <li>recovery ordering: DDL executes <b>before</b> the {@code sys_*} rows
 *       are committed, so a DDL failure leaves the rows unwritten and the next
 *       boot retries the same diff.</li>
 * </ul>
 */
class MetadataAnnotationScannerTest {

    @Test
    void emptyScopeIsAnInertNoOp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // SysJdbcWriter's constructor wires a transaction manager from the
        // DataSource, so the mock must return one during construction.
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));

        MetadataReadPipeline pipeline = mock(MetadataReadPipeline.class);
        SystemConfig systemConfig = new SystemConfig();
        systemConfig.setAppCode("test-app");
        MetadataAnnotationScanner scanner = new MetadataAnnotationScanner(
                pipeline,
                new MetadataProperties(List.of(), null, null),   // empty scope
                systemConfig,
                jdbc,
                "jdbc:mysql://localhost/unused");

        // Ignore construction-time interactions (getDataSource); assert that
        // initialize() itself touches neither the DB nor the read pipeline.
        clearInvocations(jdbc);
        scanner.initialize();
        verifyNoInteractions(jdbc);
        verifyNoInteractions(pipeline);
    }

    // ---- DDL-before-rows ordering ---------------------------------------

    private record Fixture(MetadataAnnotationScanner scanner,
                           SysJdbcWriter writer,
                           DdlOrchestrator ddl,
                           SchemaDiff diff) {}

    /** Wire a scanner whose pipeline yields one added model. */
    private Fixture scannerWithOneAddedModel() {
        SysModel customer = new SysModel();
        customer.setModelName("Customer");
        SchemaDiff diff = new SchemaDiff(
                new SchemaDiff.EntityDiff<>(List.of(customer), List.of(), List.of()),
                SchemaDiff.EntityDiff.empty(),
                SchemaDiff.EntityDiff.empty(),
                SchemaDiff.EntityDiff.empty(),
                SchemaDiff.EntityDiff.empty());
        return scannerReturningDiff(diff);
    }

    /** Wire a scanner whose pipeline yields one in-scope model and returns {@code diff} from the diff step. */
    private Fixture scannerReturningDiff(SchemaDiff diff) {
        MetadataReadPipeline pipeline = mock(MetadataReadPipeline.class);
        SysJdbcWriter writer = mock(SysJdbcWriter.class);
        DdlOrchestrator ddl = mock(DdlOrchestrator.class);

        SysModel customer = new SysModel();
        customer.setModelName("Customer");
        AnnotationScanResult fromCode = new AnnotationScanResult(
                List.of(customer), List.of(), List.of(), List.of());

        when(pipeline.discoverModelClasses()).thenReturn(Set.of(Object.class));
        when(pipeline.discoverOptionSetEnums()).thenReturn(Set.of());
        when(pipeline.parse(anyCollection(), anyCollection()))
                .thenReturn(fromCode)                       // in-scope model parse
                .thenReturn(AnnotationScanResult.empty());  // option-set parse
        when(pipeline.loadCurrentState()).thenReturn(AnnotationScanResult.empty());
        when(pipeline.diff(any(), any())).thenReturn(diff);
        when(writer.changeSummary(diff)).thenReturn(List.of());

        MetadataAnnotationScanner scanner = new MetadataAnnotationScanner(
                pipeline, new MetadataProperties(List.of("*"), null, null), "test-app", writer, ddl);
        return new Fixture(scanner, writer, ddl, diff);
    }

    @Test
    void ddlExecutesBeforeSysRowsAreCommitted() {
        Fixture f = scannerWithOneAddedModel();

        f.scanner().initialize();

        InOrder inOrder = inOrder(f.ddl(), f.writer());
        inOrder.verify(f.ddl()).apply(eq(f.diff()), anyList(), anyList());
        inOrder.verify(f.writer()).apply(f.diff());
    }

    @Test
    void ddlFailureLeavesSysRowsUnwritten() {
        Fixture f = scannerWithOneAddedModel();
        doThrow(new IllegalStateException("CREATE TABLE failed"))
                .when(f.ddl()).apply(any(), anyList(), anyList());

        assertThrows(IllegalStateException.class, f.scanner()::initialize);

        // The recovery property: no sys_* row may be written after a DDL
        // failure, so the next boot recomputes the same diff and retries.
        verify(f.writer(), never()).apply(any(SchemaDiff.class));
    }

    // ---- finalization: app_code stamp then surrogate-FK resolution --------

    @Test
    void finalizationStampsAppCodeThenResolvesFks() {
        Fixture f = scannerWithOneAddedModel();

        f.scanner().initialize();

        // populateSurrogateFks joins on the stamped app_code, so it must run AFTER backfillAppCode —
        // which itself runs after the rows are applied.
        InOrder inOrder = inOrder(f.writer());
        inOrder.verify(f.writer()).apply(f.diff());
        inOrder.verify(f.writer()).backfillAppCode();
        inOrder.verify(f.writer()).populateSurrogateFks();
    }

    @Test
    void emptyDiffStillRunsFinalizationSoAPartialBootSelfHeals() {
        Fixture f = scannerReturningDiff(SchemaDiff.empty());

        f.scanner().initialize();

        // Steady-state boot: an empty diff applies no DDL and no rows...
        verify(f.ddl(), never()).apply(any(), anyList(), anyList());
        verify(f.writer(), never()).apply(any(SchemaDiff.class));
        // ...but backfill + FK resolution are unconditional, so a row left NULL / unlinked by a prior
        // partial boot self-heals on the next (idempotent) restart.
        verify(f.writer()).backfillAppCode();
        verify(f.writer()).populateSurrogateFks();
    }
}
