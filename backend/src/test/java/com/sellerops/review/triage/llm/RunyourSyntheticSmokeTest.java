package com.sellerops.review.triage.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunyourSyntheticSmokeTest {
    @Test
    void refusesUnapprovedOrIncompleteSetupBeforeSendingAnything() {
        AtomicInteger calls = new AtomicInteger();
        LlmHttpClient http = (u, h, b) -> {
            calls.incrementAndGet();
            return new LlmHttpClient.Response(500, "not called");
        };
        assertThatThrownBy(() -> RunyourSyntheticSmoke.run(false, "model", "key", http))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RunyourSyntheticSmoke.run(true, null, "key", http))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunyourSyntheticSmoke.run(true, "model", " ", http))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void sendsOnlyTheFixedSyntheticInputOnceWithTemperatureOmittedAndTheDocumentedCapField() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        var report = RunyourSyntheticSmoke.run(true, "reviewed/model", "synthetic-key", (u, h, b) -> {
            calls.incrementAndGet();
            assertThat(u).isEqualTo(URI.create("https://api.runyour.ai/v1/chat/completions"));
            try {
                var json = mapper.readTree(b);
                assertThat(json.path("model").asText()).isEqualTo("reviewed/model");
                assertThat(json.has("temperature")).isFalse();
                assertThat(json.path("max_tokens").asInt()).isEqualTo(300);
                assertThat(json.has("max_completion_tokens")).isFalse();
                assertThat(json.path("messages").size()).isEqualTo(2);
                assertThat(json.path("messages").get(1).path("content").asText())
                        .isEqualTo(TriagePrompt.userTurn(2, "포장이 찌그러져서 왔어요"));
                assertThat(b).doesNotContain("synthetic-key");
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            return new LlmHttpClient.Response(200, "{\"choices\":[{\"message\":{\"content\":\""
                    + "{\\\"tier\\\":\\\"NEEDS_ATTENTION\\\",\\\"reasonCode\\\":\\\"DEFECT_OR_DAMAGE\\\","
                    + "\\\"tags\\\":[],\\\"suggestedNextAction\\\":\\\"INVESTIGATE_PRODUCT\\\"}" + "\"}}]}");
        });
        assertThat(calls).hasValue(1);
        assertThat(report.calls()).isEqualTo(1);
        assertThat(report.status()).isEqualTo(ReviewTriageClassifier.Status.OK);
        assertThat(report.reasonCode()).isEqualTo("DEFECT_OR_DAMAGE");
    }

    @Test
    void doesNotRetryOrPrintVendorErrors() {
        AtomicInteger calls = new AtomicInteger();
        var report = RunyourSyntheticSmoke.run(true, "model", "synthetic-key", (u, h, b) -> {
            calls.incrementAndGet();
            return new LlmHttpClient.Response(429, "synthetic-key echoed vendor payload");
        });
        assertThat(calls).hasValue(1);
        assertThat(report.status()).isEqualTo(ReviewTriageClassifier.Status.CLASSIFICATION_FAILED);
        assertThat(report.failureReason()).isEqualTo("http 429");
        assertThat(report.toString()).doesNotContain("synthetic-key", "echoed", "payload");
    }
}
