package io.softa.starter.studio.release.upgrade;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link DdlSqlSplitter}. Verifies the behaviour the deploy
 * envelope assembly relies on.
 */
class DdlSqlSplitterTest {

    @Test
    void splitsConcatenatedTableAndIndexDdl() {
        String tableDdl = "CREATE TABLE foo (id BIGINT);\nALTER TABLE bar ADD COLUMN baz INT;\n";
        String indexDdl = "CREATE INDEX idx_bar_baz ON bar(baz);\n";

        List<String> out = DdlSqlSplitter.split(tableDdl, indexDdl);

        assertEquals(List.of(
                "CREATE TABLE foo (id BIGINT)",
                "ALTER TABLE bar ADD COLUMN baz INT",
                "CREATE INDEX idx_bar_baz ON bar(baz)"
        ), out);
    }

    @Test
    void trimsAndDropsEmptyFragments() {
        String tableDdl = ";;CREATE TABLE foo ();  ;\n;";

        List<String> out = DdlSqlSplitter.split(tableDdl, null);

        assertEquals(List.of("CREATE TABLE foo ()"), out);
    }

    @Test
    void emptyOrNullInputsReturnEmptyList() {
        assertTrue(DdlSqlSplitter.split(null, null).isEmpty());
        assertTrue(DdlSqlSplitter.split("", "").isEmpty());
        assertTrue(DdlSqlSplitter.split("   ", "\n").isEmpty());
    }

    @Test
    void preservesOrderTableBeforeIndex() {
        // Deploy needs CREATE TABLE applied before CREATE INDEX (FK/dependency ordering).
        // The splitter must concatenate tableDdl first.
        List<String> out = DdlSqlSplitter.split("CREATE TABLE a ();", "CREATE INDEX i ON a ();");
        assertEquals(2, out.size());
        assertTrue(out.get(0).startsWith("CREATE TABLE"));
        assertTrue(out.get(1).startsWith("CREATE INDEX"));
    }

    @Test
    void keepsSemicolonsInsideCommentsAndLiterals() {
        String tableDdl = """
                /* model; comment */
                CREATE TABLE foo (
                    name VARCHAR(50) COMMENT 'a;b'
                );
                -- field; comment
                ALTER TABLE foo ADD note VARCHAR(50) DEFAULT 'x;y';
                """;

        List<String> out = DdlSqlSplitter.split(tableDdl, null);

        assertEquals(2, out.size());
        assertTrue(out.get(0).startsWith("/* model; comment */"));
        assertTrue(out.get(0).contains("COMMENT 'a;b'"));
        assertTrue(out.get(1).startsWith("-- field; comment"));
        assertTrue(out.get(1).contains("DEFAULT 'x;y'"));
        assertTrue(out.stream().noneMatch(statement -> statement.endsWith(";")));
    }
}
