package io.softa.starter.seed.manifest;

import java.util.List;
import lombok.Getter;

/**
 * A seed manifest that cannot be used, with everything wrong with it.
 */
@Getter
public class SeedManifestException extends IllegalStateException {

    /**
     * -- GETTER --
     * Each problem found, one sentence each.
     */
    private final transient List<String> problems;

    public SeedManifestException(String source, List<String> problems) {
        super("Invalid seed manifest " + source + ":\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

}
