package io.softa.starter.studio.release.constant;

import java.time.Duration;

/**
 * HTTP-level configuration for the studio → runtime RestClient
 */
public interface ReleaseConstant {

    /** TCP connect timeout. Short — any connect taking longer is almost certainly a bad endpoint. */
    Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Socket read timeout. Covers the slowest single call (metadata export / upgrade). */
    Duration READ_TIMEOUT = Duration.ofSeconds(60);
}
