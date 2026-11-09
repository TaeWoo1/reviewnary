package com.sellerops.review.triage.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.review.triage.llm.ReviewTriageClassifier.Input;
import com.sellerops.review.triage.llm.ReviewTriageClassifier.Status;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Vendor.RUNYOUR} (2026-11-06): the Runyour AI router speaks the OpenAI wire, so the only differences from
 * {@code OPENAI} may be where the request goes, the vendor name stamped into the version, and (2026-11-07) the
 * output-cap field, which takes the name Runyour's docs use ({@code max_tokens}). The payload floor is
 * already enforced for it by {@code TriagePayloadFloorTest}'s {@code @EnumSource}.
 */
class RunyourTriageVendorTest {

    @Test
    @DisplayName("RUNYOUR posts to the Runyour router with a Bearer key and is versioned as runyour")
    void runyourGoesToTheRouterAndIsNamedInTheVersion() {
        List<URI> uris = new ArrayList<>();
        List<Map<String, String>> sentHeaders = new ArrayList<>();
        ApiTriageClassifier classifier = new ApiTriageClassifier((uri, headers, body) -> {
            uris.add(uri);
            sentHeaders.add(headers);
            return new LlmHttpClient.Response(200,
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"not json\"}}]}");
        }, ApiTriageClassifier.Vendor.RUNYOUR, "anthropic/claude-sonnet-5-5", "k");

        ReviewTriageClassifier.Result result = classifier.classify(new Input(1, "본문"));

        assertThat(uris).containsExactly(URI.create("https://api.runyour.ai/v1/chat/completions"));
        assertThat(sentHeaders.get(0)).containsExactly(Map.entry("Authorization", "Bearer k"));
        assertThat(result.status()).as("an unreadable answer is still never FYI").isNotEqualTo(Status.OK);
        assertThat(classifier.version()).contains("+runyour:anthropic/claude-sonnet-5-5+");
    }

    @Test
    @DisplayName("RUNYOUR writes the OPENAI body except the output cap, which it names max_tokens (2026-11-07)")
    void runyourWritesTheOpenAiBodyWithItsDocumentedCapName() throws Exception {
        ApiTriageClassifier openai = new ApiTriageClassifier((u, h, b) -> new LlmHttpClient.Response(200, "{}"),
                ApiTriageClassifier.Vendor.OPENAI, "m", "k");
        ApiTriageClassifier runyour = new ApiTriageClassifier((u, h, b) -> new LlmHttpClient.Response(200, "{}"),
                ApiTriageClassifier.Vendor.RUNYOUR, "m", "k");
        Input input = new Input(2, "포장이 찌그러져서 왔어요");
        String openaiBody = openai.requestBody(input);
        String runyourBody = runyour.requestBody(input);

        // The one rename, and nothing else: swapping the field name back yields OpenAI's exact bytes.
        assertThat(runyourBody.replace("\"max_tokens\":", "\"max_completion_tokens\":")).isEqualTo(openaiBody);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var r = mapper.readTree(runyourBody);
        var o = mapper.readTree(openaiBody);
        assertThat(r.has("max_tokens")).isTrue();
        assertThat(r.has("max_completion_tokens")).isFalse();
        assertThat(o.has("max_completion_tokens")).as("OpenAI's request is unchanged").isTrue();
        assertThat(o.has("max_tokens")).isFalse();
        assertThat(r.path("max_tokens").asInt()).isEqualTo(o.path("max_completion_tokens").asInt());
    }

    @Test
    @DisplayName("RUNYOUR is for the evaluation harness only: the production gate refuses it, and only it (2026-11-07)")
    void theProductionGateRefusesTheRelay() {
        ApiTriageClassifier.Tuning tuning = new ApiTriageClassifier.Tuning(false, 4000, null);
        assertThatThrownBy(() -> ReviewTriageChannelGate.forApi(ApiTriageClassifier.Vendor.RUNYOUR, "m", "k", tuning))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("evaluation harness only");
        for (ApiTriageClassifier.Vendor v : ApiTriageClassifier.Vendor.values()) {
            assertThat(v.evalOnly()).as(v.name()).isEqualTo(v == ApiTriageClassifier.Vendor.RUNYOUR);
            if (!v.evalOnly()) {
                assertThat(ReviewTriageChannelGate.forApi(v, "m", "k", tuning).version()).contains(v.name().toLowerCase());
            }
        }
    }
}
