package io.softa.starter.seed;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import io.softa.starter.seed.manifest.SeedManifest;
import io.softa.starter.seed.manifest.SeedManifestReader;

/**
 * Seed starter auto configuration.
 *
 * <p>The application's {@code seed-manifest.yml} is read and checked at startup. An application that includes
 * this starter without a manifest, or with a broken one, does not start: the seed files it ships would
 * otherwise only fail when someone loads them.
 */
@ComponentScan
public class SeedAutoConfiguration {

    @Bean
    public SeedManifest seedManifest() {
        return SeedManifestReader.readClasspath();
    }
}
