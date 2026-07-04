package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.metadata.ddl.BuiltinDdlDialects;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.studio.release.connector.Connector;
import io.softa.starter.studio.release.ddl.DdlRenderResult;
import io.softa.starter.studio.release.ddl.MetadataChangeDdlRenderer;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.dto.RowChangeOp;
import io.softa.starter.studio.release.entity.DesignAppEnv;

/**
 * The shared apply half ({@link DesiredStateDeployService#applyToRuntime}): render
 * the rename-aware DDL on the connector's dialect, flatten the changes into the incremental
 * {@link MetadataChangeSet} (per-row CRUD keyed by business key), and hand the <b>full</b> set to
 * {@link Connector#apply} — the connector decides what to ship/execute (the autoExecuteDDL
 * gate moved into {@code SoftaRuntimeConnector.apply}, so this service no longer gates). An empty change
 * set ships nothing. Collaborators mocked.
 */
class DesiredStateDeployServiceTest {

    private static DesignAppEnv env() {
        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        env.setAppId(7L);
        return env;
    }

    /** One flat CREATE row-change (wire shape: self-describing via {@code op} + {@code table}). */
    private static RowChangeDTO oneCreatedRow() {
        RowChangeDTO row = new RowChangeDTO();
        row.setOp(RowChangeOp.CREATE);
        row.setTable(MetaTable.MODEL);
        Map<String, Object> data = new HashMap<>();
        data.put("modelName", "Order");
        row.setFullRow(data);
        return row;
    }

    /** A mocked connector whose dialect is the real builtin MySQL dialect; {@code apply} is verified. */
    private static Connector connector() {
        Connector connector = mock(Connector.class);
        when(connector.dialect()).thenReturn(BuiltinDdlDialects.registry().getDialect(DatabaseType.MYSQL));
        return connector;
    }

    private record Mocks(MetadataChangeDdlRenderer ddlRenderer, DesiredStateDeployService service) {
    }

    private static Mocks wire() {
        MetadataChangeDdlRenderer ddlRenderer = mock(MetadataChangeDdlRenderer.class);
        return new Mocks(ddlRenderer, new DesiredStateDeployService(ddlRenderer));
    }

    @Test
    @DisplayName("hands the connector the FULL rendered DDL + rows (no gating here — §A)")
    void handsConnectorFullDdlAndRows() {
        Mocks m = wire();
        Connector connector = connector();
        when(m.ddlRenderer().generateDdlResult(any(DdlDialect.class), any()))
                .thenReturn(new DdlRenderResult("CREATE TABLE orders (id BIGINT);", ""));

        DesiredStateDeployService.Applied applied = m.service()
                .applyToRuntime(env(), "demo-app", connector, List.of(oneCreatedRow()));

        // The connector receives the full DDL + rows — the deploy service does NOT apply the autoExecuteDDL
        // gate (that moved into SoftaRuntimeConnector.apply, §A).
        ArgumentCaptor<MetadataChangeSet> sent = ArgumentCaptor.forClass(MetadataChangeSet.class);
        verify(connector).apply(eq("demo-app"), sent.capture());
        assertFalse(sent.getValue().changes().isEmpty());
        assertTrue(sent.getValue().ddl().stream().anyMatch(s -> s.contains("CREATE TABLE orders")),
                "connector receives the full rendered DDL");
        // The full DDL is also returned for the audit record.
        assertTrue(applied.combinedDdl().contains("CREATE TABLE orders"));
    }

    @Test
    @DisplayName("empty change set: ships nothing (no apply)")
    void skipsWhenInSync() {
        Mocks m = wire();
        Connector connector = connector();

        MetadataChangeSet changeSet = m.service()
                .applyToRuntime(env(), "demo-app", connector, List.of())
                .changeSet();

        assertTrue(changeSet.isEmpty());
        verify(connector, never()).apply(any(), any());
    }
}
