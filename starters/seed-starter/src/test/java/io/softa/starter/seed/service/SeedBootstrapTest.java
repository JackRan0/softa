package io.softa.starter.seed.service;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.seed.dto.PlatformLoadResult;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bootstrap runs on a thread of its own after startup: whatever goes wrong is logged for the next start to
 * retry, never thrown into a thread nobody joins.
 */
class SeedBootstrapTest {

    private final PlatformSeedLoader loader = mock(PlatformSeedLoader.class);
    private final SeedBootstrap bootstrap = new SeedBootstrap(loader);

    @Test
    void aFailedLoadIsLogged() {
        when(loader.loadBootstrapFiles()).thenReturn(Optional.of(
                new PlatformLoadResult(42L, false, List.of(), "Navigation.json", "Duplicate entry")));

        assertDoesNotThrow(bootstrap::loadBootstrapFiles);
        verify(loader).loadBootstrapFiles();
    }

    @Test
    void aLoadThatCannotStartIsLogged() {
        when(loader.loadBootstrapFiles()).thenThrow(new BusinessException("A seed data operation is still running."));

        assertDoesNotThrow(bootstrap::loadBootstrapFiles);
    }
}
