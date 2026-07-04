package io.softa.starter.metadata.message.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum InnerBroadcastType {
    RELOAD_METADATA("ReloadMetadata");

    @JsonValue
    private final String type;

}
