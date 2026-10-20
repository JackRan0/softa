package io.softa.starter.metadata.sequence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.ModelWriteValidatorChain;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.framework.orm.service.validation.WriteValidationException;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which writes count as typing a code, and which are let through unchecked. */
class ManualCodeWriteValidatorTest {

    private static final String MODEL = "Employee";

    private final ManualCodeChecker checker = mock(ManualCodeChecker.class);
    private final ManualCodeWriteValidator validator = new ManualCodeWriteValidator(checker);
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        models = Mockito.mockStatic(ModelManager.class);
        MetaField code = mock(MetaField.class);
        when(code.isAutoSequence()).thenReturn(true);
        when(code.getFieldName()).thenReturn("code");
        models.when(() -> ModelManager.existModel(MODEL)).thenReturn(true);
        models.when(() -> ModelManager.getModelFields(MODEL)).thenReturn(List.of(code));
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private static Map<String, Object> row(Object code) {
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        return row;
    }

    private WriteContext update(Object typed, Object stored) {
        Map<String, Object> original = row(stored);
        Map<String, Object> patch = row(typed);
        patch.put("id", 1L);
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        return WriteContext.of(MODEL, AccessType.UPDATE, merged, patch, original);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTypedCodeOnCreateIsChecked_aBlankOneIsNot() {
        when(checker.check(eq(MODEL), eq("code"), anyList(), anyList(), any()))
                .thenReturn(java.util.Arrays.asList("Employee Code cannot contain spaces.", null));
        ObjectProvider<ModelWriteValidator> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> java.util.stream.Stream.of(validator));
        ModelWriteValidatorChain chain = new ModelWriteValidatorChain(provider);
        List<Map<String, Object>> rows = List.of(row(" ZK 1 "), row(""));

        WriteValidationException e = assertThrows(WriteValidationException.class,
                () -> ContextHolder.runWith(new Context(), () -> chain.validateCreate(MODEL, rows)));

        assertThat(e.getMessage()).contains("cannot contain spaces").doesNotContain("row 2");
        verify(checker).check(eq(MODEL), eq("code"), eq(java.util.Arrays.asList("ZK 1", null)), anyList(), any());
    }

    @Test
    void sendingTheStoredCodeBackIsNotAChange() {
        ContextHolder.runWith(new Context(), () -> validator.validateUpdate(update(" ZK1000021 ", "ZK1000021")));
        verify(checker, never()).checkOne(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aChangedCodeIsChecked_againstTheMergedRow() {
        when(checker.checkOne(eq(MODEL), eq("code"), eq("zk9"), any(), eq("ZK1000021"))).thenReturn("dup");
        WriteContext ctx = update("zk9", "ZK1000021");
        ContextHolder.runWith(new Context(), () -> validator.validateUpdate(ctx));
        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void systemWritesAreNotChecked() {
        Context system = new Context();
        system.setSkipPermissionCheck(true);
        ContextHolder.runWith(system, () -> validator.validateUpdate(update("anything", "ZK1")));
        verify(checker, never()).checkOne(anyString(), anyString(), anyString(), any(), any());
    }
}
