package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.sequence.SequenceService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link SequenceProcessor}: blank-fill on CREATE, the
 * single-allocation batch fast path, the trusted caller-value semantics of a
 * non-readonly field, and the strict system-numbering semantics of
 * {@code readonly + autoSequence} (caller values rejected by the allocator,
 * allocator-filled values exempted from the downstream readonly check).
 */
class SequenceProcessorTest {

    private static final String MODEL = "Emp";
    private static final String FIELD = "code";
    private static final String CODE = "Emp.code";

    private final SequenceService sequenceService = Mockito.mock(SequenceService.class);

    private static MetaField stringField(boolean readonly) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", FIELD);
        ReflectionTestUtils.setField(metaField, "columnName", "code");
        ReflectionTestUtils.setField(metaField, "label", "Code");
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(metaField, "autoSequence", true);
        ReflectionTestUtils.setField(metaField, "readonly", readonly);
        return metaField;
    }

    private static Map<String, Object> row(Object value) {
        Map<String, Object> row = new HashMap<>();
        if (value != null) {
            row.put(FIELD, value);
        }
        return row;
    }

    // ---- fill semantics ----------------------------------------------------

    @Test
    void create_blankValue_isFilledFromSequence() {
        when(sequenceService.next(eq(CODE), anyMap())).thenReturn("EMP-00001");
        SequenceProcessor processor = new SequenceProcessor(stringField(false), AccessType.CREATE, sequenceService);

        Map<String, Object> row = row("  ");
        processor.processInputRow(row);

        assertEquals("EMP-00001", row.get(FIELD));
    }

    @Test
    void create_callerValueOnNonReadonlyField_isTrusted() {
        SequenceProcessor processor = new SequenceProcessor(stringField(false), AccessType.CREATE, sequenceService);

        Map<String, Object> row = row("LEGACY-42");
        processor.processInputRow(row);

        assertEquals("LEGACY-42", row.get(FIELD));
        verify(sequenceService, never()).next(Mockito.anyString(), anyMap());
    }

    @Test
    void batch_blankRows_shareOneNextBatchCall_assignedInOrder() {
        when(sequenceService.nextBatch(eq(CODE), anyList(), anyCollection())).thenReturn(List.of("EMP-00001", "EMP-00002"));
        SequenceProcessor processor = new SequenceProcessor(stringField(false), AccessType.CREATE, sequenceService);

        Map<String, Object> first = row(null);
        Map<String, Object> kept = row("LEGACY-42");
        Map<String, Object> second = row("");
        processor.batchProcessInputRows(List.of(first, kept, second));

        assertEquals("EMP-00001", first.get(FIELD));
        assertEquals("LEGACY-42", kept.get(FIELD));
        assertEquals("EMP-00002", second.get(FIELD));
        verify(sequenceService, never()).next(Mockito.anyString(), anyMap());
    }

    @Test
    void batch_singleBlankRow_usesTheSingleAllocationFastPath() {
        when(sequenceService.next(eq(CODE), anyMap())).thenReturn("EMP-00007");
        SequenceProcessor processor = new SequenceProcessor(stringField(false), AccessType.CREATE, sequenceService);

        Map<String, Object> row = row(null);
        processor.batchProcessInputRows(List.of(row));

        assertEquals("EMP-00007", row.get(FIELD));
        verify(sequenceService, never()).nextBatch(Mockito.anyString(), anyList(), anyCollection());
    }

    // ---- readonly = strict system numbering ---------------------------------

    @Test
    void create_callerValueOnReadonlyField_isRejected() {
        SequenceProcessor processor = new SequenceProcessor(stringField(true), AccessType.CREATE, sequenceService);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> processor.processInputRow(row("HAND-01")));
        assertTrue(e.getMessage().contains("readonly"));
        verify(sequenceService, never()).next(Mockito.anyString(), anyMap());
    }

    @Test
    void batch_callerValueOnReadonlyField_isRejected() {
        SequenceProcessor processor = new SequenceProcessor(stringField(true), AccessType.CREATE, sequenceService);

        assertThrows(IllegalArgumentException.class,
                () -> processor.batchProcessInputRows(List.of(row(null), row("HAND-01"))));
        verify(sequenceService, never()).nextBatch(Mockito.anyString(), anyList(), anyCollection());
    }

    @Test
    void readonlyField_filledValue_passesTheDownstreamReadonlyCheck() {
        // Real chain order: SequenceProcessor fills, then StringProcessor's
        // checkReadonly sees the field present — the autoSequence CREATE
        // exemption must let the system-written value through.
        when(sequenceService.next(eq(CODE), anyMap())).thenReturn("EMP-00042");
        MetaField metaField = stringField(true);
        SequenceProcessor sequenceProcessor = new SequenceProcessor(metaField, AccessType.CREATE, sequenceService);
        StringProcessor stringProcessor = new StringProcessor(metaField, AccessType.CREATE);

        Map<String, Object> row = row(null);
        sequenceProcessor.processInputRow(row);
        assertDoesNotThrow(() -> stringProcessor.processInputRow(row));
        assertEquals("EMP-00042", row.get(FIELD));
    }

    @Test
    void readonlyField_onUpdate_isStillRejectedDownstream() {
        // The exemption is CREATE-scoped: assigning (or blanking) the number on
        // UPDATE keeps failing the ordinary readonly check.
        StringProcessor stringProcessor = new StringProcessor(stringField(true), AccessType.UPDATE);

        assertThrows(IllegalArgumentException.class,
                () -> stringProcessor.processInputRow(row("EMP-99999")));
    }

    // ---- the record reaches the service ------------------------------------

    @Test
    void create_passesTheRowItself_soRecordDependentTokensCanReadIt() {
        Map<String, Object> r = row(null);
        r.put("companyId", 9001L);
        when(sequenceService.next(eq(CODE), anyMap())).thenReturn("ZK0001");
        new SequenceProcessor(stringField(false), AccessType.CREATE, sequenceService).processInputRow(r);

        verify(sequenceService).next(CODE, r);
    }

    @Test
    void batch_passesTheBlankRowsInOrder_notJustTheirCount() {
        Map<String, Object> a = row(null);
        Map<String, Object> given = row("KEEP-1");
        Map<String, Object> b = row(null);
        when(sequenceService.nextBatch(eq(CODE), anyList(), anyCollection())).thenReturn(List.of("ZK0001", "HF0002"));
        new SequenceProcessor(stringField(false), AccessType.CREATE, sequenceService)
                .batchProcessInputRows(List.of(a, given, b));

        verify(sequenceService).nextBatch(CODE, List.of(a, b), List.of("KEEP-1"));
        assertEquals("ZK0001", a.get(FIELD));
        assertEquals("KEEP-1", given.get(FIELD));
        assertEquals("HF0002", b.get(FIELD));
    }

    @Test
    void batch_oneBlankBesideAHandTypedValue_stillTellsTheServiceAboutIt() {
        // A single blank would normally take the next() fast path, but next() cannot see the value
        // typed into the other row — it is not in the table yet. The batch path carries it.
        Map<String, Object> given = row("ZK1000030");
        Map<String, Object> blank = row(null);
        when(sequenceService.nextBatch(eq(CODE), anyList(), anyCollection())).thenReturn(List.of("ZK1000031"));
        new SequenceProcessor(stringField(false), AccessType.CREATE, sequenceService)
                .batchProcessInputRows(List.of(given, blank));

        verify(sequenceService).nextBatch(CODE, List.of(blank), List.of("ZK1000030"));
        verify(sequenceService, never()).next(Mockito.anyString(), anyMap());
        assertEquals("ZK1000031", blank.get(FIELD));
    }
}
