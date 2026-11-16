package com.sellerops.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The inquiry draft, the sentence check and the review draft as they leave for Runyour's Claude</b> (2026-11-15).
 * The judge was refused for schema counts; these three never stated one. Offline, they must still leave without a
 * keyword Claude refuses — if any of them gains a count later, this is where it shows.
 */
class RunyourRoutedDraftRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REFUSED = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minLength", "maxLength", "maxItems", "uniqueItems");

    private static AgentDraftGenerator generator() {
        return new AgentDraftGenerator((uri, headers, body) -> new AgentLlmTransport.Response(500, "", 1),
                AgentDraftGenerator.Vendor.RUNYOUR, "anthropic/claude-opus-5-5", "key-x", 4000, "low");
    }

    private static List<String> refused(JsonNode node) {
        List<String> found = new ArrayList<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                if (REFUSED.contains(name) || ("minItems".equals(name) && node.path(name).asInt() > 1)) {
                    found.add(name);
                }
            });
        }
        node.elements().forEachRemaining(child -> found.addAll(refused(child)));
        return found;
    }

    private static void leavesClean(String what, String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        assertThat(root.path("model").asText()).isEqualTo("anthropic/claude-opus-5-5");
        assertThat(root.path("messages").size()).as("%s carries its turns", what).isEqualTo(2);
        JsonNode format = root.path("response_format");
        assertThat(format.isObject()).as("%s asks for JSON", what).isTrue();
        assertThat(refused(root)).as(what).isEmpty();
    }

    @Test
    @DisplayName("the inquiry draft, the sentence check and the review draft leave without a keyword Claude refuses")
    void theDraftSideLeavesClean() throws Exception {
        AgentDraftGenerator g = generator();
        leavesClean("inquiry draft", g.requestBody(new AgentDraftGenerator.Input("배송 언제 오나요", "어제 주문했어요.")));
        leavesClean("sentence check", g.figureRequestBody(new AgentDraftGenerator.FigureInput("폭이 얼마예요?",
                List.of(new AgentDraftGenerator.Passage("상품 정보", "규격", "상품 폭 10mm")), "오늘 출고 예정입니다.",
                "상품 폭은 10mm입니다.", List.of("1. 10mm — 문장: 상품 폭은 10mm입니다"))));
        leavesClean("review draft", g.reviewRequestBody(new AgentDraftGenerator.ReviewInput("포장이 찌그러져서 왔어요",
                List.of(), null, List.of(), null, null)));
    }
}
