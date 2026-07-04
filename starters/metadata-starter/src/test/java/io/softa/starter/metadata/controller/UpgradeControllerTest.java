package io.softa.starter.metadata.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.metadata.dto.RuntimeExportFilter;
import io.softa.starter.metadata.service.MetadataApplyService;
import io.softa.starter.metadata.service.MetadataService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit unit tests for {@link UpgradeController} — the runtime side of the studio↔runtime contract.
 * The signature (proved by {@code SignatureVerificationFilter}) says <i>who</i> is calling; the app-code
 * handshake ({@code assertAppCode}) proves the call was addressed to <i>this</i> app. These pin that every
 * routing endpoint rejects a foreign {@code appCode} <b>before</b> any work is dispatched, fails closed when
 * the runtime has no configured identity, and otherwise delegates: reads to {@link MetadataService}, the
 * apply to {@link MetadataApplyService} (including the full-vs-narrowed export branch).
 */
class UpgradeControllerTest {

    private static final MetadataChangeSet CHANGE_SET = new MetadataChangeSet(List.of(), List.of());

    private static UpgradeController controllerWith(MetadataService service,
                                                    MetadataApplyService applyService,
                                                    String runtimeAppCode) {
        SystemConfig systemConfig = new SystemConfig();
        systemConfig.setAppCode(runtimeAppCode);
        return new UpgradeController(service, applyService, systemConfig);
    }

    @Test
    void applyChangesDelegatesToApplyServiceWhenAppCodeMatches() {
        MetadataService service = mock(MetadataService.class);
        MetadataApplyService applyService = mock(MetadataApplyService.class);
        UpgradeController controller = controllerWith(service, applyService, "demo-app");

        ApiResponse<Boolean> response = controller.applyChanges("demo-app", CHANGE_SET);

        assertEquals(Integer.valueOf(200), response.getCode());
        assertTrue(response.getData());
        verify(applyService).applyChanges(CHANGE_SET);
    }

    @Test
    void applyChangesRejectsForeignAppCodeBeforeDispatch() {
        MetadataApplyService applyService = mock(MetadataApplyService.class);
        UpgradeController controller = controllerWith(mock(MetadataService.class), applyService, "demo-app");

        assertThrows(IllegalArgumentException.class,
                () -> controller.applyChanges("other-app", CHANGE_SET));
        // The handshake fires before the change set is applied — a mis-addressed envelope never lands.
        verifyNoInteractions(applyService);
    }

    @Test
    void exportRuntimeMetadataRejectsForeignAppCode() {
        MetadataService service = mock(MetadataService.class);
        UpgradeController controller = controllerWith(service, mock(MetadataApplyService.class), "demo-app");

        assertThrows(IllegalArgumentException.class,
                () -> controller.exportRuntimeMetadata("SysModel", "other-app", null));
        verifyNoInteractions(service);
    }

    @Test
    void exportRuntimeChecksumsRejectsForeignAppCode() {
        MetadataService service = mock(MetadataService.class);
        UpgradeController controller = controllerWith(service, mock(MetadataApplyService.class), "demo-app");

        assertThrows(IllegalArgumentException.class,
                () -> controller.exportRuntimeChecksums("other-app"));
        verifyNoInteractions(service);
    }

    @Test
    void handshakeFailsClosedWhenRuntimeAppCodeUnconfigured() {
        MetadataService service = mock(MetadataService.class);
        MetadataApplyService applyService = mock(MetadataApplyService.class);
        // A runtime with no system.app-code cannot answer any upgrade call — the handshake is
        // unsatisfiable, so it must reject even a well-formed request rather than serve it blindly.
        UpgradeController controller = controllerWith(service, applyService, "");

        assertThrows(IllegalArgumentException.class,
                () -> controller.exportRuntimeChecksums("demo-app"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.applyChanges("demo-app", CHANGE_SET));
        verifyNoInteractions(service);
        verifyNoInteractions(applyService);
    }

    @Test
    void exportRuntimeMetadataFullExportPassesNoNarrowingWhenFilterNull() {
        MetadataService service = mock(MetadataService.class);
        UpgradeController controller = controllerWith(service, mock(MetadataApplyService.class), "demo-app");
        List<Map<String, Object>> rows = List.of(Map.of("modelName", "Account"));
        when(service.exportRuntimeMetadata("SysModel", "demo-app", null, null)).thenReturn(rows);

        ApiResponse<List<Map<String, Object>>> response =
                controller.exportRuntimeMetadata("SysModel", "demo-app", null);

        assertEquals(1, response.getData().size());
        // A null filter is a full app-scoped export — no aggregate-key narrowing reaches the service.
        verify(service).exportRuntimeMetadata("SysModel", "demo-app", null, null);
    }

    @Test
    void exportRuntimeMetadataNarrowedPassesFilterKeys() {
        MetadataService service = mock(MetadataService.class);
        UpgradeController controller = controllerWith(service, mock(MetadataApplyService.class), "demo-app");
        RuntimeExportFilter filter = new RuntimeExportFilter("modelName", List.of("Account", "Order"));
        when(service.exportRuntimeMetadata("SysField", "demo-app", "modelName", List.of("Account", "Order")))
                .thenReturn(List.of());

        controller.exportRuntimeMetadata("SysField", "demo-app", filter);

        verify(service).exportRuntimeMetadata("SysField", "demo-app", "modelName", List.of("Account", "Order"));
    }
}
