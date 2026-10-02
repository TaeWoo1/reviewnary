package com.sellerops.operationscase;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A review row never prints its own sentence twice</b> (product-owner decision, 2026-10-02).
 *
 * <p>확인할 일's row leads with the customer's own words. For a review that was already true — the row's
 * {@code title} IS the review body — so the new {@code preview} field must stay null there, or every
 * review row would draw the same sentence on two lines.
 *
 * <p>The inquiry halves of this contract are proved behaviourally against a real stored inquiry in
 * {@code StoredInquiryCaseFlowTest}. This one fences the review branch, which has no case fixture here:
 * the suppression is a {@code null} literal, and a null that is never asserted is a null that comes back
 * as a body on the next edit.
 */
class DecisionRowPreviewTest {

    private static final Path VIEW =
            Paths.get("src/main/java/com/sellerops/operationscase/dto/CustomerOperationsHomeView.java");
    private static final Path SERVICE =
            Paths.get("src/main/java/com/sellerops/operationscase/CustomerOperationsHomeService.java");

    private static String code(Path path) throws IOException {
        return Files.readString(path).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("the row carries the customer's words beside the customer's subject line")
    void rowCarriesThePreview() throws IOException {
        assertThat(code(VIEW))
                .as("title is the customer's subject line; preview is what they actually wrote")
                .contains("String title, String preview");
    }

    @Test
    @DisplayName("a review contributes no preview — its title is already its body")
    void reviewSuppressesThePreview() throws IOException {
        assertThat(code(SERVICE))
                .as("the review branch passes the body as the title and null as the preview")
                .contains("new Subject(preview(MarkupText.toPlainText(x.getBody())), null, x.getRating(),");
    }
}
