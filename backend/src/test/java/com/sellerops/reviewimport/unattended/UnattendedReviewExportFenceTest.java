package com.sellerops.reviewimport.unattended;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>This capability authorizes acquisition. The fence is that it cannot name anything else.</b>
 *
 * <p>A source scan rather than a behavioural test, for the same reason the other safety fences in this
 * repository are: the property is «this code has no way to reach that», and the way to check it is to read
 * what the code is allowed to mention. A behavioural test proves one path is closed; a name scan proves
 * there is no path.
 */
class UnattendedReviewExportFenceTest {

    private static final Path PACKAGE = Path.of("src/main/java/com/sellerops/reviewimport/unattended");

    private static Map<String, String> sources() throws IOException {
        try (Stream<Path> files = Files.list(PACKAGE)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toMap(f -> f.getFileName().toString(), f -> {
                        try {
                            return Files.readString(f);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }));
        }
    }

    /**
     * Comments stripped, the same way every other fence in this repository reads its subject. The classes
     * here EXPLAIN that they reach no publish or approval path, so a scan of the raw text would fail on the
     * sentence that states the property — and a guard that fails on its own documentation gets deleted
     * rather than fixed. The property is about the code.
     */
    private static Map<String, String> code() throws IOException {
        return sources().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "")));
    }

    @Test
    @DisplayName("the package exists and is small — one gate, one route, nothing else")
    void packageIsTwoFiles() throws IOException {
        assertThat(sources().keySet())
                .containsExactlyInAnyOrder("UnattendedReviewExportProperties.java",
                        "UnattendedReviewExportController.java");
    }

    @Test
    @DisplayName("no marketplace WRITE, approval or execution name appears anywhere in the package")
    void namesNoWritePath() throws IOException {
        List<String> forbidden = List.of(
                "Publish", "publish",           // inquiry/review answer execution
                "Approval", "approve",          // the human approval boundary
                "Execution", "execute",         // Action Executor
                "Reply", "reply",               // answering a customer
                "submission", "Submission",     // guided submission mints
                "Credential", "credential",     // the vault and handoff
                "Delete", "delete");            // nothing here removes anything
        for (Map.Entry<String, String> e : code().entrySet()) {
            for (String name : forbidden) {
                assertThat(e.getValue()).as("%s must not mention %s", e.getKey(), name)
                        .doesNotContain(name);
            }
        }
    }

    @Test
    @DisplayName("the route takes no caller-supplied target: no body, no query, no path variable")
    void routeNamesNoTarget() throws IOException {
        String controller = code().get("UnattendedReviewExportController.java");
        for (String annotation : List.of("@RequestBody", "@RequestParam", "@PathVariable")) {
            assertThat(controller).as("the unattended route must name no %s — the absence of the field is the fence",
                    annotation).doesNotContain(annotation);
        }
    }

    @Test
    @DisplayName("the channel is a constant in the source, not a property and not a parameter")
    void channelIsAConstant() throws IOException {
        String controller = code().get("UnattendedReviewExportController.java");
        assertThat(controller).contains("static final String CHANNEL_CODE = \"NAVER\"");
        assertThat(controller).doesNotContain("CAFE24").doesNotContain("COUPANG");
        // a channel read from configuration would make this capability a different, wider thing
        assertThat(controller).doesNotContain("${sellerops");
    }

    @Test
    @DisplayName("the gate reads three values and admits on all three")
    void gateIsThreeValued() throws IOException {
        String props = code().get("UnattendedReviewExportProperties.java");
        assertThat(props).contains("sellerops.review-import.unattended.enabled:false");
        assertThat(props).contains("sellerops.review-import.unattended.org-ids");
        assertThat(props).contains("sellerops.review-import.unattended.device-ids");
    }

    @Test
    @DisplayName("application.yml ships the capability OFF")
    void shippedOff() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("enabled: ${SELLEROPS_REVIEW_IMPORT_UNATTENDED_ENABLED:false}");
    }
}
