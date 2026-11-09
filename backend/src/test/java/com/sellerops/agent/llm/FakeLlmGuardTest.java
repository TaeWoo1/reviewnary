package com.sellerops.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The fake model is development only: off by default, and a prod profile refuses to start with it (2026-10-29). */
class FakeLlmGuardTest {

    @Test
    @DisplayName("off by default, on only when asked, and never on prod")
    void theFakeModelStaysInDevelopment() {
        assertThat(new AgentLlmConfiguration().agentLlmTransport(new MockEnvironment()))
                .isInstanceOf(JdkAgentLlmTransport.class);
        assertThat(new AgentLlmConfiguration().agentLlmTransport(
                new MockEnvironment().withProperty("sellerops.dev.fake-llm", "true")))
                .isInstanceOf(FakeAgentLlmTransport.class);
        MockEnvironment prod = new MockEnvironment().withProperty("sellerops.dev.fake-llm", "true");
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new AgentLlmConfiguration().agentLlmTransport(prod))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("it answers each capability in that capability's own schema, and opens no connection")
    void itAnswersInEachSchema() {
        assertThat(FakeAgentLlmTransport.answer("당신은 한국 이커머스 판매자의 고객 응대 분류 담당입니다.",
                "고객 리뷰:\n손을 베였어요")).contains("\"category\":\"S\"");
        assertThat(AgentDraftResponseParser.parseFigureVerdicts(FakeAgentLlmTransport.answer(
                "답변 초안을 근거와 대조합니다", "확인할 숫자:\n1. 10mm — 문장: 폭\n2. 7일 — 문장: 배송"), 2)).isPresent();
        assertThat(AgentDraftResponseParser.parse(FakeAgentLlmTransport.answer(AgentDraftPrompt.system(), "문의"))).isPresent();
    }
}
