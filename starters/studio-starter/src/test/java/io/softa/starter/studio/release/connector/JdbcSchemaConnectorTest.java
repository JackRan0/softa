package io.softa.starter.studio.release.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.metadata.ddl.BuiltinDdlDialects;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.dto.ChangeOp;
import io.softa.starter.metadata.dto.MetaChange;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.studio.release.desired.AggregateSelection;
import io.softa.starter.studio.release.desired.DesignRows;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * The JDBC connector's {@code apply} is DDL-only and its reverse
 * ({@code readSchema} / {@code readChecksums}) reads the physical schema via {@link JdbcSchemaReader},
 * memoizing the single physical read across the converge.
 */
class JdbcSchemaConnectorTest {

    private static DesignAppEnv env() {
        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        env.setJdbcUrl("jdbc:mysql://db.example:3306/app");
        env.setJdbcUsername("u");
        env.setJdbcPassword("p");
        return env;
    }

    private static DesignRows oneTableSchema() {
        return new DesignRows(
                List.of(Map.of("modelName", "Customer", "tableName", "customer")),
                List.of(Map.of("modelName", "Customer", "fieldName", "name", "columnName", "name",
                        "fieldType", "STRING", "length", 100)),
                List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("apply executes the DDL against the external DB and drops the row changes")
    void applyExecutesDdlOnlyAndDropsRows() {
        JdbcDdlExecutor executor = mock(JdbcDdlExecutor.class);
        JdbcSchemaConnector connector = new JdbcSchemaConnector(
                null, executor, mock(JdbcSchemaReader.class), env());
        MetadataChangeSet changeSet = new MetadataChangeSet(
                List.of(new MetaChange(MetaTable.MODEL, ChangeOp.UPSERT, Map.<String, Object>of("modelName", "X"))),
                List.of("CREATE TABLE x (id BIGINT);"));

        connector.apply("app", changeSet);

        verify(executor).execute("jdbc:mysql://db.example:3306/app", "u", "p",
                List.of("CREATE TABLE x (id BIGINT);"));
    }

    @Test
    @DisplayName("the dialect is the configured value")
    void dialectFromConfig() {
        DdlDialect dialect = BuiltinDdlDialects.registry().getDialect(DatabaseType.MYSQL);
        JdbcSchemaConnector connector = new JdbcSchemaConnector(
                dialect, mock(JdbcDdlExecutor.class), mock(JdbcSchemaReader.class), env());

        assertSame(dialect, connector.dialect());
        assertEquals(DatabaseType.MYSQL, connector.dialect().getDatabaseType());
    }

    @Test
    @DisplayName("readSchema returns the reverse-engineered physical schema; checksums cover models, not option sets")
    void readSchemaAndChecksums() {
        JdbcSchemaReader reader = mock(JdbcSchemaReader.class);
        when(reader.read(any(), any(), any())).thenReturn(oneTableSchema());
        JdbcSchemaConnector connector = new JdbcSchemaConnector(
                null, mock(JdbcDdlExecutor.class), reader, env());

        DesignRows schema = connector.readSchema("app");
        assertEquals(1, schema.models().size());
        assertEquals("Customer", schema.models().getFirst().get("modelName"));
        assertTrue(schema.optionSets().isEmpty(), "physical schema has no option sets");

        // checksums cover the model aggregate; no option-set checksums for a physical DB.
        assertTrue(connector.readChecksums("app").models().containsKey("Customer"));
        assertTrue(connector.readChecksums("app").optionSets().isEmpty());
    }

    @Test
    @DisplayName("the physical read is memoized — readChecksums + readSchema share ONE read per converge")
    void physicalReadIsMemoizedAcrossTheConverge() {
        JdbcSchemaReader reader = mock(JdbcSchemaReader.class);
        when(reader.read(any(), any(), any())).thenReturn(oneTableSchema());
        JdbcSchemaConnector connector = new JdbcSchemaConnector(
                null, mock(JdbcDdlExecutor.class), reader, env());

        // The gate (readChecksums) then the row read (readSchema) + a selective read — all in one converge.
        connector.readChecksums("app");
        connector.readSchema("app");
        connector.readSchema("app", new AggregateSelection(Set.of("Customer"), Set.of()));

        verify(reader, times(1)).read(eq("jdbc:mysql://db.example:3306/app"), eq("u"), eq("p"));
    }

    @Test
    @DisplayName("selective readSchema restricts to the requested model aggregates")
    void selectiveReadFiltersAggregates() {
        JdbcSchemaReader reader = mock(JdbcSchemaReader.class);
        when(reader.read(any(), any(), any())).thenReturn(new DesignRows(
                List.of(Map.of("modelName", "Customer", "tableName", "customer"),
                        Map.of("modelName", "Order", "tableName", "order")),
                List.of(), List.of(), List.of(), List.of()));
        JdbcSchemaConnector connector = new JdbcSchemaConnector(
                null, mock(JdbcDdlExecutor.class), reader, env());

        DesignRows selected = connector.readSchema("app",
                new AggregateSelection(Set.of("Customer"), Set.of()));

        assertEquals(1, selected.models().size());
        assertEquals("Customer", selected.models().getFirst().get("modelName"));
    }
}
