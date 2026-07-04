package io.softa.starter.metadata.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * Response of the runtime aggregate-checksum endpoint: the runtime's
 * current per-aggregate checksums, keyed by business key. Lightweight — the studio compares
 * these against its design-side checksums and pulls full rows only for the aggregates whose
 * checksum differs (the network gate).
 *
 * @param models     {@code modelName → aggregate checksum}
 * @param optionSets {@code optionSetCode → aggregate checksum}
 */
public record RuntimeChecksumsDTO(Map<String, String> models, Map<String, String> optionSets)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
