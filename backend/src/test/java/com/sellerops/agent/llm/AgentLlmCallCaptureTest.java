package com.sellerops.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.access.AgentCapabilityAccess;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The inquiry drafter's cost reaches the caller that charged for it (2026-11-01 case-eval-r10: 36 drafter usage rows,
 * every token count empty, while the log had them).
 */
class AgentLlmCallCaptureTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final String ANTHROPIC = """
            {"content":[{"type":"text","text":"{\\"category\\":\\"general_reply\\",\\"title\\":\\"답변\\",\\"comments\\":\\"안녕하세요. 두께는 8mm입니다.\\"}"}],
             "stop_reason":"end_turn","usage":{"input_tokens":4613,"output_tokens":158}}
            """;

    private static AgentDraftService drafter() {
        AgentDraftProperties properties = new AgentDraftProperties(true, ORG.toString(), "ANTHROPIC", "test-model",
                "sk-test", 4000, "low");
        return new AgentDraftService(properties, (uri, headers, body) -> new AgentLlmTransport.Response(200, ANTHROPIC,
                2535L), new AgentCapabilityAccess("ALLOW_LIST", null));
    }

    @Test
    @DisplayName("a draft made inside a capture hands back its time and tokens; outside one, nothing is kept")
    void theDraftersCallIsCaptured() {
        AgentDraftService model = drafter();
        List<AgentDraftGenerator.Passage> shown = List.of(new AgentDraftGenerator.Passage("두께", "두께는 8mm입니다."));

        AgentLlmCallCapture.Captured<java.util.Optional<AgentDraftResponseParser.ParsedDraft>> call =
                AgentLlmCallCapture.capture(() -> model.draft(ORG, "두께 문의",
                "두께가 얼마인가요?", shown, null, null, null, null, "- 두께"));

        assertThat(call.value()).isPresent();
        assertThat(call.metrics()).isNotNull();
        assertThat(call.metrics().promptTokens()).isEqualTo(4613);
        assertThat(call.metrics().completionTokens()).isEqualTo(158);
        assertThat(call.metrics().elapsedMs()).isEqualTo(2535L);

        // Reported with no capture open: dropped, and the next capture starts empty.
        model.draft(ORG, "두께 문의", "두께가 얼마인가요?", shown, null, null, null, null, "- 두께");
        assertThat(AgentLlmCallCapture.capture(() -> "no call").metrics()).isNull();
    }

    @Test
    @DisplayName("a nested capture keeps its own call, and the outer one still sees what it made itself")
    void nestedCapturesRestoreTheOuter() {
        AgentLlmCallMetrics outerCall = new AgentLlmCallMetrics(10L, 1, 2, -1);
        AgentLlmCallMetrics innerCall = new AgentLlmCallMetrics(20L, 3, 4, -1);

        AgentLlmCallCapture.Captured<AgentLlmCallMetrics> outer = AgentLlmCallCapture.capture(() -> {
            AgentLlmCallCapture.report(outerCall);
            AgentLlmCallCapture.Captured<String> inner = AgentLlmCallCapture.capture(() -> {
                AgentLlmCallCapture.report(innerCall);
                return "inner";
            });
            return inner.metrics();
        });

        assertThat(outer.value()).isEqualTo(innerCall);
        assertThat(outer.metrics()).isEqualTo(outerCall);
    }
}
