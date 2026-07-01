package io.softa.framework.web.handler;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.i18n.I18n;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaIndex;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.web.handler.DuplicateKeyMessageResolver.DuplicateKeyMessage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link DuplicateKeyMessageResolver}: maps a violated index name (PostgreSQL or MySQL) to a
 * friendly message via the in-memory index registry ({@link ModelManager#getIndex}) — either the
 * index's own {@code message} or a message composed from the member fields' labels. Failure-safe:
 * any miss yields {@link Optional#empty()} so the caller falls back to the raw driver message.
 *
 * <p>Note: MetaIndex / MetaField mocks are built into locals BEFORE the {@code MockedStatic}
 * stub — building a mock inside a {@code thenReturn(...)} argument nests stubbing (Mockito
 * "unfinished stubbing").
 */
class DuplicateKeyMessageResolverTest {

    /** PostgreSQL unique violation (SQLState 23505); constraint name parsed from the message. */
    private static SQLException pgDup(String constraint) {
        return new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"", "23505");
    }

    /** MySQL duplicate key (SQLState 23000, vendor code 1062); key may be table/schema-qualified. */
    private static SQLException mysqlDup(String key) {
        return new SQLException("Duplicate entry 'x' for key '" + key + "'", "23000", 1062);
    }

    private static MetaIndex index(String modelName, List<String> fields, String message) {
        MetaIndex idx = mock(MetaIndex.class);
        when(idx.getModelName()).thenReturn(modelName);
        when(idx.getIndexFields()).thenReturn(fields);
        when(idx.getMessage()).thenReturn(message);
        return idx;
    }

    private static MetaField labelled(String label) {
        MetaField f = mock(MetaField.class);
        when(f.getLabel()).thenReturn(label);
        return f;
    }

    @Test
    void notAUniqueViolation_returnsEmpty() {
        assertTrue(DuplicateKeyMessageResolver.resolve(new RuntimeException("some other failure")).isEmpty());
    }

    @Test
    void indexNotInRegistry_returnsEmpty() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getIndex("uk_unknown")).thenReturn(null);
            assertTrue(DuplicateKeyMessageResolver.resolve(pgDup("uk_unknown")).isEmpty());
        }
    }

    @Test
    void singleFieldIndex_derivesMessageFromLabel() {
        MetaIndex idx = index("Customer", List.of("email"), null);
        MetaField email = labelled("Email");
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<I18n> i18n = Mockito.mockStatic(I18n.class)) {
            mm.when(() -> ModelManager.getIndex("uk_customer_email")).thenReturn(idx);
            mm.when(() -> ModelManager.getModelField("Customer", "email")).thenReturn(email);
            i18n.when(() -> I18n.get(anyString(), any()))
                    .thenAnswer(inv -> "A record with the same " + inv.getArgument(1) + " already exists.");

            Optional<DuplicateKeyMessage> message = DuplicateKeyMessageResolver.resolve(pgDup("uk_customer_email"));
            assertTrue(message.isPresent());
            assertEquals("A record with the same Email already exists.", message.get().userMessage());
            // logDetail is the stable, English, value-free descriptor for server logs.
            assertEquals("constraint=uk_customer_email, model=Customer, fields=[email]",
                    message.get().logDetail());
        }
    }

    @Test
    void compositeIndex_skipsTenantId_joinsRemainingLabels() {
        MetaIndex idx = index("Customer", List.of("tenantId", "code"), null);
        MetaField code = labelled("Code");
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<I18n> i18n = Mockito.mockStatic(I18n.class)) {
            mm.when(() -> ModelManager.getIndex("uk_customer_code")).thenReturn(idx);
            mm.when(() -> ModelManager.getModelField("Customer", "code")).thenReturn(code);
            i18n.when(() -> I18n.get(anyString(), any()))
                    .thenAnswer(inv -> "A record with the same " + inv.getArgument(1) + " already exists.");

            Optional<DuplicateKeyMessage> message = DuplicateKeyMessageResolver.resolve(pgDup("uk_customer_code"));
            assertTrue(message.isPresent());
            assertEquals("A record with the same Code already exists.", message.get().userMessage());
            // tenantId must not have been resolved.
            mm.verify(() -> ModelManager.getModelField("Customer", "tenantId"), never());
        }
    }

    @Test
    void mysqlDuplicate_stripsQualifier_andResolves() {
        // MySQL reports 'table.index'; the resolver strips the qualifier to the bare, globally-unique name.
        MetaIndex idx = index("Customer", List.of("email"), null);
        MetaField email = labelled("Email");
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<I18n> i18n = Mockito.mockStatic(I18n.class)) {
            mm.when(() -> ModelManager.getIndex("uk_customer_email")).thenReturn(idx);
            mm.when(() -> ModelManager.getModelField("Customer", "email")).thenReturn(email);
            i18n.when(() -> I18n.get(anyString(), any()))
                    .thenAnswer(inv -> "A record with the same " + inv.getArgument(1) + " already exists.");

            Optional<DuplicateKeyMessage> message =
                    DuplicateKeyMessageResolver.resolve(mysqlDup("customer.uk_customer_email"));
            assertTrue(message.isPresent());
            assertEquals("A record with the same Email already exists.", message.get().userMessage());
        }
    }

    @Test
    void customMessage_returnedVerbatim_withoutComposingLabels() {
        String custom = "An employee is already assigned to this project.";
        MetaIndex idx = index("EmpProjectRel", List.of("empId", "projectId"), custom);
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<I18n> i18n = Mockito.mockStatic(I18n.class)) {
            mm.when(() -> ModelManager.getIndex("uk_emp_project")).thenReturn(idx);
            i18n.when(() -> I18n.get(anyString())).thenAnswer(inv -> inv.getArgument(0));

            Optional<DuplicateKeyMessage> message = DuplicateKeyMessageResolver.resolve(pgDup("uk_emp_project"));
            assertTrue(message.isPresent());
            assertEquals(custom, message.get().userMessage());
            // Tier 1 short-circuits: field labels are never composed when a custom message exists.
            mm.verify(() -> ModelManager.getModelField(anyString(), anyString()), never());
        }
    }

    @Test
    void customMessage_isApostropheSafe() {
        // Resolved via zero-arg I18n.get, which short-circuits MessageFormat, so an apostrophe survives.
        String custom = "An employee's assignment already exists.";
        MetaIndex idx = index("Emp", List.of("code"), custom);
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<I18n> i18n = Mockito.mockStatic(I18n.class)) {
            mm.when(() -> ModelManager.getIndex("uk_emp")).thenReturn(idx);
            i18n.when(() -> I18n.get(anyString())).thenAnswer(inv -> inv.getArgument(0));

            assertEquals(custom, DuplicateKeyMessageResolver.resolve(pgDup("uk_emp")).orElseThrow().userMessage());
        }
    }
}
