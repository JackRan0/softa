package io.softa.starter.seed.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loads the bootstrap platform files once the application has started, where they never loaded — see
 * {@link PlatformSeedLoader#loadBootstrapFiles}. In a new environment, or one moving onto this starter, it is what
 * puts the Seed Data page in the platform administrator's menu.
 *
 * <p>In the background, so startup does not wait for it, and after startup, when the tables exist. A failure is
 * logged and recorded against the file like any other load, and the next start tries again; until then
 * {@code POST /SeedData/platform/load} naming the files does the same.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeedBootstrap {

    private final PlatformSeedLoader platformLoader;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread.ofVirtual().name("seed-bootstrap").start(this::loadBootstrapFiles);
    }

    void loadBootstrapFiles() {
        try {
            platformLoader.loadBootstrapFiles().ifPresent(result -> {
                if (result.succeeded()) {
                    log.info("Seed bootstrap loaded {}: the Seed Data page is in the platform administrator's menu",
                            result.loadedFiles());
                } else {
                    log.error("Seed bootstrap failed ({}): {}. The next start tries again; until then, load the files "
                            + "with POST /SeedData/platform/load.",
                            result.failedFile() == null ? "before any file" : result.failedFile(), result.errorMessage());
                }
            });
        } catch (RuntimeException e) {
            log.error("Seed bootstrap did not run: {}. The next start tries again.", e.getMessage(), e);
        }
    }
}
