package com.sellerops.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The fake model's inquiry planner, judge and drafter (development only) — steerable from the customer's words. */
class FakeInquiryDecisionTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String PLAN = "당신은 고객이 판매자에게 보낸 문의 하나를 읽고, 답변에 포함되지 않으면 고객의 요청이 해결되지 않는 "
            + "독립적인 정보·행동 단위(need)로 나눕니다.";
    private static final String JUDGE = "당신은 고객 문의, 그 문의의 need 목록, 판매자가 가진 현재 근거 후보(E…)를 받습니다. "
            + "출력은 need id를 키로 하는 객체 하나입니다.";

    private static String type(String customer) throws Exception {
        String user = M.createObjectNode().put("customer", customer).toString();
        return M.readTree(FakeAgentLlmTransport.answer(PLAN, user)).path("needs").get(0).path("type").asText();
    }

    @Test
    @DisplayName("the planner reads an action, a state, or anything else as policy")
    void plans() throws Exception {
        assertThat(type("사이즈를 잘못 골랐어요. 환불해 주세요.")).isEqualTo("ORDER_ACTION");
        assertThat(type("주문한 거 언제 도착하나요?")).isEqualTo("ORDER_STATE");
        assertThat(type("환불은 어떻게 신청하나요?")).isEqualTo("POLICY");
        assertThat(type("[상태] 환불해 주세요")).as("a tag wins only where it is checked first").isEqualTo("ORDER_ACTION");
    }

    @Test
    @DisplayName("the judge answers an order need only from the seller's record, any other from policy")
    void judges() throws Exception {
        String user = "{\"customer\":\"x\",\"needs\":[{\"id\":\"N1\",\"type\":\"ORDER_ACTION\"},"
                + "{\"id\":\"N2\",\"type\":\"POLICY\"}],\"evidence\":[{\"id\":\"E1\",\"source\":\"운영 정책\"},"
                + "{\"id\":\"E2\",\"source\":\"판매자 확인\"}],\"precedents\":[]}";
        JsonNode v = M.readTree(FakeAgentLlmTransport.answer(JUDGE, user)).path("verdicts");
        assertThat(v.path("N1").path("status").asText()).isEqualTo("FULL");
        assertThat(v.path("N1").path("evidence").get(0).asText()).isEqualTo("E2");
        assertThat(v.path("N2").path("evidence").get(0).asText()).isEqualTo("E1");
        String none = user.replace(",{\"id\":\"E2\",\"source\":\"판매자 확인\"}", "");
        assertThat(M.readTree(FakeAgentLlmTransport.answer(JUDGE, none)).path("verdicts").path("N1").path("status")
                .asText()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("the drafter says the seller's record back, and a scenario can make the drafter or planner fail")
    void draftsAndFails() throws Exception {
        String draft = FakeAgentLlmTransport.answer("판매자가 받은 고객 문의 하나를 읽고",
                "근거\n판매자가 판매자센터에서 직접 확인한 이 문의의 결과(이 문의에만 해당, 채널이 확인한 사실 아님): 접수·진행 중 — 환불 대기\n");
        assertThat(M.readTree(draft).path("comments").asText()).contains("접수·진행 중 — 환불 대기");
        String check = FakeAgentLlmTransport.answer("답변 초안을 근거와 대조합니다",
                "이번 답변이 다룰 내용\n- N1. 환불 결과\n- N2. 배송 → 이번 답변에서 다루지 않습니다.\n확인할 숫자:\n");
        JsonNode c = M.readTree(check);
        assertThat(c.path("answered").asText()).isEqualTo("ANSWERED");
        assertThat(c.path("covered").path("N1").asText()).isEqualTo("ANSWERED");
        assertThat(c.path("covered").has("N2")).as("an item the draft was told to leave").isFalse();
        FakeAgentLlmTransport t = new FakeAgentLlmTransport();
        String body = "{\"system\":\"판매자가 받은 고객 문의 하나를 읽고\",\"messages\":[{\"role\":\"user\","
                + "\"content\":\"[생성실패] 환불해 주세요\"}]}";
        assertThat(t.post(java.net.URI.create("https://api.anthropic.com/v1/messages"), java.util.Map.of(), body)
                .status()).isEqualTo(500);
    }

    @Test
    @DisplayName("[정책+조치]: a rule and an action; the rule PARTIAL, settled by a 처리 완료 record only when v6 asks — and "
            + "[되묻기] asks once, never in a rewrite (2026-11-06)")
    void settlesAndAsks() throws Exception {
        String plan = FakeAgentLlmTransport.answer(PLAN, "{\"customer\":\"[정책+조치] 포장이 찢어졌어요\"}");
        assertThat(M.readTree(plan).path("needs")).extracting(n -> n.path("type").asText())
                .containsExactly("POLICY", "ORDER_ACTION");
        String user = "{\"customer\":\"[정책+조치] x\",\"needs\":[{\"id\":\"N1\",\"type\":\"POLICY\"},"
                + "{\"id\":\"N2\",\"type\":\"ORDER_ACTION\"}],\"evidence\":[{\"id\":\"E1\",\"source\":\"운영 정책\","
                + "\"text\":\"교환은 7일 이내\"},{\"id\":\"E2\",\"source\":\"판매자 확인\",\"text\":\"… 처리 완료 — 발송함\"}],"
                + "\"precedents\":[]}";
        JsonNode v6 = M.readTree(FakeAgentLlmTransport.answer(
                com.sellerops.inquiry.decision.InquiryDecisionPromptAccess.v6(), user)).path("verdicts");
        assertThat(v6.get(0).path("status").asText()).isEqualTo("PARTIAL");
        assertThat(v6.get(0).path("settled_by").asText()).isEqualTo("E2");
        assertThat(v6.get(1).path("settled_by").isNull()).isTrue();
        JsonNode v5 = M.readTree(FakeAgentLlmTransport.answer(
                com.sellerops.inquiry.decision.InquiryDecisionPromptAccess.v5(), user)).path("verdicts");
        assertThat(v5.get(0).has("settled_by")).as("v5 is not asked").isFalse();
        String first = FakeAgentLlmTransport.answer("판매자가 받은 고객 문의 하나를 읽고", "[되묻기] 교환되나요");
        assertThat(M.readTree(first).path("comments").asText()).endsWith("알려주시겠어요?");
        String again = FakeAgentLlmTransport.answer("판매자가 받은 고객 문의 하나를 읽고", "[되묻기] 교환되나요\n- 다시 쓰기: …");
        assertThat(M.readTree(again).path("comments").asText()).doesNotContain("?");
    }

    @Test
    @DisplayName("[정책+조치] with 배송비: a third rule the judge wrongly settles, and the check calls not taken up")
    void aSeparateFeeQuestion() throws Exception {
        String plan = FakeAgentLlmTransport.answer(PLAN, "{\"customer\":\"[정책+조치] 교환 되나요? 배송비는요?\"}");
        assertThat(M.readTree(plan).path("needs")).extracting(n -> n.path("ask").asText())
                .containsExactly("이 상품을 교환할 수 있는지", "이 주문에 요청한 조치", "교환 배송비는 누가 내는지");
        String check = FakeAgentLlmTransport.answer("답변 초안을 근거와 대조합니다",
                "이번 답변이 다룰 내용:\n- N1. 이 상품을 교환할 수 있는지\n- N3. 교환 배송비는 누가 내는지\n\n답변 초안:\n교환 상품을 "
                        + "발송했습니다.\n\n확인할 숫자:\n(없음)");
        JsonNode c = M.readTree(check).path("covered");
        assertThat(c.path("N1").asText()).isEqualTo("ANSWERED");
        assertThat(c.path("N3").asText()).isEqualTo("NOT_COVERED");
    }
}
