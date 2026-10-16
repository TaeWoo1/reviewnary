package com.sellerops.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every request to a model provider passes {@link LlmSendGate}, and only the prepared body is sent</b> —
 * proposal §17.9 AI 경계 1–3.
 *
 * <p>The model hosts are reachable on a seller's PC only under {@code Destination.LLM} ({@link OutboundGate}), so the
 * files that check that destination are exactly the model transports. Counted two ways — by the destination check
 * and by the send-gate call — and the two sets must agree and be non-empty (CLAUDE.md 「검사 도구의 실패는 초록색」
 * 3 · 5). Recursive scan with comments stripped. Proven both ways when written: deleting the {@code prepare} call
 * from one transport, or sending {@code jsonBody} instead of the prepared body, turns this red.
 */
class LlmSendGateFenceTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "sellerops");

    private static final Pattern LLM_CHECK = Pattern.compile("OutboundGate\\.check\\([^;]*Destination\\.LLM\\)");
    private static final Pattern PREPARE = Pattern.compile("LlmSendGate\\.prepare\\(");
    private static final Pattern SENDS_RAW = Pattern.compile("BodyPublishers\\.ofString\\(\\s*jsonBody");
    private static final Pattern SENDS_PREPARED = Pattern.compile("BodyPublishers\\.ofString\\(\\s*prepared\\.body\\(\\)");
    private static final Pattern PRODUCT_IMAGES = Pattern.compile("\\.postProductImages\\(");

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    private static int count(Pattern pattern, String code) {
        Matcher m = pattern.matcher(code);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("the files that reach a model are the files that prepare the body, and they send only that")
    void everyModelRequestIsPrepared() throws IOException {
        Set<String> checking = new TreeSet<>();
        Set<String> preparing = new TreeSet<>();
        Set<String> sendingRaw = new TreeSet<>();
        Set<String> sendingPrepared = new TreeSet<>();
        Set<String> productImageCallers = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path source : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = source.getFileName().toString();
                if (name.equals("LlmSendGate.java") || name.equals("OutboundGate.java")) {
                    continue;
                }
                String code = stripComments(Files.readString(source));
                if (LLM_CHECK.matcher(code).find()) {
                    checking.add(name);
                }
                if (PREPARE.matcher(code).find()) {
                    preparing.add(name);
                }
                if (SENDS_RAW.matcher(code).find()) {
                    sendingRaw.add(name);
                }
                if (count(SENDS_PREPARED, code) > 0) {
                    sendingPrepared.add(name);
                }
                if (PRODUCT_IMAGES.matcher(code).find()) {
                    productImageCallers.add(name);
                }
            }
        }
        assertThat(checking).as("the scan found no model transport — it is reading the wrong place").isNotEmpty();
        assertThat(preparing).as("files that reach a model must be exactly the files that prepare its body")
                .isEqualTo(checking);
        assertThat(sendingPrepared).as("each transport sends the prepared body").isEqualTo(checking);
        assertThat(sendingRaw).as("no transport sends the unprepared body").doesNotContainAnyElementsOf(checking);
        assertThat(productImageCallers)
                .as("only the product-detail image capability may send images unchecked; a review photo never does")
                .containsExactly("ImageFactExtractionGenerator.java");
    }
}
