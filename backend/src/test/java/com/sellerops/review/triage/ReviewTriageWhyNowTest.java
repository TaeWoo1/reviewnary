package com.sellerops.review.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.itemanalysis.ItemAnalysisCategories;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The review workspace's own 「왜 지금」 sentence — what it may say, and what it may never say.
 *
 * <p>Every fence here sweeps the WHOLE input space rather than the three cases a hand-written test would
 * remember, because the point of moving this sentence into the backend was that there be exactly one place
 * it is written and exactly one place it is checked.
 */
class ReviewTriageWhyNowTest {

    private static final String BODY = "접착력이 약해서 자꾸 떨어집니다";

    private static final Integer[] RATINGS = {null, 1, 2, 3, 4, 5};
    private static final String[] BODIES = {null, "", "   ", BODY};
    private static final String[] CATEGORIES = {null, "설치", ItemAnalysisCategories.FALLBACK, "존재하지않는분류"};
    private static final long[] COUNTS = {0, 1, ReviewTriageNote.REPEAT_MIN, 99};

    private static List<String> emitted() {
        List<String> out = new ArrayList<>();
        for (Integer rating : RATINGS) {
            for (String body : BODIES) {
                for (String category : CATEGORIES) {
                    for (long count : COUNTS) {
                        String text = ReviewTriageWhyNow.of(rating, body, category, count);
                        if (text != null) {
                            out.add(text);
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("답변할 수 있다고 말하는 문장은 하나도 나오지 않는다")
    void nothingThisClassCanEmitSuggestsAnsweringTheBuyer() {
        List<String> all = emitted();
        assertThat(all).isNotEmpty();
        for (String text : all) {
            // 쿠팡은 상품평에 판매자 답변 자리를 주지 않는다. 채널이 없는 단추를 문장이 약속하면 안 된다.
            assertThat(text).doesNotContain("답변").doesNotContain("답글").doesNotContain("회신");
            // 「반복」은 이슈 메모리의 것이고, 같은 화면 몇 센티미터 아래에 「반복 신호」가 제 구역으로 서 있다.
            // 여기서 센 것은 저장된 자동 분류 세 건 이상이라는 다른 기계의 다른 입력이다.
            assertThat(text).doesNotContain("반복");
        }
    }

    @Test
    @DisplayName("사실을 말하고 시키지 않는다 — 명령형으로 끝나는 문장이 없다")
    void everySentenceStatesRatherThanInstructs() {
        for (String text : emitted()) {
            // 평서형 종결 하나뿐 — 「…있습니다」와 「…없습니다」. 「확인해 보세요」 같은 꼴이 끼어들 자리가 없다.
            assertThat(text).endsWith("습니다.");
            assertThat(text).doesNotContain("주세요").doesNotContain("보세요").doesNotContain("하세요");
        }
    }

    @Test
    @DisplayName("참고는 아무 말도 하지 않는다 — 빈 칸을 메우는 문장은 모든 리뷰를 일거리로 보이게 한다")
    void fyiSaysNothingAtAll() {
        for (String category : CATEGORIES) {
            for (long count : COUNTS) {
                assertThat(ReviewTriageWhyNow.of(4, BODY, category, count)).isNull();
                assertThat(ReviewTriageWhyNow.of(5, BODY, category, count)).isNull();
            }
        }
    }

    @Test
    @DisplayName("확인 필요는 별점과 확인할 내용을 한 문장으로 말한다")
    void needsAttentionNamesTheRatingAndWhatThereIsToCheck() {
        assertThat(ReviewTriageWhyNow.of(2, BODY, null, 0))
                .isEqualTo("2점 리뷰이며, 상품 상태를 확인할 내용이 있습니다.");
        assertThat(ReviewTriageWhyNow.of(1, BODY, null, 0))
                .isEqualTo("1점 리뷰이며, 상품 상태를 확인할 내용이 있습니다.");
        // 같은 분류가 쌓이면 그 사실이 확인할 내용의 자리를 가져간다.
        assertThat(ReviewTriageWhyNow.of(2, BODY, "설치", ReviewTriageNote.REPEAT_MIN))
                .isEqualTo("2점 리뷰이며, 같은 자동 분류의 상품평이 여러 건 있습니다.");
        // 세 건 미만은 아직 묶음이 아니다 — REPEAT_MIN의 선언이 그 이유를 가지고 있다.
        assertThat(ReviewTriageWhyNow.of(2, BODY, "설치", ReviewTriageNote.REPEAT_MIN - 1))
                .isEqualTo("2점 리뷰이며, 상품 상태를 확인할 내용이 있습니다.");
    }

    @Test
    @DisplayName("본문이 없으면 없다고 말한다 — 별점만 남긴 리뷰는 지켜보기에만 있다")
    void atextlessReviewSaysSo() {
        assertThat(ReviewTriageWhyNow.of(1, "", null, 0))
                .isEqualTo("별점만 남긴 1점 리뷰이며, 읽을 본문이 없습니다.");
        assertThat(ReviewTriageWhyNow.of(null, "   ", null, 0))
                .isEqualTo("별점만 남긴 평점 없는 리뷰이며, 읽을 본문이 없습니다.");
        // ReviewTriageRules: 1–2점 무본문은 지켜보기다. 확인 필요에서는 이 문장이 닿을 수 없다.
        for (Integer rating : RATINGS) {
            for (String body : new String[] {null, "", "   "}) {
                assertThat(ReviewTriageRules.tier(rating, body)).isNotEqualTo(ReviewTriageTier.NEEDS_ATTENTION);
            }
        }
    }

    @Test
    @DisplayName("평점이 없으면 0점이라고 꾸미지 않는다")
    void anAbsentRatingIsSaidAsAbsent() {
        assertThat(ReviewTriageWhyNow.of(null, BODY, null, 0))
                .isEqualTo("평점 없는 리뷰이며, 같은 분류가 늘어나는지 볼 내용이 있습니다.");
        for (String text : emitted()) {
            assertThat(text).doesNotContain("0점").doesNotContain("null");
        }
    }

    @Test
    @DisplayName("목록의 권유 문장은 이 변경에 끌려오지 않는다")
    void theListsRecommendedActionIsUntouched() {
        // 상세가 제 문장을 가지게 된 이유는 목록의 문장이 틀렸기 때문이 아니라 다른 자리의 것이기 때문이다.
        // 이 둘이 같은 입력에서 서로 다른 말을 한다는 사실 자체가 계약이다.
        ReviewTriageNote note = ReviewTriageNote.of(2, BODY, null, 0);
        assertThat(note.recommendedAction()).isEqualTo("내용을 읽고 상품 상태를 확인해 보세요.");
        assertThat(note.reason()).isEqualTo("2점");
        assertThat(ReviewTriageWhyNow.of(2, BODY, null, 0)).isNotEqualTo(note.recommendedAction());
    }

    @Test
    @DisplayName("같은 리뷰에 대해 note와 whyNow가 서로 다른 등급을 보지 않는다")
    void bothDeriveTheSameTierFromTheSameRules() {
        for (Integer rating : RATINGS) {
            for (String body : BODIES) {
                boolean fyi = ReviewTriageRules.tier(rating, body) == ReviewTriageTier.FYI;
                for (String category : CATEGORIES) {
                    for (long count : COUNTS) {
                        String why = ReviewTriageWhyNow.of(rating, body, category, count);
                        ReviewTriageNote note = ReviewTriageNote.of(rating, body, category, count);
                        // 참고는 둘 다 말이 없고, 나머지는 둘 다 말이 있다.
                        assertThat(why == null)
                                .as("rating %s × body %s", rating, body)
                                .isEqualTo(fyi)
                                .isEqualTo(note.recommendedAction() == null);
                    }
                }
            }
        }
    }
}
