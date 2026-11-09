package com.sellerops.agent.reply;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.agent.llm.AgentDraftGenerator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>What the reply agent's steps send, and that it is the standard the owner decided</b> (2026-10-28). The instructions
 * are rendered from {@code contracts/reply-standard/v1}; the payload is the redacted review, the seller's passages and
 * wording, and our own draft — no rating, no ids, no channel.
 */
class ReplyAgentPromptFloorTest {

    private final ReplyStandard standard = new ReplyStandard();

    @Test
    @DisplayName("the judge is shown every category in priority order and asked for one code — and no rating")
    void theJudgeSeesTheCategoriesAndNoRating() {
        String system = ReplyAgentPrompt.classifySystem(standard);
        for (ReplyStandard.Category c : standard.categories()) {
            assertThat(system).contains("- " + c.code() + ": " + c.name());
        }
        assertThat(system.indexOf("- S:")).isLessThan(system.indexOf("- P:"));
        assertThat(system).contains("별점은 주어지지 않습니다");
        assertThat(ReplyAgentPrompt.classifyUser("[전화번호]로 연락 주세요")).isEqualTo("고객 리뷰:\n[전화번호]로 연락 주세요");
    }

    @Test
    @DisplayName("the draft is told its category's order and bans, and every rule by its id")
    void theDraftCarriesTheCategoryAndEveryRule() {
        ReplyStandard.Category r = standard.category("R").orElseThrow();
        String system = ReplyAgentPrompt.draftSystem(standard, r);
        assertThat(system).contains("이 리뷰의 범주: R");
        r.skeleton().forEach(step -> assertThat(system).contains(step));
        r.mustNot().forEach(not -> assertThat(system).contains(not));
        standard.rules().forEach(rule -> assertThat(system).contains("- " + rule.id() + ": " + rule.text()));
    }

    @Test
    @DisplayName("the user turn carries the review, the passages, the wording and bans — and on a revision, the failed rules")
    void theUserTurnIsTheFloor() {
        String first = ReplyAgentPrompt.draftUser("하루 만에 떨어졌어요", List.of(new AgentDraftGenerator.Passage("상품 정보", "부착 방법",
                "벽면을 닦고 붙입니다")), "안녕하세요", List.of("고객님 탓"), null, null, null, null);
        assertThat(first).contains("고객 리뷰:\n하루 만에 떨어졌어요").contains("[상품 정보] [부착 방법] 벽면을 닦고 붙입니다")
                .contains("\"고객님 탓\"").doesNotContain("지난 초안").doesNotContain("별점");
        String revision = ReplyAgentPrompt.draftUser("하루 만에 떨어졌어요", List.of(), null, List.of(), null,
                "불편을 드려 죄송합니다. 테이프를 써 보세요.", List.of("G4"), "G4: 고객이 이미 쓴 테이프를 다시 권하지 마세요.");
        assertThat(revision).contains("지난 초안:").contains("검토에서 걸린 기준: G4").contains("(없음)");
    }

    @Test
    @DisplayName("the critique is asked for rule ids only, from the same standard")
    void theCritiqueAsksForRuleIds() {
        String system = ReplyAgentPrompt.critiqueSystem(standard, standard.category("P").orElseThrow());
        assertThat(system).contains("{\"failed\":[{\"id\":\"<규칙 번호>\"");
        // Doubt about a legal or platform rule is a failure (review 2026-10-28): the critique must not lean to a pass.
        assertThat(system).contains("N으로 시작하는 규칙(법·플랫폼)은 어겼는지 애매하면 어긴 것으로 고릅니다").doesNotContain("확신이 없으면 고르지 않습니다");
        standard.rules().forEach(rule -> assertThat(system).contains(rule.id() + ": "));
        assertThat(ReplyAgentPrompt.critiqueUser("좋아요", List.of(), "감사합니다")).contains("검토할 초안:\n감사합니다");
    }
}
