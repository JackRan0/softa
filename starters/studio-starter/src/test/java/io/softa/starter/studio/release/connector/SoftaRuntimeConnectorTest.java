package io.softa.starter.studio.release.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.metadata.dto.ChangeOp;
import io.softa.starter.metadata.dto.MetaChange;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.studio.release.desired.AggregateSelection;
import io.softa.starter.studio.release.desired.DesignRows;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;

/**
 * The Softa connector's selective {@code readSchema} narrows each meta-table fetch to the
 * requested aggregate business keys (modelName for model tables, optionSetCode for option-set tables),
 * and skips the RPC entirely for an empty key set (zero round-trip when an aggregate root has nothing to
 * pull).
 */
class SoftaRuntimeConnectorTest {

    @Test
    @DisplayName("fetches model tables by modelName and skips the option-set RPCs when no option sets requested")
    void selectiveReadNarrowsAndSkipsEmpty() {
        RemoteApiClient client = mock(RemoteApiClient.class);
        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        SoftaRuntimeConnector connector = new SoftaRuntimeConnector(null, client, env);

        List<Map<String, Object>> modelRows = List.of(Map.of("modelName", "Account"));
        when(client.fetchRuntimeMetadata(eq(env), eq("app"), eq("SysModel"), eq("modelName"), any()))
                .thenReturn(modelRows);
        when(client.fetchRuntimeMetadata(eq(env), eq("app"), eq("SysField"), eq("modelName"), any()))
                .thenReturn(List.of());
        when(client.fetchRuntimeMetadata(eq(env), eq("app"), eq("SysModelIndex"), eq("modelName"), any()))
                .thenReturn(List.of());

        DesignRows observed = connector.readSchema("app",
                new AggregateSelection(Set.of("Account"), Set.of()));

        assertEquals(modelRows, observed.models());
        // The three model-aggregate tables were fetched, narrowed by modelName...
        verify(client).fetchRuntimeMetadata(eq(env), eq("app"), eq("SysModel"), eq("modelName"), eq(Set.of("Account")));
        verify(client).fetchRuntimeMetadata(eq(env), eq("app"), eq("SysField"), eq("modelName"), eq(Set.of("Account")));
        verify(client).fetchRuntimeMetadata(eq(env), eq("app"), eq("SysModelIndex"), eq("modelName"), eq(Set.of("Account")));
        // ...and the option-set tables were NOT fetched (empty key set → no RPC).
        verify(client, never()).fetchRuntimeMetadata(any(), anyString(), eq("SysOptionSet"), anyString(), any());
        verify(client, never()).fetchRuntimeMetadata(any(), anyString(), eq("SysOptionItem"), anyString(), any());
    }

    @Test
    @DisplayName("fetches option-set tables by optionSetCode and skips the model RPCs when no models requested")
    void selectiveReadNarrowsOptionSetLaneAndSkipsModels() {
        RemoteApiClient client = mock(RemoteApiClient.class);
        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        SoftaRuntimeConnector connector = new SoftaRuntimeConnector(null, client, env);

        List<Map<String, Object>> setRows = List.of(Map.of("optionSetCode", "status"));
        when(client.fetchRuntimeMetadata(eq(env), eq("app"), eq("SysOptionSet"), eq("optionSetCode"), any()))
                .thenReturn(setRows);
        when(client.fetchRuntimeMetadata(eq(env), eq("app"), eq("SysOptionItem"), eq("optionSetCode"), any()))
                .thenReturn(List.of());

        DesignRows observed = connector.readSchema("app",
                new AggregateSelection(Set.of(), Set.of("status")));

        assertEquals(setRows, observed.optionSets());
        // Option-set tables fetched, narrowed by optionSetCode (NOT modelName — guards a copy-paste of the key column).
        verify(client).fetchRuntimeMetadata(eq(env), eq("app"), eq("SysOptionSet"), eq("optionSetCode"), eq(Set.of("status")));
        verify(client).fetchRuntimeMetadata(eq(env), eq("app"), eq("SysOptionItem"), eq("optionSetCode"), eq(Set.of("status")));
        // Model tables NOT fetched (empty key set → no RPC).
        verify(client, never()).fetchRuntimeMetadata(any(), anyString(), eq("SysModel"), anyString(), any());
        verify(client, never()).fetchRuntimeMetadata(any(), anyString(), eq("SysField"), anyString(), any());
        verify(client, never()).fetchRuntimeMetadata(any(), anyString(), eq("SysModelIndex"), anyString(), any());
    }

