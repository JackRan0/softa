package io.softa.starter.studio.release.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.softa.starter.studio.release.entity.DesignActivity;
import io.softa.starter.studio.release.entity.DesignSnapshot;
import io.softa.starter.studio.release.enums.DesignActivityKind;
import io.softa.starter.studio.release.enums.DesignActivityStatus;
import io.softa.starter.studio.release.service.DesignSnapshotService;

/**
 * DesignActivityServiceImpl: the unified synchronous-operation audit. start opens a
 * RUNNING record; succeed/fail close it terminally; snapshot persists via DesignSnapshotService.
 * CRUD is stubbed on a spy (the ORM layer is out of scope here).
 */
class DesignActivityServiceImplTest {

    private final DesignSnapshotService snapshotService = mock(DesignSnapshotService.class);
    private final DesignActivityServiceImpl service =
            Mockito.spy(new DesignActivityServiceImpl(snapshotService));

    @Test
    void startOpensRunningActivity() {
        doReturn(7L).when(service).createOne(any(DesignActivity.class));

        DesignActivity activity = service.start(100L, 5L, DesignActivityKind.PUBLISH, null, 42L);

        assertEquals(7L, activity.getId());
        assertEquals(100L, activity.getAppId());
        assertEquals(5L, activity.getEnvId());
        assertEquals(DesignActivityKind.PUBLISH, activity.getKind());
        assertEquals(DesignActivityStatus.RUNNING, activity.getStatus());
        assertEquals(42L, activity.getOperatorId());
        assertNotNull(activity.getStartedTime());
    }

    @Test
    void succeedClosesWithSnapshotLink() {
        DesignActivity activity = new DesignActivity();
        activity.setId(7L);
        activity.setStatus(DesignActivityStatus.RUNNING);
        doReturn(Optional.of(activity)).when(service).getById(7L);
        doReturn(true).when(service).updateOne(any(DesignActivity.class));

        service.succeed(7L, null, null, 99L);

        assertEquals(DesignActivityStatus.SUCCESS, activity.getStatus());
        assertEquals(99L, activity.getSnapshotId());
        assertNotNull(activity.getFinishedTime());
        verify(service).updateOne(activity);
    }

    @Test
    void failClosesWithMessage() {
        DesignActivity activity = new DesignActivity();
        activity.setId(7L);
        activity.setStatus(DesignActivityStatus.RUNNING);
        doReturn(Optional.of(activity)).when(service).getById(7L);
        doReturn(true).when(service).updateOne(any(DesignActivity.class));

        service.fail(7L, "boom");

        assertEquals(DesignActivityStatus.FAILURE, activity.getStatus());
        assertEquals("boom", activity.getErrorMessage());
        assertNotNull(activity.getFinishedTime());
        verify(service).updateOne(activity);
    }

    @Test
    void snapshotPersistsLinkedToActivity() {
        when(snapshotService.createOne(any(DesignSnapshot.class))).thenReturn(55L);

        Long snapshotId = service.snapshot(7L, null);

        assertEquals(55L, snapshotId);
        ArgumentCaptor<DesignSnapshot> captor = ArgumentCaptor.forClass(DesignSnapshot.class);
        verify(snapshotService).createOne(captor.capture());
        assertEquals(7L, captor.getValue().getActivityId());
    }
}
