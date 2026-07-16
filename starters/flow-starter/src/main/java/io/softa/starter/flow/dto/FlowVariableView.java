package io.softa.starter.flow.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One variable available to a node's expressions — the payload behind the
 * editor's autocomplete for expression inputs and {@code variableRef} pickers.
 */
@Schema(name = "FlowVariableView")
public record FlowVariableView(

        @Schema(description = "Variable name as referenced in expressions")
        String name,

        @Schema(description = "Where the variable comes from")
        Source source,

        @Schema(description = "Producing node id; null for trigger/builtin variables")
        String sourceNodeId,

        @Schema(description = "Human-readable origin, e.g. the producing node's label or the trigger model")
        String sourceLabel,

        @Schema(description = "Value type when derivable — the platform FieldType wire value "
                + "(String/Integer/DateTime/ManyToOne/...) for trigger fields, or a coarse "
                + "Object/List/String for node outputs; null = unknown")
        String type,

        @Schema(description = "Model name when the variable is record-shaped (relation target or data-node model)")
        String modelName
) {

    @Getter
    @AllArgsConstructor
    public enum Source {
        TRIGGER("Trigger"),
        NODE_OUTPUT("NodeOutput"),
        BUILTIN("Builtin");

        @JsonValue
        private final String type;
    }
}