    @Test
    @DisplayName("both roots non-empty → all five tables fetched, each by its own key column")
    void selectiveReadFetchesBothRoots() {
        RemoteApiClient client = mock(RemoteApiClient.class);
        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        SoftaRuntimeConnector connector = new SoftaRuntimeConnector(null, client, env);
        when(client.fetchRuntimeMetadata(any(), anyString(), anyString(), anyString(), any())).thenReturn(List.of());

        connector.readSchema("app", new AggregateSelection(Set.of("Account"), Set.of("status")));

        verify(client).fetchRuntimeMetadata(env, "app", "SysModel", "modelName", Set.of("Account"));
        verify(client).fetchRuntimeMetadata(env, "app", "SysField", "modelName", Set.of("Account"));
        verify(client).fetchRuntimeMetadata(env, "app", "SysModelIndex", "modelName", Set.of("Account"));
        verify(client).fetchRuntimeMetadata(env, "app", "SysOptionSet", "optionSetCode", Set.of("status"));
        verify(client).fetchRuntimeMetadata(env, "app", "SysOptionItem", "optionSetCode", Set.of("status"));
    }

    @Test
    @DisplayName("full readSchema fetches all five meta-tables unfiltered (the P3 reverse/import primitive)")
    void fullReadFetchesAllTablesUnfiltered() {
        RemoteApiClient client = mock(RemoteApiClient.class);
        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        SoftaRuntimeConnector connector = new SoftaRuntimeConnector(null, client, env);
        when(client.fetchRuntimeMetadata(eq(env), eq("app"), anyString())).thenReturn(List.of());

        connector.readSchema("app");

        for (String table : List.of("SysModel", "SysField", "SysModelIndex", "SysOptionSet", "SysOptionItem")) {
            verify(client).fetchRuntimeMetadata(env, "app", table);
        }
        // No narrowed (5-arg) call on the full path.
        verify(client, never()).fetchRuntimeMetadata(any(), anyString(), anyString(), anyString(), any());
    }

    // ----------------------------------------------------------------- apply (autoExecuteDDL gate, §A)

    private static MetadataChangeSet changeSetWithDdl() {
        return new MetadataChangeSet(
                List.of(new MetaChange(MetaTable.MODEL, ChangeOp.UPSERT, Map.<String, Object>of("modelName", "Order"))),
                List.of("CREATE TABLE orders (id BIGINT);"));
    }

    @Test
    @DisplayName("apply ships the DDL when autoExecuteDDL is set (gate lives here now, §A)")
    void applyShipsDdlWhenAutoExecute() {
        RemoteApiClient client = mock(RemoteApiClient.class);
        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        env.setAutoExecuteDDL(true);
        SoftaRuntimeConnector connector = new SoftaRuntimeConnector(null, client, env);

        connector.apply("app", changeSetWithDdl());

        ArgumentCaptor<MetadataChangeSet> shipped = ArgumentCaptor.forClass(MetadataChangeSet.class);
        verify(client).applyChanges(eq(env), eq("app"), shipped.capture());
        assertEquals(List.of("CREATE TABLE orders (id BIGINT);"), shipped.getValue().ddl());
        assertEquals(1, shipped.getValue().changes().size());
    }

    @Test
    @DisplayName("apply strips the DDL when autoExecuteDDL is off (DBA runs it) — rows still ship")
    void applyStripsDdlWhenNotAutoExecute() {
        RemoteApiClient client = mock(RemoteApiClient.class);
        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        env.setAutoExecuteDDL(false);
        SoftaRuntimeConnector connector = new SoftaRuntimeConnector(null, client, env);

        connector.apply("app", changeSetWithDdl());

        ArgumentCaptor<MetadataChangeSet> shipped = ArgumentCaptor.forClass(MetadataChangeSet.class);
        verify(client).applyChanges(eq(env), eq("app"), shipped.capture());
        assertTrue(shipped.getValue().ddl().isEmpty(), "DBA runs DDL out of band → ship no DDL");
        assertEquals(1, shipped.getValue().changes().size(), "rows always ship");
    }
}
