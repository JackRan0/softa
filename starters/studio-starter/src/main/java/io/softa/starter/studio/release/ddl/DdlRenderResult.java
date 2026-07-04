package io.softa.starter.studio.release.ddl;

/**
 * Structured DDL render result.
 *
 * @param tableDdl table-related DDL
 * @param indexDdl index-related DDL
 */
public record DdlRenderResult(String tableDdl, String indexDdl) {

    public String combinedDdl() {
        return (tableDdl != null ? tableDdl : "") + (indexDdl != null ? indexDdl : "");
    }
}
