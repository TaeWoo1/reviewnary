package com.sellerops.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The claim check says need by need whether the draft took it up</b> (v11, 2026-11-02). With a need list the
 * item-by-item answer is required — without it nothing says what the draft dropped; without a list it is not asked for.
 */
class ClaimCheckCoverageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCOPE = "- N1. 두께가 얼마인가요?\n- N2. 색상은 무엇인가요?";

    private static AgentDraftGenerator.FigureResult check(String answer, String scope) {
        String envelope = JSON.createObjectNode().put("stop_reason", "end_turn").set("content",
                JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text").put("text", answer))).toString();
        AgentDraftGenerator g = new AgentDraftGenerator((uri, headers, body) -> new AgentLlmTransport.Response(200,
                envelope, 3), AgentDraftGenerator.Vendor.ANTHROPIC, "m", "k", 4000, "low");
        return g.checkFigures(new AgentDraftGenerator.FigureInput("두께와 색상은요?",
                List.of(new AgentDraftGenerator.Passage("상품 정보", "두께", "두께는 8mm입니다.")), null,
                "두께는 8mm입니다.", List.of(), null, scope));
    }

    private static final String BASE = "\"figures\":[],\"claims\":[],\"answered\":\"ANSWERED\"";

    @Test
    @DisplayName("with a need list, each need's answer is read")
    void readsEachNeed() {
        var r = check("{" + BASE + ",\"covered\":{\"N1\":\"ANSWERED\",\"N2\":\"NOT_COVERED\"}}", SCOPE);
        assertThat(r.reason()).isEqualTo("ok");
        assertThat(r.covered()).isEqualTo(Map.of("N1", "ANSWERED", "N2", "NOT_COVERED"));
    }

    @Test
    @DisplayName("with a need list, a missing or malformed answer is no answer")
    void requiredWithAList() {
        assertThat(check("{" + BASE + "}", SCOPE).verdicts()).isEmpty();
        assertThat(check("{" + BASE + ",\"covered\":{\"N1\":\"MAYBE\"}}", SCOPE).verdicts()).isEmpty();
        assertThat(check("{" + BASE + ",\"covered\":[\"N1\"]}", SCOPE).verdicts()).isEmpty();
    }

    @Test
    @DisplayName("without a list it is not asked for, and the answer reads as before")
    void notAskedWithoutAList() {
        var r = check("{" + BASE + "}", null);
        assertThat(r.reason()).isEqualTo("ok");
        assertThat(r.covered()).isEmpty();
    }

    @Test
    @DisplayName("the instruction asks for it and says how to read the received line")
    void theInstruction() {
        assertThat(AgentDraftPrompt.figureSystem()).contains("6) covered:").contains("NOT_COVERED")
                .contains("「문의 받은 때」는 시스템이 기록한 사실");
    }
}
