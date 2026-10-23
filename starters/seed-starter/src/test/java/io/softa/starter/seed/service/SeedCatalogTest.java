package io.softa.starter.seed.service;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * A seed file's checksum is what decides whether it loads again, so it must not change with the line endings
 * a checkout happens to use.
 */
class SeedCatalogTest {

    @Test
    void crlfReadsAsLf() {
        assertArrayEquals(bytes("{\n  \"id\": \"a\"\n}\n"), SeedCatalog.withLfLineEndings(bytes("{\r\n  \"id\": \"a\"\r\n}\r\n")));
    }

    @Test
    void aFileWithLfLineEndingsHashesAsItAlwaysDid() {
        // Every record written before CRLF was read as LF still matches its file.
        byte[] lf = bytes("{\n  \"label\": \"Résumé\"\n}\n");
        assertArrayEquals(lf, SeedCatalog.withLfLineEndings(lf));
    }

    @Test
    void aCarriageReturnOfItsOwnIsContent() {
        byte[] lone = bytes("a\rb");
        assertArrayEquals(lone, SeedCatalog.withLfLineEndings(lone));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
