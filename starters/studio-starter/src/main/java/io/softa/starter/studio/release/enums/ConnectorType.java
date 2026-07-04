package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The kind of target a {@link io.softa.starter.studio.release.entity.DesignAppEnv} connects to:
 * a Softa runtime (signed upgrade API) or a raw JDBC database. Selects which
 * {@link io.softa.starter.studio.release.connector.Connector} the factory builds for the env.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum ConnectorType {

    SOFTA("Softa"),

    @OptionItem(label = "JDBC")
    JDBC("JDBC");

    @JsonValue
    private final String code;
}
