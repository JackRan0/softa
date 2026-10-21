package io.softa.framework.orm.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.softa.framework.base.exception.IllegalArgumentException;

/**
 * A file's name has to fit {@code FileRecord.fileName}: a name a person picked is refused, a name the
 * system made up is cut.
 */
class FileServiceImplFileNameTest {

    private static final int MAX = FileServiceImpl.MAX_FILE_NAME_LENGTH;

    @Test
    void aNameThatFitsIsKeptAsItIs() {
        String name = "N".repeat(MAX - 4) + ".pdf";

        assertThat(FileServiceImpl.fitFileName(name)).isEqualTo(name);
        assertThat(FileServiceImpl.fitFileName(null)).isNull();
    }

    @Test
    void aLongGeneratedNameIsCutKeepingItsDateAndExtension() {
        String name = "Employment Contract — " + "A".repeat(200) + " (signed)_20261020.pdf";

        String fitted = FileServiceImpl.fitFileName(name);

        assertThat(fitted).hasSize(MAX).startsWith("Employment Contract — AAA").endsWith("A_20261020.pdf");
    }

    @Test
    void aLongNameWithoutADateKeepsItsExtension() {
        String fitted = FileServiceImpl.fitFileName("x".repeat(300) + ".xlsx");

        assertThat(fitted).hasSize(MAX).endsWith("x.xlsx");
    }

    @Test
    void neverEndsOnHalfAnEmoji() {
        // The emoji's high surrogate would be the last char kept before the extension.
        String name = "a".repeat(MAX - 5) + "😀" + "b".repeat(50) + ".pdf";

        String fitted = FileServiceImpl.fitFileName(name);

        assertThat(fitted).hasSize(MAX - 1).isEqualTo("a".repeat(MAX - 5) + ".pdf");
    }

    @Test
    void aPickedFileWithATooLongNameIsRefusedBeforeAnythingIsStored() {
        // A bare service: anything past the check would need storage and a database.
        FileServiceImpl service = new FileServiceImpl();
        MockMultipartFile file = new MockMultipartFile("file", "S".repeat(MAX - 3) + ".pdf",
                "application/pdf", new byte[]{1});

        assertThatThrownBy(() -> service.uploadFile("EmpDocumentFile", file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File name is too long (max 128 characters)")
                .hasMessageNotContaining("SSSS");
    }
}
