package com.sellerops.agent.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.agent.access.AgentCapabilityAccess;
import com.sellerops.agent.llm.AgentLlmTransport;
import com.sellerops.agent.quota.AgentQuotaService;
import com.sellerops.agent.quota.AgentUsageActor;
import com.sellerops.agent.quota.QuotaDecision;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The reply agent's door reads the model strictly and meters every step</b> (2026-10-28): a category or a rule id the
 * standard does not have is no answer, and each call lands on the meter with its step, trigger, time and tokens.
 */
class ReplyAgentServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private AgentLlmTransport transport;
    private AgentQuotaService quota;
    private ReplyAgentService service;
    private final UUID usageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transport = mock(AgentLlmTransport.class);
        quota = mock(AgentQuotaService.class);
        AgentCapabilityAccess access = mock(AgentCapabilityAccess.class);
        when(access.allows(any(), eq(ORG))).thenReturn(true);
        when(quota.consumeStep(eq(ORG), anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(new AgentQuotaService.Charge(new QuotaDecision(true, null, 0, 0), usageId));
        ReplyAgentProperties properties = new ReplyAgentProperties(true, "*", "OPENAI", "test-model", "k", 4000, "low");
        service = new ReplyAgentService(properties, transport, access, quota, new ReplyStandard());
    }

    private void answer(String content) throws Exception {
        String envelope = new ObjectMapper().createObjectNode()
                .set("choices", new ObjectMapper().createArrayNode().add(new ObjectMapper().createObjectNode()
                        .put("finish_reason", "stop")
                        .set("message", new ObjectMapper().createObjectNode().put("role", "assistant").put("content", content))))
                .toString();
        String withUsage = envelope.substring(0, envelope.length() - 1)
                + ",\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":40}}";
        when(transport.post(any(), any(), anyString())).thenReturn(new AgentLlmTransport.Response(200, withUsage, 5));
    }

    @Test
    @DisplayName("a category from the standard is the answer; one outside it is none")
    void onlyTheStandardsCategories() throws Exception {
        answer("{\"category\":\"R\",\"also\":[],\"points\":[],\"actions\":[\"PUBLIC_REPLY\"],\"unsure\":null}");
        assertThat(service.classify(ORG, "run-1", "INGEST", "하루 만에 떨어졌어요")).contains("R");
        answer("{\"category\":\"Z\",\"also\":[],\"points\":[],\"actions\":[\"PUBLIC_REPLY\"],\"unsure\":null}");
        assertThat(service.classify(ORG, "run-1", "INGEST", "하루 만에 떨어졌어요")).isEmpty();
        answer("{\"category\":\"P\",\"category\":\"S\"}");
        assertThat(service.classify(ORG, "run-1", "INGEST", "하루 만에 떨어졌어요")).as("a field written twice").isEmpty();
        answer("{\"category\":\"R\"} {\"category\":\"S\"}");
        assertThat(service.classify(ORG, "run-1", "INGEST", "하루 만에 떨어졌어요")).as("a second document").isEmpty();
        answer("{\"comments\":\"죄송합니다.\",\"unanswered\":[],\"conflict\":false} {\"comments\":\"다른 답글\",\"unanswered\":[],\"conflict\":false}");
        assertThat(service.draft(ORG, "run-1", "INGEST", new ReplyStandard().category("R").orElseThrow(), "본문", List.of(),
                null, List.of(), null, null, List.of(), null)).as("a draft followed by another").isEmpty();
    }

    @Test
    @DisplayName("the judge keeps every category and point, may say UNSURE, and anything off the standard is no answer")
    void theJudgeSaysMore() throws Exception {
        answer("{\"category\":\"R\",\"also\":[\"Q\",\"P\"],\"points\":[\"부품 누락\",\"교환 방법 질문\"],"
                + "\"actions\":[\"CHECK_ORDER\",\"PUBLIC_REPLY\"],\"unsure\":null}");
        assertThat(service.judge(ORG, "run-1", "INGEST", "본문")).contains(new ReplyAgentService.Classified("R",
                List.of("Q", "P"), List.of("부품 누락", "교환 방법 질문"), false, List.of("CHECK_ORDER", "PUBLIC_REPLY"), null));
        answer("{\"category\":\"UNSURE\",\"also\":[],\"points\":[],\"actions\":[],\"unsure\":\"PRODUCT_UNCLEAR\"}");
        assertThat(service.judge(ORG, "run-1", "INGEST", "본문"))
                .contains(new ReplyAgentService.Classified(null, List.of(), List.of(), true, List.of(), "PRODUCT_UNCLEAR"));
        answer("{\"category\":\"R\",\"also\":[],\"points\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"],"
                + "\"actions\":[\"PUBLIC_REPLY\"],\"unsure\":null}");
        assertThat(service.judge(ORG, "run-1", "INGEST", "본문")).as("seven points are kept, none dropped")
                .get().satisfies(c -> assertThat(c.points()).hasSize(7));
        for (String bad : List.of(
                "{\"category\":\"R\",\"also\":[\"Z\"],\"points\":[],\"actions\":[\"PUBLIC_REPLY\"],\"unsure\":null}",
                "{\"category\":\"R\",\"also\":\"Q\",\"points\":[],\"actions\":[\"PUBLIC_REPLY\"],\"unsure\":null}",
                "{\"category\":\"R\",\"also\":[],\"points\":[],\"actions\":[\"REFUND_NOW\"],\"unsure\":null}",
                "{\"category\":\"UNSURE\",\"also\":[],\"points\":[],\"actions\":[],\"unsure\":\"BORED\"}",
                "{\"category\":\"R\",\"also\":[],\"points\":[\"\"],\"actions\":[\"PUBLIC_REPLY\"],\"unsure\":null}")) {
            answer(bad);
            assertThat(service.judge(ORG, "run-1", "INGEST", "본문")).as(bad).isEmpty();
        }
    }

    @Test
    @DisplayName("a field left out is not an empty answer — actions, also, points and unsure are all required")
    void aMissingFieldIsNoAnswer() throws Exception {
        for (String missing : List.of(
                "{\"category\":\"P\",\"also\":[],\"points\":[],\"unsure\":null}",
                "{\"category\":\"P\",\"points\":[],\"actions\":[\"NO_RESPONSE\"],\"unsure\":null}",
                "{\"category\":\"P\",\"also\":[],\"actions\":[\"NO_RESPONSE\"],\"unsure\":null}",
                "{\"category\":\"P\",\"also\":[],\"points\":[],\"actions\":[\"NO_RESPONSE\"]}",
                "{\"category\":\"P\",\"also\":[],\"points\":[],\"actions\":[],\"unsure\":null}",
                "{\"category\":\"R\",\"also\":[],\"points\":[],\"actions\":[\"NO_RESPONSE\",\"PUBLIC_REPLY\"],\"unsure\":null}")) {
            answer(missing);
            assertThat(service.judge(ORG, "run-1", "INGEST", "본문")).as(missing).isEmpty();
        }
        ReplyStandard.Category q = new ReplyStandard().category("Q").orElseThrow();
        answer("{\"comments\":\"네, 됩니다.\"}");
        assertThat(service.write(ORG, "run-1", "INGEST", q, "본문", List.of(), List.of(), null, List.of(), null, null,
                List.of(), null)).as("a drafter that left out unanswered and conflict").isEmpty();
    }

    @Test
    @DisplayName("a known rule failed twice is one failure")
    void eachRuleOnce() throws Exception {
        answer("{\"failed\":[{\"id\":\"G4\",\"note\":\"다시 권하지 마세요\"},{\"id\":\"G4\",\"note\":\"y\"}]}");
        assertThat(service.critique(ORG, "run-1", "INGEST", new ReplyStandard().category("R").orElseThrow(), "본문",
                List.of(), "초안").orElseThrow()).extracting(ReplyAgentService.Failed::id).containsExactly("G4");
        answer("{\"failed\":[]}");
        assertThat(service.critique(ORG, "run-1", "INGEST", new ReplyStandard().category("R").orElseThrow(), "본문",
                List.of(), "초안")).as("an empty list read whole is a pass").contains(List.of());
    }

    @Test
    @DisplayName("an answer the critique cannot read whole is a failed step, never a pass (review 2026-10-28)")
    void anUnreadableCritiqueIsNoPass() throws Exception {
        for (String unreadable : List.of(
                "{\"failed\":[{\"id\":\"알 수 없는 규칙\",\"note\":\"문제가 있습니다\"}]}",
                "{\"failed\":[{\"id\":\"G4\",\"note\":\"x\"},{\"id\":\"ZZ\",\"note\":\"x\"}]}",
                "{\"failed\":[\"G4\"]}",
                "{\"failed\":[{\"note\":\"번호 없음\"}]}",
                "{\"failed\":\"G4\"}",
                "{\"failed\":[]} {\"failed\":[{\"id\":\"N9\"}]}",
                "{\"failed\":[{\"id\":\"N9\"}],\"failed\":[]}")) {
            answer(unreadable);
            assertThat(service.critique(ORG, "run-1", "INGEST", new ReplyStandard().category("R").orElseThrow(), "본문",
                    List.of(), "초안")).as(unreadable).isEmpty();
        }
    }

    @Test
    @DisplayName("each step is metered with its step and trigger, as the run when no seller started it, with its tokens")
    void eachStepIsMetered() throws Exception {
        answer("{\"comments\":\"감사합니다.\",\"unanswered\":[],\"conflict\":false}");
        service.draft(ORG, "run-1", "INGEST", new ReplyStandard().category("P").orElseThrow(), "좋아요", List.of(), null,
                List.of(), null, null, List.of(), null);
        verify(quota).consumeStep(ORG, "run-1", "DRAFT", "INGEST", AgentUsageActor.RUN, 3);
        verify(quota).recordOutcome(eq(usageId), any(Long.class), eq(1200), eq(40));

        service.draft(ORG, "run-2", "SELLER", new ReplyStandard().category("P").orElseThrow(), "좋아요", List.of(), null,
                List.of(), null, "지난 초안", List.of("G1"), null);
        verify(quota).consumeStep(ORG, "run-2", "REVISE", "SELLER", AgentUsageActor.USER, 0);
    }

    @Test
    @DisplayName("the drafter may say a point has no fact or the facts conflict — a reason, not an empty answer")
    void theDrafterSaysWhatItCannotAnswer() throws Exception {
        ReplyStandard.Category q = new ReplyStandard().category("Q").orElseThrow();
        answer("{\"comments\":\"\",\"unanswered\":[\"세탁 방법 질문\"],\"conflict\":false}");
        assertThat(service.write(ORG, "run-1", "INGEST", q, "본문", List.of("세탁 방법 질문"), List.of(), null, List.of(),
                null, null, List.of(), null)).contains(new ReplyAgentService.DraftAnswer("", List.of("세탁 방법 질문"), false));
        answer("{\"comments\":\"\",\"unanswered\":[],\"conflict\":false}");
        assertThat(service.write(ORG, "run-1", "INGEST", q, "본문", List.of(), List.of(), null, List.of(), null, null,
                List.of(), null)).as("neither a reply nor a reason").isEmpty();
    }
}
