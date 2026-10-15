package io.softa.framework.orm.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeContentTypeTest {

    @Test
    void namesTheMimeTypeOfTheExtension() {
        assertThat(FileType.contentTypeOf("EmpDocumentFile/2026/contract.pdf")).isEqualTo("application/pdf");
        assertThat(FileType.contentTypeOf("scan.PNG")).isEqualTo("image/png");
        assertThat(FileType.contentTypeOf("scan.jpeg")).isEqualTo("image/jpeg");
    }

    @Test
    void fallsBackToOctetStreamWhenTheExtensionSaysNothing() {
        assertThat(FileType.contentTypeOf("README")).isEqualTo("application/octet-stream");
        assertThat(FileType.contentTypeOf("archive.unknownext")).isEqualTo("application/octet-stream");
        assertThat(FileType.contentTypeOf("trailing.")).isEqualTo("application/octet-stream");
        assertThat(FileType.contentTypeOf(null)).isEqualTo("application/octet-stream");
    }
}
