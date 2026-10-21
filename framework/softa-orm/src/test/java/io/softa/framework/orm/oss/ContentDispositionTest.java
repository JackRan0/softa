package io.softa.framework.orm.oss;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentDispositionTest {

    @Test
    void carriesTheNameBothAsAsciiAndAsUtf8() {
        assertThat(ContentDisposition.of(ContentDisposition.ATTACHMENT, "Contract 2026.pdf"))
                .isEqualTo("attachment; filename=\"Contract 2026.pdf\"; filename*=UTF-8''Contract%202026.pdf");
    }

    @Test
    void keepsANonAsciiNameIntactInTheUtf8Form() {
        String header = ContentDisposition.of(ContentDisposition.INLINE, "劳动合同.pdf");

        assertThat(header).startsWith("inline; filename=\"____.pdf\";");
        assertThat(header).endsWith("filename*=UTF-8''%E5%8A%B3%E5%8A%A8%E5%90%88%E5%90%8C.pdf");
    }

    @Test
    void neverLetsTheNameBreakOutOfTheQuotedValue() {
        assertThat(ContentDisposition.asciiFallback("a\"b\\c\nd.pdf")).isEqualTo("a_b_c_d.pdf");
    }

    @Test
    void aBlankNameGivesTheBareType() {
        assertThat(ContentDisposition.of(ContentDisposition.ATTACHMENT, " ")).isEqualTo("attachment;");
        assertThat(ContentDisposition.of(ContentDisposition.ATTACHMENT, null)).isEqualTo("attachment;");
    }

    @Test
    void encodesAStarWhichTheExtendedFormDoesNotAllow() {
        assertThat(ContentDisposition.of(ContentDisposition.ATTACHMENT, "a*b.pdf"))
                .endsWith("filename*=UTF-8''a%2Ab.pdf");
    }
}
