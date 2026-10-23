package com.sellerops.review.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.knowledge.style.AnswerStyleProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/** <b>The company's ban list reaches review replies, less the bare topic words</b> — owner decision 2026-10-23, B24. */
class ReviewStyleGuardTest {

    private static AnswerStyleProfile banning(String... phrases) {
        return new AnswerStyleProfile(null, null, null, null, null, null, List.of(), List.of(phrases), null, 1);
    }

    @Test
    void aBannedPhraseIsFoundWhateverItsSpacing() {
        assertThat(ReviewStyleGuard.forbiddenIn("불편을 드려 죄송합니다. 고객님탓이 아닙니다.", banning("고객님 탓")))
                .containsExactly("고객님 탓");
    }

    @Test
    void aBareTopicWordIsNotAPhrasingAndIsLeftToThePromiseCheck() {
        AnswerStyleProfile profile = banning("환불", " 교환 ", "무조건 환불");
        assertThat(ReviewStyleGuard.enforced(profile)).containsExactly("무조건 환불");
        assertThat(ReviewStyleGuard.forbiddenIn("교환이나 환불은 주문 내역에서 접수해 주세요.", profile)).isEmpty();
        assertThat(ReviewStyleGuard.forbiddenIn("무조건 환불해 드립니다.", profile)).containsExactly("무조건 환불");
    }

    @Test
    void noProfileNoBan() {
        assertThat(ReviewStyleGuard.forbiddenIn("아무 문장", (com.sellerops.knowledge.style.AnswerStyleProfile) null)).isEmpty();
        assertThat(ReviewStyleGuard.enforced(AnswerStyleProfile.defaults())).isEmpty();
    }
}
