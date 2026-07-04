package io.softa.starter.studio.release.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * What a {@link io.softa.starter.studio.release.entity.DesignActivity} did: the studio
 * operations against an env — {@code PUBLISH} / {@code IMPORT} / {@code REVERSE} / {@code MERGE}.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum DesignActivityKind {

    /** design → runtime synchronous converge (the studio publish). */
    PUBLISH("Publish"),

    /** Softa runtime → design import: reverse the env's {@code sys_*} catalog (full fidelity) into design. */
    IMPORT("Import"),

    /**
     * Raw JDBC database → design reverse: reverse-engineer the physical schema
     * ({@code information_schema} / {@code DatabaseMetaData}) into design — structural only, no option sets.
     */
    REVERSE("Reverse"),

    /** env → env design merge (converge a target env's design to a source env's). */
    MERGE("Merge"),
    ;

    @JsonValue
    private final String kind;
}
