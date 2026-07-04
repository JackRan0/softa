package io.softa.starter.metadata.scanner.checker;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.config.MetadataProperties;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.scanner.MetadataReadPipeline;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The shared {@link MetadataReadPipeline} seam makes the checker unit-testable
 * without a live database — stub the pipeline and assert the scope gate. (Before
 * the seam the checker hand-{@code new}ed a {@code SysJdbcLoader} bound to a real
 * {@code JdbcTemplate}, so its drift path could not be driven in isolation.)
 */
class MetadataAnnotationCheckerTest {

    @Test
    void activeScopeDisablesChecker() {
        MetadataReadPipeline pipeline = mock(MetadataReadPipeline.class);
        RelationShapeLinter linter = mock(RelationShapeLinter.class);
        MetadataAnnotationChecker checker = new MetadataAnnotationChecker(
                pipeline, linter, new MetadataProperties(List.of("io\\.acme\\..*"), null, null)); // active scope

        checker.check();

        // Under an active scope the scanner owns reconciliation; the checker must
        // stay silent and touch neither the read pipeline nor the linter.
        verifyNoInteractions(pipeline);
        verifyNoInteractions(linter);
    }

    @Test
    void emptyScopeRunsDriftScanThroughPipeline() {
        MetadataReadPipeline pipeline = mock(MetadataReadPipeline.class);
        RelationShapeLinter linter = mock(RelationShapeLinter.class);
        when(pipeline.discoverModelClasses()).thenReturn(Set.of());
        when(pipeline.discoverOptionSetEnums()).thenReturn(Set.of());
        when(pipeline.parse(any(), any())).thenReturn(AnnotationScanResult.empty());
        when(pipeline.loadCurrentStateLenient()).thenReturn(AnnotationScanResult.empty());
        SchemaDiff noDrift = mock(SchemaDiff.class);
        when(noDrift.isEmpty()).thenReturn(true);
        when(pipeline.diff(any(), any())).thenReturn(noDrift);

        MetadataAnnotationChecker checker = new MetadataAnnotationChecker(
                pipeline, linter, new MetadataProperties(List.of(), null, null)); // empty scope (prod default)

        checker.check();

        // Empty scope = the checker is the active safety net: it lints relations,
        // loads current state (lenient: read-only path), and diffs it against
        // the parsed annotations.
        verify(linter).warnUnannotatedRelations(any());
        verify(pipeline).loadCurrentStateLenient();
        verify(pipeline).diff(any(), any());
    }

    @Test
    void checkerStampsToOneFksBeforeDiff_soStampedRowsDontFalseDrift() {
        // The stored FK row carries the reconciliation-time stamp
        // (relatedFieldType + mirrored length). The checker must apply the same
        // stamp to the parsed side before diffing — otherwise every FK reports
        // perpetual "attrs differ" drift in production logs.
        SysField codeFk = fk();                         // fresh parse: unstamped
        SysField dbFk = fk();
        dbFk.setRelatedFieldType(FieldType.STRING);     // stamped at scan time
        dbFk.setLength(3);

        AnnotationScanResult fromCode = new AnnotationScanResult(
                List.of(), List.of(codeFk, currencyId()), List.of(), List.of());
        AnnotationScanResult current = new AnnotationScanResult(
                List.of(), List.of(dbFk, currencyId()), List.of(), List.of());

        MetadataReadPipeline pipeline = mock(MetadataReadPipeline.class);
        RelationShapeLinter linter = mock(RelationShapeLinter.class);
        when(pipeline.discoverModelClasses()).thenReturn(Set.of());
        when(pipeline.discoverOptionSetEnums()).thenReturn(Set.of());
        when(pipeline.parse(any(), any())).thenReturn(fromCode);
        when(pipeline.loadCurrentStateLenient()).thenReturn(current);
        SchemaDiff noDrift = mock(SchemaDiff.class);
        when(noDrift.isEmpty()).thenReturn(true);
        when(pipeline.diff(any(), any())).thenReturn(noDrift);

        new MetadataAnnotationChecker(pipeline, linter,
                new MetadataProperties(List.of(), null, null))
                .check();

        // The stamp mutates the parsed fields in place, before diff() sees them.
        assertEquals(FieldType.STRING, codeFk.getRelatedFieldType());
        assertEquals(3, codeFk.getLength());
    }

    private static SysField fk() {
        SysField f = new SysField();
        f.setModelName("Order");
        f.setFieldName("currency");
        f.setColumnName("currency");
        f.setFieldType(FieldType.MANY_TO_ONE);
        f.setRelatedModel("Currency");
        return f;
    }

    /** The referenced code-as-id master's id column: String(3). */
    private static SysField currencyId() {
        SysField f = new SysField();
        f.setModelName("Currency");
        f.setFieldName("id");
        f.setColumnName("id");
        f.setFieldType(FieldType.STRING);
        f.setLength(3);
        return f;
    }
}
