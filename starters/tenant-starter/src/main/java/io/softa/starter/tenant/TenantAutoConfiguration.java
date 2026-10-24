package io.softa.starter.tenant;

import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Tenant module auto configuration
 */
@ComponentScan
public class TenantAutoConfiguration {

    /**
     * Caps how many times a failed seed message is redelivered.
     *
     * <p>Uncapped, a tenant whose seed can never succeed retries forever: it holds a consumer slot and repeats
     * the same failure in the log — which also buries the {@code [SEED_FAILURE]} line each seed consumer
     * writes, the one line worth finding, under thousands of copies of itself. Capped, a permanent failure
     * prints three and stops.
     *
     * <p>Three attempts, spaced by {@code spring.pulsar.consumer.negative-ack-redelivery-delay}, covers an
     * application restart. That delay is not optional alongside this: at Pulsar's default the three are spent
     * in seconds, so a failure lasting as long as a restart exhausts them and writes off a tenant that would
     * have seeded fine on its own.
     *
     * <p>The type is {@code DeadLetterPolicy} because that is Pulsar's only way to bound redelivery — an
     * exhausted message has to go somewhere, so it lands on {@code <topic>-<subscription>-DLQ}. Nothing reads
     * that topic and nothing is meant to: the bucket is a side effect of the cap, not a feature. The cap is also
     * how a seeder knows its failure is final: it reports each failure with the message's redelivery count, the
     * tenant stays Initializing while deliveries remain, and the last one's failure leaves a Draft tenant for
     * Retry Setup; which seeder failed comes from its progress row and the log. Nothing times a setup out: a tenant
     * whose message never arrives stays Initializing until someone sets it to Draft.
     */
    @Bean("tenantSeedRetryPolicy")
    @ConditionalOnMissingBean(name = "tenantSeedRetryPolicy")
    public DeadLetterPolicy tenantSeedRetryPolicy() {
        return DeadLetterPolicy.builder()
                .maxRedeliverCount(3)
                .build();
    }
}
