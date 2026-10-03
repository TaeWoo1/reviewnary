package com.sellerops.operationscase;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Both halves of the elapsed-time contract stay on the wire</b> (product-owner decision, 2026-10-02).
 *
 * <p>The defect: 확인할 일's list dated a case by {@code openedAt} — when reviewnary opened OUR record —
 * while the pane beside it dated the same case by the subject's {@code receivedOn}. One item, 8일 대기 in
 * the row and 9일 대기 in the detail.
 *
 * <p>The frontend now picks the source in one function, and that function can only prefer the customer's
 * own time if the customer's own time ARRIVES. If either field is dropped here the frontend silently
 * falls back and the two numbers diverge again with nothing failing — so the wire is fenced, not the
 * arithmetic.
 */
class ElapsedTimeSourceTest {

    private static final Path VIEW =
            Paths.get("src/main/java/com/sellerops/operationscase/dto/CustomerOperationsHomeView.java");
    private static final Path SERVICE =
            Paths.get("src/main/java/com/sellerops/operationscase/CustomerOperationsHomeService.java");
    private static final Path DETAIL =
            Paths.get("src/main/java/com/sellerops/knowledge/teach/dto/CaseDetailView.java");
    private static final Path DETAIL_SERVICE =
            Paths.get("src/main/java/com/sellerops/knowledge/teach/CaseKnowledgeService.java");

    private static String code(Path path) throws IOException {
        return Files.readString(path).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("the list row carries the customer's own event date, not only when we opened the case")
    void rowCarriesCustomerTime() throws IOException {
        assertThat(code(VIEW))
                .as("the row the list is built from must hand over the customer's clock")
                .contains("Instant openedAt, java.time.LocalDate receivedOn, String to");

        String service = code(SERVICE);
        assertThat(service)
                .as("resolved off the subject record this method already loads — the inquiry's own receivedAt")
                .contains("receivedOn(i.getReceivedAt())")
                .contains("receivedOn(x.getReceivedAt())");
        assertThat(service)
                .as("KST, the zone the detail's receivedOn is in; one conversion, written once")
                .contains("at.atZone(java.time.ZoneId.of(\"Asia/Seoul\")).toLocalDate()");
        assertThat(service)
                .as("and it reaches the row")
                .contains("subject.receivedOn(), linkOf(c)");
    }

    @Test
    @DisplayName("the detail carries the fallback, so both sides apply one contract to the same two facts")
    void detailCarriesFallback() throws IOException {
        assertThat(code(DETAIL))
                .as("openedAt is the elapsed-time FALLBACK and the pane needs it to apply the same rule")
                .contains("List<Media> media, java.time.Instant openedAt");
        assertThat(code(DETAIL_SERVICE))
                .as("off the case itself — never estimated, never offset")
                // The trailing «)» became a «,» when the answer-state note joined the view after it
                // (2026-10-03). What is fenced is the SOURCE of the fallback, and that is unchanged.
                .contains("c.getCreatedAt(),");
    }
}
