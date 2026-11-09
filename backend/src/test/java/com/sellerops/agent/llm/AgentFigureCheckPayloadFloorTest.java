package com.sellerops.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * <b>The figure check's payload floor, on the serialized bytes, and its strict answer</b> (owner, 2026-10-28: whether a
 * number is about the same thing is read by a model). What leaves is what the inquiry draft call already sent — the
 * question, the seller's passages, the order's sentence — plus the draft this backend got back and the figures to
 * check. No id, no name, no channel, no key.
 */
class AgentFigureCheckPayloadFloorTest {

    private static final List<String> FORBIDDEN = List.of(
            "7f3a1c9e-0000-4000-8000-000000000001", "sk-should-never-appear", "NAVER", "김구매");

    private static AgentDraftGenerator generator(AgentDraftGenerator.Vendor vendor) {
        return new AgentDraftGenerator((uri, headers, body) -> new AgentLlmTransport.Response(200, "{}"),
                vendor, "test-model", "sk-should-never-appear", 4000, "low");
    }

    @ParameterizedTest
    @EnumSource(AgentDraftGenerator.Vendor.class)
    @DisplayName("the body carries the question, the passages, the order sentence, the draft and the figures — nothing else")
    void theBodyCarriesWhatTheDraftSaw(AgentDraftGenerator.Vendor vendor) {
        String body = generator(vendor).figureRequestBody(new AgentDraftGenerator.FigureInput("폭이 얼마예요?",
                List.of(new AgentDraftGenerator.Passage("상품 정보", "규격", "상품 폭 10mm")), "오늘 출고 예정입니다.",
                "상품 높이는 10mm입니다.", List.of("1. 10mm — 문장: 상품 높이는 10mm입니다")));
        assertThat(body).contains("폭이 얼마예요?").contains("상품 폭 10mm").contains("오늘 출고 예정")
                .contains("상품 높이는 10mm입니다").contains("NOT_SUPPORTED");
        for (String forbidden : FORBIDDEN) {
            assertThat(body).as("%s must never reach the vendor", forbidden).doesNotContain(forbidden);
        }
    }

