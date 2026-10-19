package io.softa.framework.orm.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The object key carries the file's extension, never its name: a long name used to push the key past
 * its column and fail the upload.
 */
class FileServiceImplOssKeyTest {

    @Test
    void takesOnlyTheExtension() {
        String longName = "AICT0944U6 " + "M".repeat(200) + "_20261015.pdf";

        assertThat(FileServiceImpl.extensionOf(longName)).isEqualTo(".pdf");
        assertThat(FileServiceImpl.extensionOf("scan.final.JPEG")).isEqualTo(".JPEG");
    }

    @Test
    void aNameWithoutARealExtensionGivesNone() {
        assertThat(FileServiceImpl.extensionOf("README")).isEmpty();
        assertThat(FileServiceImpl.extensionOf("trailing.")).isEmpty();
        assertThat(FileServiceImpl.extensionOf("v1.2 final copy")).isEmpty();
        assertThat(FileServiceImpl.extensionOf("archive.averyverylongext")).isEmpty();
        assertThat(FileServiceImpl.extensionOf(null)).isEmpty();
    }
}
