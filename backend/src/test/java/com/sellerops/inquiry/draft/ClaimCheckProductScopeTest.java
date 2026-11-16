package com.sellerops.inquiry.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.llm.AgentDraftGenerator;
import com.sellerops.agent.llm.AgentDraftPrompt;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The claim check is told which product the inquiry is about, and what that does and does not change</b> (2026-11-16
 * §7.57: q13's check read the shipping rule beside its 「주문 제작 상품은 따르지 않습니다」 exception without being told the
 * question was about bedding, and held the common rule twice; q06 S1's first draft was held for a 7-day rule whose
 * conditions stood in its next sentence).
 */
class ClaimCheckProductScopeTest {

    @Test
    @DisplayName("the product line goes to the drafter and the check in the same scope block — none without a name")
    void theProductIsInTheScope() {
        String line = InquiryDraftComposer.productLine("[합성] 면 침구 세트(퀸)");
        assertThat(line).startsWith("- 문의 상품: [합성] 면 침구 세트(퀸) → 시스템이 기록한");
        assertThat(InquiryDraftComposer.productLine(null)).isNull();
        assertThat(InquiryDraftComposer.productLine(" ")).isNull();
        String scope = InquiryDraftComposer.withLine("- N1. 어제 오후에 결제한 주문을 언제 받는지", line);
        String user = AgentDraftPrompt.figureUser("어제 오후 4시에 결제했는데 언제 받을 수 있나요?",
                List.of(new AgentDraftGenerator.Passage("운영 정책", "01_공통_배송정책", "- 평일 오후 3시 이전 결제 완료 주문은 당일 출고합니다.")),
                null, null, scope, "평일 오후 3시 이전 결제는 당일 출고합니다.", List.of());
        assertThat(user).contains("이번 답변이 다룰 내용:\n- N1.").contains("- 문의 상품: [합성] 면 침구 세트(퀸)");
    }

    @Test
    @DisplayName("v15 says when an exception or a condition is not dropped — and every refusal before stays")
    void theRulesNarrowNothingElse() {
        String system = AgentDraftPrompt.figureSystem();
        assertThat(AgentDraftPrompt.FIGURE_PROMPT_VERSION).isEqualTo("inquiry-claim-check/v15");
        // new: what is not a dropped condition
        assertThat(system).contains("조건은 문장 하나가 아니라 초안 전체에서 봅니다")
                .contains("「문의 상품」이 그 상품이 아니라고 근거와 그 줄로 알 수")
                .contains("「문의 상품」 줄이 없거나, 예외가 이 상품에 해당하는지 알 수 없으면")
                .contains("그 안의 한 경우")
                .contains("고객이 쓴 시각 · 상태가 근거의 어느 기준에 해당하는지만");
        // kept: a missing condition, another case's or target's value, the order settled from policy, doubt
        assertThat(system).contains("초안이 그 조건 없이 된다고 말하면")
                .contains("다른 대상(높이 ↔ 폭) · 다른 조건(평일 ↔ 제주, 다른 규격 · 기간)의 값")
                .contains("뒤의 것은 주문 정보가 그렇게 말할 때만")
                .contains("그 주문이 어느 날 출고된다 · 됐다 · 도착한다고 말하면 위와 같이")
                .contains("거꾸로 한 경우의 값을 전체로 넓히거나")
                .contains("단위를 바꾼 값은 정확히 같을 때만")
                .contains("애매하면 NOT_SUPPORTED입니다");
    }
}