    @ParameterizedTest
    @EnumSource(AgentDraftGenerator.Vendor.class)
    @DisplayName("v10: the need list the drafter was given travels with the draft, and the check is told how to read it")
    void theDraftersScopeTravelsWithIt(AgentDraftGenerator.Vendor vendor) {
        String scope = "- 두께가 얼마인가요?\n- 욕실에서 써도 되나요? → 이번 답변에서 다루지 않습니다";
        String scoped = generator(vendor).figureRequestBody(new AgentDraftGenerator.FigureInput("두께가 얼마인가요?",
                List.of(new AgentDraftGenerator.Passage("상품 정보", "두께", "두께는 8mm입니다.")), null,
                "두께는 8mm입니다.", List.of("1. 8mm — 문장: 두께는 8mm입니다"), null, scope));
        assertThat(scoped).contains("이번 답변이 다룰 내용").contains("이번 답변에서 다루지 않습니다")
                .contains("NOT_ANSWERED의 이유가 아닙니다");
        String unscoped = generator(vendor).figureRequestBody(new AgentDraftGenerator.FigureInput("두께가 얼마인가요?",
                List.of(new AgentDraftGenerator.Passage("상품 정보", "두께", "두께는 8mm입니다.")), null,
                "두께는 8mm입니다.", List.of("1. 8mm — 문장: 두께는 8mm입니다")));
        assertThat(unscoped).as("no list, no section — the user turn is v9's").doesNotContain("이번 답변이 다룰 내용:");
        assertThat(AgentDraftPrompt.FIGURE_PROMPT_VERSION).isEqualTo("inquiry-claim-check/v13");
        // v13 (2026-11-06 thirteenth review): a question nobody judged necessary is a claim the check refuses.
        assertThat(AgentDraftPrompt.figureSystem()).contains("그 밖의 되묻기는 claims로 골라 NOT_SUPPORTED로 판정합니다");
        // v12 (2026-11-05 re-eval: after a kind correction the check returned N1 and never S1, 3 of 3 and on every
        // recheck): the item-by-item answer names both kinds of number.
        assertThat(AgentDraftPrompt.figureSystem()).contains("「S1.」").contains("N으로 시작하든 S로 시작하든 빠뜨리지 않습니다");
        for (String forbidden : FORBIDDEN) {
            assertThat(scoped).as("%s must never reach the vendor", forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("one closed verdict per figure asked, or no answer at all")
    void theAnswerIsStrict() {
        assertThat(AgentDraftResponseParser.parseFigureVerdicts(
                "{\"figures\":[{\"n\":2,\"verdict\":\"SAID_BACK\"},{\"n\":1,\"verdict\":\"SUPPORTED\"}]}", 2))
                .get().asList().extracting("verdict").containsExactly("SUPPORTED", "SAID_BACK");
        for (String bad : List.of(
                "{\"figures\":[{\"n\":1,\"verdict\":\"SUPPORTED\"}]}",                                   // one missing
                "{\"figures\":[{\"n\":1,\"verdict\":\"SUPPORTED\"},{\"n\":1,\"verdict\":\"SUPPORTED\"}]}", // twice
                "{\"figures\":[{\"n\":1,\"verdict\":\"OK\"},{\"n\":2,\"verdict\":\"SUPPORTED\"}]}",        // off vocabulary
                "{\"figures\":[{\"n\":1,\"verdict\":\"SUPPORTED\"},{\"n\":3,\"verdict\":\"SUPPORTED\"}]}", // off list
                "{\"figures\":[]} {\"figures\":[{\"n\":1,\"verdict\":\"SUPPORTED\"},{\"n\":2,\"verdict\":\"SUPPORTED\"}]}",
                "{\"figures\":[{\"n\":1,\"verdict\":\"NOT_SUPPORTED\"},{\"n\":2,\"verdict\":\"NOT_SUPPORTED\"}],"
                        + "\"figures\":[{\"n\":1,\"verdict\":\"SUPPORTED\"},{\"n\":2,\"verdict\":\"SUPPORTED\"}]}")) {
            assertThat(AgentDraftResponseParser.parseFigureVerdicts(bad, 2)).as(bad).isEmpty();
        }
    }

    @Test
    @DisplayName("claims: a short excerpt and one closed verdict each; none is an empty list; anything else is no answer")
    void theClaimsAreStrict() {
        assertThat(AgentDraftResponseParser.parseClaimVerdicts("{\"figures\":[],\"claims\":[]}")).contains(List.of());
        assertThat(AgentDraftResponseParser.parseClaimVerdicts("{\"figures\":[]}"))
                .as("claims left out has not said the draft states no fact").isEmpty();
        assertThat(AgentDraftResponseParser.parseClaimVerdicts(
                "{\"figures\":[],\"claims\":[{\"text\":\"식기세척기 사용 가능\",\"verdict\":\"NOT_SUPPORTED\"}]}"))
                .get().asList().hasSize(1);
        for (String bad : List.of(
                "{\"claims\":[{\"text\":\"식기세척기 사용 가능\",\"verdict\":\"SAID_BACK\"}]}",
                "{\"claims\":[{\"text\":\"\",\"verdict\":\"SUPPORTED\"}]}",
                "{\"claims\":\"없음\"}",
                "{\"claims\":[]} {\"claims\":[]}")) {
            assertThat(AgentDraftResponseParser.parseClaimVerdicts(bad)).as(bad).isEmpty();
        }
    }

    @Test
    @DisplayName("answered (v7): ANSWERED or NOT_ANSWERED, required — left out or anything else is no answer (2026-11-01)")
    void whetherTheDraftAnswersIsRequired() {
        assertThat(AgentDraftResponseParser.parseAnswered("{\"claims\":[],\"answered\":\"ANSWERED\"}")).contains(true);
        assertThat(AgentDraftResponseParser.parseAnswered("{\"claims\":[],\"answered\":\"NOT_ANSWERED\"}")).contains(false);
        for (String bad : List.of("{\"claims\":[]}", "{\"answered\":\"PARTLY\"}", "{\"answered\":true}", "not json")) {
            assertThat(AgentDraftResponseParser.parseAnswered(bad)).as(bad).isEmpty();
        }
        assertThat(AgentDraftPrompt.figureSystem()).contains("\"answered\":\"ANSWERED|NOT_ANSWERED\"");
    }
}
