package com.aravindakv.hiretrack.resume.service;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.resume.TestPdfs;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extractsTextFromATextPdf() {
        assertThat(extractor.extract(TestPdfs.withText("Java Spring Kafka"))).isEqualTo("Java Spring Kafka");
    }

    @Test
    void scannedPdfIsRejectedWithAHelpfulCode() {
        assertThatThrownBy(() -> extractor.extract(TestPdfs.blankPage()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PDF_HAS_NO_TEXT"));
    }

    @Test
    void nonPdfIsRejectedByMagicBytes() {
        assertThatThrownBy(() -> extractor.extract("hello".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_A_PDF"));
    }

    @Test
    void normaliseCollapsesSpacesButKeepsParagraphs() {
        assertThat(PdfTextExtractor.normalise("  a   b \r\n\n\n\n c  ")).isEqualTo("a b\n\nc");
    }
}