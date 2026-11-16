package com.sellerops.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A customer who TELLS what happened writes past and connective endings — 세탁했더니, 줄어들었어요, 벗겨졌어요 — and those
 * reach the seller's stem the way a question's 나요 does (2026-11-15 real eval r01 · r04: both reviews had no ground at
 * all). Matching only: no token is dropped from a question, and a one-syllable stem still matches nothing.
 */
class NarrativeEndingMatchTest {

    private static final String WASH = KnowledgeText.normalize("세탁: 첫 세탁은 찬물 단독 세탁을 권합니다. 건조기 사용 시 3~5% 줄어들 수 있습니다.");
    private static final String COAT = KnowledgeText.normalize("식기세척기 사용은 권하지 않습니다(도장이 벗겨질 수 있습니다). 손세척해 주세요.");
    private static final String PARTS = KnowledgeText.normalize("구성: 이불 커버 1, 베개 커버 2, 매트리스 커버 1.");

    @Test
    @DisplayName("a told stem meets the seller's word: 세탁+했더니, 줄어들+었어요, 벗겨+졌어요")
    void aToldStemMeetsTheSellersWord() {
        assertThat(KnowledgeText.prefixMatch("세탁했더니", WASH)).isEqualTo(2);
        assertThat(KnowledgeText.prefixMatch("줄어들었어요", WASH)).isEqualTo(3);
        assertThat(KnowledgeText.prefixMatch("벗겨졌어요", COAT)).isEqualTo(2);
    }

    @Test
    @DisplayName("r04 reaches its passage through the unchanged gates; an unrelated passage does not come with it")
    void theReviewReachesItsPassage() {
        List<KnowledgeRetriever.Candidate<String>> tumbler = List.of(
                new KnowledgeRetriever.Candidate<>("coat", COAT, COAT),
                new KnowledgeRetriever.Candidate<>("parts", PARTS, PARTS));
        assertThat(KnowledgeRetriever.rank("식기세척기 돌렸더니 겉이 벗겨졌어요.", tumbler, "스테인리스 텀블러 500ml"))
                .extracting(KnowledgeRetriever.Hit::ref).containsExactly("coat");
        // r01 is matched word by word but the absence gate is not loosened for it: 사이즈 is in no passage, and the
        // grammar tails still count as unmatched letters (counting them as matched raised wrong evidence on the
        // retrieval benchmark, 2026-11-15). Whether it clears the gate depends on the rest of the library.
        List<KnowledgeRetriever.Candidate<String>> bedding = List.of(
                new KnowledgeRetriever.Candidate<>("wash", WASH, WASH),
                new KnowledgeRetriever.Candidate<>("parts", PARTS, PARTS));
        assertThat(KnowledgeText.weigh("세탁했더니 줄어들었어요. 사이즈가 안 맞아요.", List.of(WASH, PARTS), "면 침구 세트(퀸)")
                .askableRatio()).isBetween(0.30, 0.35);
        assertThat(KnowledgeRetriever.rank("세탁했더니 줄어들었어요. 사이즈가 안 맞아요.", bedding, "면 침구 세트(퀸)")).isEmpty();
    }

    @Test
    @DisplayName("not widened: a one-syllable stem matches nothing, a different word's first syllable is not a stem")
    void notWidened() {
        assertThat(KnowledgeText.prefixMatch("줄었어요", WASH)).as("줄 alone is a syllable, not a word").isZero();
        assertThat(KnowledgeText.prefixMatch("방수했어요", KnowledgeText.normalize("설치 방법은 아래와 같습니다."))).isZero();
        assertThat(QueryWords.isFunctionWord("줄었어요")).as("no token is dropped because of the new list").isFalse();
        assertThat(QueryWords.isPredicateForm("세탁했더니")).as("gap wording reads the question list only").isFalse();
    }
}
