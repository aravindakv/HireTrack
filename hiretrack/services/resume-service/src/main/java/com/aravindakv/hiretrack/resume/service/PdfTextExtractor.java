package com.aravindakv.hiretrack.resume.service;

import com.aravindakv.hiretrack.common.web.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
public class PdfTextExtractor {

    private static final byte[] MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    public String extract(byte[] bytes) {
        if (bytes.length < MAGIC.length || !Arrays.equals(Arrays.copyOf(bytes, MAGIC.length), MAGIC)) {
            throw ApiException.unprocessable("NOT_A_PDF", "The file is not a PDF.");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw ApiException.unprocessable("PDF_ENCRYPTED", "This PDF is password-protected. Remove the password or paste the text.");
            }
            String text = normalise(new PDFTextStripper().getText(document));
            if (text.isEmpty()) {
                throw ApiException.unprocessable("PDF_HAS_NO_TEXT",
                        "We couldn't read any text in this PDF. It may be a scanned image. Paste the text instead.");
            }
            return text;
        } catch (IOException e) {
            throw ApiException.unprocessable("PDF_UNREADABLE", "This PDF couldn't be opened. Paste the text instead.");
        }
    }

    /** Collapse runs of spaces, keep line breaks, trim. */
    static String normalise(String raw) {
        return raw.replace("\r", "")
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}